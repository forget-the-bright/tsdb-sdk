# 04 公共 API 与数据模型规范

> ⚠️ 范围变更（2026-11-09）：横河已移出本期范围，新增力控 pSpace；运行态改为“单活动实例 + 策略切换”。本文横河驱动/OPC UA 章节为历史设计；冲突时以 [`docs/06-范围变更与力控接入.md`](06-范围变更与力控接入.md) 为准。

> 文档版本：v1.0（2026-11-09）  
> 状态：API 契约冻结候选  
> 目标：让调用方只依赖 `tsdb-sdk-common`，即可在 HLS/GE/力控/IoTDB 间切换。

---

## 1. 约定

| 项 | 约定 |
|---|---|
| Java 基线 | Java 8 字节码（`--release 8`），运行时可上 8/11/17/21 |
| 包根 | `io.github.forget_the_bright.tsdb.common` |
| 时间 | `java.time.Instant`，UTC；厂商本地时间按配置时区解析 |
| 时间精度 | 毫秒（厂商更高精度截断/四舍五入，驱动文档写明） |
| 区间 | 默认 `[start, end)` 左闭右开 |
| 空值 | 返回空集合，不返回 `null` |
| 线程安全 | `TsdbClient` 及其子接口线程安全；`Sample`/`TagInfo` 不可变 |
| 异常 | 受检异常不使用；统一 `TsdbException` 运行时异常 |
| 原生 | `unwrap` 返回的对象生命周期归 client |

---

## 2. 公共接口

### 2.1 `TsdbClient`

```java
package io.github.forget_the_bright.tsdb.common.api;

import io.github.forget_the_bright.tsdb.common.model.CapabilitySet;
import io.github.forget_the_bright.tsdb.common.model.HealthStatus;

public interface TsdbClient extends AutoCloseable {

    /** 配置中的实例名，如 hls_main */
    String instanceName();

    /** 驱动类型，如 hls / ge / pspace / iotdb */
    String type();

    /** 当前能力快照 */
    CapabilitySet capabilities();

    TagCatalog tagCatalog();

    CurrentValueReader currentValues();

    HistoryReader history();

    /** 未开启写能力时抛 TsdbUnsupportedException */
    WriteSession writeSession();

    HealthStatus health();

    /**
     * 获取厂商原生 API。
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException 类型不匹配
     */
    <T> T unwrap(Class<T> nativeType);

    @Override
    void close();
}
```

### 2.2 `TagCatalog`

```java
package io.github.forget_the_bright.tsdb.common.api;

import io.github.forget_the_bright.tsdb.common.model.TagInfo;
import io.github.forget_the_bright.tsdb.common.model.TagQuery;
import java.util.List;
import java.util.Optional;

public interface TagCatalog {

    /** 按条件分页查询；不支持的过滤条件由驱动忽略并在文档说明 */
    List<TagInfo> listTags(TagQuery query);

    Optional<TagInfo> find(String tagName);

    default boolean exists(String tagName) {
        return find(tagName).isPresent();
    }
}
```

### 2.3 `CurrentValueReader`

```java
package io.github.forget_the_bright.tsdb.common.api;

import io.github.forget_the_bright.tsdb.common.model.TagSample;
import java.util.Collection;
import java.util.List;

public interface CurrentValueReader {

    /**
     * 批量读取当前值，结果顺序与入参一致；
     * 单个标签不存在时对应 sample 为空/质量 UNKNOWN，不整体失败。
     */
    List<TagSample> read(Collection<String> tagNames);
}
```

### 2.4 `HistoryReader`

```java
package io.github.forget_the_bright.tsdb.common.api;

import io.github.forget_the_bright.tsdb.common.model.HistoryQuery;
import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import java.util.List;

public interface HistoryReader {

    /**
     * 按查询模式读取历史。
     * @throws io.github.forget_the_bright.tsdb.common.exception.TsdbUnsupportedException 能力不支持
     */
    List<TagSeries> read(HistoryQuery query);
}
```

### 2.5 `WriteSession`

```java
package io.github.forget_the_bright.tsdb.common.api;

import io.github.forget_the_bright.tsdb.common.model.TagSeries;
import io.github.forget_the_bright.tsdb.common.model.WriteOptions;
import io.github.forget_the_bright.tsdb.common.model.WriteResult;
import java.util.List;

public interface WriteSession extends AutoCloseable {

    WriteResult write(List<TagSeries> series, WriteOptions options);

    @Override
    void close();
}
```

### 2.6 `HealthCheck`

```java
package io.github.forget_the_bright.tsdb.common.api;

import io.github.forget_the_bright.tsdb.common.model.HealthStatus;

public interface HealthCheck {
    HealthStatus check();
}
```

---

## 3. 数据模型

> 查询窗口辅助类 `HistorianUnit`（`common.model`）承载「结束时刻 + 总时长 + 间隔」：`begin`/`end`、
> `intervalMs`、`count = floor(total/intervalMs)`、`coveredEnd()` 与 `tailMs()`，并可选择按业务时区把
> 结束时刻向下对齐到间隔网格。`DeltaReader.intervalDeltas(...)` 直接消费该对象。

### 3.1 `TagInfo`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | String | 是 | 标签名，厂商内唯一 |
| `description` | String | 否 | 描述/中文名 |
| `valueType` | `ValueType` | 是 | 归一类型 |
| `engineeringUnit` | String | 否 | 工程单位 |
| `vendorType` | String | 否 | 厂商原始类型（如 HLS TagType 数字、GE DataType） |
| `attributes` | Map<String,String> | 否 | 厂商扩展属性（只读） |

```java
public final class TagInfo {
    private final String name;
    private final String description;
    private final ValueType valueType;
    private final String engineeringUnit;
    private final String vendorType;
    private final Map<String, String> attributes;
    // builder + getters
}
```

### 3.2 `TagQuery`

```java
public final class TagQuery {
    private final String nameMask;      // 支持 * ?，驱动不支持则忽略
    private final Integer limit;        // 默认 1000，最大 100000
    private final Integer offset;       // 默认 0
    private final ValueType valueType;  // 可空
    private final String unit;          // 可空
    private final boolean includeAttributes;
}
```

### 3.3 `ValueType`

```java
public enum ValueType {
    DOUBLE, LONG, BOOLEAN, STRING, BYTES, UNKNOWN
}
```

映射：

| 厂商 | 原类型 | ValueType |
|---|---|---|
| HLS | 0 BOOL | BOOLEAN |
| HLS | 1 BYTE / 2 WORD / 3 DWORD / 4 SBYTE / 5 SWORD / 6 SDWORD | LONG |
| HLS | 7 SINGLE / 8 DOUBLE | DOUBLE |
| HLS | 18 STRING | STRING |
| GE | 数字/浮点字符串 | DOUBLE 或 LONG（按 DataType 判断） |
| GE | Boolean | BOOLEAN |
| GE | String | STRING |
| OPC UA | Boolean/Int16.../Double/String | 对应 |
| IoTDB | INT32/INT64/FLOAT/DOUBLE/BOOLEAN/TEXT | 对应 |

