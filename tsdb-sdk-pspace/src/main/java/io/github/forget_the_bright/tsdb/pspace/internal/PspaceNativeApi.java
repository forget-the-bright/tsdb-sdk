package io.github.forget_the_bright.tsdb.pspace.internal;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbException;
import io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException;
import io.github.forget_the_bright.tsdb.pspace.PspaceConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * pSpace 原生 REST API 门面：端点、刷新/重试与请求体拼装。
 *
 * <p>响应字段解析已拆到 {@link PspaceResponseParser}；传输、信封与错误映射由
 * {@link PspaceHttp} 负责。本类只回答“调哪个路径、带什么请求体、何时重试/刷新”。</p>
 *
 * <p>全部读接口按 7.1 实测使用 <b>POST + JSON 请求体</b>：手册写 GET，但现场 CREST
 * 对 GET 一律返回 {@code Parse Json data failed}，POST 携带同样的字段才返回数据。
 * 写接口仍按手册使用 PUT/DELETE。全部方法都提供 {@code String} 级入口（返回解包后的
 * JSON 文本，可直接给需要厂商原始字段的调用方），适配器需要的再做类型化便捷方法。
 * 厂商用 {@code code=1} 表示“范围内无数据”，按空结果处理。</p>
 *
 * <p><b>端点索引</b>（路径 / 方法 / 用途 / 返回）：</p>
 * <ul>
 *   <li><b>生命周期与刷新</b>：{@code POST /Refresh} 重载点位缓存；
 *       {@code POST /ServerInfo} 服务信息。</li>
 *   <li><b>目录与元数据</b>：{@code POST /PointChildren} 直接子节点；
 *       {@code POST /PointChildrenPage} 子节点分页；{@code POST /PointPage} 全量分页；
 *       {@code POST /Point} 按 id/长名取点位；{@code POST /PointFilter} 服务端正则过滤；
 *       {@code POST /Property} 点位属性字段。</li>
 *   <li><b>实时值</b>：{@code POST /RealData} 实时值列表。</li>
 *   <li><b>历史</b>：{@code POST /HistDataPage}、{@code /HistData} 原始历史；
 *       {@code POST /HistAggreDataPage}、{@code /HistAggreData} 聚合历史；
 *       {@code POST /HistDataByAggre} 多聚合函数一次查询。</li>
 *   <li><b>事件与报警</b>：{@code POST /EventData}、{@code /EventDataPage}；
 *       {@code POST /AlarmData}、{@code /AlarmDataPage}；{@code POST /AlarmAck} 确认报警。</li>
 *   <li><b>点位维护与写入</b>：{@code POST /AddPoint}、{@code PUT /UpdPoint}、
 *       {@code DELETE /DelPoint}、{@code PUT /UpdRealData}。</li>
 *   <li><b>订阅</b>：8081 WebSocket，见 {@link #subscribe(String, Consumer)}。</li>
 * </ul>
 *
 * <p>自动刷新（厂商确认的固定解法）：服务刚启动或缓存未热时，
 * {@code /PointChildren} 会报 {@code Parent (0) , Not find children}、
 * {@code /Point} 只返回根节点、{@code /RealData} 报 {@code No Valid Data}。
 * 读路径首次取空或命中上述错误时，{@link #withAutoRefresh(Supplier, Predicate)}
 * 会 POST 一次 {@code /Refresh}（body 固定 {@code {}}）后重试原请求一次；
 * 两次自动刷新之间受 {@code refresh-min-interval-ms} 限速，避免并发失败
 * 触发刷新风暴。建点/改点/删点不做自动刷新，由调用方显式调用
 * {@link #refresh()}。</p>
 *
 * <p><b>非公共 API</b>：随内部重构变化，宿主应用请勿直接依赖；如需厂商原始能力，
 * 通过 {@code PspaceClient#unwrap(PspaceNativeApi.class)} 获取。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
public final class PspaceNativeApi {

    /** 目录/元数据查询固定字段集：只用 fieldnames，绕开 7.1 数字 fields 的缺陷。 */
    public static final String POINT_FIELDS =
            "TagId,TagType,Name,LongName,Desc,ParentId,DataType,IsNode,EngUnit,His_IsSave";

    private final PspaceConfig config;
    private final PspaceHttp http;
    private final PspaceMapper mapper;
    private final PspaceResponseParser parser;

    /** 刷新的短临界区锁。 */
    private final Object refreshLock = new Object();

    /** 上次自动刷新时刻（epoch 毫秒），0 表示从未刷新。 */
    private long lastRefreshAt;

    /**
     * @param config 已校验的配置，不能为 {@code null}
     * @param mapper 映射器，不能为 {@code null}
     */
    public PspaceNativeApi(PspaceConfig config, PspaceMapper mapper) {
        if (config == null || mapper == null) {
            throw new IllegalArgumentException("config and mapper are required");
        }
        this.config = config;
        this.mapper = mapper;
        this.http = new PspaceHttp(config);
        this.parser = new PspaceResponseParser(mapper);
    }

    /**
     * 返回配置。
     *
     * @return 非 {@code null} 的配置
     */
    public PspaceConfig config() {
        return config;
    }

    /**
     * 返回映射器。
     *
     * @return 非 {@code null} 的映射器
     */
    public PspaceMapper mapper() {
        return mapper;
    }

    // ---- 生命周期与刷新 ----------------------------------------------------

    /**
     * POST {@code /Refresh}，要求服务端重新加载点位缓存。
     *
     * <p>这是服务端缓存刷新，不是数据写入；但仍可能造成瞬时负载，调用方不要
     * 高频调用。驱动内部的自动刷新已限速。</p>
     *
     * @return 服务端返回的数据文本（通常为空）
     * @throws TsdbException 刷新失败时
     */
    public String refresh() {
        return http.post("/Refresh", PspaceHttp.EMPTY_JSON);
    }

    /**
     * POST {@code /ServerInfo}，读取服务端信息。
     *
     * @return 原始 JSON 文本
     */
    public String serverInfo() {
        return readPost("/ServerInfo", PspaceHttp.EMPTY_JSON);
    }

    // ---- 目录与元数据 ------------------------------------------------------

    /**
     * POST {@code /PointChildren}，读取某父节点下的直接子节点（非分页）。
     *
     * <p>7.1 在缓存未热时对 {@code parentid=0} 会报 {@code Not find children}；
     * 本方法把该错误原样抛出，交给 {@link #withAutoRefresh} 处理。</p>
     *
     * @param parentId 父节点 id，根为 0
     * @return 原始 JSON 文本
     */
    public String pointChildren(long parentId) {
        JSONObject body = body();
        body.put("parentid", parentId);
        body.put("fieldnames", POINT_FIELDS);
        return readPost("/PointChildren", body.toJSONString());
    }

    /**
     * POST {@code /PointChildrenPage}，分页读取某父节点下的子节点。
     *
     * @param parentId   父节点 id
     * @param pageSize   页大小，必须为正
     * @param pageNumber 页号，从 1 开始
     * @return 分页结果，非 {@code null}
     */
    public PspaceModels.Page pointChildrenPage(long parentId, int pageSize, int pageNumber) {
        JSONObject body = pagedBody(pageSize, pageNumber);
        body.put("parentid", parentId);
        body.put("fieldnames", POINT_FIELDS);
        JSONObject root = PspaceHttp.asObject(readPost("/PointChildrenPage", body.toJSONString()),
                "POST", "/PointChildrenPage");
        return parser.parsePage(root);
    }

    /**
     * POST {@code /PointPage}，全量分页读取点位目录（不带父节点过滤）。
     *
     * @param pageSize   页大小
     * @param pageNumber 页号，从 1 开始
     * @return 分页结果，非 {@code null}
     */
    public PspaceModels.Page pointPage(int pageSize, int pageNumber) {
        JSONObject body = pagedBody(pageSize, pageNumber);
        body.put("fieldnames", POINT_FIELDS);
        JSONObject root = PspaceHttp.asObject(readPost("/PointPage", body.toJSONString()),
                "POST", "/PointPage");
        return parser.parsePage(root);
    }

    /**
     * POST {@code /Point}，按 tagids 读取点位元数据。
     *
     * @param ids 点位 id 集合，非空
     * @return 点位列表，非 {@code null}
     */
    public List<PspaceModels.Point> pointsByIds(Collection<Long> ids) {
        JSONObject body = body();
        body.put("tagids", joinIds(ids));
        body.put("fieldnames", POINT_FIELDS);
        return parser.parsePoints(readPost("/Point", body.toJSONString()), "/Point");
    }

    /**
     * POST {@code /Point}，按 tagnames（长名）读取点位元数据。
     *
     * @param names 位号长名集合，非空
     * @return 点位列表，非 {@code null}
     */
    public List<PspaceModels.Point> pointsByNames(Collection<String> names) {
        JSONObject body = body();
        body.put("tagnames", String.join(",", names));
        body.put("fieldnames", POINT_FIELDS);
        return parser.parsePoints(readPost("/Point", body.toJSONString()), "/Point");
    }

    /**
     * POST {@code /PointFilter}，按条件过滤点位（服务端正则）。
     *
     * @param mode        {@code search}（包含匹配）或 {@code match}（整串匹配）
     * @param field       过滤字段，常用 {@code LongName}
     * @param value       过滤值（pSpace 按正则解释，调用方负责转义）
     * @param ignoreCase  是否忽略大小写
     * @return 点位列表，非 {@code null}
     */
    public List<PspaceModels.Point> filterPoints(String mode, String field, String value,
                                                 boolean ignoreCase) {
        JSONObject body = body();
        body.put("filtermode", mode);
        body.put("filterfield", field);
        body.put("filtervalue", value);
        body.put("filterignorecase", ignoreCase);
        body.put("fieldnames", POINT_FIELDS);
        return parser.parsePoints(readPost("/PointFilter", body.toJSONString()), "/PointFilter");
    }

    /**
     * POST {@code /Property}，读取点位属性字段。
     *
     * @param ids        点位 id 集合
     * @param fieldNames 逗号分隔的字段名（如 {@code PV,PV_Tm,PV_Qly}）
     * @return 原始 JSON 文本
     */
    public String property(Collection<Long> ids, String fieldNames) {
        JSONObject body = body();
        body.put("tagids", joinIds(ids));
        body.put("fieldnames", fieldNames);
        return readPost("/Property", body.toJSONString());
    }

    // ---- 实时值 ------------------------------------------------------------

    /**
     * POST {@code /RealData}，读取实时值。
     *
     * <p>只传 {@code tagnames}，避免与 {@code tagids} 的互斥歧义；
     * 服务端 {@code No Valid Data} 视为“全部无数据”，返回空列表而不抛异常，
     * 以便上层区分“缓存未热”和“这些点位确实没值”。读路径的自动刷新由
     * {@link #withAutoRefresh(Supplier, Predicate)} 负责；本方法自身不触发刷新。</p>
     *
     * @param names 位号长名集合，非空
     * @return 实时值列表，非 {@code null}
     */
    public List<PspaceModels.RealValue> realData(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return new ArrayList<PspaceModels.RealValue>();
        }
        JSONObject body = body();
        body.put("tagnames", String.join(",", names));
        String text;
        try {
            text = readPost("/RealData", body.toJSONString());
        } catch (TsdbException e) {
            if (PspaceHttp.looksCold(e.getMessage())) {
                return new ArrayList<PspaceModels.RealValue>();
            }
            throw e;
        }
        JSONObject root = PspaceHttp.asObject(text, "POST", "/RealData");
        return parser.parseRealValues(root);
    }

    // ---- 历史 --------------------------------------------------------------

    /**
     * POST {@code /HistDataPage}，分页读取原始历史。
     *
     * @param ids        点位 id 集合
     * @param btime      起始时刻（含）
     * @param etime      结束时刻（不含）
     * @param pageSize   页大小
     * @param pageNumber 页号，从 1 开始
     * @return 分页结果，非 {@code null}
     */
    public PspaceModels.Page histDataPage(Collection<Long> ids, Instant btime, Instant etime,
                                          int pageSize, int pageNumber) {
        JSONObject body = pagedBody(pageSize, pageNumber);
        body.put("tagids", joinIds(ids));
        body.put("btime", mapper.formatRequestTime(btime));
        body.put("etime", mapper.formatRequestTime(etime));
        JSONObject root = PspaceHttp.asObject(readPost("/HistDataPage", body.toJSONString()),
                "POST", "/HistDataPage");
        return parser.parsePage(root);
    }

    /**
     * POST {@code /HistData}，读取原始历史（非分页）。
     *
     * @param ids   点位 id 集合
     * @param btime 起始时刻（含）
     * @param etime 结束时刻（不含）
     * @return 按点位分组的行，非 {@code null}
     */
    public List<PspaceModels.HistRow> histData(Collection<Long> ids, Instant btime, Instant etime) {
        JSONObject body = body();
        body.put("tagids", joinIds(ids));
        body.put("btime", mapper.formatRequestTime(btime));
        body.put("etime", mapper.formatRequestTime(etime));
        return parser.parseHistRows(readPost("/HistData", body.toJSONString()), "/HistData");
    }

    /**
     * POST {@code /HistAggreDataPage}，分页读取聚合历史。
     *
     * @param ids             点位 id 集合
     * @param aggfunc         厂商聚合函数名，如 {@code endval}、{@code intve}
     * @param intervalSeconds 聚合间隔，单位<b>秒</b>，必须为正
     * @param btime           起始时刻（含）
     * @param etime           结束时刻（不含）
     * @param pageSize        页大小
     * @param pageNumber      页号，从 1 开始
     * @return 分页结果，非 {@code null}
     */
    public PspaceModels.Page histAggreDataPage(Collection<Long> ids, String aggfunc,
                                               int intervalSeconds, Instant btime, Instant etime,
                                               int pageSize, int pageNumber) {
        JSONObject body = pagedBody(pageSize, pageNumber);
        body.put("aggredata", aggreData(aggfunc, ids));
        body.put("interval", intervalSeconds);
        body.put("btime", mapper.formatRequestTime(btime));
        body.put("etime", mapper.formatRequestTime(etime));
        JSONObject root = PspaceHttp.asObject(readPost("/HistAggreDataPage", body.toJSONString()),
                "POST", "/HistAggreDataPage");
        return parser.parsePage(root);
    }

    /**
     * POST {@code /HistAggreData}，读取聚合历史（非分页）。
     *
     * @param ids             点位 id 集合
     * @param aggfunc         厂商聚合函数名
     * @param intervalSeconds 聚合间隔，单位秒
     * @param btime           起始时刻（含）
     * @param etime           结束时刻（不含）
     * @return 按点位分组的行，非 {@code null}
     */
    public List<PspaceModels.HistRow> histAggreData(Collection<Long> ids, String aggfunc,
                                                    int intervalSeconds, Instant btime, Instant etime) {
        JSONObject body = body();
        body.put("aggredata", aggreData(aggfunc, ids));
        body.put("interval", intervalSeconds);
        body.put("btime", mapper.formatRequestTime(btime));
        body.put("etime", mapper.formatRequestTime(etime));
        return parser.parseHistRows(readPost("/HistAggreData", body.toJSONString()), "/HistAggreData");
    }

    /**
     * POST {@code /HistDataByAggre}，一次查询多个聚合函数，原始文本返回。
     *
     * @param ids             点位 id 集合
     * @param aggfuncs        逗号分隔的聚合函数名
     * @param intervalSeconds 聚合间隔，单位秒
     * @param btime           起始时刻（含）
     * @param etime           结束时刻（不含）
     * @return 原始 JSON 文本
     */
    public String histDataByAggre(Collection<Long> ids, String aggfuncs, int intervalSeconds,
                                  Instant btime, Instant etime) {
        JSONObject body = body();
        body.put("aggredata", aggreData(aggfuncs, ids));
        body.put("interval", intervalSeconds);
        body.put("btime", mapper.formatRequestTime(btime));
        body.put("etime", mapper.formatRequestTime(etime));
        return readPost("/HistDataByAggre", body.toJSONString());
    }

    // ---- 事件与报警 --------------------------------------------------------

    /**
     * POST {@code /EventData}，读取事件数据（原始文本）。
     *
     * @param btime 起始时刻（含）
     * @param etime 结束时刻（不含）
     * @return 原始 JSON 文本
     */
    public String eventData(Instant btime, Instant etime) {
        JSONObject body = body();
        body.put("btime", mapper.formatRequestTime(btime));
        body.put("etime", mapper.formatRequestTime(etime));
        return readPost("/EventData", body.toJSONString());
    }

    /**
     * POST {@code /EventDataPage}，分页读取事件数据（原始文本）。
     *
     * @param btime      起始时刻（含）
     * @param etime      结束时刻（不含）
     * @param pageSize   页大小
     * @param pageNumber 页号，从 1 开始
     * @return 原始 JSON 文本
     */
    public String eventDataPage(Instant btime, Instant etime, int pageSize, int pageNumber) {
        JSONObject body = pagedBody(pageSize, pageNumber);
        body.put("btime", mapper.formatRequestTime(btime));
        body.put("etime", mapper.formatRequestTime(etime));
        return readPost("/EventDataPage", body.toJSONString());
    }

    /**
     * POST {@code /AlarmData}，读取报警数据（原始文本）。
     *
     * @param btime 起始时刻（含）
     * @param etime 结束时刻（不含）
     * @return 原始 JSON 文本
     */
    public String alarmData(Instant btime, Instant etime) {
        JSONObject body = body();
        body.put("btime", mapper.formatRequestTime(btime));
        body.put("etime", mapper.formatRequestTime(etime));
        return readPost("/AlarmData", body.toJSONString());
    }

    /**
     * POST {@code /AlarmDataPage}，分页读取报警数据（原始文本）。
     *
     * @param btime      起始时刻（含）
     * @param etime      结束时刻（不含）
     * @param pageSize   页大小
     * @param pageNumber 页号，从 1 开始
     * @return 原始 JSON 文本
     */
    public String alarmDataPage(Instant btime, Instant etime, int pageSize, int pageNumber) {
        JSONObject body = pagedBody(pageSize, pageNumber);
        body.put("btime", mapper.formatRequestTime(btime));
        body.put("etime", mapper.formatRequestTime(etime));
        return readPost("/AlarmDataPage", body.toJSONString());
    }

    // ---- 点位维护与写入 ----------------------------------------------------

    /**
     * POST {@code /AddPoint}，新增点位，请求体为原始 JSON。
     *
     * <p>返回逐条的 {@code idx/pid/code/mesg}，请自行解析；重复名返回
     * {@code -19392}。不做自动刷新：缓存同步由调用方按需 {@link #refresh()}。</p>
     *
     * @param jsonBody 请求体，不能为 {@code null}
     * @return 原始 JSON 文本
     */
    public String addPoint(String jsonBody) {
        return http.post("/AddPoint", jsonBody);
    }

    /**
     * PUT {@code /UpdPoint}，修改点位，请求体为原始 JSON。
     *
     * @param query    额外查询参数，允许为 {@code null}
     * @param jsonBody 请求体，不能为 {@code null}
     * @return 原始 JSON 文本
     */
    public String updPoint(Map<String, String> query, String jsonBody) {
        return http.put("/UpdPoint", query, jsonBody);
    }

    /**
     * DELETE {@code /DelPoint}，删除点位。
     *
     * @param tagIds 待删除的点位 id 集合
     * @return 原始 JSON 文本
     */
    public String delPoint(Collection<Long> tagIds) {
        Map<String, String> query = new LinkedHashMap<String, String>();
        query.put("tagids", joinIds(tagIds));
        return http.delete("/DelPoint", query);
    }

    /**
     * POST {@code /AlarmAck}，确认报警，请求体为原始 JSON。
     *
     * @param jsonBody 请求体，不能为 {@code null}（无内容时传 {@code {}}）
     * @return 原始 JSON 文本
     */
    public String alarmAck(String jsonBody) {
        return http.post("/AlarmAck", jsonBody);
    }

    /**
     * PUT {@code /UpdRealData}，写入实时值，请求体为原始 JSON。
     *
     * @param jsonBody 请求体，不能为 {@code null}
     * @return 原始 JSON 文本，逐条结果由调用方解析
     */
    public String updRealData(String jsonBody) {
        return http.put("/UpdRealData", null, jsonBody);
    }

    // ---- 订阅 --------------------------------------------------------------

    /**
     * 打开 8081 WebSocket 实时订阅（不是 REST 端点）。
     *
     * @param requestJson 与 {@code /RealData} 同构的订阅请求
     * @param onMessage   每收到一条文本消息回调一次
     * @return 非 {@code null} 的订阅句柄，用完必须关闭
     */
    public PspaceSubscription subscribe(String requestJson, Consumer<String> onMessage) {
        return PspaceSubscription.open(config, requestJson, onMessage);
    }

    /**
     * 关闭底层 HTTP 资源（当前实现无连接池持有，预留）。
     */
    public void close() {
        // HttpTransport 不持有需要显式关闭的资源
    }

    /**
     * 读路径的统一安全网：首次为空或命中“缓存未热”错误时，刷新一次后重试。
     *
     * <p>刷新受 {@code auto-refresh} 与 {@code refresh-min-interval-ms} 双重约束；
     * 不满足条件时按原语义返回（空集合或原异常），不会循环刷新。</p>
     *
     * @param read    读请求
     * @param isEmpty 结果是否为空
     * @param <T>     结果类型
     * @return 读取结果；刷新后重试仍为空时返回空结果
     */
    public <T> T withAutoRefresh(Supplier<T> read, Predicate<T> isEmpty) {
        T first;
        try {
            first = read.get();
        } catch (TsdbException e) {
            if (!PspaceHttp.looksCold(e.getMessage()) || !acquireRefresh()) {
                throw e;
            }
            refresh();
            return read.get();
        }
        if (!isEmpty.test(first) || !acquireRefresh()) {
            return first;
        }
        refresh();
        return read.get();
    }

    /**
     * 占用一次自动刷新机会：同时检查开关与最小间隔，并原子更新上次刷新时刻。
     *
     * @return 允许立即刷新返回 {@code true}；开关关闭或仍在限速窗口内返回 {@code false}
     */
    private boolean acquireRefresh() {
        if (!config.isAutoRefresh()) {
            return false;
        }
        synchronized (refreshLock) {
            long now = System.currentTimeMillis();
            if (lastRefreshAt != 0 && now - lastRefreshAt < config.getRefreshMinIntervalMillis()) {
                return false;
            }
            lastRefreshAt = now;
            return true;
        }
    }

    /**
     * 发送 POST 读请求，只对超时与连接失败做有限重试。
     *
     * <p>重试次数取 {@code max-read-attempts}；每次失败后按
     * {@code retry-backoff-ms} 休眠。HTTP 业务错误（如 {@code code=-1}）不重试，
     * 以免把确定性失败放大。全部尝试失败时抛出最后一次异常。</p>
     *
     * @param path 相对路径
     * @param body JSON 请求体
     * @return 解包后的数据文本
     */
    private String readPost(String path, String body) {
        TsdbException last = null;
        int attempts = config.getMaxReadAttempts();
        for (int i = 0; i < attempts; i++) {
            try {
                return http.post(path, body);
            } catch (TsdbTimeoutException e) {
                last = e;
            } catch (TsdbConnectionException e) {
                last = e;
            }
            if (i + 1 < attempts) {
                sleep(config.getRetryBackoffMillis());
            }
        }
        throw last;
    }

    /**
     * 按毫秒休眠；被中断时恢复中断标记并立即返回。
     *
     * @param millis 休眠毫秒，{@code <= 0} 时不休眠
     */
    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @return 空的请求体对象
     */
    private static JSONObject body() {
        return new JSONObject();
    }

    /**
     * 构造成员分页请求体；页大小与页号小于 1 时按 1 处理。
     *
     * @param pageSize   页大小
     * @param pageNumber 页号
     * @return 含 {@code pagesize} / {@code pagenumber} 的请求体
     */
    private static JSONObject pagedBody(int pageSize, int pageNumber) {
        JSONObject body = body();
        body.put("pagesize", Math.max(1, pageSize));
        body.put("pagenumber", Math.max(1, pageNumber));
        return body;
    }

    /**
     * 构造聚合请求片段：{@code {aggfunc, tagids}}。
     *
     * @param aggfunc 厂商聚合函数名
     * @param ids     点位 id；空集合序列化为空字符串
     * @return 聚合请求对象
     */
    private static JSONObject aggreData(String aggfunc, Collection<Long> ids) {
        JSONObject aggre = new JSONObject();
        aggre.put("aggfunc", aggfunc);
        aggre.put("tagids", joinIds(ids));
        return aggre;
    }

    /**
     * 把点位 id 拼成逗号分隔文本；{@code null} 元素跳过，空集合返回空字符串。
     *
     * @param ids 点位 id 集合，可为 {@code null}
     * @return 非 {@code null} 的逗号分隔文本
     */
    private static String joinIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /**
     * 把分页/列表响应中的原始元素解析成点位。
     *
     * <p>保留此入口是为了让适配器和集成测试复用 {@link PspaceResponseParser}，
     * 解析规则（大小写、别名、空值）见该类的类注释。</p>
     *
     * @param values 原始元素数组，可为 {@code null}
     * @return 非 {@code null} 的点位列表
     */
    public List<PspaceModels.Point> parsePoints(JSONArray values) {
        return parser.parsePoints(values);
    }

    /**
     * 把分页/列表响应中的原始元素解析成历史行。
     *
     * <p>语义与 {@link PspaceResponseParser#parseHistRows(JSONArray)} 完全一致；
     * 元素既可以是 {@code {pid,count,items}} 行，也可以直接是单值。</p>
     *
     * @param values 原始元素数组，可为 {@code null}
     * @return 非 {@code null} 的历史行列表
     */
    public List<PspaceModels.HistRow> parseHistRows(JSONArray values) {
        return parser.parseHistRows(values);
    }
}