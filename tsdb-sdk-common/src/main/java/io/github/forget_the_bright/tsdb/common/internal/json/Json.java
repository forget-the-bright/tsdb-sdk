package io.github.forget_the_bright.tsdb.common.internal.json;

import com.alibaba.fastjson.parser.ParserConfig;

/**
 * fastjson 全局安全策略入口。
 *
 * <p>SDK 只把 fastjson 当作 JSON 树解析器使用，不按类名反序列化为任意类型。
 * 为避免 fastjson 1.x 的 AutoType 风险，本类在加载时关闭 AutoType 并开启
 * safeMode。{@link JsonText} 的静态初始化会调用 {@link #init()}，只要通过
 * {@code JsonText} 解析 JSON，该策略就已生效。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class Json {

    static {
        ParserConfig config = ParserConfig.getGlobalInstance();
        config.setAutoTypeSupport(false);
        config.setSafeMode(true);
    }

    private Json() {
    }

    /**
     * 触发静态初始化。
     *
     * <p>方法本身无副作用；调用它可确保安全策略已应用。重复调用安全。</p>
     */
    public static void init() {
        // 静态块已完成初始化，这里只负责触发类加载。
    }
}