### 3.4 `Quality`

```java
public enum Quality {
    GOOD, UNCERTAIN, BAD, STALE, UNKNOWN;

    public boolean isGood() { return this == GOOD; }
}
```

**映射表（关键）**：

| 厂商 | 原始码 | 统一 Quality | 说明 |
|---|---|---|---|
| HLS | 0 | BAD | |
| HLS | 1 | GOOD | 注意不是 GE 的 3 |
| HLS | 其他 | UNKNOWN | 保留原码 |
| GE | 0 | BAD | |
| GE | 1 | UNCERTAIN | |
| GE | 2 (NA) | UNKNOWN | 无数据，不等于 BAD |
| GE | 3 | GOOD | |
| GE | 其他 | UNKNOWN | |
| OPC UA | 0 (Good) | GOOD | 含子码 |
| OPC UA | 1 (Uncertain) | UNCERTAIN | |
| OPC UA | 2 (Bad) | BAD | |
| IoTDB | 无 | GOOD | 有值 |
| IoTDB | null | UNKNOWN | 空值/未采集 |
| 其他 | — | UNKNOWN | 保留 `vendorQualityCode` |

> 原则：**未知不猜测**。宁可 UNKNOWN + 原码，也不把 UNCERTAIN 压成 BAD。

### 3.5 `Sample`

```java
public final class Sample {
    private final Instant timestamp;       // 绝对时刻，JSON 为 ISO 字符串
    private final Date timestampDate;      // java.util.Date 视图，便于 Java/前端序列化
    private final String timestampText;    // 按实例 time.response.* 格式化，前端可直接展示
    private final ValueType valueType;
    private final Object value;            // Double/Long/Boolean/String/byte[]
    private final Quality quality;
    private final Integer vendorQualityCode;

    public Instant getTimestamp();
    public Date getTimestampDate();
    public String getTimestampText();
    public Sample withTimeFormat(TimeConfig timeConfig);
}
```

工厂方法：

```java
Sample.ofDouble(Instant ts, double value, Quality q, Integer vendorCode);
Sample.ofLong(Instant ts, long value, Quality q, Integer vendorCode);
Sample.ofBoolean(Instant ts, boolean value, Quality q, Integer vendorCode);
Sample.ofString(Instant ts, String value, Quality q, Integer vendorCode);
Sample.ofBytes(Instant ts, byte[] value, Quality q, Integer vendorCode);
Sample.ofNull(Instant ts, ValueType type, Quality q, Integer vendorCode);
```

`timestampDate` 与 `timestampText` 是同一绝对时刻的两种便利视图：前者给 Java 老代码/模板引擎，后者给前端直接展示；`withTimeFormat` 只重算文本，不改 `Instant`/`Date`。JSON 中 `timestamp` 为 ISO 字符串（fastjson 对 `Instant` 的默认输出），`timestampDate` 为 epoch 毫秒（fastjson 对 `Date` 的默认表示），`timestampText` 为按实例时区/模板生成的字符串；前端展示时间应直接读取 `timestampText`。健康检查的 `HealthStatus` 同样提供 `checkedAt`、`checkedAtDate`、`checkedAtText`。

### 3.6 `TagSample`

```java
public final class TagSample {
    private final String tagName;
    private final Sample sample;   // 可为 null（标签不存在/无值）
}
```

### 3.7 `TagSeries`

```java
public final class TagSeries {
    private final String tagName;
    private final ValueType valueType;
    private final String engineeringUnit;
    private final List<Sample> samples;
    private final boolean truncated;       // 是否因 maxPoints 截断
    private final boolean derived;         // 是否客户端补算（如插值）
    private final Map<String, String> attributes;
}
```

### 3.8 `HistoryQuery`

```java
public final class HistoryQuery {
    private final List<String> tags;
    private final Instant start;
    private final Instant end;
    private final QueryMode mode;
    private final Aggregation aggregation; // AGGREGATED 必填
    private final Duration interval;       // AGGREGATED/INTERPOLATED 必填
    private final Integer count;           // 可空；有 interval 时由区间推导且必须一致
    private final int maxPoints;           // 可空，默认 100000
    private final Direction direction;     // 默认 FORWARD
    private final FillPolicy fill;         // 默认 NONE
    private final boolean partialResultAllowed;
    private final boolean includeQuality;
}
```

Builder 校验：

- `tags` 非空、去重、单次 ≤ 驱动上限（默认 1000，超出自动分批）；
- `start < end`；
- `mode=AGGREGATED` 时 `aggregation` 与 `interval` 必填；
- `interval > 0`；
- `maxPoints > 0`；
- `count` 可空。设置了 `interval` 时可省略：GE 在适配层用 `HistorianUnit.resolveCount`
  按区间推导服务端点数，显式填写且与推导值不一致则抛 `IllegalArgumentException`；
  HLS / IoTDB 按 `interval` 下发、不校验 `count`。只有 `RAW` 的点数上限与
  无 `interval` 的纯 count 网格必须显式填写。

### 3.9 枚举

```java
public enum QueryMode {
    RAW,            // 原始点
    AGGREGATED,     // 区间聚合
    INTERPOLATED,   // 插值
    SAMPLED,        // 采样
    TREND           // 趋势
}

public enum Aggregation {
    AVG, MIN, MAX, SUM, COUNT, FIRST, LAST, RANGE, BOUND, INTERVAL_SUM
}

public enum Direction {
    FORWARD, BACKWARD
}

public enum FillPolicy {
    NONE,       // 不填充
    PREVIOUS,   // 前值
    LINEAR,     // 线性
    ZERO        // 补零
}
```

### 3.10 `WriteOptions` / `WriteResult`

```java
public final class WriteOptions {
    private final boolean overwrite;     // 是否覆盖同时间点
    private final int batchSize;         // 默认 1000
    private final Duration timeout;
}

public final class WriteResult {
    private final int acceptedTags;
    private final int acceptedSamples;
    private final int rejectedSamples;
    private final List<WriteError> errors; // tag, timestamp, code, message
}
```

### 3.11 `HealthStatus`

```java
public final class HealthStatus {
    public enum State { UP, DEGRADED, DOWN }

    private final State state;
    private final String message;
    private final Instant lastSuccess;
    private final long latencyMs;
}
```

---

## 4. 能力模型

