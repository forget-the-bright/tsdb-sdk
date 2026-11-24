package io.github.forget_the_bright.tsdb.pspace.internal;

import com.alibaba.fastjson.JSONArray;

import java.time.Instant;
import java.util.List;

/**
 * pSpace 原生响应的轻量载体（不可变值类型集中定义）。
 *
 * <p>只承载已经解析、尚未映射成公共模型的字段；值本身保持 {@code Object}
 * （{@code Double}/{@code Long}/{@code String}/{@code Boolean}），由
 * {@link PspaceMapper} 结合位号数据类型再做归一化。所有类均为静态内部类，
 * 字段 {@code final}，可安全跨线程传递。</p>
 *
 * <p><b>非公共 API</b>：随内部重构变化，宿主应用请勿直接依赖。</p>
 *
 * @author wanghao
 * @since 0.1.2
 */
public final class PspaceModels {

    private PspaceModels() {
    }

    /**
     * 点位元数据的一行（来自 {@code /Point*} 与 {@code /PointFilter}）。
     */
    public static final class Point {

        /** 点位 id（TagId）。 */
        public final long id;

        /** 短名（Name），通常为空。 */
        public final String name;

        /** 长名（LongName），SDK 中即“位号名”。 */
        public final String longName;

        /** 描述（Desc），可能为 {@code null}。 */
        public final String description;

        /** 工程单位（EngUnit），可能为 {@code null}。 */
        public final String unit;

        /** 父节点 id（ParentId），根为 0。 */
        public final long parentId;

        /** 厂商 DataType 码，未知时为 -1。 */
        public final int dataType;

        /** 厂商 TagType 码，未知时为 -1。 */
        public final int tagType;

        /** 是否为目录节点（IsNode）。 */
        public final boolean node;

        /** 是否保存历史（His_IsSave）；未返回时为 {@code null}。 */
        public final Boolean archive;

        /**
         * @param id          点位 id
         * @param name        短名，可为 {@code null}
         * @param longName    长名，可为 {@code null}
         * @param description 描述，可为 {@code null}
         * @param unit        工程单位，可为 {@code null}
         * @param parentId    父节点 id
         * @param dataType    厂商 DataType 码
         * @param tagType     厂商 TagType 码
         * @param node        是否为目录节点
         * @param archive     是否保存历史，可为 {@code null}
         */
        public Point(long id, String name, String longName, String description, String unit,
                     long parentId, int dataType, int tagType, boolean node, Boolean archive) {
            this.id = id;
            this.name = name;
            this.longName = longName;
            this.description = description;
            this.unit = unit;
            this.parentId = parentId;
            this.dataType = dataType;
            this.tagType = tagType;
            this.node = node;
            this.archive = archive;
        }
    }

    /**
     * 实时值的一行（来自 {@code /RealData}）。
     */
    public static final class RealValue {

        /** 点位 id（pid）。 */
        public final long id;

        /** 原始值（pv），可能为 {@code null}。 */
        public final Object value;

        /** 采集时刻（tm），可能为 {@code null}。 */
        public final Instant timestamp;

        /** 厂商质量码（qy），可能为 {@code null}。 */
        public final Integer quality;

        /**
         * @param id        点位 id
         * @param value     原始值
         * @param timestamp 采集时刻，可为 {@code null}
         * @param quality   厂商质量码，可为 {@code null}
         */
        public RealValue(long id, Object value, Instant timestamp, Integer quality) {
            this.id = id;
            this.value = value;
            this.timestamp = timestamp;
            this.quality = quality;
        }
    }

    /**
     * 历史序列中的一个值。
     */
    public static final class HistValue {

        /** 样本时刻。 */
        public final Instant timestamp;

        /** 原始值，可能为 {@code null}。 */
        public final Object value;

        /** 厂商质量码，可能为 {@code null}。 */
        public final Integer quality;

        /**
         * @param timestamp 样本时刻，非 {@code null}
         * @param value     原始值
         * @param quality   厂商质量码，可为 {@code null}
         */
        public HistValue(Instant timestamp, Object value, Integer quality) {
            this.timestamp = timestamp;
            this.value = value;
            this.quality = quality;
        }
    }

    /**
     * 一个点位的历史序列（来自 {@code /HistData*}）。
     */
    public static final class HistRow {

        /** 点位 id（pid）。 */
        public final long id;

        /** 按服务端顺序排列的值，非 {@code null}。 */
        public final List<HistValue> values;

        /**
         * @param id     点位 id
         * @param values 值列表，非 {@code null}
         */
        public HistRow(long id, List<HistValue> values) {
            this.id = id;
            this.values = values;
        }
    }

    /**
     * 分页响应（来自 {@code /PointPage}、{@code /HistDataPage} 等）。
     *
     * <p>{@code values} 保持原始 JSON，因为不同端点的元素结构不同，由调用方
     * 按端点语义解析；{@code pageCount} 是服务端给出的总页数。当前页号与页大小
     * 对翻页判断没有作用，故不保留。</p>
     */
    public static final class Page {

        /** 服务端记录总数。 */
        public final long total;

        /** 服务端总页数（{@code page.count}）；0 表示厂商未返回。 */
        public final int pageCount;

        /** 当前页原始元素，非 {@code null}（缺失时为空数组）。 */
        public final JSONArray values;

        /**
         * @param total     总记录数
         * @param pageCount 总页数，0 表示未返回
         * @param values    当前页元素，可为 {@code null}（归一化为空数组）
         */
        public Page(long total, int pageCount, JSONArray values) {
            this.total = total;
            this.pageCount = pageCount;
            this.values = values == null ? new JSONArray() : values;
        }
    }
}