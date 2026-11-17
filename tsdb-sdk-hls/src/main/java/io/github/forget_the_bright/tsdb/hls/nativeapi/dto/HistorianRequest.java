package io.github.forget_the_bright.tsdb.hls.nativeapi.dto;

import com.alibaba.fastjson.annotation.JSONField;


import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * 历史值查询请求体。
 *
 * <p>对应 HLS <code>POST /hdb/read_hdbtagvalue</code>。字段含义：</p>
 * <ul>
 *   <li><code>startTime</code>/<code>endTime</code>：查询区间，单位为秒
 *       （和利时接口按秒接收；公共层会先把 {@link Instant} 转成 epoch 秒）。</li>
 *   <li><code>interval</code>：聚合间隔，单位为秒。</li>
 *   <li><code>NeedQueryAVG/MIN/MAX/Bound</code>：需要返回的聚合列，至少一个为 true。</li>
 *   <li><code>TagNameList</code>：标签列表。</li>
 * </ul>
 *
 * <p><strong>与原 SDK 的差异：</strong>原 SDK 的
 * {@code buildRequest} 在最后两行把 MAX/MIN 写反
 * （<code>setNeedQueryMAX(needQueryMIN)</code>），本实现已修正；
 * 其余字段与 JSON 名称保持一致。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class HistorianRequest {

    /** 开始时间，epoch 秒。 */
    @JSONField(name = "startTime")
    private Long startTime;

    /** 结束时间，epoch 秒。 */
    @JSONField(name = "endTime")
    private Long endTime;

    /** 聚合间隔，单位秒。 */
    @JSONField(name = "interval")
    private Long interval;

    /** 是否需要最大值。 */
    @JSONField(name = "NeedQueryMAX")
    private Boolean needQueryMAX;

    /** 是否需要最小值。 */
    @JSONField(name = "NeedQueryMIN")
    private Boolean needQueryMIN;

    /** 是否需要平均值。 */
    @JSONField(name = "NeedQueryAVG")
    private Boolean needQueryAVG;

    /** 是否需要边界值。 */
    @JSONField(name = "NeedQueryBound")
    private Boolean needQueryBound;

    /** 标签列表。 */
    @JSONField(name = "TagNameList")
    private List<TagNameRequest> tagNameList = new ArrayList<TagNameRequest>();

    /** 构造空请求。 */
    public HistorianRequest() {
    }

    /**
     * 构建历史查询请求。
     *
     * @param startTime     开始时间
     * @param endTime       结束时间
     * @param intervalSeconds 聚合间隔（秒）
     * @param needQueryAVG  是否需要平均值
     * @param needQueryMIN  是否需要最小值
     * @param needQueryMAX  是否需要最大值
     * @param needQueryBound 是否需要边界值
     * @param tagNames      标签名称集合
     * @return 请求对象
     * @throws IllegalArgumentException 标签为空，或四个 need 全为 false
     */
    public static HistorianRequest buildRequest(Instant startTime, Instant endTime, Long intervalSeconds,
                                                Boolean needQueryAVG, Boolean needQueryMIN,
                                                Boolean needQueryMAX, Boolean needQueryBound,
                                                Collection<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            throw new IllegalArgumentException("tagNames 不能为空");
        }
        boolean avg = Boolean.TRUE.equals(needQueryAVG);
        boolean min = Boolean.TRUE.equals(needQueryMIN);
        boolean max = Boolean.TRUE.equals(needQueryMAX);
        boolean bound = Boolean.TRUE.equals(needQueryBound);
        if (!avg && !min && !max && !bound) {
            throw new IllegalArgumentException("NeedQueryAVG/MIN/MAX/Bound 不能同时为 false");
        }
        HistorianRequest request = new HistorianRequest();
        request.startTime = startTime == null ? null : startTime.getEpochSecond();
        request.endTime = endTime == null ? null : endTime.getEpochSecond();
        request.interval = intervalSeconds;
        request.needQueryAVG = avg;
        request.needQueryMIN = min;
        request.needQueryMAX = max;
        request.needQueryBound = bound;
        for (String tagName : tagNames) {
            request.tagNameList.add(new TagNameRequest(tagName));
        }
        return request;
    }

    /**
     * 变长参数版本。
     *
     * @param startTime       开始时间
     * @param endTime         结束时间
     * @param intervalSeconds 聚合间隔（秒）
     * @param needQueryAVG    是否需要平均值
     * @param needQueryMIN    是否需要最小值
     * @param needQueryMAX    是否需要最大值
     * @param needQueryBound  是否需要边界值
     * @param tagNames        标签名称
     * @return 请求对象
     */
    public static HistorianRequest buildRequest(Instant startTime, Instant endTime, Long intervalSeconds,
                                                Boolean needQueryAVG, Boolean needQueryMIN,
                                                Boolean needQueryMAX, Boolean needQueryBound,
                                                String... tagNames) {
        return buildRequest(startTime, endTime, intervalSeconds, needQueryAVG, needQueryMIN,
                needQueryMAX, needQueryBound, Arrays.asList(tagNames));
    }

    /** @return 开始时间（epoch 秒） */
    public Long getStartTime() { return startTime; }
    /** @param startTime 开始时间（epoch 秒） @return this */
    public HistorianRequest setStartTime(Long startTime) { this.startTime = startTime; return this; }
    /** @return 结束时间（epoch 秒） */
    public Long getEndTime() { return endTime; }
    /** @param endTime 结束时间（epoch 秒） @return this */
    public HistorianRequest setEndTime(Long endTime) { this.endTime = endTime; return this; }
    /** @return 聚合间隔（秒） */
    public Long getInterval() { return interval; }
    /** @param interval 聚合间隔（秒） @return this */
    public HistorianRequest setInterval(Long interval) { this.interval = interval; return this; }
    /** @return 是否需要最大值 */
    public Boolean getNeedQueryMAX() { return needQueryMAX; }
    /** @param needQueryMAX 是否需要最大值 @return this */
    public HistorianRequest setNeedQueryMAX(Boolean needQueryMAX) { this.needQueryMAX = needQueryMAX; return this; }
    /** @return 是否需要最小值 */
    public Boolean getNeedQueryMIN() { return needQueryMIN; }
    /** @param needQueryMIN 是否需要最小值 @return this */
    public HistorianRequest setNeedQueryMIN(Boolean needQueryMIN) { this.needQueryMIN = needQueryMIN; return this; }
    /** @return 是否需要平均值 */
    public Boolean getNeedQueryAVG() { return needQueryAVG; }
    /** @param needQueryAVG 是否需要平均值 @return this */
    public HistorianRequest setNeedQueryAVG(Boolean needQueryAVG) { this.needQueryAVG = needQueryAVG; return this; }
    /** @return 是否需要边界值 */
    public Boolean getNeedQueryBound() { return needQueryBound; }
    /** @param needQueryBound 是否需要边界值 @return this */
    public HistorianRequest setNeedQueryBound(Boolean needQueryBound) { this.needQueryBound = needQueryBound; return this; }
    /** @return 标签列表 */
    public List<TagNameRequest> getTagNameList() { return tagNameList; }
    /** @param tagNameList 标签列表 @return this */
    public HistorianRequest setTagNameList(List<TagNameRequest> tagNameList) { this.tagNameList = tagNameList; return this; }
}
