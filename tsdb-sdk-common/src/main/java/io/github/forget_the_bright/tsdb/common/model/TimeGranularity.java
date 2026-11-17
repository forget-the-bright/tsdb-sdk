package io.github.forget_the_bright.tsdb.common.model;

import io.github.forget_the_bright.tsdb.common.exception.TsdbConfigurationException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 等间隔查询的时间粒度。
 *
 * <p>用于“按秒/分/时/天/月”查询历史数据。秒、分、时、天是固定长度；
 * 月是日历单位，长度随月份变化，不能换算成固定毫秒数，因此本枚举对月单独处理。</p>
 *
 * <p>月粒度会把查询起点归整到自然月的 1 号 00:00:00，并按日历逐月推进，
 * 避免用 {@code 30 天} 近似一个月造成跨月错位。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public enum TimeGranularity {

    /** 秒粒度，固定 1000 毫秒。 */
    SECOND("1s", 1000L),

    /** 分钟粒度，固定 60_000 毫秒。 */
    MINUTE("1m", 60_000L),

    /** 小时粒度，固定 3_600_000 毫秒。 */
    HOUR("1h", 3_600_000L),

    /** 天粒度，固定 86_400_000 毫秒。 */
    DAY("1d", 86_400_000L),

    /** 月粒度，按日历月推进，没有固定毫秒长度。 */
    MONTH("1mo", -1L);

    private final String literal;
    private final long millis;

    TimeGranularity(String literal, long millis) {
        this.literal = literal;
        this.millis = millis;
    }

    /**
     * 返回可直接拼入 IoTDB {@code GROUP BY} 的时间字面量。
     *
     * @return {@code 1s}、{@code 1m}、{@code 1h}、{@code 1d} 或 {@code 1mo}
     */
    public String getLiteral() {
        return literal;
    }

    /**
     * 返回固定毫秒长度。
     *
     * @return 固定粒度的毫秒数；月粒度返回 {@code -1}
     */
    public long getMillis() {
        return millis;
    }

    /**
     * 判断是否为日历月粒度。
     *
     * @return 月粒度返回 {@code true}
     */
    public boolean isCalendarMonth() {
        return this == MONTH;
    }

    /**
     * 把查询起点归整到自然边界。
     *
     * <p>天粒度归整到当天 00:00:00，小时归整到整点，分钟/秒同理；
     * 月粒度归整到当月 1 号 00:00:00。时区由调用方提供，
     * 不硬编码东八区。</p>
     *
     * @param instant 原始起点，不能为 {@code null}
     * @param zone    业务时区，不能为 {@code null}
     * @return 归整后的起点
     * @throws TsdbConfigurationException 参数为空时
     */
    public Instant normalizeStart(Instant instant, ZoneId zone) {
        if (instant == null || zone == null) {
            throw new TsdbConfigurationException("instant and zone are required");
        }
        ZonedDateTime time = instant.atZone(zone);
        switch (this) {
            case SECOND:
                return time.truncatedTo(ChronoUnit.SECONDS).toInstant();
            case MINUTE:
                return time.truncatedTo(ChronoUnit.MINUTES).toInstant();
            case HOUR:
                return time.truncatedTo(ChronoUnit.HOURS).toInstant();
            case DAY:
                return time.truncatedTo(ChronoUnit.DAYS).toInstant();
            case MONTH:
                return time.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
            default:
                return instant;
        }
    }
}
