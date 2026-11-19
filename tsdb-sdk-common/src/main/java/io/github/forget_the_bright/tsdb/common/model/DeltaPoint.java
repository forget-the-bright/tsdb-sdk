package io.github.forget_the_bright.tsdb.common.model;

import java.time.Instant;
import java.util.Date;

/**
 * 累计量差分结果中的一个时间点。
 *
 * <p>用于“累计值点位求区间增量”的场景：例如某流量点位记录的是累计流量，
 * 按月查询时，需要先取每个月末的累计值，再与上一个月末相减，得到本月用量。
 * 本类既承载差值，也记录差值是否因点位清零而重置、该区间是否缺数据。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class DeltaPoint {

    private final Instant timestamp;
    private final Date timestampDate;
    private final String timestampText;
    private final Double delta;
    private final boolean reset;
    private final boolean missing;

    /**
     * 创建差分点。
     *
     * @param timestamp     该差值所属桶的样本时间戳（驱动原样返回，不做起终点偏移），不能为 {@code null}
     * @param timestampDate Java 兼容时间视图，可为 {@code null}
     * @param timestampText 按实例时区格式化后的展示文本，可为 {@code null}
     * @param delta         差值；缺数据时为 {@code null}
     * @param reset         是否检测到累计值回落（通常表示点位清零）
     * @param missing       该区间是否缺数据
     */
    public DeltaPoint(Instant timestamp, Date timestampDate, String timestampText,
                      Double delta, boolean reset, boolean missing) {
        this.timestamp = timestamp;
        this.timestampDate = timestampDate;
        this.timestampText = timestampText;
        this.delta = delta;
        this.reset = reset;
        this.missing = missing;
    }

    /**
     * 返回该差值所属桶的样本时间戳（驱动原样返回，不做起终点偏移）。
     *
     * @return 绝对时刻，非 {@code null}
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * 返回 Java {@link Date} 视图。
     *
     * @return 时间视图；未配置时为 {@code null}
     */
    public Date getTimestampDate() {
        return timestampDate;
    }

    /**
     * 返回格式化后的展示文本。
     *
     * @return 展示文本；未配置时为 {@code null}
     */
    public String getTimestampText() {
        return timestampText;
    }

    /**
     * 返回差值。
     *
     * @return 差值；缺数据时为 {@code null}
     */
    public Double getDelta() {
        return delta;
    }

    /**
     * 判断是否检测到累计值回落。
     *
     * @return 回落时返回 {@code true}，此时差值取本区间末值
     */
    public boolean isReset() {
        return reset;
    }

    /**
     * 判断该区间是否缺数据。
     *
     * @return 缺数据返回 {@code true}
     */
    public boolean isMissing() {
        return missing;
    }
}
