package io.github.forget_the_bright.tsdb.iotdb.internal.sql;

import io.github.forget_the_bright.tsdb.common.exception.TsdbDataException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

/**
 * 位号到 IoTDB 规范路径的映射插口。
 *
 * <p><b>为什么必须有这一层</b>：业务侧的位号写法与 IoTDB 的树模型路径不是一一对应的
 * 机械变换关系。铜陵现场 891 个字面位号中 779 个含连字符，另有纯数字起始段、逗号多值、
 * 中文占位说明与 {@code ${expr}} 形式的未替换模板；同一 {@code 节点.测量点} 在不同装置
 * 下还可能挂在不同层级。任何“按最后一个点切分 + 去特殊字符”的机械清洗都会把某些位号
 * 指向一个<b>并不存在</b>的序列，而 IoTDB 查询不存在的序列返回空结果而不是报错——
 * 于是错误静默表现为“没有数据”。所以正确做法是显式映射表优先，清洗只做兜底并计数报警。</p>
 *
 * <p><b>规范路径（canonical path）约定</b>：实现返回的 {@link IotdbTagPath} 必须是完整的
 * {@code root.<database>.<device...>.<measurement>} 路径，且满足两点：</p>
 * <ul>
 *   <li>IoTDB 的层级分隔符是 {@code .}，因此<b>单个节点内部不允许出现 {@code .}</b>；
 *       若厂商侧确实存在含点的测量点名，必须在映射表里改写成合法节点名（并在文档中登记），
 *       否则驱动无法可靠区分设备与测量点；</li>
 *   <li>切分点由映射表决定，而不是由驱动按最后一个点猜：这样 {@code BGZH1A.X-PV}
 *       这类位号可以被映射到 {@code root.factory.线1.BGZH1A.X-PV}（设备多一层）
 *       而不会被错切成 {@code device=BGZH1A}。</li>
 * </ul>
 *
 * <p><b>解析时机</b>：{@link #of(Map)} 在启动期就把每个值交给
 * {@link IotdbTagPath#ofAbsolute(String)} 解析一次，运行期命中映射表是纯查表，
 * 零解析开销。已加反引号的值同样接受，并按同一套白名单规范化重发。</p>
 *
 * <p><b>与三通道的位置关系</b>：本接口只负责“名字 → 路径”，不接触 SQL 与执行器，
 * 因此 native、JDBC、REST 三条通道共享同一份映射语义。</p>
 *
 * <p><b>线程安全</b>：实现必须不可变或线程安全，驱动会在多线程查询中复用同一实例。</p>
 *
 * @author wanghao
 * @since 0.1.0
 */
public interface TagPathMapper {

    /**
     * 把业务位号映射为已解析的 IoTDB 路径。
     *
     * @param tag 原始位号，非 {@code null} 且已去除首尾空白
     * @return 映射后的路径；映射表没有该位号时返回 {@code null}，由调用方走兜底清洗
     * @throws TsdbDataException 映射表中该键的值本身非法（配置错误应尽早暴露，而不是静默查空）
     */
    IotdbTagPath tagPath(String tag);

    /**
     * 返回映射表条目数，用于覆盖率统计与启动期自检。
     *
     * @return 条目数；无映射能力时返回 0
     */
    default int size() {
        return 0;
    }

    /**
     * 返回一个不做任何映射的实例：所有位号都走兜底清洗。
     *
     * @return 非 {@code null} 的空映射器
     */
    static TagPathMapper none() {
        return tag -> null;
    }

    /**
     * 用显式映射表创建映射器。
     *
     * <p>构造时即把每个值解析成 {@link IotdbTagPath}：必须是以 {@code root.} 开头的
     * 完整合法路径（至少“库、设备、测点”三层）。出现非法值直接抛异常并列出全部问题
     * 条目——映射表写错的后果是“数据静默为空”，比启动失败严重得多，因此这里选择
     * fail-fast，而不是等到某个位号查空再回头翻映射表。</p>
     *
     * @param mapping 位号到规范路径的映射，可为 {@code null} 或空
     * @return 非 {@code null} 的不可变映射器
     * @throws TsdbDataException 任一映射值不是完整合法路径
     */
    static TagPathMapper of(Map<String, String> mapping) {
        if (mapping == null || mapping.isEmpty()) {
            return new MapBacked(Collections.<String, IotdbTagPath>emptyMap());
        }
        Map<String, IotdbTagPath> copy = new LinkedHashMap<String, IotdbTagPath>(mapping.size() * 2);
        TreeSet<String> invalid = new TreeSet<String>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String key = entry.getKey() == null ? null : entry.getKey().trim();
            String value = entry.getValue() == null ? null : entry.getValue().trim();
            if (key == null || key.isEmpty()) {
                invalid.add("<blank key> -> " + entry.getValue());
                continue;
            }
            if (value == null || value.isEmpty()) {
                invalid.add(key + " -> " + entry.getValue());
                continue;
            }
            try {
                copy.put(key, IotdbTagPath.ofAbsolute(value));
            } catch (TsdbDataException e) {
                invalid.add(key + " -> " + entry.getValue() + " (" + e.getMessage() + ")");
            }
        }
        if (!invalid.isEmpty()) {
            throw new TsdbDataException("invalid IoTDB tag mapping entries (must be full 'root.<db>.<device>"
                    + ".<measurement>' paths): " + invalid);
        }
        return new MapBacked(copy);
    }

    /**
     * 从 properties 文件加载映射表。
     *
     * <p>文件格式为一行一条 {@code 业务位号=root.db.device.measurement}；properties
     * 语法天然支持 {@code \\:} 转义，因此含冒号的位号必须写成 {@code \\:}。加载失败
     * 一律抛异常，不返回“部分可用”的映射表。</p>
     *
     * @param file 文件路径，不能为空
     * @return 非 {@code null} 的映射器
     * @throws TsdbDataException 文件不存在、读取失败或含非法映射值
     */
    static TagPathMapper fromPropertiesFile(String file) {
        if (file == null || file.trim().isEmpty()) {
            throw new TsdbDataException("IoTDB tag mapping file must not be blank");
        }
        Path path = Paths.get(file.trim());
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        } catch (IOException e) {
            throw new TsdbDataException("cannot read IoTDB tag mapping file: " + path, e);
        }
        Map<String, String> mapping = new LinkedHashMap<String, String>();
        for (String name : properties.stringPropertyNames()) {
            mapping.put(name, properties.getProperty(name));
        }
        return of(mapping);
    }

    /**
     * 不可变映射表实现，值是启动期解析好的路径。
     */
    final class MapBacked implements TagPathMapper {

        private final Map<String, IotdbTagPath> mapping;

        MapBacked(Map<String, IotdbTagPath> mapping) {
            this.mapping = Collections.unmodifiableMap(new LinkedHashMap<String, IotdbTagPath>(mapping));
        }

        @Override
        public IotdbTagPath tagPath(String tag) {
            if (tag == null) {
                return null;
            }
            IotdbTagPath hit = mapping.get(tag);
            return hit == null ? mapping.get(tag.trim()) : hit;
        }

        @Override
        public int size() {
            return mapping.size();
        }
    }
}
