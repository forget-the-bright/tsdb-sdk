package io.github.forget_the_bright.tsdb.common.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 写入操作的结果。
 *
 * <p>由 {@code WriteSession#write(List)} 返回，用“接受数 / 拒绝数 / 错误信息”
 * 三部分描述一次批量写入的成败。</p>
 *
 * <p>语义约定：</p>
 * <ul>
 *   <li>驱动只有在厂商确实支持“部分成功”语义时，才允许只拒绝个别位号
 *       而让整批调用正常返回，并在 {@link #getErrors()} 中给出失败原因；
 *       否则一旦有任一数据点写入失败，就应抛出
 *       {@code TsdbWriteException} / {@code TsdbException} 之类的异常，
 *       而不是返回一个“部分成功”的结果。</li>
 *   <li>{@link #getAccepted()} 与 {@link #getRejected()} 的统计单位是
 *       <b>数据点（sample）</b>还是<b>位号（tag）</b>，由驱动定义并需在
 *       驱动文档中说明；两者之和不保证等于请求总量，本类不做该一致性校验。</li>
 *   <li>{@link #getErrors()} 保存人类可读的失败描述，可能为空列表，
 *       但永不为 {@code null}；列表顺序不构成契约。</li>
 * </ul>
 *
 * <p>不可变性与线程安全：错误列表在构造时被复制并包装为不可修改视图，
 * 其余字段为 {@code final}，实例完全不可变，可安全地在多线程间共享。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public final class WriteResult {

    /**
     * 被接受（写入成功）的数量，统计单位由驱动定义。
     */
    private final int accepted;

    /**
     * 被拒绝（写入失败）的数量，统计单位由驱动定义。
     */
    private final int rejected;

    /**
     * 失败原因描述列表，非 {@code null}、不可修改。
     */
    private final List<String> errors;

    /**
     * 构造一个写入结果。
     *
     * @param accepted 被接受的数量，不做范围校验（负值不报错，
     *                 但语义上应 {@code >= 0}）
     * @param rejected 被拒绝的数量，不做范围校验（语义上应 {@code >= 0}）
     * @param errors   失败原因列表，允许为 {@code null}
     *                 （归一化为空列表）；元素允许为 {@code null}
     */
    public WriteResult(int accepted, int rejected, List<String> errors) {
        this.accepted = accepted;
        this.rejected = rejected;
        this.errors = errors == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(errors));
    }

    /**
     * 便捷工厂：构造一个“全部成功”的结果。
     *
     * <p>拒绝数固定为 0，错误列表固定为空列表。</p>
     *
     * @param accepted 被接受的数量
     * @return 非 {@code null} 的结果，其 {@link #isFullyAccepted()} 为 {@code true}
     */
    public static WriteResult success(int accepted) {
        return new WriteResult(accepted, 0, Collections.<String>emptyList());
    }

    /**
     * 返回被接受的数量。
     *
     * @return 接受数；统计单位（点或位号）由驱动定义
     */
    public int getAccepted() {
        return accepted;
    }

    /**
     * 返回被拒绝的数量。
     *
     * @return 拒绝数；为 0 表示本批没有失败项
     */
    public int getRejected() {
        return rejected;
    }

    /**
     * 返回失败原因描述列表。
     *
     * @return 非 {@code null} 的只读列表；全部成功时为空列表，
     *         修改会抛 {@link UnsupportedOperationException}
     */
    public List<String> getErrors() {
        return errors;
    }

    /**
     * 判断本批是否全部被接受。
     *
     * <p>判定依据仅为 {@code rejected == 0}，不检查错误列表是否为空；
     * 因此理论上存在“rejected 为 0 但 errors 非空”的组合（例如驱动把
     * 警告也放进错误列表），需要严格判断时请同时检查
     * {@link #getErrors()}。</p>
     *
     * @return 当且仅当拒绝数为 0 时返回 {@code true}
     */
    public boolean isFullyAccepted() {
        return rejected == 0;
    }
}
