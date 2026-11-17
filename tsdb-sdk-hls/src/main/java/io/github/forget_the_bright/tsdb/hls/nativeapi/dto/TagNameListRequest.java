package io.github.forget_the_bright.tsdb.hls.nativeapi.dto;

import com.alibaba.fastjson.annotation.JSONField;


import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 实时值请求体：标签名称列表。
 *
 * <p>对应 HLS <code>POST /ddb/read_ddbtagvalue</code> 的请求体：
 * <pre>{@code
 * {"TagNameList":[{"TagName":"AI-101"},{"TagName":"AI-102"}]}
 * }</pre>
 * </p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class TagNameListRequest {

    /** 标签请求列表。 */
    @JSONField(name = "TagNameList")
    private List<TagNameRequest> tagNameList = new ArrayList<TagNameRequest>();

    /** 构造空请求。 */
    public TagNameListRequest() {
    }

    /**
     * 从标签名集合构造请求。
     *
     * @param tagNames 标签名集合
     */
    public TagNameListRequest(Collection<String> tagNames) {
        if (tagNames != null) {
            for (String tagName : tagNames) {
                tagNameList.add(new TagNameRequest(tagName));
            }
        }
    }

    /** @return 标签请求列表 */
    public List<TagNameRequest> getTagNameList() {
        return tagNameList;
    }

    /**
     * 设置标签请求列表。
     *
     * @param tagNameList 标签请求列表
     * @return this
     */
    public TagNameListRequest setTagNameList(List<TagNameRequest> tagNameList) {
        this.tagNameList = tagNameList;
        return this;
    }

    /**
     * 追加一个标签。
     *
     * @param tagName 标签名
     * @return this
     */
    public TagNameListRequest add(String tagName) {
        this.tagNameList.add(new TagNameRequest(tagName));
        return this;
    }
}