```java
public enum Capability {
    TAG_BROWSE, TAG_SEARCH, TAG_CRUD,
    CURRENT_VALUE,
    RAW_HISTORY, AGGREGATED_HISTORY, INTERPOLATED_HISTORY,
    SAMPLED_HISTORY, TREND,
    WRITE, DELETE,
    ALARM, SUBSCRIPTION, SYSTEM_ADMIN
}
```

```java
public final class CapabilitySet {
    private final Set<Capability> capabilities;

    public boolean supports(Capability c);
    public Set<Capability> all();
    public static CapabilitySet of(Capability... cs);
    public CapabilitySet union(CapabilitySet other);
}
```

**驱动声明（v1.0）**：

| Capability | HLS | GE | pSpace（7.1 实测） | IoTDB（三通道） |
|---|---|---|---|---|
| TAG_BROWSE | ✅ | ✅ | ✅ `/PointPage` 翻页 | ✅ |
| TAG_SEARCH | ❌ | ✅ | ✅ `/PointFilter`（服务端正则） | ✅ |
| TAG_CRUD | ❌ | ✅（原生） | ❌（原生 `/AddPoint`、`/UpdPoint`、`/DelPoint` 可调） | ❌ |
| CURRENT_VALUE | ✅ | ✅ | ✅ `/RealData` | ✅ |
| RAW_HISTORY | ✅ 1 秒 `BOUND` 桶近似（带 `rawSource` 标注） | ✅ | ✅ `/HistDataPage` | ✅ |
| AGGREGATED_HISTORY | ✅ | ✅ | ✅ `/HistAggreDataPage`（间隔单位秒） | ✅ |
| INTERPOLATED_HISTORY | ❌ | ✅ | ✅ `intve`（数字量点位可能无数据） | ✅ 驱动内存网格，不外推 |
| SAMPLED_HISTORY | ✅ `BOUND` 列充当桶边界值 | ✅ `calculated LastRawValue` | ✅ `orgintve` | ✅ 任意间隔（服务端 GROUP BY 上限 10000 行） |
| TREND | ❌ | ❌（原生可调） | ❌ | ❌ |
| CUMULATIVE_DELTA | ✅ 基于 `BOUND`（生产同款迂回方案） | ✅ | ✅ `endval` 基线折叠 | ✅ |
| WRITE | ❌ | ❌（原生可调） | 🟡 默认关闭（`/UpdRealData`） | 🟡 默认关闭 |
| DELETE | ❌ | ✅（原生） | ❌（原生 `/DelPoint`） | ❌ |
| ALARM | ❌ | ✅（原生） | ❌（原生读/确认可调） | ❌ |
| SUBSCRIPTION | ❌ | ❌ | ❌（原生 8081 WebSocket） | ❌ |
| SYSTEM_ADMIN | ❌ | ✅（原生） | ❌ | ❌ |

> HLS 没有原生原始点与 `LAST`，`RAW`/`SAMPLED`/差分/`INTERVAL_SUM` 统一基于厂商的
> `TagValue_Bound` 列：`RAW` 用 1 秒 `BOUND` 桶近似（属性 `rawSource=hls_history_1s_bound`），
> 其余用 `BOUND` 充当桶边界值。这是生产同款迂回方案，适用前提与换版本复核见 docs/10 §8.1。
> GE 的等间隔与差分都用 `calculated` 的 `LastRawValue=19`：差分把查询起点前移一个间隔、服务端 `count = 窗口桶数 + 1`，第 0 个样本即基线；扩展桶为空才补一次批量 `raw` 原始基线；
> IoTDB 的等间隔用 `GROUP BY ... FILL`、差分用内存相邻桶相减，
> 而整窗累计（`INTERVAL_SUM`）用 `SELECT first_value(m), last_value(m)` 单窗单值，
> 三者语义不同，不可互相顶替。

> 表中“原生”表示公共层未声明、但可通过 `unwrap(...)` 调厂商接口；实现后必须与 `CapabilitySet` 一致。🟡 表示受安全开关限制。

---

## 5. 异常与错误码

### 5.1 异常类

```java
public class TsdbException extends RuntimeException {
    private final TsdbErrorCode code;
    private final String instance;
    private final String type;
    private final String operation;
    private final String vendorCode;
    private final boolean retryable;
}

public class TsdbConfigurationException extends TsdbException {}
public class TsdbConnectionException extends TsdbException {}
public class TsdbAuthenticationException extends TsdbException {}
public class TsdbTimeoutException extends TsdbException {}
public class TsdbUnsupportedException extends TsdbException {}
public class TsdbQueryException extends TsdbException {}
public class TsdbDataException extends TsdbException {}
```

### 5.2 错误码

| code | 含义 | 默认可重试 |
|---|---|---|
| `TSDB-CONFIG` | 配置缺失/非法 | ❌ |
| `TSDB-CONN` | 连接失败/断线 | ✅ |
| `TSDB-AUTH` | 认证/授权失败 | ❌（刷新 token 后一次） |
| `TSDB-TIMEOUT` | 超时 | ✅ |
| `TSDB-RATE-LIMIT` | 限流（429） | ✅ |
| `TSDB-UNSUPPORTED` | 能力不支持 | ❌ |
| `TSDB-QUERY` | 厂商查询错误 | ❌ |
| `TSDB-DATA` | 解析/类型/质量异常 | ❌ |
| `TSDB-INTERNAL` | SDK 内部错误 | ❌ |

### 5.3 示例

```java
try {
    client.history().read(query);
} catch (TsdbUnsupportedException e) {
    // 能力不支持：降级到聚合或提示
} catch (TsdbTimeoutException e) {
    // 可重试
} catch (TsdbException e) {
    log.error("tsdb failed, code={}, instance={}, vendorCode={}",
              e.getCode(), e.getInstance(), e.getVendorCode(), e);
}
```

---

## 6. 查询语义

### 6.1 时间

- 输入 `Instant`（绝对时刻，不带时区）；所有已实现驱动共享实例级 `TimeConfig`。GE 的请求参数与响应文本实际走格式化/解析；HLS/IoTDB 的协议字段是 epoch 秒/毫秒，不走文本转换，但仍提供同一套配置与 `parseTimestamp`/`formatTimestamp`，便于统一展示，也为未来文本接口预留；
- 请求侧：`options.time.request.enabled/zone/pattern`，默认启用、UTC、`yyyy-MM-dd'T'HH:mm:ssXXX`；仅 GE 等文本请求驱动实际使用，HLS/IoTDB 忽略但保留配置；
- 响应侧：`options.time.response.*`
  - `source-zone`：厂商返回文本**没有**时区时按该时区解释；
  - `target-zone`：供 `formatTimestamp`/`formatResponse` 展示；不改变 `Sample.timestamp` 的绝对时刻；
  - `pattern`：展示格式；`enabled=false` 时 `formatResponse` 退回 ISO-8601；
