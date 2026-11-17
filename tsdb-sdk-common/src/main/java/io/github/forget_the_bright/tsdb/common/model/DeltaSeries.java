package io.github.forget_the_bright.tsdb.common.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 单个累计量点位的差分序列。
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class DeltaSeries {

    private final String tagName;
    private final List<DeltaPoint> points;

    /**
     * 创建差分序列。
     *
     * @param tagName 位号名，不能为 {@code null}
     * @param points  差分点列表，可为 {@code null}
     */
    public DeltaSeries(String tagName, List<DeltaPoint> points) {
        this.tagName = Objects.requireNonNull(tagName, "tagName");
        this.points = points == null
                ? Collections.<DeltaPoint>emptyList()
                : Collections.unmodifiableList(new ArrayList<DeltaPoint>(points));
    }

    /**
     * 返回位号名。
     *
     * @return 非 {@code null}
     */
    public String getTagName() {
        return tagName;
    }

    /**
     * 返回差分点列表。
     *
     * @return 不可修改列表，非 {@code null}
     */
    public List<DeltaPoint> getPoints() {
        return points;
    }
}
