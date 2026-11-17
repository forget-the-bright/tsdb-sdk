package io.github.forget_the_bright.tsdb.iotdb.internal.sql;

import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;
import io.github.forget_the_bright.tsdb.iotdb.IotdbConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IoTDB 位号解析器：映射表优先、兜底清洗并计数。
 *
 * <p><b>为什么需要它</b>：现场业务位号与 IoTDB 的树模型路径不是机械变换关系，
 * 而同一条批量 SQL 的 {@code FROM} 前缀与 SELECT 表达式必须由同一份层级派生。
 * 本类负责“这个名字该落到哪条路径上”（映射、默认库、统计报警），
 * {@link IotdbTagPath} 负责“这条路径本身合不合法、每一级是什么”。
 * 把两件事分开，字符校验就只剩一处，SQL 生成侧不必再重复校验同一个字符串。</p>
 *
 * <p><b>映射优先，清洗兜底</b>：铜陵 891 个字面位号中 779 个含连字符，另有纯数字
 * 段、中文占位与 {@code ${expr}} 模板；之所以不允许清洗器改写字符，是因为 IoTDB
 * 查询不存在的序列<b>返回空结果而不是报错</b>，改写出来的错路径会表现为
 * “数据静默为空”，比失败更难发现。映射表的值在启动期就被
 * {@link TagPathMapper#of(java.util.Map)} 解析成 {@link IotdbTagPath}，
 * 命中即直接复用该对象，运行期零解析开销；未命中才走兜底清洗并计数报警。</p>
 *
 * <p><b>层级切分依据</b>：设备与测量点的切分基于<b>解析后的规范路径</b>，
 * 而不是调用方传入的原始位号。业务位号 {@code BGZH1A.X-PV} 若在映射表里对应
 * {@code root.factory.线1.BGZH1A.X-PV}，设备路径就是 {@code root.factory.线1.BGZH1A}
 * （比原始位号多一层），按原始位号猜切分会把它错切成 {@code device=BGZH1A}。</p>
 *
 * <p><b>解析规则</b>（由 {@link IotdbTagPath#parse(String, String)} 执行）：</p>
 * <ol>
 *   <li>映射表命中时，映射值必须是完整 {@code root.} 路径，直接使用；</li>
 *   <li>首节点是 {@code root} → 视为全名称，数据库由路径自己给出；
 *       {@code root.factoryx.d.m} 属于 {@code root.factoryx}，不会被默认库改写；</li>
 *   <li>首节点不是 {@code root} → 用 {@code options.default-database}（形如
 *       {@code root.factory}）补齐两级库名；没配默认库就抛
 *       {@link TsdbDataException}，而不是让服务端返回含糊的空结果；</li>
 *   <li>已用反引号包裹的位号先拆成节点、按同一套白名单校验，再规范化重发，
 *       因此「已经处理过」的入参与「没处理」的入参得到同一个对象；</li>
 *   <li>未替换的模板占位（{@code ${...}}、{@code #{...}}）与不含任何 ASCII
 *       字母数字的纯中文位号（现场多为占位说明）显式拒绝；</li>
 *   <li>含逗号或分号的“多位号串”显式拒绝：SDK 不替调用方拆参数，拆分由业务侧
 *       的集合入口完成，否则整串会被拼成一个不存在的序列而表现为查空；</li>
 *   <li>解析后的路径至少包含“数据库、设备、测量点”三段，最后一个节点是测量点；
 *       数据库名固定是第二段。</li>
 * </ol>
 *
 * <p><b>多数据库</b>：不同位号可以带不同的 {@code root.db} 前缀，本类不要求所有位号
 * 属于同一个数据库；查询层按 {@link IotdbTagPath#getDatabase()} 分组生成 SQL，
 * 因此跨库查询天然可用。</p>
 *
 * <p><b>安全</b>：本类不拼 SQL，只做“名字 → 已校验路径”。真正的标识符引用与 SQL
 * 拼接在 {@link IotdbSql} 完成，路径的字符白名单在 {@link IotdbTagPath} 唯一把关；
 * 引号、分号、空白等元字符在进入 SQL 之前就会被拒绝，不会被静默放过。</p>
 *
 * <p><b>线程安全</b>：配置字段为 {@code final}，统计计数使用原子类型与并发集合，
 * 因此可与多线程共享。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class IotdbTagResolver {

    private static final Logger LOG = LoggerFactory.getLogger(IotdbTagResolver.class);

    /**
     * 已告警位号的上限：映射覆盖率不足时，只报警前这么多条去重后的位号，
     * 避免刷屏；总数仍照常累计在 {@link TagMappingStats#getFallbackTotal()} 中。
     */
    private static final int WARN_TAG_LIMIT = 4096;

    private final String defaultDatabase;
    private final TagPathMapper tagPathMapper;

    /** 映射表命中次数。 */
    private final AtomicLong mappedHits = new AtomicLong();
    /** 兜底清洗次数（等于“映射表没覆盖到”的次数，是切换门槛的核心指标）。 */
    private final AtomicLong fallbackHits = new AtomicLong();
    /** 去重后的兜底位号集合，用于报警与覆盖率清单。 */
    private final Set<String> fallbackTags = ConcurrentHashMap.newKeySet();

    /**
     * 创建解析器（不带映射表，全部位号走兜底清洗）。
     *
     * @param config 驱动配置，不能为 {@code null}
     */
    public IotdbTagResolver(IotdbConfig config) {
        this(config, null);
    }

    /**
     * 创建可插映射表的解析器。
     *
     * @param config  驱动配置，不能为 {@code null}
     * @param mapper  位号映射表；为 {@code null} 时等价于 {@link TagPathMapper#none()}，
     *                即所有位号走兜底清洗
     */
    public IotdbTagResolver(IotdbConfig config, TagPathMapper mapper) {
        this.defaultDatabase = config.getDefaultDatabase();
        this.tagPathMapper = mapper == null ? TagPathMapper.none() : mapper;
    }

    /**
     * 返回默认数据库前缀。
     *
     * @return 形如 {@code root.factory}；未配置时为 {@code null}
     */
    public String getDefaultDatabase() {
        return defaultDatabase;
    }

    /**
     * 返回映射命中与兜底统计。
     *
     * <p>切换硬门槛是“映射覆盖率 100%”，即 {@code fallbackTotal} 应为 0；
     * 运维可直接读取该统计生成未覆盖位号清单，交业主补映射表。</p>
     *
     * @return 非 {@code null} 的统计快照
     */
    public TagMappingStats mappingStats() {
        return new TagMappingStats(mappedHits.get(), fallbackHits.get(),
                new ArrayList<String>(new LinkedHashSet<String>(fallbackTags)));
    }

    /**
     * 把调用方传入的位号解析为路径对象。
     *
     * <p>解析顺序：映射表命中 → 直接用启动期已解析好的对象（运行期零解析开销）；
     * 未命中 → 记一次兜底、拒绝多值串，再交给 {@link IotdbTagPath#parse(String, String)}
     * 做唯一的字符与层级校验。校验只在这一处发生，因此调用方不会再看到同一个
     * 字符串被三处白名单各查一遍。</p>
     *
     * @param tag 原始位号，可为全名称、相对名称或已加反引号的名称
     * @return 非 {@code null} 的路径对象
     * @throws TsdbDataException 位号为空、含多值分隔符、是未替换占位、缺少默认库、
     *                           层级不足或含非法字符
     */
    public IotdbTagPath resolvePath(String tag) {
        if (tag == null || tag.trim().isEmpty()) {
            throw new TsdbDataException("IoTDB tag must not be blank");
        }
        String raw = tag.trim();
        IotdbTagPath mapped = tagPathMapper.tagPath(raw);
        if (mapped != null) {
            mappedHits.incrementAndGet();
            return mapped;
        }
        recordFallback(raw);
        rejectMultiValue(raw);
        return IotdbTagPath.parse(raw, defaultDatabase);
    }

    /**
     * 把标签查询的掩码/前缀解析为完整路径前缀。
     *
     * <p>调用方可以传 {@code device01.*}、{@code device01.**} 或
     * {@code root.other.device01.**}。本方法会去掉末尾的 {@code *}/{@code .}，
     * 再补默认数据库；查询层随后统一拼 {@code .**} 执行
     * {@code SHOW TIMESERIES}。空掩码表示查询默认数据库下的全部位号；未配置
     * 默认数据库时返回 {@code null}，表示查询所有数据库。</p>
     *
     * <p><b>映射表不参与前缀解析</b>：目录浏览的语义是“按层级树列出序列”，
     * 掩码本身就是 IoTDB 路径；映射表只解决“业务名字 → 序列”的对应关系，
     * 用它改写掩码会让浏览结果随映射表变化而失去可预期性。</p>
     *
     * @param mask 名称掩码或路径前缀，可为空
     * @return 完整前缀；空掩码且无默认库时为 {@code null}
     * @throws TsdbDataException 前缀非法或缺少默认库
     */
    public String resolvePrefix(String mask) {
        if (mask == null || mask.trim().isEmpty()) {
            return defaultDatabase;
        }
        String prefix = mask.trim();
        while (prefix.endsWith("*") || prefix.endsWith(".")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        if (prefix.isEmpty()) {
            return defaultDatabase;
        }
        if (!prefix.startsWith("root.")) {
            if (defaultDatabase == null) {
                throw new TsdbDataException("IoTDB tag mask must start with 'root.' or configure "
                        + "options.default-database: " + mask);
            }
            prefix = defaultDatabase + "." + prefix;
        }
        if (!IotdbSql.isValidMask(prefix) || prefix.contains("..") || prefix.endsWith(".")) {
            throw new TsdbDataException("invalid IoTDB tag prefix: " + mask);
        }
        return prefix;
    }

    /**
     * 拒绝把含多值分隔符的位号当单个位号解析。
     *
     * <p>业务侧确有把多个位号用逗号拼成一个参数的写法，但 SDK 从没有把它拆开过
     * （整串直接拼进路径就是查空）。静默按整体处理会拼出一个不存在的路径，表现为
     * “数据缺失”，因此这里显式报错，并要求调用方自己传集合。</p>
     *
     * @param raw 已 trim 的原始位号
     * @throws TsdbDataException 位号含逗号或分号
     */
    private static void rejectMultiValue(String raw) {
        if (indexOfSeparator(raw) >= 0) {
            throw new TsdbDataException("IoTDB tag contains multiple values separated by ',' or ';', "
                    + "pass each tag as a separate element of the collection: " + raw);
        }
    }

    private static int indexOfSeparator(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ',' || c == ';') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 记录一次兜底解析，并按位号去重报警。
     *
     * @param raw 原始位号
     */
    private void recordFallback(String raw) {
        fallbackHits.incrementAndGet();
        if (fallbackTags.size() < WARN_TAG_LIMIT && fallbackTags.add(raw)) {
            LOG.warn("IoTDB tag mapping miss, falling back to prefix completion: tag={}, "
                    + "defaultDatabase={}, mappedEntries={}", raw, defaultDatabase, tagPathMapper.size());
        }
    }

    /**
     * 位号映射统计快照。
     *
     * <p>用于灰度期观察映射覆盖率：{@code fallbackTotal} 持续增长说明映射表
     * 缺条目，而 IoTDB 对不存在序列返回空结果，所以这些位号在业务侧表现为
     * “数据缺失”，必须逐条补映射而不是调容差。</p>
     */
    public static final class TagMappingStats {

        private final long mappedTotal;
        private final long fallbackTotal;
        private final List<String> fallbackTags;

        TagMappingStats(long mappedTotal, long fallbackTotal, List<String> fallbackTags) {
            this.mappedTotal = mappedTotal;
            this.fallbackTotal = fallbackTotal;
            this.fallbackTags = Collections.unmodifiableList(fallbackTags);
        }

        /**
         * @return 映射表命中次数
         */
        public long getMappedTotal() {
            return mappedTotal;
        }

        /**
         * @return 走兜底清洗的次数（映射表未覆盖的次数）
         */
        public long getFallbackTotal() {
            return fallbackTotal;
        }

        /**
         * @return 去重后的兜底位号清单（有条数上限，超出后只累计总数不再入清单）
         */
        public List<String> getFallbackTags() {
            return fallbackTags;
        }

        /**
         * @return 映射命中率；无任何解析时返回 {@code 1.0}
         */
        public double mappedRatio() {
            long total = mappedTotal + fallbackTotal;
            return total == 0L ? 1.0d : (double) mappedTotal / (double) total;
        }

        @Override
        public String toString() {
            return "TagMappingStats{mapped=" + mappedTotal + ", fallback=" + fallbackTotal
                    + ", distinctFallbackTags=" + fallbackTags.size() + "}";
        }
    }
}
