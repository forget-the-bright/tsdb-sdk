package io.github.forget_the_bright.tsdb.common.api;

import io.github.forget_the_bright.tsdb.common.model.WriteRequest;
import io.github.forget_the_bright.tsdb.common.model.WriteResult;

import java.util.List;

/**
 * 采样点写入操作。
 *
 * <p>在 SDK 中承担“数据回写”的角色，把一批位号的采样点写入时序库。
 * 一个客户端始终会返回一个写入会话对象（{@code TsdbClient#writeSession()}），
 * 从而让调用方不必到处判空；是否真的能写，由
 * {@link #isWritable()} 表明。</p>
 *
 * <p>只读驱动的约定：不支持写入的驱动仍然返回会话实例，但
 * {@link #isWritable()} 必须返回 {@code false}，且
 * {@link #write(List)} 必须抛 {@code TSDB-UNSUPPORTED}
 * （{@code TsdbUnsupportedException}），不能静默丢弃数据。</p>
 *
 * <p>能力要求：需要客户端声明
 * {@link io.github.forget_the_bright.tsdb.common.model.Capability#WRITE} 能力。</p>
 *
 * <p>实现约束：实现必须线程安全；写入是否具备事务性、
 * 是否允许乱序时间戳、重复时间戳如何处理，均由驱动遵守厂商语义并需在
 * 驱动文档中说明。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public interface WriteSession {

    /**
     * 判断当前会话是否可写。
     *
     * <p>该值在会话生命周期内应当是稳定的；调用方应在真正写入前先判断，
     * 以避免用异常做流程控制。返回 {@code false} 时调用
     * {@link #write(List)} 必定失败。</p>
     *
     * @return 可写返回 {@code true}；只读驱动或当前连接被降级为只读时
     *         返回 {@code false}
     */
    boolean isWritable();

    /**
     * 批量写入多个位号的采样点。
     *
     * <p>入参是一个“每个元素对应一个位号、元素内含该位号的多个点”的列表。
     * 正常返回表示写入已被服务端接受；个别位号失败是否允许“部分成功”，
     * 取决于厂商能力，见 {@link WriteResult} 的语义说明。</p>
     *
     * @param requests 待写入的请求列表，不能为 {@code null}；
     *                 每个元素都已由 {@code WriteRequest} 构造器保证
     *                 位号非空且采样点列表非空
     * @return 非 {@code null} 的写入结果，包含接受数、拒绝数与错误描述
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException
     *         当 {@link #isWritable()} 为 {@code false}（驱动不支持写入）时
     * @throws IllegalArgumentException 当 {@code requests} 为 {@code null}
     *                                  或为空列表时（由实现决定是否显式校验）
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbDataException
     *         当数据本身非法（如时间戳越界、类型不匹配）且整批失败时
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbTimeoutException
     *         当写入超时时（注意：超时不代表一定没写成功，重试需考虑幂等性）
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbConnectionException
     *         当连接中断时
     */
    WriteResult write(List<WriteRequest> requests);
}
