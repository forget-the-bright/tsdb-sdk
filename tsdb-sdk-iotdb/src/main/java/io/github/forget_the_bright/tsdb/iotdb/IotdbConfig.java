package io.github.forget_the_bright.tsdb.iotdb;

import io.github.forget_the_bright.tsdb.common.config.SecurityConfig;
import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import io.github.forget_the_bright.tsdb.common.config.TsdbInstanceConfig;
import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;
import io.github.forget_the_bright.tsdb.common.internal.Texts;
import io.github.forget_the_bright.tsdb.iotdb.internal.sql.Grouping;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 经过校验、不可变的 Apache IoTDB REST v2（树模型）连接配置。
 *
 * <p><b>职责</b>：把公共层的 {@link TsdbInstanceConfig} 与
 * {@link SecurityConfig} 归一化为 IoTDB 驱动内部使用的配置快照，
 * 负责必填项校验、URL 规范化、默认值填充，以及 API 地址拼接
 * （{@link #apiUrl(String)}、{@link #rootUrl(String)}）。</p>
 *
 * <p><b>设计要点</b>：所有校验都在构造器内完成并快速失败，配置一旦创建即不可变；
 * 校验不发起任何网络请求，因此可在启动阶段安全调用。
 * {@code path-prefix} 选项用于适配网关或反向代理改写过前缀的部署，
 * 缺省为 {@value #DEFAULT_API_PREFIX}。</p>
 *
 * <p><b>线程安全</b>：全部字段为 {@code final}，构造后无任何写入，
 * 因此本类天然不可变、可安全地被多线程共享。</p>
 *
 * <p><b>与公共层/原生层的关系</b>：只依赖公共层的配置模型，不依赖 HTTP 或
 * JSON 库；由 {@link IotdbDriver#create} 创建，供
 * {@link io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbHttpClient} 与
 * {@link io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbRestApi} 消费。</p>
 *
 * <p><b>限制与注意事项</b>：</p>
 * <ul>
 *   <li>IoTDB 的 REST 服务默认关闭，必须在 DataNode 上设置
 *       {@code enable_rest_service=true} 才能使用；REST 通道有 JSON/HTTP
 *       开销，适合集成与中低吞吐场景，大批量写入应使用原生 SessionPool；</li>
 *   <li>IoTDB 只支持 Basic 认证，因此用户名与密码是必填项，且密码以
 *       {@link String} 明文形式驻留内存，无法像 {@code char[]} 那样主动擦除；</li>
 *   <li>使用 {@code http} 明文传输时必须显式开启
 *       {@code allow-insecure-transport=true}；</li>
 *   <li>{@link #getMaxReadAttempts()} 与 {@link #getRetryBackoffMillis()}
 *       仅保留配置语义：当前 REST 传输层不执行任何重试，这两个值不会被使用；</li>
 *   <li>本类不做 SSL 证书校验相关的细粒度配置，仅通过
 *       {@link #isVerifyHostname()} 控制主机名校验；</li>
 *   <li>{@code options.time.*} 仅用于时间解析与展示。IoTDB 协议时间是 epoch 毫秒，
 *       该配置不改变 SQL 或响应数值，但与其他驱动共享同一套
 *       {@link TimeConfig} 语义；</li>
 *   <li>{@code options.zone} 与 {@code options.shift-hour} 决定<b>日历口径</b>：
 *       天/月这类自然粒度的起点对齐，以及班次窗口 {@link #shiftWindow(Instant)}
 *       的换班时刻。两者都只影响“把哪个绝对时刻当作一天/一班的开始”，
 *       不改写任何协议数值。展示时区（{@code options.time.*}）与本项独立，
 *       用 UTC 展示、按东八区对齐是合法组合。</li>
 * </ul>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbConfig {

    /**
     * REST v2 接口的默认路径前缀，值为 {@code "/rest/v2"}；
     * 当实例配置未提供 {@code path-prefix} 选项时使用。
     */
    public static final String DEFAULT_API_PREFIX = "/rest/v2";

    /**
     * 插值窗口内原始点拉取总量的默认上限，值为 {@value}。
     *
     * <p>按 1 秒采集频率折算约覆盖 55 小时的网格；再大就该由业务改用
     * 分窗查询，而不是一次把几百万行拉进内存。</p>
     */
    public static final int DEFAULT_INTERPOLATION_RAW_LIMIT = 200_000;

    /**
     * 单条批量 SQL 携带的表达式上限，值为 {@value}。
     *
     * <p>覆盖全部查询族（最新值、等间隔、聚合、整窗增量、差分、原始值、插值），
     * 不再只作用于最新值。上限只防止单条语句过长（网关、代理对请求体长度有限制），
     * 不影响正确性：2026-11-13 实测约 13 KB、300 个表达式的语句被正常接受，
     * 因此 {@value} 是<b>客户端的保守选择</b>，不是服务端限制。业务最新值接口
     * 单请求最多约 345 个位号，默认值下这类请求拆成 2 条 SQL。</p>
     */
    public static final int DEFAULT_QUERY_BATCH_SIZE = 200;

    /**
     * 批量 SQL 在分组维度上的并行度上限，值为 {@value}。
     *
     * <p>位号跨库分布时一次请求会拆成多条 SQL；并行度决定同时发出几条。
     * 设为 {@code 1} 即完全顺序执行。目标档位（按库）生效后大多数请求只有
     * 1 条 SQL，本上限主要在跨块与跨库时起作用。</p>
     */
    public static final int DEFAULT_QUERY_PARALLELISM = 4;

    /**
     * 批量分组默认档位，值为 {@link Grouping#DATABASE}。
     *
     * <p>目标档位是 {@link Grouping#DATABASE}（业务侧要求“一次请求基本一个库里，
     * 一条 SQL 查完”），现已生效。生效的入场券是同库自对自的双跑对数：同一实例、
     * 同一批位号、同一窗口，{@code query-batch-size: 1}（逐表达式，等价于改造前的
     * 逐位号取数）、{@code device} 与 {@code database} 三种配置各跑一遍，
     * 逐序列逐点 0 容差比对，最新值 / RAW / 等间隔 / 聚合 / 整窗增量 / 差分 / 插值
     * 七个族全部 {@code diffs=0}——见 {@code IotdbTierParityIT}，结论与实测数字
     * 记在 docs/14 §8.3。</p>
     *
     * <p>{@link Grouping#DEVICE} 仍是现场回退档：它与改造前“一个设备一条 SQL”逐字节
     * 相同，怀疑列对齐护栏出错时用它二分定位。</p>
     *
     * <p>选库级而不是跨库单语句（{@link Grouping#ROOT}，实测三通道均可用、往返更少）
     * 的理由是<b>权限边界而非性能</b>：受限账号可能只授予按库授权，
     * {@code FROM root} 在现场未必通得过。</p>
     */
    public static final Grouping DEFAULT_QUERY_BATCH_SCOPE = Grouping.DATABASE;

    /**
     * 批量原始值查询单条语句的行预算，值为 {@value}。
     *
     * <p>实测服务端单次响应最多 10 000 行（超限报 {@code code=708}），且多列
     * {@code raw} 的 {@code LIMIT} 是<b>合并时间戳后的行数</b>而不是每位号点数。
     * 本值直接取实测上限，用于判断“某列是否可能还有尾部的点没取到”，从而决定
     * 要不要逐位号补采。</p>
     */
    public static final int DEFAULT_QUERY_BATCH_ROW_BUDGET = 10_000;

    /**
     * 一个块内允许逐位号补采的位号数上限，值为 {@value}。
     *
     * <p>批量原始值里被密集位号挤掉点的稀疏列要逐位号补齐，这会把一次批量重新
     * 展开成若干次往返。超过本上限时整块直接退回逐位号查询，避免 345 位号
     * 乘以补采的乘积往返；两种路径都必须给出与逐位号相同的结果，因此这是
     * 往返数取舍而不是正确性开关。</p>
     */
    public static final int DEFAULT_QUERY_BATCH_COMPLETION_MAX = 32;

    /**
     * 日历口径默认时区，值为 {@code "Asia/Shanghai"}。
     *
     * <p>天/月粒度的起点对齐与班次边界都按本时区计算。取东八区为默认而不是 UTC：
     * 现场报表的一天是本地 00:00 到 24:00，按 UTC 对齐会把日界挪到本地早上
     * 8 点，跨日的桶整体错一格，而协议里没有任何线索能发现这件事。</p>
     */
    public static final String DEFAULT_ZONE = "Asia/Shanghai";

    /**
     * 默认换班小时，值为 {@value}，即班次从当地每天 08:00 开始。
     *
     * <p>铜陵业务的一天以白班起点为准（白班 08:00 起，跨到次日 08:00），
     * 日累计与班次累计都以此为锚点。</p>
     */
    public static final int DEFAULT_SHIFT_HOUR = 8;

    /**
     * 实例逻辑名，来自配置键 {@code tsdb.instances.*}，可为 {@code null}；
     * 仅用于日志与异常上下文。
     */
    private final String instanceName;

    /**
     * 服务基地址，已去除尾部斜杠，形如 {@code https://host:6667}；
     * 不含 {@code /rest/v2} 前缀，前缀由 {@link #apiPrefix} 单独保存。
     */
    private final String baseUrl;

    /**
     * REST 接口路径前缀，保证以 {@code /} 开头且不以 {@code /} 结尾；
     * 缺省为 {@link #DEFAULT_API_PREFIX}。
     */
    private final String apiPrefix;

    /**
     * Basic 认证用户名，非 {@code null}；已去除首尾空白。
     */
    private final String username;

    /**
     * Basic 认证密码，非 {@code null}；以明文 {@link String} 保存，
     * 无法主动擦除，注意不要将其写入日志。
     */
    private final String password;

    /**
     * 建立 TCP 连接的超时时间，单位毫秒，恒大于 0。
     */
    private final long connectTimeoutMillis;

    /**
     * 等待响应的读取超时时间，单位毫秒，恒大于 0。
     */
    private final long readTimeoutMillis;

    /**
     * 是否校验服务端证书的主机名；{@code false} 仅用于自签名证书的测试环境。
     */
    private final boolean verifyHostname;

    /**
     * 配置声明的最大读取尝试次数，恒大于等于 1；
     * 当前 REST 传输层不重试，该值仅作配置记录。
     */
    private final int maxReadAttempts;

    /**
     * 配置声明的重试退避时间，单位毫秒，恒大于等于 0；
     * 当前 REST 传输层不重试，该值仅作配置记录。
     */
    private final long retryBackoffMillis;

    /**
     * 时间解析与展示配置，来源于 {@code options.time.*}。
     *
     * <p>IoTDB 的 SQL 与 REST 响应时间都是 epoch 毫秒数字，该配置不改变
     * 协议；它用于让调用方以统一方式解析、展示时间，并与 GE 等文本时间
     * 驱动保持同一套配置语义。</p>
     */
    private final TimeConfig timeConfig;

    /**
     * 是否允许写入，取自 {@link SecurityConfig#isAllowWrite()}，默认 {@code false}；
     * 该开关同时决定客户端能力集是否包含 {@code WRITE}。
     */
    private final boolean allowWrite;

    /**
     * 访问通道，默认 {@link IotdbTransport#REST}，与改造前行为保持兼容。
     */
    private final IotdbTransport transport;

    /**
     * RPC 主机名，原生 Session 与 JDBC 共用；从 {@code options.host} 或
     * {@code base-url} 的主机部分取得。
     */
    private final String host;

    /**
     * RPC 端口，原生 Session 与 JDBC 共用；默认 6667，可由
     * {@code options.rpc-port} 覆盖。
     */
    private final int rpcPort;

    /**
     * 默认数据库前缀，形如 {@code root.factory}；当调用方传入的位号不是完整
     * {@code root.} 路径时，驱动会把它拼在该前缀之后。为 {@code null} 表示
     * 不提供默认库，此时所有位号必须是完整路径。
     */
    private final String defaultDatabase;

    /**
     * 位号映射表文件路径，取自 {@code options.tag-mapping-file}，可为 {@code null}。
     *
     * <p>文件格式为一行一条 {@code 业务位号=root.<库>.<设备>.<测量点>} 的 properties。
     * 之所以需要显式映射表：现场位号与 IoTDB 路径不是机械变换关系（大量连字符、
     * 纯数字段、逗号多值、中文占位），而 IoTDB 查询不存在的序列返回空结果而不是
     * 报错，靠字符清洗猜路径会把配置缺陷伪装成“没有数据”。</p>
     *
     * <p>本类只记录并校验该字符串，文件读取由
     * {@link io.github.forget_the_bright.tsdb.iotdb.IotdbDriver#create} 在装配阶段
     * 完成，因此 {@link #IotdbConfig(String, TsdbInstanceConfig, SecurityConfig)}
     * 保持“不发起任何外部访问”的构造语义。</p>
     */
    private final String tagMappingFile;

    /**
     * 插值网格的单侧外推容差，单位毫秒，取自
     * {@code options.interpolation-tolerance}，默认 {@code 0}。
     *
     * <p>只在网格槽位<b>缺另一侧包围点</b>时起作用：槽位落在数据序列之外时，
     * 距离超过该容差就输出空值而不是把最后一个读数一路保持下去。默认 0 表示
     * 一律不外推——停产、检修或位号改名后仍画出一条“平直的真实曲线”，
     * 比缺一段数据更难被发现。两侧包围点齐全时的内插跨度不受本项限制。</p>
     */
    private final long interpolationToleranceMillis;

    /**
     * 一次插值在网格窗口内最多拉取的原始点数，取自
     * {@code options.interpolation-raw-limit}，默认 {@value #DEFAULT_INTERPOLATION_RAW_LIMIT}。
     *
     * <p>插值要把窗口内原始点全部取回内存才能算出每个槽位的权重，采样密集时
     * 这个量与网格密度无关、只与采集频率有关，必须有上限。达到上限后停止分页
     * 并在结果上如实标记 {@code rawTruncated}，宁可让调用方看到“依据不完整”，
     * 也不要一次几百万行的响应把内存打满。</p>
     */
    private final int interpolationRawLimit;

    /**
     * 单条批量 SQL 携带的表达式上限，取自 {@code options.query-batch-size}，
     * 默认 {@value #DEFAULT_QUERY_BATCH_SIZE}。
     *
     * <p>只影响一次请求被拆成几条 SQL，不改变返回结果。旧的
     * {@code current-batch-size} 不再接受：现网残留旧键必须启动失败，
     * 而不是静默按新默认值跑。</p>
     */
    private final int queryBatchSize;

    /**
     * 批量 SQL 在分组维度上的并行度上限，取自 {@code options.query-parallelism}，
     * 默认 {@value #DEFAULT_QUERY_PARALLELISM}；{@code 1} 表示顺序执行。
     *
     * <p>上限而不是倍率：不设上限时一次 345 位号的请求可能瞬间打出上百条并发
     * HTTP，把 REST 服务打成瓶颈，反而让整批更慢。旧的 {@code current-parallelism}
     * 同样不再接受。</p>
     */
    private final int queryParallelism;

    /**
     * 批量分组档位，取自 {@code options.query-batch-scope}，
     * 默认 {@link #DEFAULT_QUERY_BATCH_SCOPE}。
     *
     * <p>档位决定一条 SQL 的覆盖范围与 {@code FROM} 前缀层级：{@code database}
     * 一个库一条、{@code root} 整批一条、{@code device} 一个设备一条。三档共用
     * 同一套列对齐护栏，切换档位只改往返数。</p>
     */
    private final Grouping queryBatchScope;

    /**
     * 批量原始值单条语句的行预算，取自 {@code options.query-batch-row-budget}，
     * 默认 {@value #DEFAULT_QUERY_BATCH_ROW_BUDGET}。
     *
     * <p>多列 {@code raw} 的 {@code LIMIT} 是合并时间戳后的行数，稀疏位号会被
     * 密集位号挤出结果；撞预算且某列点数不足时该列要逐位号补采。本值是这条
     * 判定的分母，因此它必须是实测的服务端上限，不能随手调大。</p>
     */
    private final int queryBatchRowBudget;

    /**
     * 一个块内允许逐位号补采的位号数上限，取自
     * {@code options.query-batch-completion-max}，默认 {@value #DEFAULT_QUERY_BATCH_COMPLETION_MAX}。
     *
     * <p>超过本上限时整块直接退回逐位号查询。结果两种路径相同，本值只控制
     * 往返数，不改变答案。</p>
     */
    private final int queryBatchCompletionMax;

    /**
     * 是否要求装配统一 SQL 查询服务，取自 {@code options.require-query-service}，
     * 默认 {@code true}。
     *
     * <p>采样、插值、整窗增量与批量最新值都只有查询服务这一条实现路径；缺了它
     * 客户端只是少声明几项能力、查询模式改走 REST 直连的旧实现，配置写错与
     * “这本来就是精简部署”在运行期看不出区别，等到业务发起插值才报错。
     * 因此默认在构造期直接失败；确实要保留旧的 REST 直连路径时显式置
     * {@code false}。</p>
     */
    private final boolean requireQueryService;

    /**
     * 日历口径时区，取自 {@code options.zone}，默认 {@link #DEFAULT_ZONE}。
     *
     * <p>用于天/月粒度的起点对齐与班次边界换算。它与仅影响文本解析和展示的
     * {@link #getTimeConfig()} 是两件事：前者决定“哪一刻算一天的开始”，
     * 后者决定“把时刻印成什么字符串”。</p>
     */
    private final ZoneId zone;

    /**
     * 换班小时（当地时区），取自 {@code options.shift-hour}，默认
     * {@link #DEFAULT_SHIFT_HOUR}，取值范围 {@code 0..23}。
     *
     * <p>班次窗口 {@code [start, start+24h)} 从这一小时起算，
     * 见 {@link #shiftWindow(Instant)}。</p>
     */
    private final int shiftHour;

    /**
     * 构造并校验 IoTDB 配置。
     *
     * <p>校验顺序：实例配置非空 → {@code base-url} 非空且可解析 → scheme 为
     * {@code http}/{@code https} → 明文传输是否被允许 → 路径前缀规范化 →
     * 用户名/密码必填 → 连接与读取超时大于 0 → 重试次数大于等于 1。
     * 全程不访问网络。</p>
     *
     * @param instanceName 实例逻辑名，可为 {@code null}
     * @param config       公共层实例配置，不能为 {@code null}
     * @param security     全局安全配置；为 {@code null} 时使用默认值
     *                     （{@code allowWrite=false}、{@code verifyHostname=true}、
     *                     {@code allowInsecureTransport=false}）
     * @throws TsdbConfigurationException 当实例配置为空、{@code base-url} 缺失或非法、
     *                                    scheme 不是 http/https、使用明文传输但未开启
     *                                    {@code allow-insecure-transport}、
     *                                    用户名或密码为空、超时非正、重试次数小于 1 时
     */
    public IotdbConfig(String instanceName, TsdbInstanceConfig config, SecurityConfig security) {
        if (config == null) {
            throw new TsdbConfigurationException("IoTDB instance config is required");
        }
        SecurityConfig sec = security == null ? new SecurityConfig() : security;

        String url = Texts.trimToNull(config.getBaseUrl());
        if (url == null) {
            throw new TsdbConfigurationException("IoTDB base-url is required");
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new TsdbConfigurationException("invalid IoTDB base-url: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new TsdbConfigurationException("IoTDB base-url must use http or https: " + url);
        }
        if ("http".equalsIgnoreCase(scheme) && !sec.isAllowInsecureTransport()) {
            throw new TsdbConfigurationException(
                    "plaintext IoTDB transport is disabled; use https or set allow-insecure-transport=true");
        }

        String prefix = Texts.trimToNull(config.option("path-prefix"));
        if (prefix == null) {
            prefix = DEFAULT_API_PREFIX;
        }
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }

        String user = Texts.trimToNull(config.getAuth() == null ? null : config.getAuth().getUsername());
        String pass = Texts.trimToNull(config.getAuth() == null ? null : config.getAuth().getPassword());
        if (user == null || pass == null) {
            throw new TsdbConfigurationException("IoTDB auth.username and auth.password are required");
        }

        long connect = config.getTimeout().getConnectMillis();
        long read = config.getTimeout().getReadMillis();
        if (connect <= 0 || read <= 0) {
            throw new TsdbConfigurationException("IoTDB connect/read timeouts must be > 0");
        }

        int attempts = config.getRetry().getMaxAttempts();
        if (attempts < 1) {
            throw new TsdbConfigurationException("IoTDB retry.max-attempts must be >= 1");
        }

        this.instanceName = instanceName;
        this.baseUrl = url;
        this.apiPrefix = prefix;
        this.username = user;
        this.password = pass;
        this.connectTimeoutMillis = connect;
        this.readTimeoutMillis = read;
        this.verifyHostname = sec.isVerifyHostname();
        this.maxReadAttempts = attempts;
        this.retryBackoffMillis = Math.max(0L, config.getRetry().getBackoffMillis());
        this.timeConfig = TimeConfig.from(config);
        this.allowWrite = sec.isAllowWrite();

        String transportName = Texts.trimToNull(config.option("transport"));
        this.transport = transportName == null
                ? IotdbTransport.REST
                : parseTransport(transportName);
        String configuredHost = Texts.trimToNull(config.option("host"));
        this.host = configuredHost == null ? uri.getHost() : configuredHost;
        if (this.host == null || this.host.trim().isEmpty()) {
            throw new TsdbConfigurationException("IoTDB host is required for native/JDBC transport");
        }
        this.rpcPort = parsePort(config.option("rpc-port"), 6667);
        this.defaultDatabase = parseDefaultDatabase(config.option("default-database"));
        this.tagMappingFile = Texts.trimToNull(config.option("tag-mapping-file"));
        this.interpolationToleranceMillis = parseNonNegativeMillis(
                config.option("interpolation-tolerance"), 0L);
        this.interpolationRawLimit = parsePositiveInt(
                config.option("interpolation-raw-limit"), DEFAULT_INTERPOLATION_RAW_LIMIT);
        rejectRenamedOption(config, "current-batch-size", "query-batch-size");
        rejectRenamedOption(config, "current-parallelism", "query-parallelism");
        this.queryBatchSize = parsePositiveInt(
                config.option("query-batch-size"), DEFAULT_QUERY_BATCH_SIZE);
        this.queryParallelism = parsePositiveInt(
                config.option("query-parallelism"), DEFAULT_QUERY_PARALLELISM);
        this.queryBatchScope = parseBatchScope(config.option("query-batch-scope"));
        this.queryBatchRowBudget = parsePositiveInt(
                config.option("query-batch-row-budget"), DEFAULT_QUERY_BATCH_ROW_BUDGET);
        this.queryBatchCompletionMax = parsePositiveInt(
                config.option("query-batch-completion-max"), DEFAULT_QUERY_BATCH_COMPLETION_MAX);
        this.requireQueryService = parseBooleanOption(
                config.option("require-query-service"), true);
        this.zone = parseZone(config.option("zone"));
        this.shiftHour = parseShiftHour(config.option("shift-hour"));
    }

    /**
     * 解析日历口径时区。
     *
     * <p>接受任何 {@link ZoneId} 合法写法（{@code Asia/Shanghai}、{@code GMT+8}、
     * {@code UTC}）。写错时区会让每一天的边界整体平移，而报表数值看起来依然
     * 合理，因此宁可在启动期失败也不要按 UTC 兜底。</p>
     *
     * @param value 配置值，可为 {@code null} 或空白
     * @return 时区；未配置时为 {@link #DEFAULT_ZONE}
     * @throws TsdbConfigurationException 时区标识非法时
     */
    private static ZoneId parseZone(String value) {
        String trimmed = Texts.trimToNull(value);
        if (trimmed == null) {
            return ZoneId.of(DEFAULT_ZONE);
        }
        try {
            return ZoneId.of(trimmed);
        } catch (RuntimeException e) {
            throw new TsdbConfigurationException("invalid IoTDB zone: " + value, e);
        }
    }

    /**
     * 解析换班小时。
     *
     * @param value 配置值，可为 {@code null} 或空白
     * @return {@code 0..23} 的小时；未配置时为 {@link #DEFAULT_SHIFT_HOUR}
     * @throws TsdbConfigurationException 非数字或越界时
     */
    private static int parseShiftHour(String value) {
        String trimmed = Texts.trimToNull(value);
        if (trimmed == null) {
            return DEFAULT_SHIFT_HOUR;
        }
        try {
            int hour = Integer.parseInt(trimmed);
            if (hour < 0 || hour > 23) {
                throw new TsdbConfigurationException("IoTDB shift-hour must be 0..23: " + value);
            }
            return hour;
        } catch (NumberFormatException e) {
            throw new TsdbConfigurationException("invalid IoTDB shift-hour: " + value, e);
        }
    }

    /**
     * 解析布尔配置项。
     *
     * <p>只接受 {@code true}/{@code false}（忽略大小写与首尾空白），其他写法
     * 一律拒绝。本项控制的是“缺依赖时构造期失败还是静默降级”，把
     * {@code yes}、{@code 0} 这类误写悄悄当成 {@code false}，等于让一次笔误
     * 关掉整条快速失败路径。</p>
     *
     * @param value        配置值，可为 {@code null} 或空白
     * @param defaultValue 缺省值
     * @return 解析出的布尔值
     * @throws TsdbConfigurationException 取值不是 {@code true}/{@code false} 时
     */
    private static boolean parseBooleanOption(String value, boolean defaultValue) {
        String trimmed = Texts.trimToNull(value);
        if (trimmed == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        throw new TsdbConfigurationException("IoTDB boolean option must be true or false: " + value);
    }

    /**
     * 解析并校验默认数据库前缀。
     *
     * <p>只接受 {@code root.<database>} 两段格式，不接受通配符或更深的路径；
     * 默认库只用于补全“没有写 {@code root.} 前缀”的位号，不改变已经完整的路径。</p>
     *
     * @param value 配置值，可为 {@code null}
     * @return 规范化后的默认数据库；未配置时返回 {@code null}
     * @throws TsdbConfigurationException 格式非法时
     */
    private static String parseDefaultDatabase(String value) {
        String database = Texts.trimToNull(value);
        if (database == null) {
            return null;
        }
        while (database.endsWith(".")) {
            database = database.substring(0, database.length() - 1);
        }
        if (!database.matches("root\\.[\\p{L}\\p{N}_:\\-]+")) {
            throw new TsdbConfigurationException(
                    "IoTDB default-database must look like 'root.<database>': " + value);
        }
        return database;
    }

    /**
     * 解析 transport 配置，大小写不敏感。
     *
     * @param value 配置值，不能为 {@code null}
     * @return 对应通道
     * @throws TsdbConfigurationException 取值非法时
     */
    private static IotdbTransport parseTransport(String value) {
        try {
            return IotdbTransport.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new TsdbConfigurationException(
                    "IoTDB transport must be native, jdbc or rest: " + value, e);
        }
    }

    /**
     * 解析批量分组档位，大小写不敏感。
     *
     * <p>档位只改变一条语句覆盖的范围与 {@code FROM} 层级，不改变结果口径；
     * 写错的档位名（如 {@code db}）必须当场失败，因为按名字兜底到默认档会让
     * 现场以为自己在用回退档、实际跑的是库级批量。</p>
     *
     * @param value 配置值，可为 {@code null} 或空白
     * @return 分组档位；未配置时为 {@link #DEFAULT_QUERY_BATCH_SCOPE}
     * @throws TsdbConfigurationException 取值不是 {@code database}/{@code root}/{@code device} 时
     */
    private static Grouping parseBatchScope(String value) {
        String trimmed = Texts.trimToNull(value);
        if (trimmed == null) {
            return DEFAULT_QUERY_BATCH_SCOPE;
        }
        try {
            return Grouping.valueOf(trimmed.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new TsdbConfigurationException("IoTDB query-batch-scope must be database, root or device: "
                    + value, e);
        }
    }

    /**
     * 拒绝已改名的配置键。
     *
     * <p>改名不留别名（本轮裁决）：旧键还留在现网配置里说明这份配置没有被核对过，
     * 静默忽略就等于“上限还是旧的那个值”，而调用方读到的是新默认值。</p>
     *
     * @param config 实例配置
     * @param oldKey 已废弃的键名
     * @param newKey 替代的键名
     * @throws TsdbConfigurationException 旧键出现时
     */
    private static void rejectRenamedOption(TsdbInstanceConfig config, String oldKey, String newKey) {
        if (config.option(oldKey) != null) {
            throw new TsdbConfigurationException("IoTDB option '" + oldKey + "' was renamed to '" + newKey
                    + "'; remove the legacy key or move its value");
        }
    }

    /**
     * 解析 RPC 端口。
     *
     * @param value        配置值，可为 {@code null}
     * @param defaultValue 缺省端口
     * @return 合法端口
     * @throws TsdbConfigurationException 非数字或越界时
     */
    private static int parsePort(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65535) {
                throw new TsdbConfigurationException("IoTDB rpc-port out of range: " + value);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new TsdbConfigurationException("invalid IoTDB rpc-port: " + value, e);
        }
    }

    /**
     * 解析毫秒数配置，接受 0（表示“关闭该档行为”）。
     *
     * @param value        配置值，可为 {@code null} 或空白
     * @param defaultValue 缺省值
     * @return 非负的毫秒数
     * @throws TsdbConfigurationException 非数字或为负数时
     */
    private static long parseNonNegativeMillis(String value, long defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            long millis = Long.parseLong(value.trim());
            if (millis < 0) {
                throw new TsdbConfigurationException("IoTDB option must be >= 0: " + value);
            }
            return millis;
        } catch (NumberFormatException e) {
            throw new TsdbConfigurationException("invalid IoTDB millisecond option: " + value, e);
        }
    }

    /**
     * 解析正整数配置。
     *
     * @param value        配置值，可为 {@code null} 或空白
     * @param defaultValue 缺省值
     * @return 大于 0 的整数
     * @throws TsdbConfigurationException 非数字或小于等于 0 时
     */
    private static int parsePositiveInt(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed <= 0) {
                throw new TsdbConfigurationException("IoTDB option must be > 0: " + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new TsdbConfigurationException("invalid IoTDB integer option: " + value, e);
        }
    }

    /**
     * 返回实例逻辑名。
     *
     * @return 实例名，可能为 {@code null}
     */
    public String getInstanceName() {
        return instanceName;
    }

    /**
     * 返回服务基地址。
     *
     * @return 已去除尾部斜杠的地址，形如 {@code https://host:6667}，非 {@code null}
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 返回 REST 接口路径前缀。
     *
     * @return 以 {@code /} 开头、不以 {@code /} 结尾的前缀，非 {@code null}
     */
    public String getApiPrefix() {
        return apiPrefix;
    }

    /**
     * 返回 Basic 认证用户名。
     *
     * @return 已去除首尾空白的用户名，非 {@code null}
     */
    public String getUsername() {
        return username;
    }

    /**
     * 返回 Basic 认证密码。
     *
     * <p>返回值是明文 {@link String}，调用方不应将其打印到日志或写入异常信息。</p>
     *
     * @return 已去除首尾空白的密码，非 {@code null}
     */
    public String getPassword() {
        return password;
    }

    /**
     * 返回建立连接的超时时间。
     *
     * @return 超时毫秒数，恒大于 0
     */
    public long getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    /**
     * 返回读取响应的超时时间。
     *
     * @return 超时毫秒数，恒大于 0
     */
    public long getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    /**
     * 返回是否校验服务端证书的主机名。
     *
     * <p>仅对 {@code https} 生效；{@code false} 会接受任意主机名，
     * 只建议在自签名证书的测试环境中使用。</p>
     *
     * @return {@code true} 表示执行主机名校验
     */
    public boolean isVerifyHostname() {
        return verifyHostname;
    }

    /**
     * 返回配置声明的最大读取尝试次数。
     *
     * <p>注意：当前 REST 传输层不执行重试，该值不会影响实际请求行为，
     * 仅用于保留公共层配置语义。</p>
     *
     * @return 尝试次数，恒大于等于 1
     */
    public int getMaxReadAttempts() {
        return maxReadAttempts;
    }

    /**
     * 返回配置声明的重试退避时间。
     *
     * <p>注意：当前 REST 传输层不执行重试，该值不会影响实际请求行为。</p>
     *
     * @return 退避毫秒数，恒大于等于 0
     */
    public long getRetryBackoffMillis() {
        return retryBackoffMillis;
    }

    /**
     * 返回时间解析与展示配置。
     *
     * <p>IoTDB 协议时间仍是 epoch 毫秒；该配置用于调用方统一解析、
     * 格式化时间，不参与 SQL 条件或响应数值的转换。</p>
     *
     * @return 非 {@code null} 的不可变时间配置
     */
    public TimeConfig getTimeConfig() {
        return timeConfig;
    }

    /**
     * 返回是否允许写入。
     *
     * @return {@code true} 表示已开启 {@code tsdb.security.allow-write}
     */
    public boolean isWriteAllowed() {
        return allowWrite;
    }

    /**
     * 返回访问通道。
     *
     * @return {@code NATIVE}、{@code JDBC} 或 {@code REST}
     */
    public IotdbTransport getTransport() {
        return transport;
    }

    /**
     * 返回 RPC 主机名。
     *
     * @return 非 {@code null} 的主机名
     */
    public String getHost() {
        return host;
    }

    /**
     * 返回 RPC 端口。
     *
     * @return 1 到 65535 之间的端口
     */
    public int getRpcPort() {
        return rpcPort;
    }

    /**
     * 返回默认数据库前缀。
     *
     * <p>形如 {@code root.factory}；未配置时为 {@code null}。该值只用于补全
     * 缺少 {@code root.} 前缀的位号，完整路径不会被改写。</p>
     *
     * @return 默认数据库前缀，可能为 {@code null}
     */
    public String getDefaultDatabase() {
        return defaultDatabase;
    }

    /**
     * 返回位号映射表文件路径。
     *
     * <p>取自 {@code options.tag-mapping-file}，值为 properties 格式文件的绝对路径
     * 或相对于工作目录的路径；未配置时为 {@code null}，此时所有位号走
     * “补默认库前缀”的兜底清洗并计入映射未命中统计。</p>
     *
     * <p>本 getter 只返回配置原文：文件不存在或映射值非法是在
     * {@link IotdbDriver#create} 装载映射表时才暴露的，不在配置校验阶段做文件 IO。</p>
     *
     * @return 映射表文件路径，可能为 {@code null}
     */
    public String getTagMappingFile() {
        return tagMappingFile;
    }

    /**
     * 返回插值网格的单侧外推容差。
     *
     * <p>只作用于 {@code QueryMode.INTERPOLATED}：网格槽位两侧包围点齐全时的
     * 内插不受本项约束，只有槽位落在数据一端之外时才用它决定“还能保持多久”。
     * 默认 {@code 0} 表示禁止外推，超出的槽位输出空值。</p>
     *
     * @return 容差毫秒数，恒大于等于 0
     */
    public long getInterpolationToleranceMillis() {
        return interpolationToleranceMillis;
    }

    /**
     * 返回一次插值在网格窗口内允许拉取的原始点总量上限。
     *
     * <p>只作用于 {@code QueryMode.INTERPOLATED}。达到上限时驱动停止分页，
     * 并在序列属性里如实标记 {@code interpolationRawTruncated=true}，
     * 而不是静默给出依据不完整的曲线。</p>
     *
     * @return 原始点上限，恒大于 0
     */
    public int getInterpolationRawLimit() {
        return interpolationRawLimit;
    }

    /**
     * 返回单条批量 SQL 携带的表达式上限。
     *
     * <p>覆盖全部查询族：一批位号按档位分组后再按本上限顺序切块，只影响一次请求
     * 发几条 SQL，不改变返回结果。</p>
     *
     * @return 表达式上限，恒大于 0
     */
    public int getQueryBatchSize() {
        return queryBatchSize;
    }

    /**
     * 返回批量 SQL 在分组维度上的并行度上限。
     *
     * @return 并行度，恒大于 0；{@code 1} 表示逐条 SQL 顺序执行
     */
    public int getQueryParallelism() {
        return queryParallelism;
    }

    /**
     * 返回批量分组档位。
     *
     * <p>档位决定一条 SQL 的 {@code FROM} 前缀层级与列表达式层级（两者必须同源，
     * 否则服务端静默返回空），以及一次请求要发几条 SQL。结果口径与档位无关。</p>
     *
     * @return 非 {@code null} 的分组档位
     */
    public Grouping getQueryBatchScope() {
        return queryBatchScope;
    }

    /**
     * 返回批量原始值单条语句的行预算。
     *
     * <p>多列 {@code raw} 的 {@code LIMIT} 是合并时间戳后的行数，因此本值是
     * “某列是否可能还有尾部的点没取到”这条判定的分母。取实测的服务端上限
     * （超限 {@code code=708}），调大只会让语句被服务端拒绝。</p>
     *
     * @return 行预算，恒大于 0
     */
    public int getQueryBatchRowBudget() {
        return queryBatchRowBudget;
    }

    /**
     * 返回一个块内允许逐位号补采的位号数上限。
     *
     * <p>超过本上限时整块直接退回逐位号查询。两条路径给出的点逐位号相同，
     * 本值只控制往返数，不改变答案，也不掩盖任何数据缺失。</p>
     *
     * @return 补采位号数上限，恒大于 0
     */
    public int getQueryBatchCompletionMax() {
        return queryBatchCompletionMax;
    }

    /**
     * 返回是否要求装配统一 SQL 查询服务。
     *
     * <p>{@code true}（默认）时，未注入 {@code IotdbQueryService} 的
     * {@link IotdbClient} 在构造期就抛配置异常；{@code false} 才允许退回
     * “采样、插值、整窗增量不可用，聚合与最新值走 REST 直连”的旧路径。</p>
     *
     * @return {@code true} 表示缺少查询服务时必须快速失败
     */
    public boolean isRequireQueryService() {
        return requireQueryService;
    }

    /**
     * 返回日历口径时区。
     *
     * <p>天/月粒度的起点对齐按本时区计算，与只影响文本展示
     * {@link #getTimeConfig()} 无关。</p>
     *
     * @return 非 {@code null} 的时区
     */
    public ZoneId getZone() {
        return zone;
    }

    /**
     * 返回换班小时（当地时区）。
     *
     * @return {@code 0..23} 的小时
     */
    public int getShiftHour() {
        return shiftHour;
    }

    /**
     * 计算给定时刻所在班次的窗口。
     *
     * <p>班次从 {@link #getZone()} 时区的每天 {@link #getShiftHour()} 点整开始，
     * 长度固定 24 小时，窗口为<b>左闭右开</b> {@code [start, start + 24h)}，
     * 与 {@code HistoryQuery} 的时间区间口径一致。例如默认口径下
     * {@code 2026-11-13 07:59}（东八区）落在 {@code 11-12 08:00 ~ 11-13 08:00} 这一班。</p>
     *
     * <p>本方法是班次口径的<b>唯一换算落点</b>：调用方传绝对时刻即可，
     * 不应该再自己加减 8 小时。之所以放在 SDK 而不是业务里，是因为
     * “日界在哪一刻”取决于配置的时区与换班小时，业务硬编码偏移量时，
     * 一旦这两个配置改动（或换到现场时区不同的实例），错位的报表数值
     * 看起来仍然合理，事后无从发现。</p>
     *
     * @param at 班次内的任意时刻，不能为 {@code null}
     * @return 该时刻所属的班次窗口，非 {@code null}
     * @throws IllegalArgumentException 当 {@code at} 为 {@code null} 时
     */
    public ShiftWindow shiftWindow(Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("at is required");
        }
        ZonedDateTime local = at.atZone(zone);
        ZonedDateTime start = local.truncatedTo(ChronoUnit.DAYS).plusHours(shiftHour);
        if (local.isBefore(start)) {
            start = start.minusDays(1);
        }
        return new ShiftWindow(start.toInstant(), start.plusDays(1).toInstant());
    }

    /**
     * 班次窗口：{@code [start, end)}，长度为 24 小时。
     *
     * <p>由 {@link IotdbConfig#shiftWindow(Instant)} 产出，是不可变值对象，
     * 只承载边界时刻，不含任何位号或数值语义。</p>
     *
     * @author wanghao
     * @since 0.1.0
     */
    public static final class ShiftWindow {

        /** 班次起点（含），为 {@code zone} 时区某天 {@code shiftHour} 点整的绝对时刻。 */
        private final Instant start;

        /** 班次终点（不含），恒为 {@code start + 24h}。 */
        private final Instant end;

        /**
         * 构造班次窗口。
         *
         * @param start 起点（含），不能为 {@code null}
         * @param end   终点（不含），不能为 {@code null}
         */
        ShiftWindow(Instant start, Instant end) {
            this.start = start;
            this.end = end;
        }

        /**
         * 返回班次起点。
         *
         * @return 起点时刻（含），非 {@code null}
         */
        public Instant getStart() {
            return start;
        }

        /**
         * 返回班次终点。
         *
         * @return 终点时刻（不含），非 {@code null}
         */
        public Instant getEnd() {
            return end;
        }

        /**
         * 以可读形式返回班次窗口，便于日志与用例断言。
         *
         * @return 形如 {@code ShiftWindow[2026-11-12T00:00:00Z,2026-11-13T00:00:00Z)}
         */
        @Override
        public String toString() {
            return "ShiftWindow[" + start + "," + end + ")";
        }
    }

    /**
     * 拼接一个 REST v2 接口的完整地址。
     *
     * <p>结果为 {@code baseUrl + apiPrefix + path}，其中 {@code path} 会自动补上
     * 前导斜杠，因此传入 {@code "query"} 与 {@code "/query"} 等价。
     * 例如 {@code https://host:6667/rest/v2/query}。</p>
     *
     * @param path 相对路径，不能为 {@code null}
     * @return 可直接用于 HTTP 请求的绝对地址
     * @throws NullPointerException 当 {@code path} 为 {@code null} 时
     */
    public String apiUrl(String path) {
        return baseUrl + apiPrefix + normalize(path);
    }

    /**
     * 拼接一个不带 API 前缀的根路径地址。
     *
     * <p>用于 {@code /ping} 这类挂在服务根路径下、不在 {@code /rest/v2} 之内的
     * 端点；结果为 {@code baseUrl + path}，同样自动补前导斜杠。</p>
     *
     * @param path 相对路径，不能为 {@code null}
     * @return 可直接用于 HTTP 请求的绝对地址
     * @throws NullPointerException 当 {@code path} 为 {@code null} 时
     */
    public String rootUrl(String path) {
        return baseUrl + normalize(path);
    }

    /**
     * 为路径补齐前导斜杠，避免拼接出 {@code /rest/v2query} 这类错误地址。
     *
     * @param path 原始路径，不能为 {@code null}
     * @return 以 {@code /} 开头的路径
     */
    private static String normalize(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }
}
