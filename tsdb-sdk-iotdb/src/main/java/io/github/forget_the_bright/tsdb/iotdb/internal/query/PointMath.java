package io.github.forget_the_bright.tsdb.iotdb.internal.query;

import io.github.forget_the_bright.tsdb.common.config.TimeConfig;
import io.github.forget_the_bright.tsdb.common.model.DeltaPoint;
import io.github.forget_the_bright.tsdb.common.model.Direction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import io.github.forget_the_bright.tsdb.iotdb.internal.IotdbModels;

/**
 * 由原始采样点/锚点推导报表数值点的纯计算（插值网格 + 累计差分）。
 *
 * <p>从 {@link IotdbQueryService} 拆出，承载两套 IoTDB <b>没有原生对应物</b>的
 * 数值学：固定网格线性插值（{@link #gridOf}）与累计量差分/整窗增量
 * （{@link #deltaSeries}、{@link #intervalSumOf}）。这里只做内存计算，不发起
 * SQL；取数由 {@link IotdbQueryService} 编排，结果点构造复用 {@link ResultColumns#point}。</p>
 *
 * <p>逐位号与批量两条查询路径共用本类，保证两种形态下槽位值、{@code reset}
 * （计数器归零）与 {@code missing}（缺数）的口径完全相同——这正是“批量后的
 * 结果与逐位号逐点相同”的落点。不可运算的值一律返回 {@code null}，绝不编造
 * 一个“看起来对”的数字。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
final class PointMath {

    private final TimeConfig timeConfig;
    private final ResultColumns columns;

    /**
     * 创建数值推导器。
     *
     * @param timeConfig 实例时间配置，用于构造差分点，不能为 {@code null}
     * @param columns    结果点构造器，不能为 {@code null}
     */
    PointMath(TimeConfig timeConfig, ResultColumns columns) {
        this.timeConfig = timeConfig;
        this.columns = columns;
    }

    /**
     * 把原始值转 {@link Double}：数值取 {@code doubleValue()}，文本尝试解析，
     * 其余（布尔、{@code null}、不可解析文本）返回 {@code null}。
     *
     * @param value 原始值，可为 {@code null}
     * @return 双精度值；不可运算时 {@code null}
     */
    Double numeric(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 把首末锚点转换为 {@link BigDecimal}。
     *
     * <p>累计量是报表数值，二进制浮点相减会把 {@code 0.1} 这类不可精确表示的
     * 小数误差带进结果，因此统一走“先转文本、再构造 {@code BigDecimal}”的路径，
     * 避免 {@code new BigDecimal(double)} 把误差固化下来。布尔、文本等非数值
     * 返回 {@code null}，由调用方按“锚点缺失”处理。</p>
     *
     * @param value 原始锚点值，可为 {@code null}
     * @return 十进制精确值；不可解析时 {@code null}
     */
    BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (!(value instanceof Number) && !(value instanceof String)) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 按槽位时刻求插值。
     *
     * <p>两侧包围点都有数值时线性内插，内插跨度不做限制；只剩单侧时只有落在
     * {@code toleranceMillis} 之内才保持该侧值，容差为 0 表示一律不外推。文本、
     * 布尔等不可运算的值返回 {@code null}，驱动不会为它们编造一个“看起来对”的数字。</p>
     *
     * @param previous        左侧包围点，可为 {@code null}
     * @param next            右侧包围点，可为 {@code null}
     * @param slotTime        槽位时刻（epoch 毫秒）
     * @param toleranceMillis 单侧外推容差（毫秒）
     * @return 插值得到的数值；无法插值时 {@code null}
     */
    Double interpolatedValue(IotdbModels.Point previous, IotdbModels.Point next,
                             long slotTime, long toleranceMillis) {
        Double left = previous == null ? null : numeric(previous.getValue());
        Double right = next == null ? null : numeric(next.getValue());
        if (left != null && right != null) {
            long leftTime = previous.getTimestamp().toEpochMilli();
            long rightTime = next.getTimestamp().toEpochMilli();
            if (rightTime == leftTime) {
                return left;
            }
            double ratio = (double) (slotTime - leftTime) / (double) (rightTime - leftTime);
            return Double.valueOf(left + (right - left) * ratio);
        }
        if (left != null) {
            long distance = slotTime - previous.getTimestamp().toEpochMilli();
            return toleranceMillis > 0 && distance <= toleranceMillis ? left : null;
        }
        if (right != null) {
            long distance = next.getTimestamp().toEpochMilli() - slotTime;
            return toleranceMillis > 0 && distance <= toleranceMillis ? right : null;
        }
        return null;
    }

    /**
     * 把插值用的采样点按时刻排序后铺到固定网格上。
     *
     * <p>逐位号与批量共用本方法，避免“两条路径各写一遍内插数学”——那是最容
     * 易出现批量与逐位号差一个槽位的地方。</p>
     *
     * @param samples         包围点与窗内原始点的合集，顺序任意
     * @param first           第 0 个槽位的时刻（毫秒）
     * @param stepMillis      槽位间隔（毫秒）
     * @param slots           槽位数
     * @param toleranceMillis 单侧外推容差（毫秒）
     * @return 长度为 {@code slots} 的网格点列表，时间戳恰为网格时刻
     */
    List<IotdbModels.Point> gridOf(List<IotdbModels.Point> samples, long first, long stepMillis, int slots,
                                   long toleranceMillis) {
        List<IotdbModels.Point> ordered = new ArrayList<IotdbModels.Point>(zeroIfNull(samples));
        Collections.sort(ordered, new Comparator<IotdbModels.Point>() {
            @Override
            public int compare(IotdbModels.Point left, IotdbModels.Point right) {
                return Long.compare(left.getTimestamp().toEpochMilli(), right.getTimestamp().toEpochMilli());
            }
        });

        List<IotdbModels.Point> grid = new ArrayList<IotdbModels.Point>(slots);
        int index = 0;
        for (int slot = 0; slot < slots; slot++) {
            long slotTime = first + stepMillis * (long) slot;
            while (index < ordered.size() && ordered.get(index).getTimestamp().toEpochMilli() < slotTime) {
                index++;
            }
            IotdbModels.Point next = index < ordered.size() ? ordered.get(index) : null;
            if (next != null && next.getTimestamp().toEpochMilli() == slotTime) {
                grid.add(next);
                continue;
            }
            IotdbModels.Point previous = index > 0 ? ordered.get(index - 1) : null;
            grid.add(columns.point(slotTime, interpolatedValue(previous, next, slotTime, toleranceMillis)));
        }
        return grid;
    }

    /**
     * 把 {@code null} 列表当空列表处理，用于批量结果里可能缺键的位号。
     *
     * @param points 点列表，可为 {@code null}
     * @return 非 {@code null} 的列表
     */
    List<IotdbModels.Point> zeroIfNull(List<IotdbModels.Point> points) {
        return points == null ? Collections.<IotdbModels.Point>emptyList() : points;
    }

    /**
     * 由“桶末值序列 + 起点基线”算差分序列。
     *
     * <p>逐位号与批量两条路径共用这段计算，保证两种查询形态下 {@code reset}
     *（计数器归零后取当前值而不是负增量）与 {@code missing}（本桶或上一条锚点
     * 缺失）的口径完全相同。</p>
     *
     * @param buckets   按时间升序的桶末值序列
     * @param anchor    起点之前的基线点，可为 {@code null}
     * @param direction 返回方向
     * @param maxPoints 最大点数，非正数表示不裁剪
     * @return 差分点列表
     */
    List<DeltaPoint> deltaSeries(List<IotdbModels.Point> buckets, IotdbModels.Point anchor,
                                 Direction direction, int maxPoints) {
        Double previous = anchor == null ? null : numeric(anchor.getValue());
        List<DeltaPoint> result = new ArrayList<DeltaPoint>();
        for (IotdbModels.Point bucket : buckets) {
            Double current = numeric(bucket.getValue());
            if (current == null) {
                result.add(delta(bucket.getTimestamp().toEpochMilli(), null, false, true));
                continue;
            }
            if (previous == null) {
                result.add(delta(bucket.getTimestamp().toEpochMilli(), null, false, true));
            } else {
                double difference = current - previous;
                boolean reset = difference < 0;
                result.add(delta(bucket.getTimestamp().toEpochMilli(), reset ? current : difference, reset, false));
            }
            previous = current;
            if (maxPoints > 0 && result.size() >= maxPoints) {
                break;
            }
        }
        if (direction == Direction.BACKWARD) {
            Collections.reverse(result);
        }
        return result;
    }

    /**
     * 构造一个差分点，时间文本按实例时间配置格式化。
     *
     * @param timestamp epoch 毫秒
     * @param value     差分值，可为 {@code null}
     * @param reset     是否计数器归零
     * @param missing   是否缺数
     * @return 差分点
     */
    private DeltaPoint delta(long timestamp, Double value, boolean reset, boolean missing) {
        Instant instant = Instant.ofEpochMilli(timestamp);
        return new DeltaPoint(instant, java.util.Date.from(instant),
                timeConfig.formatResponse(instant), value, reset, missing);
    }

    /**
     * 由“首值列 + 末值列”算出整窗增量。
     *
     * @param columns 两列（首值列 + 末值列）
     * @param start   窗口起点（毫秒），作为结果点的时间戳
     * @return 单点增量；任一锚点缺失或不可运算时 {@code null}
     */
    IotdbModels.Point intervalSumOf(List<List<IotdbModels.Point>> columns, long start) {
        Object first = columns.get(0).isEmpty() ? null : columns.get(0).get(0).getValue();
        Object last = columns.get(1).isEmpty() ? null : columns.get(1).get(0).getValue();
        BigDecimal firstValue = decimal(first);
        BigDecimal lastValue = decimal(last);
        if (firstValue == null || lastValue == null) {
            return null;
        }
        return this.columns.point(start, lastValue.subtract(firstValue));
    }
}