- 解析优先级：显式 `Z`/`+08:00` 偏移 > 配置的 `pattern` > 常见本地格式 > `source-zone`；
- 输出统一 `Instant`（毫秒），时区只影响“怎么解析文本”和“怎么展示”，不改变时间值；
- GE：请求参数与响应时间通常都是 UTC；铜陵现场配 `request.zone=UTC`、`response.source-zone=UTC`、`response.target-zone=GMT+8`，即可“UTC 进出、GMT+8 展示”；
- 若某项目 GE 本身就用 GMT+8，把 `request.zone` 与 `response.source-zone` 都设为 `GMT+8`，无需任何代码改动；
- 其他厂商：HLS 用秒 epoch；IoTDB 用毫秒 epoch；两者协议不消费 `request.*`，但 `timeConfig()`、`parseTimestamp(String)`、`formatTimestamp(Instant)` 与 GE 完全一致；OPC UA 用 `DateTime`（UTC）。

### 6.2 区间

- 默认 `[start, end)`；
- 若厂商只支持闭区间，驱动做边界去重；
- 文档与契约测试固定该语义。

### 6.3 聚合

| 统一 | HLS | GE | 横河 | IoTDB |
|---|---|---|---|---|
| AVG | `NeedQueryAVG` | `calculated`/`sampled` | HDA 聚合 | `avg` |
| MIN | `NeedQueryMIN` | 同上 | HDA | `min_value` |
| MAX | `NeedQueryMAX` | 同上 | HDA | `max_value` |
| SUM | ❌ | 计算/采样 | HDA | `sum` |
| COUNT | ❌ | 计算 | HDA | `count` |
| FIRST | ❌ | 计算 | HDA | `first_value` |
| LAST | ❌ | 计算 | HDA | `last_value` |
| RANGE | ❌ | 计算 | HDA | `max-min` |
| BOUND | `NeedQueryBound` | 计算 | HDA | 组合 |

> HLS 的 MAX/MIN 参数缺陷在驱动内修正；不得调用有 bug 的 `buildRequest`。

### 6.4 插值

- `INTERPOLATED` + `interval`：按固定间隔取点；
- `FillPolicy`：`NONE`（缺失不补）、`PREVIOUS`、`LINEAR`、`ZERO`；
- GE 原生 `interpolated`；IoTDB 由驱动取回原始点后在内存按 `start + i*step` 网格线性内插，
  **不采用** `FILL(LINEAR)`——它只在已生成的 `GROUP BY` 桶之间填，既不保证点数也不等价于
  原始值边界内插；横河/ HLS 不支持时：
  - 横河可由客户端在原始点上线性插值，标记 `derived=true`；
  - HLS 直接 `UNSUPPORTED`（没有原始点，无法补）。

### 6.5 截断

- 超过 `maxPoints`：默认截断并置 `truncated=true`；
- 可选 `failOnTruncate=true`（后续版本）抛 `TsdbQueryException`；
- 文档写明“截断方向与 `direction` 一致”。

### 6.6 批量与顺序

- 结果顺序与 `tags` 入参一致，**返回长度恒等于入参长度**：驱动内部把位号合并成一条 SQL
  批量发送，服务端会打乱行序、也可能整行丢弃没有数据的序列，但驱动按位号名回填并留
  `null` 占位。批量只是减少往返，**不改变这条契约**，任何情况下都不允许把 A 位号的值
  挂到 B 位号名下；
- 单标签失败：默认整体失败；`partialResultAllowed=true` 时返回成功部分，失败标签在
  `TagSeries.attributes["error"]` 标记；
- 单次标签数超过驱动上限自动分批，批间不保证原子；
- 失败归因有两种粒度，取决于驱动的批量档位：一条 SQL 只覆盖一个位号时是逐位号归因；
  合并了 k 个位号的 SQL 失败时，影响范围就是这 k 个位号，驱动给块内每个位号写同样的
  `vendorError`，并额外标 `vendorErrorScope=chunk` 与失败 SQL。不得把块级失败伪装成
  「只有那一个位号失败」，也不得在只影响 1 个位号时把范围说大；
- 批量降低的是往返次数，不承诺时延收益：合并多列查询会共享服务端的行数预算，稀疏位号
  可能被密集位号挤掉数据点，驱动必须逐位号补采才能还原成与单独查询逐点相同的结果，
  密集采样 × 多位号 × 大 `maxPoints` 的窗口会整体退回逐位号往返。

---

## 7. 驱动映射

### 7.1 HLS

| 公共操作 | HLS 接口 | 说明 |
|---|---|---|
| `listTags` | `GET /ddb/read_alltagname` | 全量；驱动分页/缓存 |
| `find` | 同上 | 内存查找 |
| `currentValues` | `POST /ddb/read_ddbtagvalue` | `TagNameListRequest` |
| `history(RAW)` | ❌ | 抛 `UNSUPPORTED` |
| `history(AGGREGATED)` | `POST /hdb/read_hdbtagvalue` | `HistorianRequest`，interval 单位为秒，宽表拆列 |
| `history(INTERPOLATED)` | ❌ | |
| `write` | ❌ | |
| `unwrap` | `HlsNativeApi` | 暴露 `HLSApiInvoker`/原始 client |

**宽表拆分**：

```
HDBTagValue{TagName, OneTagHDBValueList[
  {TagValueTime, TagValue_AVG, TagValue_MIN, Quality_MIN, ...}   // 无 Quality_AVG
]}
→ TagSeries{tagName, samples[
  {timestamp, value=所选聚合列, quality=对应质量码}
]}
```

**必须按请求的 `aggregation` 选列**（AVG→`TagValue_AVG`，MIN→`TagValue_MIN`，MAX→`TagValue_MAX`，BOUND→`TagValue_Bound`），不能固定取 AVG；同一次请求只返回一个聚合序列。`TagValueTime` 是区间起点还是终点需现场对照厂商工具确认，并在契约测试基线中固定。

**AVG 质量缺失**：HLS 响应只有 `Quality_Bound`/`Quality_MAX`/`Quality_MIN`，**没有 `Quality_AVG`**。v1.0 对 AVG 查询置 `Quality.UNKNOWN`，并在 `TagSeries.attributes["qualitySource"]="hls_not_provided"` 标注；MIN/MAX/BOUND 使用各自质量码。现场确认 AVG 质量语义后再补映射，禁止猜测为 GOOD。

### 7.2 GE

