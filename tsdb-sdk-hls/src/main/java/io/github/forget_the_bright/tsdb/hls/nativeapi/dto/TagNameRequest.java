package io.github.forget_the_bright.tsdb.hls.nativeapi.dto;

import com.alibaba.fastjson.annotation.JSONField;


/**
 * 单个标签名称请求。
 *
 * <p>对应和利时 HLS 实时/历史接口请求体中的
 * <code>{"TagName":"AI-101"}</code> 元素。原生 API 保留该结构，
 * 是为了让调用方能够完全按厂商文档组织请求，而不被公共层的
 * “标签字符串”抽象限制。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public class TagNameRequest {

    /** 标签名称。 */
    @JSONField(name = "TagName")
    private String tagName;

    /** 构造空对象。 */
    public TagNameRequest() {
    }

    /**
     * 构造并设置标签名。
     *
     * @param tagName 标签名称
     */
    public TagNameRequest(String tagName) {
        this.tagName = tagName;
    }

    /** @return 标签名称 */
    public String getTagName() {
        return tagName;
    }

    /**
     * 设置标签名称。
     *
     * @param tagName 标签名称
     * @return this，便于链式调用
     */
    public TagNameRequest setTagName(String tagName) {
        this.tagName = tagName;
        return this;
    }
}
