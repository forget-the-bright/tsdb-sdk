package io.github.forget_the_bright.tsdb.common.model;

/**
 * 位号请求体，与旧版 {@code TagNamesEntity} 的 JSON 形状一致（{@code {"tagNames":"A;B"}}）。
 *
 * <p>本地定义而非复用 GE 实体，避免业务工程为了一个 DTO 继续依赖旧 GE SDK。</p>
 *
 * @author wanghao(helloworlwh@163.com)
 * @since 2026/11/23 10:08
 */
public class TagNamesBody {

    /**
     * 位号串，多个以分号或逗号分隔。
     */
    private String tagNames;

    /**
     * @return 位号串
     */
    public String getTagNames() {
        return tagNames;
    }

    /**
     * @param tagNames 位号串
     */
    public void setTagNames(String tagNames) {
        this.tagNames = tagNames;
    }
}