| 公共操作 | GE 接口（公共读路径=POST，位号在 body 明文分号拼接，标量参数在查询串） |
|---|---|
| `listTags` | `GET /v1/tags?maxNumber=&nameMask=` |
| `find` | `GET /v1/tags/{nameMask}/{maxNumber}` + 内存精确匹配 | 无单标签 GET，按掩码过滤 |
| `currentValues` | `POST /v1/datapoints/sampled`（`samplingMode="1"`、`queryModifier=1`）；遗留 `GET /v1/datapoints/currentvalue` 仅原生保留 |
| `history(RAW)` | `POST /v1/datapoints/raw`（位号进 body）；遗留 `GET /raw` 原生保留 |
| `history(AGGREGATED)` | `POST /v1/datapoints/calculated`（数字 `calculationMode`）；遗留 `GET /calculated` 原生保留 |
| `history(INTERPOLATED)` | `POST /v1/datapoints/interpolated`；遗留 `GET /interpolated` 原生保留 |
| `history(SAMPLED)` | `POST /v1/datapoints/sampled`（`samplingMode=2`、`queryModifier=1`） |
| `history(TREND)` | 公共层未声明（原生 `GET /v1/datapoints/trend` 可调） |
| `write` | `POST /v1/datapoints/create` |
| `unwrap` | `GeNativeApi` + `GeEndpoint`（92 个端点） |

> 精确的“公共 API → 端点 + HTTP 方法 + 参数位置”映射见 [docs/08 §2.5](08-原生API完整性对照.md)。遗留 GET 变体只作原生逃生口与生产逐点对比基准，公共读路径已统一走 POST。

### 7.3 横河

| 公共操作 | OPC UA |
|---|---|
| `listTags` | Browse（递归/分页） |
| `find` | TranslateBrowsePathsToNodeIds / Browse |
| `currentValues` | Read（DA）或 Subscription 最新值 |
| `history(RAW)` | HistoryRead（HDA） |
| `history(AGGREGATED)` | HistoryRead + AggregateFilter（若支持） |
| `history(INTERPOLATED)` | 原始点 + 客户端插值（`derived=true`） |
| `write` | Write（默认禁用） |
| `unwrap` | `YokogawaNativeApi`（Milo 客户端） |

**待现场确认**：HDA 是否启用、支持的聚合、命名空间、证书策略。

### 7.4 IoTDB

| 公共操作 | IoTDB |
|---|---|
| `listTags` | `SHOW TIMESERIES`（解析 `column_names`/`values`） |
| `find` | `SHOW TIMESERIES <path>` + 精确匹配 |
| `currentValues` | `SELECT LAST <位号表达式>, … FROM root.<db>`，缺失序列按位号名回填 `null` 占位 |
| `history(RAW)` | `SELECT <位号表达式>, … FROM root.<db> WHERE time >= ? AND time < ? ORDER BY TIME … LIMIT ?`，多列共享 LIMIT，不足列逐位号补采 |
| `history(AGGREGATED)` | `SELECT AVG(<位号表达式>), … FROM root.<db> GROUP BY ([start,end),interval)` |
| `history(SAMPLED)` | `SELECT LAST_VALUE(<位号表达式>), … FROM root.<db> GROUP BY ([start,end),1s/1m/1h/1d/1mo) FILL(…)` |
| `deltaHistory` | 同上取桶末值，但范围改为 `[start - 间隔, end)`：第 0 个桶即基线，每块 1 条 SQL；扩展桶未命中才补一条 `SELECT LAST_VALUE(…) … WHERE time < start`，内存相邻相减 |
| `history(INTERPOLATED)` | 服务端无等价能力：驱动取窗内原始点与两侧包围点，在内存按 `start + i*step` 线性内插 |
| `history(TREND)` | 不支持（驱动抛 `TsdbUnsupportedException`） |
| `write` | `insertTablet`（默认禁用，`allow-write=true` 开启） |
| `unwrap` | `IotdbRestApi` / `IotdbQueryService` / `IotdbConfig` |

> 表中 `<位号表达式>` 是**相对于 `FROM` 前缀**的写法，`FROM` 与表达式的层级必须一致，层级
> 错配时服务端**静默返回空**而不报错（详见 [docs/11 §4](11-IoTDB三通道与等间隔差分.md)）。
> 多位号合并成一条 SQL 的分组粒度由 `options.query-batch-scope` 决定：`database` / `root` /
> `device` 三档都实测可用，**当前默认是 `database`**（同库三档双跑对数七族 `diffs=0` 换来的，
> 见 [docs/14 §8.3](14-GE迁移IoTDB对照与替换方案.md)），`device` 是改造前行为的回退档，
> `query-batch-size: 1` 可退化成一 SQL 一位号。

---

## 8. 原生 API

### 8.1 实现形态

每个驱动提供一个**具体类**作为原生调用面（不是公共层的接口），通过 `client.unwrap(Class)` 取得；未识别的类型抛 `TsdbUnsupportedException`。

| 驱动 | 原生类（包） | 主要方法 |
|---|---|---|
| HLS | `io.github.forget_the_bright.tsdb.hls.internal.HlsNativeApi` | 原 SDK 的 `queryAllTags()`、`getDDBTagValue(TagNameListRequest)`、`getHDBTagValue(HistorianRequest)`、`getHDBTagValue(...)`，以及 Bound/Max/Min/Avg/All 的 `String...`/`Collection<String>` 全部重载；另保留 `listTags/readCurrent/readHistory/checkHealth/rawRequest` |
| GE | `io.github.forget_the_bright.tsdb.ge.internal.GeNativeApi` | `listTags/readCurrent/readRaw/readAggregated/readInterpolated/write/checkHealth`；另提供 `invoke(GeEndpoint)`、`invoke(endpoint,pathVars,queryParams,body)`、`rawRequest(method,path,body)`，覆盖 92 个端点 |
| IoTDB | `io.github.forget_the_bright.tsdb.iotdb.internal.transport.IotdbRestApi` / `io.github.forget_the_bright.tsdb.iotdb.internal.query.IotdbQueryService` | `listTags/readCurrent/readRaw/readAggregated/write/checkHealth`；另提供 `query(String)`、`nonQuery(String)`、`insertTablet(JSONObject)`、`ping()`；`IotdbQueryService` 对 native/JDBC/REST 统一提供 `current/raw/interval/deltas/listTags/serverVersion` |

> 完整对照见 [`docs/08-原生API完整性对照.md`](08-原生API完整性对照.md)：HLS 14 个原方法、GE 92 个端点、IoTDB 任意 SQL 均有入口。

