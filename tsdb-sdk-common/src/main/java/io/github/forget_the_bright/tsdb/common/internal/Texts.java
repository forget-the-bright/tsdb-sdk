package io.github.forget_the_bright.tsdb.common.internal;

import cn.hutool.core.util.StrUtil;

/**
 * 文本处理的共享实现，替代各驱动中的私有副本。
 *
 * <p>本类只做无状态的纯函数转换，线程安全。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class Texts {

    /**
     * 错误响应体进入异常消息前的默认截断长度。
     */
    public static final int DEFAULT_ABBREVIATE_LENGTH = 512;

    private Texts() {
    }

    /**
     * 去除首尾空白；空串或全空白返回 {@code null}。
     *
     * <p>与各配置类此前私有的 {@code trimToNull} 语义一致，底层委托 Hutool
     * {@link StrUtil#trimToNull(CharSequence)}。</p>
     *
     * @param text 原始文本，可为 {@code null}
     * @return 去空白后的文本；无有效内容时为 {@code null}
     */
    public static String trimToNull(String text) {
        return StrUtil.trimToNull(text);
    }

    /**
     * 截断过长文本并追加省略号。
     *
     * <p>用于异常消息中的响应体：不超过 {@code max} 个字符时原样返回，否则保留前
     * {@code max} 个字符再加 {@code "..."}。{@code null} 原样返回。</p>
     *
     * @param text 原始文本，可为 {@code null}
     * @param max  保留的最大字符数，不能为负
     * @return 截断后的文本，或 {@code null}
     * @throws IllegalArgumentException {@code max} 为负时
     */
    public static String abbreviate(String text, int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max must be >= 0, was " + max);
        }
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }

    /**
     * 用默认长度 {@value #DEFAULT_ABBREVIATE_LENGTH} 截断文本。
     *
     * @param text 原始文本，可为 {@code null}
     * @return 截断后的文本，或 {@code null}
     */
    public static String abbreviate(String text) {
        return abbreviate(text, DEFAULT_ABBREVIATE_LENGTH);
    }
}