```java
// 具体签名以源码为准；这里只表达“原生调用面仍是厂商语义”
// JSON 承载类型统一为 fastjson：对象用 JSONObject，数组用 JSONArray，
// 根类型不确定时用 Object（调用方可用 JsonText.object/array 显式转换）。
public final class HlsNativeApi {
    public JSONObject queryAllTags();
    public JSONObject getDDBTagValue(TagNameListRequest request);
    public JSONObject getHDBTagValue(HistorianRequest request);
    public JSONObject getHDBTagValueBound(Instant start, Instant end, Long intervalSeconds, String... tags);
    public List<HlsModels.Series> readHistory(Instant start, Instant end, long intervalSeconds,
                                              Aggregation aggregation, Collection<String> tags);
}

public final class GeNativeApi {
    public Object invoke(GeEndpoint endpoint);
    public Object invoke(GeEndpoint endpoint, Map<String,Object> arguments);
    public Object invoke(GeEndpoint endpoint, Map<String,Object> pathVars,
                         Map<String,Object> queryParams, Object body);
    public Object rawRequest(String method, String relativePath, Object body);
}

public final class IotdbRestApi {
    public JSONObject query(String sql);
    public JSONObject nonQuery(String sql);
    public JSONObject insertTablet(JSONObject request);
    public String ping();
}
```

### 8.2 示例

```java
// GE 原生标签检索
GeNativeApi ge = client.unwrap(GeNativeApi.class);
List<String> tags = ge.listTags(1000, "AI*");

// HLS 原生历史请求（厂商宽表语义，不走公共归一）
HlsNativeApi hls = client.unwrap(HlsNativeApi.class);
List<HlsModels.Series> raw = hls.readHistory(start, end, 60, Aggregation.AVG,
        Arrays.asList("AI-101"));

// IoTDB 原生 SQL/REST 调用面
IotdbRestApi iotdb = client.unwrap(IotdbRestApi.class);
List<IotdbModels.Tag> series = iotdb.listTags("root.tongling.**");
```

> 原生调用不经过能力校验、质量归一、审计；写操作安全由调用方与网络策略负责。文档与代码评审必须强调。

---

## 9. 配置参考

### 9.1 顶层

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `tsdb.default-instance` | string | 第一个启用的 | 默认实例名 |
| `tsdb.fail-fast` | bool | true | 启动时坏实例是否失败 |
| `tsdb.security.allow-write` | bool | false | 全局写开关 |
| `tsdb.security.allow-insecure-transport` | bool | false | 允许 HTTP/明文；仅限隔离内网，启动 WARN + 审计 |
| `tsdb.security.audit.enabled` | bool | true | 审计开关 |
| `tsdb.security.tls.verify-hostname` | bool | true | 主机名校验 |

### 9.2 实例公共

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `type` | string | 必填 | `hls`/`ge`/`yokogawa`/`iotdb` |
| `protocol` | string | 驱动默认 | `rest`/`opcua`/`session` |
| `enabled` | bool | true | 是否启用 |
| `base-url` | string | — | REST 基地址 |
| `endpoint` | string | — | OPC UA endpoint |
| `node-urls` | list | — | IoTDB 节点 |
| `auth.mode` | string | 必填 | 见下 |
| `timeout.connect` | Duration | 5s | |
| `timeout.read` | Duration | 30s | |
| `timeout.write` | Duration | 30s | |
| `retry.max-attempts` | int | 2 | 仅读 |
| `retry.backoff` | Duration | 200ms | |
| `pool.max-concurrent` | int | 8 | |
| `cache.tag-ttl` | Duration | 10m | |
| `cache.token-skew` | Duration | 60s | |
| `options` | map | — | 驱动专属 |

### 9.3 认证模式

| mode | 适用 | 字段 |
|---|---|---|
| `header-token` | HLS | `user-id`,`secret-key` |
| `oauth2` | GE | `client-id`,`client-secret`,`username`,`password` |
| `basic` | GE/通用 | `username`,`password` |
| `x509` | 横河 OPC UA | `certificate`,`private-key` |
| `password` | IoTDB | `username`,`password` |
| `none` | 测试 | —（生产禁止） |

### 9.4 HLS 示例

```yaml
tsdb:
  security:
    allow-insecure-transport: true   # 参照环境为 http；生产应在 TLS 代理后改为 false
  instances:
    hls_main:
      type: hls
      base-url: http://10.10.7.177:9000   # 参照工程为 http；生产建议前置 TLS 代理
      auth:
        mode: header-token
        user-id: ${HLS_USER}
        secret-key: ${HLS_SECRET}
      options:
        token-expire-seconds: 1800
        cache-model: local
```

### 9.5 GE 示例

```yaml
tsdb:
  instances:
    ge_main:
      type: ge
      base-url: https://172.16.176.9
      auth:
        mode: oauth2
        client-id: ${GE_CLIENT_ID}
        client-secret: ${GE_CLIENT_SECRET}
        username: ${GE_USER}
        password: ${GE_PASSWORD}
      options:
        token-expire-seconds: 43199
        time:
          request:
            enabled: true
            zone: UTC
            pattern: "yyyy-MM-dd'T'HH:mm:ssXXX"
          response:
            enabled: true
            source-zone: UTC
            target-zone: GMT+8
            pattern: "yyyy-MM-dd HH:mm:ss"
```

> 铜陵现场：GE 的请求与响应都是 UTC，`target-zone: GMT+8` 只影响展示；
> 若某项目 GE 本身用 GMT+8，把 `request.zone` 和 `response.source-zone` 改为 `GMT+8` 即可。
> 公共 API 的 `Sample.timestamp` 始终是绝对 `Instant`，可用 `GeClient.formatTimestamp(...)`
> 或 `TimeConfig.formatResponse(...)` 输出到界面；返回对象上的 `timestampDate`/`timestampText`
> 已经按同样配置生成，前端直接读取 `timestampText` 即可。

### 9.6 横河示例（历史范围，已移出本期）

```yaml
tsdb:
  instances:
    yokogawa_dcs:
      type: yokogawa
      protocol: opcua
      endpoint: opc.tcp://10.20.30.40:4840
      auth:
        mode: x509
        certificate: ${YOKO_CERT}
        private-key: ${YOKO_KEY}
      options:
        security-policy: Basic256Sha256
        security-mode: SignAndEncrypt
        namespace-uri: "urn:yokogawa:centum"
        browse-root: "ns=2;s=..."
```

### 9.7 IoTDB 示例

```yaml
tsdb:
  instances:
    iotdb_historian:
      type: iotdb
      protocol: rest
      base-url: http://10.30.1.10:18080
      auth:
        mode: password
        username: ${IOTDB_USER}
        password: ${IOTDB_PASSWORD}
      options:
        path-prefix: /rest/v2
      timeout:
        connect-ms: 5000
        read-ms: 30000
```

> REST 服务需在 `conf/iotdb-system.properties` 开启：`enable_rest_service=true`、`rest_service_port=18080`。
> 高吞吐写入场景可后续增加基于 `SessionPool` 的可选驱动；当前 REST 驱动适合查询与中低吞吐写入。

### 9.8 旧配置映射（规划，当前版本未内置）

下表是迁移目标，`TsdbConfigLoader` 当前只读取 `tsdb.*`；旧前缀自动映射尚未实现。

| 旧键 | 新键 |
|---|---|
| `hls.datacollection.base-url` | `tsdb.instances.<name>.base-url` |
| `hls.datacollection.userid` | `...auth.user-id` |
| `hls.datacollection.secretkey` | `...auth.secret-key` |
| `hls.datacollection.token-expire-seconds` | `...options.token-expire-seconds` |
| `hls.datacollection.cacheModel` | `...options.cache-model` |
| `ge.datacollection.base-url` | `tsdb.instances.<name>.base-url` |
| `ge.datacollection.client-id` | `...auth.client-id` |
| `ge.datacollection.client-secret` | `...auth.client-secret` |
| `ge.datacollection.username` | `...auth.username` |
| `ge.datacollection.password` | `...auth.password` |

规划规则：新前缀优先；仅旧前缀时自动合成 `<type>_default` 实例；启动日志打印映射结果。当前版本需手工迁移为 `tsdb.*`。

---

## 10. 使用示例

### 10.1 纯 Java（无 Spring）

```java
TsdbManager manager = TsdbClients.manager()
        .config(TsdbConfigLoader.fromYaml(Paths.get("tsdb.yml")))
        .build();

TsdbClient client = manager.client("hls_main");

List<TagInfo> tags = client.tagCatalog().listTags(
        TagQuery.builder().nameMask("AI*").limit(100).build());

List<TagSample> current = client.currentValues().read(
        Arrays.asList("AI-101", "AI-102"));

HistoryQuery query = HistoryQuery.builder()
        .tags("AI-101")
        .range(Instant.now().minus(Duration.ofHours(24)), Instant.now())
        .mode(QueryMode.AGGREGATED)
        .aggregation(Aggregation.AVG)
        .interval(Duration.ofMinutes(5))
        .build();

List<TagSeries> series = client.history().read(query);

manager.close();
```

### 10.2 Spring Boot

```java
@Service
public class TrendService {
    private final TsdbClient tsdb;

    public TrendService(@Qualifier("hls_main") TsdbClient tsdb) {
        this.tsdb = tsdb;
    }

    public List<TagSeries> last24h(String tag) {
        return tsdb.history().read(HistoryQuery.builder()
                .tags(tag)
                .range(Instant.now().minus(Duration.ofHours(24)), Instant.now())
                .mode(QueryMode.RAW)
                .build());
    }
}
```

### 10.3 切换数据库

```yaml
tsdb:
  default-instance: ge_main   # 只改这一行
```

```java
TsdbClient client = manager.defaultClient(); // 业务代码不变
```

> 前提：查询使用的标签在目标库存在；不存在时返回空/错误，属数据问题而非代码问题。

---

## 11. 兼容性与迁移

### 11.1 从旧 SDK 迁移

| 旧 | 新 | 说明 |
|---|---|---|
| `HLSApiInvoker.getDDBTagValue(req)` | `client.currentValues().read(tags)` | 模型归一 |
| `HLSApiInvoker.getHDBTagValue(req)` | `client.history().read(query)` | 宽表→长表 |
| `DataApiInvoker.getRawData...` | `client.history().read(rawQuery)` | |
| `DataApiInvoker.getCurrentValue...` | `client.currentValues().read(tags)` | |
| 静态 `ApiClient` | `client.unwrap(...)` | 原生逃生口 |

### 11.2 不兼容点

- 静态方法 → 实例方法；
- 厂商模型 → 公共模型；
- 质量码 → 统一 Quality；
- 默认超时 → 强制超时；
- 配置前缀 → `tsdb.*`（旧前缀可映射）。

**2026-11-16 迁移轮新增的不兼容点（用户拍板：保留的是 API 能力，不是实现库）**：

| 变更 | 旧 | 新 | 影响 |
|---|---|---|---|
| JSON 承载类型 | `com.fasterxml.jackson.databind.JsonNode` | GE `invoke(...)`/`rawRequest(...)` 返回 `Object`（fastjson `JSONObject`/`JSONArray`/标量）；HLS `queryAllTags()`/`getDDBTagValue(...)`/`getHDBTagValue*` 与 IoTDB `query`/`nonQuery`/`insertTablet` 返回 `JSONObject` | 直接依赖 `JsonNode` 类型的调用方需改类型；根类型不确定处用 `JsonText.object/array/text` 显式转换 |
| 原生构造器 | `GeNativeApi(config,http,tokens,mapper)`、`IotdbRestApi(config,http,mapper)`、`IotdbRestQueryExecutor(config,mapper)` | 去掉 mapper 参数 | 只有自建驱动实例的调用方受影响 |
| 公共模型注解 | `@JsonSerialize(ToStringSerializer)`、`@JsonProperty` | 删除 / `@JSONField(name=...)`，序列化靠 fastjson 默认（`Instant` → ISO 字符串、`Date` → epoch 毫秒） | null 字段默认**不输出**（Jackson 会输出 `null`）；契约由 `SampleTimeFieldsTest` 钉住 |
| 布尔配置解析 | `Boolean.parseBoolean`（非法值静默 false） | `TsdbInstanceConfig.booleanOption` 只认 `true`/`false`，其余启动即抛 `TsdbConfigurationException` | GE `trust-all-certificates` 写 `1/yes/on` 由静默失效变为失败 |
| `@Deprecated` 冗余入口 | `IotdbSql` 8 个单路径重载、`IotdbQueryService.current(String)`/旧 `currentBatch(...)`、`IotdbTagResolver.resolve(String)`、`TagPathMapper.canonicalPath(String)` | 删除；单路径统一走 `Grouping.DEVICE` + `Collections.singletonList(path)` 批量形态 | SQL 文本等价性由 `IotdbTagPathTest`/`IotdbSqlTest` 断言；批量能力未减少 |
| 传输实现 | 各驱动手写 `HttpURLConnection` | 公共层 `HttpTransport`（Hutool 5.8.25）+ `HttpErrors`/`TrustAllSsl`/`BasicAuth` | 状态码→异常映射、UTF-8 解码、错误体截断（512 + `...`）语义保持一致，由 `HttpTransportTest` + `HttpErrorsTest` 覆盖 |

**2026-11-16 包结构归位轮新增的不兼容点（纯结构移动：不改逻辑、断言、配置键、SQL 语义）**：

公共 API 的 **import 包路径一个都没变**——`common.api`、`common.model`、`common.config`、
`common.exception`、`common.spi` 与 `<vendor>.Client|Config|Driver|Mapper`（IoTDB 另有
`IotdbTransport`）仍在原包。`javap` 实测（2026-11-16，改造前基线 vs 改造后，共 63 个类块）：

- `common.api`（7 类）+ `common.model`（20 类）+ 各自的 `package-info`：**29 个类块逐字一致，0 处差异**；
- 18 个下沉的装配层类：**只有 18 个类声明 + 18 个构造器 + 2 个方法**（`GeSupport.unsupported`、
  `HlsSupport.ensureOpen`）从 package-private 变 `public`，其余成员可见性 **0 处变化**；
- **3 个根包入口类的签名文本有差异，但差异全部来自「签名里引用的内部类型换了包」**，不是新增/删除成员：

| 类 | 差异成员 | 原因 |
|---|---|---|
| `iotdb.IotdbClient` | 3 个构造器的参数类型、`tagMappingStats()` 的返回类型 | 参数/返回类型是 `IotdbRestApi`、`IotdbQueryService`、`TagPathMapper`、`IotdbTagResolver$TagMappingStats`，随内部包移动 |
| `iotdb.IotdbConfig` | `DEFAULT_QUERY_BATCH_SCOPE` 字段、`getQueryBatchScope()` 返回类型 | 类型是 `Grouping`，移到 `internal.sql` |
| `ge.GeClient` | 编译器合成桥 `access$000(GeClient)` 的返回类型 | `GeSupport` 移到 `internal.adapter`；合成成员不出现在源码里 |

也就是说：**只 import 公共入口与公共模型的调用方，源码与二进制都不需要改**；
直接 `new IotdbClient(cfg, restApi, queryService, mapper)` 或把 `getQueryBatchScope()`
赋给 `Grouping` 变量的调用方，要按下表改内部类型的 import。受影响的只有**直接 import
内部实现类**的调用方（含 `unwrap(...)` 取原生能力的代码）：

| 变更 | 旧 import | 新 import | 影响 |
|---|---|---|---|
| 共享 HTTP 工具下沉 | `common.internal.BasicAuth` / `HttpTransport` / `HttpResult` / `HttpErrors` / `TrustAllSsl` | `common.internal.http.*`（同名类） | 只改 import；`common.internal.Texts` **留在原包** |
| 共享 JSON 工具下沉 | `common.internal.Json` / `JsonText` | `common.internal.json.*` | 只改 import |
| IoTDB 位号与 SQL | `iotdb.internal.IotdbSql` / `IotdbTagPath` / `IotdbTagResolver` / `TagPathMapper` / `Grouping` | `iotdb.internal.sql.*` | 只改 import；`TagPathMapper` 仍是宿主可实现的扩展插口 |
| IoTDB 查询编排 | `iotdb.internal.IotdbQueryService` / `SeriesKeyIndex`（及 6 个 `*Queries`、`IotdbQueryCore`、`PointMath`、`ResultColumns`） | `iotdb.internal.query.*` | `unwrap(IotdbQueryService.class)` 的**方法签名与语义不变**，只需改 import |
| IoTDB 通道与原生 REST | `iotdb.internal.IotdbRestApi` / `IotdbHttpClient` / `IotdbResultTable` / `IotdbQueryExecutor` / `IotdbNativeQueryExecutor` / `IotdbJdbcQueryExecutor` / `IotdbRestQueryExecutor` | `iotdb.internal.transport.*` | `unwrap(IotdbRestApi.class)` 同上，只改 import |
| IoTDB 厂商 DTO | `iotdb.internal.IotdbModels` | **不变** | 跨子包共享，刻意留在 `internal` 根包 |
| 装配层下沉（18 个类） | `ge.GeSupport` / `Ge*Adapter`（5）、`hls.HlsSupport` / `Hls*Adapter`（6）、`iotdb.IotdbSupport` / `IotdbSeriesAssembler` / `Iotdb*Adapter`（7） | `<vendor>.internal.adapter.*` | **非公共 API，仅可见性放宽**：18 个类名 + 18 个构造器改 `public`（门户需跨包构造），另加 `GeSupport.unsupported`、`HlsSupport.ensureOpen` 两个方法；`Support` 其余访问器与 `IotdbSeriesAssembler` 的方法**仍 package-private**。这些类从来不是承诺契约，宿主不应直接依赖 |
| pSpace 装配层（6 个类，新增模块） | `pspace.PspaceSupport` / `Pspace*Adapter`（5） | `pspace.internal.adapter.*` | 新模块按本规范直接落位，`public final` + 「非公共 API」标注；不计入上表 18 类的历史 `javap` 测量 |
| 测试包路径 | `iotdb.internal.IotdbSqlTest` 等 7 个测试类 | 镜像到 `internal/sql`、`internal/query`、`internal/transport`；`common.internal` 的 4 个测试镜像到 `http`/`json` | 只影响按包名筛选测试的命令（`-Dtest=` 用简单类名不受影响） |

新增 12 个 `package-info.java`（全仓 25 个），每个包写明职责、可见性契约与「改什么找哪个类」；
`common/model` 的 20 个类**保持平铺**，改为在 package-info 里给分类索引（枚举 8 / 查询构造 2 /
结果容器 8 / 写入 2），因为拆子包会改掉几乎所有公共签名的 import。

### 11.3 版本兼容

- 公共 API 1.x 只增不删；
- SPI 新方法用 `default`；
- 驱动内部包不承诺；
- 配置键删除需大版本 + 迁移器。

---

## 12. 契约测试清单（testkit）

每个驱动必须通过：

| 编号 | 用例 | 断言 |
|---|---|---|
| CT-01 | 创建/关闭 | 幂等，无泄漏 |
| CT-02 | 能力声明 | 与实际行为一致 |
| CT-03 | 标签分页 | 不重不漏 |
| CT-04 | 实时值顺序 | 与入参一致 |
| CT-05 | 不存在标签 | 不整体失败 |
| CT-06 | 历史区间 | `[start,end)` |
| CT-07 | 聚合值 | 与厂商工具抽样一致 |
| CT-08 | 质量映射 | 各码正确 |
| CT-09 | 超时 | 抛 `TsdbTimeoutException` |
| CT-10 | 401 | 刷新 token 一次 |
| CT-11 | 不支持能力 | 发请求前抛 `UNSUPPORTED` |
| CT-12 | 大结果截断 | `truncated=true` |
| CT-13 | 并发 | 无交叉污染 |
| CT-14 | 原生 API | 可取到且可用 |
| CT-15 | 写门禁 | 默认拒绝 |

---

## 13. 本章结论

1. 公共 API 的边界是“**通用查询 + 能力协商 + 原生逃生口**”，不是“把厂商所有方法都包一遍”。
2. 质量码、时间、类型、区间语义必须先定死，否则换库结果不可比。
3. 配置以 `tsdb.instances.*` 为核心，旧前缀只做兼容。
4. 契约测试是 API 规范的一部分；没有测试的“支持”等于不支持。
