# 15 · API 参考与原生对照

> 版本：2026-11-17 · 适用 `tsdb-sdk` 0.1.0
> 本文是 [README](../README.md) 第 6.5 节「公共方法 → 原生对照」的展开版。
> 每个公共方法都标注三库各自落到哪条厂商端点或哪条 SQL；每个原生门面逐方法列出端点。
> 所有签名、端点号、SQL 形态、白名单均从源码核对，出处以 `文件名:行号` 给出。

---

## 1. 怎么读本文

SDK 有两条并列的调用路径，**不是上下层封装关系**：

```text
业务代码
  ├── 公共层  TsdbClient.tagCatalog() / currentValues() / history() / deltaHistory() / writeSession()
  │            → 换库不改业务代码，但只有跨厂商通用能力
  └── 原生层  client.unwrap(XxxNativeApi.class)
               → 厂商原始 JSON / 原始 DTO / 任意 SQL，能力不阉割，但不可移植
```

两条路径共享同一个连接、认证、超时与配置对象，不会各开一套连接。

### 1.1 包可见性契约（先读这条，否则会误用）

| 包 | 可见性 | 兼容性承诺 |
|---|---|---|
| `common.api` / `common.model` / `common.config` / `common.exception` / `common.spi` | public | **公共 API**，语义稳定 |
| 驱动根包（`hls` / `ge` / `iotdb`） | public | **公共 API**：`XxxClient`、`XxxConfig`、`XxxDriver` |
| `ge.nativeapi` / `hls.nativeapi` | public | 厂商常量与 DTO，随厂商接口变化 |
| `*.internal.*`（含 `internal.adapter`） | public 类 | **非公共 API，不承诺兼容**；只能通过 `unwrap(...)` 取出使用 |

原生门面全部在 `internal` 包下：

- HLS → `hls/internal/HlsNativeApi.java`
- GE → `ge/internal/GeNativeApi.java`（端点常量表 `ge/nativeapi/GeEndpoint.java` 是唯一放在 `nativeapi` 的原生面）
- IoTDB → `iotdb/internal/transport/IotdbRestApi.java` 与 `iotdb/internal/query/IotdbQueryService.java`

> **IoTDB 没有 `nativeapi` 包**。它的原生面就是上面两个 `internal` 类，不要去找 `IotdbNativeApi`——不存在。

`internal` 类是 `public` 只是为了让 `unwrap` 能跨包返回，不代表它们进入兼容承诺。直接 `new HlsNativeApi(config)` 也能编译，但升级时可能改签名。

### 1.2 本文的方法体例

每个公共方法固定给五项：

- **签名**：源码原样
- **语义**：中文一句 + 区间开闭 / 顺序 / 空值约定
- **原生对照**：HLS / GE / IoTDB 三行，写端点或 SQL + 关键参数
- **不支持时**：哪个驱动抛 `TsdbUnsupportedException`（不静默降级、不返回空冒充成功）
- **示例**：必要时给 3–5 行

### 1.3 全局约定（所有读方法都适用）

| 约定 | 内容 |
|---|---|
| 时间区间 | 左闭右开 `[start, end)` |
| 时间类型 | 入参出参都是 `java.time.Instant`（绝对时刻，UTC 语义） |
| 空集合 | 返回空 `List`，**不返回 `null`** |
| 缺失位号 | 当前值返回 `TagSample(tag, null)`，**不伪造 0** |
| 当前值顺序 | 与请求集合的迭代顺序一致 |
| 质量码 | 统一 `GOOD/UNCERTAIN/BAD/STALE/UNKNOWN`，同时保留 `vendorQualityCode` |
| 展示文本 | `Sample.timestampText` 只是按实例时区格式化后的展示值，不参与计算 |
| 默认上限 | `HistoryQuery.DEFAULT_MAX_POINTS = 10000`；`TagQuery.DEFAULT_LIMIT = 100` |

---

## 2. 公共入口：`TsdbClients` / `TsdbManager`

这一层**不产生任何厂商调用**，只做装配与实例生命周期管理，所以「原生对照」一栏统一标注「本地装配」。

### 2.1 `TsdbClients`（静态门面，3 个方法）

| 签名 | 语义 | 原生对照 |
|---|---|---|
| `static TsdbManager.Builder manager()` | 返回可链式配置的构造器 | 本地装配 |
| `static TsdbManager fromConfig(TsdbConfig config)` | 用已解析的配置对象直接建管理器 | 本地装配 |
| `static TsdbManager fromYaml(Path yaml)` | 读 YAML 文件 → 解析 → 建管理器 | 本地装配 |

```java
TsdbManager manager = TsdbClients.fromYaml(Paths.get("tsdb.yml"));
TsdbClient client = manager.client();
```

### 2.2 `TsdbManager.Builder`

| 签名 | 语义 | 备注 |
|---|---|---|
| `Builder config(TsdbConfig)` | 设置配置对象 | 必填 |
| `Builder driver(TsdbDriver)` | 注册单个驱动 | 与 `drivers` 可混用 |
| `Builder drivers(Collection<TsdbDriver>)` | 批量注册驱动 | — |
| `Builder discoverDrivers()` | 用 `ServiceLoader` 发现 classpath 上的驱动 | 三个驱动各自注册了 `META-INF/services/io.github.forget_the_bright.tsdb.common.spi.TsdbDriver` |
| `Builder metrics(Metrics metrics)` | 注入指标出口 | 可选 |
| `TsdbManager build()` | 构建管理器 | **`drivers` 为空时自动调用 `discoverDrivers()`**（`TsdbManager.java:422`），所以只引一个驱动 jar 也能工作 |

### 2.3 `TsdbManager`

| 签名 | 语义 | 原生对照 |
|---|---|---|
| `String activeInstance()` | 当前活动实例名 | 本地 |
| `Set<String> availableInstances()` | 配置里声明的全部实例名 | 本地 |
| `TsdbConfig config()` | 返回生效配置 | 本地 |
| `TsdbClient client()` | 取活动实例的客户端（懒加载） | 触发对应驱动的建连/取 token |
| `TsdbClient client(String name)` | 按名取客户端 | 同上 |
| `void switchTo(String name)` | 运行期切换活动实例 | **先建新客户端，成功后才关旧的**；新连接失败时旧客户端仍可用 |
| `void close()` | 关闭全部已创建客户端 | 释放 HTTP 连接 / IoTDB SessionPool |

> 运行期只保留**一个活动客户端**，这是刻意设计：避免现场误连两套库导致数据写错地方。
> 需要同时读两套库做对数时，用 `client(name)` 显式取两个客户端，而不是靠 `switchTo` 来回切。

### 2.4 `TsdbConfigLoader`

| 签名 | 语义 |
|---|---|
| `static TsdbConfig load(Path yaml)` | 读文件，支持 `${ENV}` / `${ENV:default}` 占位符 |
| `static TsdbConfig fromYaml(InputStream in)` | 从流解析 |
| `static TsdbConfig fromYaml(String yamlText)` | 从字符串解析 |

占位符在**解析期**替换，凭据不落配置文件。解析失败抛 `TsdbConfigurationException`（错误码 `TSDB-CONFIG`，不可重试）。

---

## 3. 公共接口逐方法

`common.api` 包共 **7 个接口**：`TsdbClient`、`TagCatalog`、`CurrentValueReader`、`HistoryReader`、`DeltaReader`、`WriteSession`、`HealthCheck`。

### 3.1 `TsdbClient`（11 个方法）

三个驱动客户端都是 `public final class XxxClient implements TsdbClient`（`HlsClient.java:52`、`GeClient.java:52`、`IotdbClient.java:77`）。

| 签名 | 语义 | 原生对照 |
|---|---|---|
| `String instanceName()` | 配置里的实例名 | 本地 |
| `String type()` | 驱动类型：`hls` / `ge` / `iotdb` | 本地 |
| `CapabilitySet capabilities()` | 该实例声明的能力集 | 本地（声明值见 §3.8） |
| `TagCatalog tagCatalog()` | 标签目录入口 | 见 §3.2 |
| `CurrentValueReader currentValues()` | 当前值入口 | 见 §3.3 |
| `HistoryReader history()` | 历史入口 | 见 §3.4 |
| `default DeltaReader deltaHistory()` | 累计差分入口 | 见 §3.5；**接口默认实现直接抛 `TsdbUnsupportedException`**，驱动不覆盖就没有差分能力 |
| `WriteSession writeSession()` | 写入口 | 见 §3.6 |
| `HealthStatus health()` | 探活 | 见 §3.7 |
| `<T> T unwrap(Class<T> nativeType)` | 取原生门面 | 见 §5 / §6 / §7 |
| `void close()` | 关闭本客户端 | 释放连接 |

`unwrap` 的三库可用类型：

| 驱动 | 可 unwrap 的类型 |
|---|---|
| HLS | `HlsNativeApi`、`HlsConfig` |
| GE | `GeNativeApi`、`GeConfig` |
| IoTDB | `IotdbRestApi`、`IotdbQueryService`（装配了才有）、`IotdbConfig` |

传入不支持的类型抛 `TsdbUnsupportedException`，**不返回 `null`**。

### 3.2 `TagCatalog`

#### `listTags(TagQuery query)`

- **签名**：`List<TagInfo> listTags(TagQuery query)`
- **语义**：按条件浏览位号目录。`query == null` 等价于 `TagQuery.builder().build()`（默认 `limit=100`、`offset=0`）。`limit == 0` 表示不限量。返回空列表而非 `null`。
- **原生对照**：

| 驱动 | 端点 / SQL | 关键参数与过滤位置 |
|---|---|---|
| HLS | `GET /ddb/read_alltagname` | **服务端无过滤能力**：一次拉全量（现场约 4.3 MB），`offset`/`limit` 在客户端裁剪（`HlsTagCatalogAdapter.java:61-64`） |
| GE | `GET /v1/tags`（`QUERY_TAGS_BY_PARAMS`） | `nameMask` 下推服务端；`maxNumber = limit==0 ? 100000 : min(100000, limit+offset)`（`GeTagCatalogAdapter.java:33,67`），`offset`/`limit` 客户端裁剪 |
| IoTDB | `SHOW TIMESERIES <prefix>` | `prefix` 由 `tagResolver().resolvePrefix(nameMask)` 得到；空掩码 → 裸 `SHOW TIMESERIES`（`IotdbRestApi.java:79`）。REST v2 不支持 offset/limit，分页与值类型过滤都在客户端（`IotdbTagCatalogAdapter.java:17,56-70`） |

- **不支持时**（全部抛 `TsdbUnsupportedException`，不退化为本地过滤）：

| 过滤条件 | HLS | GE | IoTDB |
|---|---|---|---|
| `nameMask` 非空 | ❌ 抛（`HlsTagCatalogAdapter.java:46`） | ✅ 下推 | ✅ 转前缀 |
| `valueType` 非 null | ❌ 抛（`:49`） | ❌ 抛（`GeTagCatalogAdapter.java:57`） | ✅ 客户端过滤（`:61`） |
| `unit` 非空 | ❌ 抛（`:49`） | ❌ 抛（`:61`） | ❌ 抛（`IotdbTagCatalogAdapter.java:47`） |

- **示例**：

```java
// 三库都能跑：只给 limit
List<TagInfo> tags = client.tagCatalog().listTags(TagQuery.builder().limit(50).build());

// 只有 GE / IoTDB 能跑；HLS 会抛 TsdbUnsupportedException
List<TagInfo> ai = client.tagCatalog().listTags(
        TagQuery.builder().nameMask("AI*").limit(50).build());
```

> **换库注意**：`nameMask` 在 HLS 上不可用。如果业务依赖掩码检索，切到 HLS 前必须改成「拉全量 + 本地缓存 + 本地过滤」，这是业务改造，不是配置改造。

#### `getTag(String tagName)`

- **签名**：`TagInfo getTag(String tagName)`（接口有 `default` 实现，三个驱动都 `@Override` 了）
- **语义**：按名精确查找单个位号；不存在返回 `null`；`tagName` 为 `null`/空白抛 `IllegalArgumentException`。
- **原生对照**：

| 驱动 | 实现方式 | 代价 |
|---|---|---|
| HLS | 拉全量目录后线性扫描（`HlsTagCatalogAdapter.java:80-85`） | **每次调用都拉全量**，现场约 199 s，不要放在循环里 |
| GE | `listTags(1000, tagName)` 把名字当掩码，再精确字符串匹配（`GeTagCatalogAdapter.java:89-98`） | 上限固定 1000 条 |
| IoTDB | `resolvePath(tagName)` → 取 device 前缀 → `SHOW TIMESERIES <device>` → 按绝对路径精确匹配（`IotdbTagCatalogAdapter.java:83-95`） | 只扫一个 device |

- **不支持时**：三库都支持，无 `TsdbUnsupportedException`。
- **接口默认实现**（驱动未覆盖时）：`TagQuery.builder().nameMask(tagName).limit(0).build()` + 线性扫描——在 HLS 上会因为 `nameMask` 被拒而抛异常，所以驱动必须覆盖，实际三个都覆盖了。

### 3.3 `CurrentValueReader`

#### `read(Collection<String> tagNames)`

- **签名**：`List<TagSample> read(Collection<String> tagNames)`
- **语义**：批量读当前值。**返回顺序与入参迭代顺序一致**；缺失位号给 `TagSample(tag, null)`，不伪造 0；空集合返回空列表。per-tag 错误落在 `TagSample.attributes`（`errorCode`/`errorMessage`/`dataType`/`engineeringUnits`/量程）。
- **原生对照**：

| 驱动 | 端点 / SQL | 关键参数 |
|---|---|---|
| HLS | `POST /ddb/read_ddbtagvalue` | body `TagNameList:[{TagName:…}]`（`HlsNativeApi.java:361-371`）；无 LAST 语义，取实时库当前值 |
| GE | `POST /v1/datapoints/sampled`（`GET_SAMPLED_BY_REQUEST_PARAM_POST`） | `samplingMode="1"`、`queryModifier=1L`，body 明文分号拼接 `tagNames`（`GeNativeApi.readCurrentRawValue`，`:203`） |
| IoTDB | `SELECT LAST E(p1), E(p2), … FROM <分组前缀>` | 按 `query-batch-scope` 分块（默认 `database`），`IotdbSql.java:92`；**应答行序不等于请求序**，必须按 `Timeseries` 列名回填（`SeriesKeyIndex.java:11`） |

> GE 的当前值走的是 `sampled` 端点而不是 `/v1/datapoints/currentvalue`：后者是遗留 GET 形态，
> 高频批量读统一改 POST 以避开 URL 长度限制。原生层仍保留 `readCurrent(...)`（GET 形态）供对照。

- **不支持时**：三库都声明 `CURRENT_VALUE`，无不支持路径。
- **示例**：

```java
List<TagSample> now = client.currentValues().read(Arrays.asList("AI-101", "AI-102"));
for (TagSample ts : now) {
    if (ts.getSample() == null) { continue; }          // 缺失位号
    System.out.println(ts.getTagName() + " = " + ts.getSample().getValue()
            + " @" + ts.getSample().getTimestampText());
}
```

#### `read(String tagName)`

- **签名**：`default TagSample read(String tagName)`
- **语义**：单点便捷重载，内部包成单元素集合调批量方法，语义完全一致。
- **原生对照**：同上（一次往返，不是新端点）。

### 3.4 `HistoryReader`

#### `read(HistoryQuery query)`

- **签名**：`List<TagSeries> read(HistoryQuery query)`
- **语义**：按 `mode` 读历史，区间 `[start, end)`。每个位号一条 `TagSeries`；点数超 `maxPoints` 时 `truncated=true`。`partialResultAllowed=true` 时 per-tag 失败落 `TagSeries.attributes.vendorError`，否则整条查询抛异常。
- **原生对照（按 `QueryMode` 分）**：

| mode | HLS | GE | IoTDB |
|---|---|---|---|
| `RAW` | ✅ 用历史值接口替代：`POST /hdb/read_hdbtagvalue`，`interval=1` + `NeedQueryBound=true`，每 1 秒桶取 `TagValue_Bound` 近似原始点；结果 `attributes` 带 `rawSource=hls_history_1s_bound`、`rawApproximate=true`；仅 `FORWARD`、`FillPolicy` 仅 `NONE` | `POST /v1/datapoints/raw`（`GET_RAW_DATA_BY_REQUEST_PARAM_POST`），query 标量 + body `tagNames` | 多列 `SELECT E(p1), E(p2) … FROM <前缀> WHERE time >= s AND time < e ORDER BY TIME [ASC\|DESC] LIMIT <预算>`，欠采列逐位号补采 |
| `AGGREGATED` | `POST /hdb/read_hdbtagvalue`，`interval` 秒 + `NeedQueryAVG/MIN/MAX/Bound` 四布尔 | `POST /v1/datapoints/calculated`（`GET_CALCULATED_BY_REQUEST_PARAM_POST`），`calculationMode` + `intervalMs` + `count` | 多列 `SELECT AVG(E(p)), … FROM <前缀> GROUP BY ([s,e),<字面量>) [FILL(…)]` |
| `AGGREGATED` + `INTERVAL_SUM` | ✅ 两侧 1 分钟 `BOUND` 探测（各一次 `read_hdbtagvalue`，`interval=1`），末−首，缺边不出点 | `POST /v1/datapoints/calculated` **×2**：`calculationMode=17`（FirstRawValue）与 `19`（LastRawValue），`count=1`、`intervalMs=1`，末−首（`GeNativeApi.readIntervalSum`，`:460`） | `SELECT first_value(E(p)), last_value(E(p)), … FROM <前缀> WHERE …`（**无 GROUP BY**，1 个逻辑行），末−首 |
| `SAMPLED` | `POST /hdb/read_hdbtagvalue`，用 `NeedQueryBound=true` 取桶边界值（`HlsHistoryAdapter.java:64`） | `POST /v1/datapoints/sampled`，`samplingMode=2`、`queryModifier=1`（`GeHistoryAdapter.java:79-81`） | 多列 `GROUP BY` + `LAST_VALUE`，等价于按间隔取每桶末值 |
| `INTERPOLATED` | ❌ 抛 | `POST /v1/datapoints/interpolated`（`GET_INTERPOLATED_BY_REQUEST_PARAM_POST`），`count` + `intervalMs` | **SDK 内存网格**：窗内多列 raw + 左右包围点多列 raw + 补采，再线性内插；不用服务端 `FILL(LINEAR)` |
| `TREND` | ❌ 抛 | ❌ 抛（公共层未声明） | ❌ 抛 |

- **模式白名单出处**：`HlsHistoryAdapter.java:80-83`（AGGREGATED/SAMPLED/RAW，`RAW` 走 `rawSeries` 近似路径，`:86`）、`GeHistoryAdapter.java:72-88`（RAW/AGGREGATED/SAMPLED/INTERPOLATED，其余抛）、`IotdbHistoryAdapter.java:103-104`。
- **IoTDB 的一处例外**：`queryService == null` 时，**任何 mode 都改走 REST 兜底**取数并组装（`IotdbHistoryAdapter.java:130-134`），不会在模式守卫处抛异常；但此时 `capabilities()` **不声明** `SAMPLED_HISTORY`/`INTERPOLATED_HISTORY`/`CUMULATIVE_DELTA`。所以「能力集说不支持」与「调用一定抛异常」在 IoTDB 上不是同一件事——按能力集判断，别靠试。`require-query-service: true`（默认）会让这种残缺装配在构造期就失败。
- **不支持时**：不在白名单内的 `mode` 一律抛 `TsdbUnsupportedException`，**不会退回 RAW 或返回空**。
- **示例**：

```java
List<TagSeries> raw = client.history().read(HistoryQuery.builder()
        .tags("AI-101")
        .range(start, end)
        .mode(QueryMode.RAW)
        .maxPoints(5000)
        .build());                                        // HLS 上走 1 秒 BOUND 桶近似（带 rawSource 标注），GE/IoTDB 为原生 raw
```

#### `HistoryQuery.Builder` 全字段

| 方法 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tags(String...)` / `tags(Collection<String>)` / `addTag(String)` | 位号集合 | ✅ | 空集合 → 返回空列表 |
| `range(Instant, Instant)` / `range(Date, Date)` | 区间 | ✅ | 左闭右开 |
| `start(Instant)` / `end(Instant)` / `end(Date)` | 区间分设 | — | 与 `range` 二选一 |
| `mode(QueryMode)` | 枚举 | ✅ | 5 值，见上表 |
| `aggregation(Aggregation)` | 枚举 | AGGREGATED/SAMPLED 必填 | 10 值，白名单见 §3.8 |
| `interval(Duration)` | 间隔 | 分桶模式必填 | IoTDB 任意 `Duration` 可下推；HLS 必须 ≥1 秒 |
| `count(Integer)` | 点数 | 可选 | 设置了 `interval` 时可省略：GE 用 `HistorianUnit.resolveCount` 精确推导服务端点数，显式填写且不一致会抛 `IllegalArgumentException`；HLS / IoTDB 按 `interval` 下发、不校验。`RAW` 的点数上限与无 `interval` 的纯 count 网格必须显式 |
| `maxPoints(int)` | 上限 | 可选 | 默认 `DEFAULT_MAX_POINTS = 10000` |
| `direction(Direction)` | 方向 | 可选 | HLS **仅 FORWARD**（`HlsHistoryAdapter.java:103`；`RAW` 在 `:155`） |
| `fill(FillPolicy)` | 填充 | 可选 | HLS **仅 NONE**（`HlsHistoryAdapter.java:107`；`RAW` 在 `:161`）；GE **仅 NONE**（`GeHistoryAdapter.java:59-61`）；IoTDB 只有「SAMPLED 且装配了 queryService」这一条路径能下推 FILL，其余路径非 NONE 一律抛（`IotdbHistoryAdapter.java:90-92`），且 `ZERO` 未实现 |
| `partialResultAllowed(boolean)` | 容错 | 可选 | `true` 才把 per-tag 失败落进 attributes |
| `includeQuality(boolean)` | 质量码 | 可选 | 是否要求携带质量码 |

### 3.5 `DeltaReader`

#### `read(...)`（8 参）

- **签名**：

```java
List<DeltaSeries> read(Collection<String> tags, Instant start, Instant end,
                       TimeGranularity granularity, Aggregation aggregation,
                       FillPolicy fill, Direction direction, int maxPoints)
```

- **语义**：累计量（如流量累计值）的**逐桶差分** = 本桶末值 − 上一桶末值。第一个桶没有基线时 `DeltaPoint.missing=true`；负差视为计数器清零，`reset=true` 并取本桶末值。月粒度自动归整到 1 号 00:00。
- **原生对照**：

| 驱动 | 端点 / SQL | 基线怎么取 |
|---|---|---|
| HLS | `POST /hdb/read_hdbtagvalue`，`NeedQueryBound=true` 分桶 | 主查询扩到 `[start - 桶长, end)`，第 0 个桶即基线，**1 次请求**；样本数对不上时才补 1 条批量 `BOUND` 兜底（`HlsDeltaAdapter`）。`BOUND` 是生产同款迂回方案（HLS 无 `LAST`/原生原始点），适用前提与升级复核见 docs/10 §8.1 |
| GE | `POST /v1/datapoints/calculated`（`calculationMode=19` 取每桶末值） | 主查询起点前移一个间隔、服务端 `count = 窗口桶数 + 1`，第 0 个样本即基线，**1 次请求**；首值为空才补 1 条批量 raw（Backward、`count=1`、回看 366 天）兜底（`GeDeltaAdapter`） |
| IoTDB | 每块 1 条多列 `GROUP BY`，范围 `[start - 间隔, end)` | 第 0 个桶就是基线，**每块 1 条 SQL**；首桶未命中才为缺桶位号补 1 条批量 `LAST_VALUE … WHERE time < start`（`IotdbDeltaQueries`）。月粒度扩展起点用业务时区 `minusMonths(1)`，与 `1mo` 对齐 |

- **逐驱动白名单**：

| 约束 | HLS | GE | IoTDB |
|---|---|---|---|
| 粒度 | 不支持 `MONTH`（`HlsDeltaAdapter.java:70`） | 不支持 `MONTH`（`GeDeltaAdapter.java:73`） | **支持 `MONTH`**（归整到 1 日 00:00，SQL 用 `1mo`） |
| 聚合 | 仅 `LAST`（`:75`） | 仅 `LAST`（`GeDeltaAdapter.java:77`） | `LAST` |
| 填充 | `NONE` / `PREVIOUS`（`:78`） | **仅 `NONE`**（`:81`） | `NONE` / `PREVIOUS` / `LINEAR` |

- **不支持时**：`TsdbClient.deltaHistory()` 的接口默认实现直接抛 `TsdbUnsupportedException`；上表白名单外的组合由各 DeltaAdapter 抛同异常。
- **示例**：

```java
List<DeltaSeries> daily = client.deltaHistory().read(
        Collections.singletonList("device01.flow"),
        start, end,
        TimeGranularity.DAY, Aggregation.LAST, FillPolicy.PREVIOUS,
        Direction.FORWARD, 1000);
```

> HLS 的差分包基于 `TagValue_Bound`（厂商桶边界列）：这是生产同款迂回方案，不是协议级桶末值，
> 适用前提与换版本复核见 docs/10 §8.1。
> 门控 IT 里对应用例默认跳过（历史查询在代理侧成本高），需显式 `-Dhls.it.delta=true` 才跑。
> `INTERVAL_SUM`（整窗末−首，单值）与 `CUMULATIVE_DELTA`（逐桶差分，序列）**语义不同，不可互相顶替**。

#### `readByInterval(...)`（8 参，任意毫秒间隔）

```java
List<DeltaSeries> readByInterval(Collection<String> tags, Instant start, Instant end,
                                 long intervalMillis, Aggregation aggregation, FillPolicy fill,
                                 Direction direction, int maxPoints)
```

- **语义**：与 `read(...)` 相同的逐桶末值相减、首桶基线、`reset`/`missing` 规则；区别只有两点：
  间隔是毫秒数，且分桶网格**锚定在 `start`**（不按自然边界归整）。
- **驱动支持**：HLS 仅接受 `intervalMillis >= 1000` 且能被 1000 整除；GE/IoTDB 接受任意正整数毫秒
  （IoTDB 走 `IotdbSql.intervalLiteral`，`5m`/`250ms` 已被生产只读验证）。
- **不支持月**：`TimeUnit` 没有月；月差分继续用 `read(..., TimeGranularity.MONTH, ...)`。
- **默认实现**：未覆写 `readByInterval` 的第三方驱动，运行期抛 `TsdbUnsupportedException`。
- **与参考 GE SDK 的差异**：参考实现 `getHistorianIntervalValueBySampled` 使用边界插值、实时值补齐、
  BAD 当 0、保留两位小数；本 SDK **一条都不做**。

#### `intervalDeltas(...)`（两个便利重载）

```java
List<DeltaSeries> intervalDeltas(Collection<String> tags, HistorianUnit unit,
                                 FillPolicy fill, Direction direction, int maxPoints);
List<DeltaSeries> intervalDeltas(Collection<String> tags, Instant metaTime,
                                 int total, TimeUnit totalUnit, int interval, TimeUnit intervalUnit,
                                 FillPolicy fill, Direction direction, int maxPoints);
```

底层调用 `readByInterval(tags, unit.getBegin(), unit.getCoveredEnd(), unit.getIntervalMs(),
Aggregation.LAST, ...)`：输出点数 = `unit.getCount()`，时间戳取驱动返回的桶样本时间（不做起终点偏移；
IoTDB `GROUP BY` 返回桶起点，HLS/GE 桶时间起终点未统一确认），末尾不足一个间隔的
`tailMs` 不产出点。

#### `HistorianUnit`（`common.model`）

| 成员 | 含义 |
|---|---|
| `begin` / `end` | 窗口起点（含）/ 终点（不含） |
| `intervalMs` | 单段间隔毫秒数 |
| `count` | `floor((end-begin)/intervalMs)` 个完整间隔 |
| `coveredEnd()` | `begin + count*intervalMs`，实际可出数的右边界 |
| `tailMs()` | 末尾不足一个间隔的毫秒数 |

`HistorianUnit.calculate(metaTime, total, totalUnit, interval, intervalUnit[, zone, round])` 计算窗口：
`metaTime == null` 取当前时刻；段数用毫秒整除（不用参考实现会截断子秒的 `toSeconds`）；
`round=true` 把结束时刻向下对齐到「业务时区当地 00:00 锚点 + 间隔步长」的网格，
5 分钟、8 小时、1 天都得到自然边界，`round=true` 必须传 `zone`。

#### 差分/整窗增量三库差异

| 需求 | HLS | GE | IoTDB | 本轮 |
|---|---|---|---|---|
| 任意毫秒间隔差分列表 | 🟡 仅整秒（≥1s） | ✅ | ✅ | **新增** `readByInterval` / `intervalDeltas` |
| `MONTH` 差分 | ❌ | ❌ | ✅ | 不变 |
| 差分填充 | `NONE`/`PREVIOUS` | 仅 `NONE` | `NONE`/`PREVIOUS`/`LINEAR` | 不变 |
| 差分基线 | 扩窗后第 0 个 `BOUND` 桶 | 扩窗后第 0 个 `calculated` 桶（`count+1`） | 扩窗后第 0 个 `GROUP BY` 桶 | **折叠进主查询**（缺桶批量兜底，最坏 2 次） |
| 首桶无基线 | `missing=true` | `missing=true` | `missing=true` | 一致 |
| `INTERVAL_SUM` | ✅ 两侧 1 分钟 `BOUND` 探测，缺边不出点 | ✅ `17`/`19` 双请求，BAD/空按 0（遗留） | ✅ `first_value`/`last_value` | **新增 HLS** |
| `INTERVAL_SUM` 多片（`count`/`interval`>1） | ❌ | ❌ | ❌ | **未实现**（三库都只做整窗单值） |
| 未闭合区间用实时值补齐 | ❌ | ❌ | ❌ | **不实现** |
| BAD 质量码当 0 | ❌ 跳过 BAD | `INTERVAL_SUM` 遗留按 0 | 无质量码 | **不统一**，显式标注 |
| 差值保留 N 位小数 | ❌ | ❌ | ❌ | **不实现**，调用方自行 `BigDecimal.setScale` |
| 差分结果截断标记 | ❌ | ❌ | ❌ | **未实现**（`DeltaSeries` 无 `truncated`，`maxPoints` 静默截断） |
| HLS `BOUND` 语义 | 已定案：生产同款迂回方案（定义与复核步骤见 docs/10 §8.1） | — | — | 2026-11-18 定案，不再是阻塞项 |

HLS `INTERVAL_SUM` 的实现：起点窗 `[start, start+min(60s, span))`、终点窗
`[end-min(60s, span), end)`，两次 `readHistory(..., 1L, BOUND, tags)`（每次 ≤60 个 1 秒桶），
取起点窗第一个、终点窗最后一个可解析且质量非 `BAD` 的值，`BigDecimal` 末−首；任一侧缺值返回空序列。
单点结果忽略 `direction`，填充仍只允许 `NONE`。

### 3.6 `WriteSession`

| 签名 | 语义 |
|---|---|
| `boolean isWritable()` | 当前实例是否允许写 |
| `WriteResult write(List<WriteRequest> requests)` | 批量写入 |

- **原生对照**：

| 驱动 | `isWritable()` | 端点 / SQL |
|---|---|---|
| HLS | **恒 `false`** | 无写接口；`write(...)` 抛 `TsdbUnsupportedException`（`HlsWriteAdapter`） |
| GE | **`false`**（公共层未声明 `WRITE`） | 原生层可写：`GeNativeApi.write(String tagName, List<GeModels.WritePoint> points)` → `POST /v1/datapoints/create`（`CREATE_TAG_DATA`，`:517`） |
| IoTDB | `allow-write: true` 才 `true` | `IotdbRestApi.write(String path, List<Sample> samples)` / `insertTablet(JSONObject)` → `POST /rest/v2/insertTablet`；native/JDBC 走同一份写入语义 |

- **不支持时**：HLS 与 GE 的公共 `write(...)` 抛 `TsdbUnsupportedException`；IoTDB 在 `allow-write: false`（默认）时同样抛。
- **模型字段**：

| 类型 | 字段 |
|---|---|
| `WriteRequest` | `WriteRequest(String tagName, List<Sample> samples)` |
| `WriteResult` | `int accepted`、`int rejected`、`List<String> errors`；`static success(int)`、`isFullyAccepted()` |

> 安全默认：`tsdb.security.allow-write: false`。开关只作用于公共层 `TsdbClient`，
> **不影响原生调用**——`unwrap` 出去的写方法不受这个开关约束，安全边界由调用方负责。

### 3.7 `HealthCheck` 与 `TsdbClient.health()`

- **签名**：`HealthStatus health()`（`TsdbClient` 上）；`HealthStatus check()`（`HealthCheck` 接口上）
- **语义**：轻量探活，返回 `HealthStatus`，**不抛异常**——失败也返回 `DOWN` 并带 message 与耗时。
- **原生对照**：

| 驱动 | 探活动作 | 出处 |
|---|---|---|
| HLS | 配了静态 token → `listTags()`（`GET /ddb/read_alltagname`）；否则 `tokens.refresh()`（`POST /user/get_token`，失败回退 GET） | `HlsNativeApi.java:305-311` |
| GE | `listTags(1, null)` → `GET /v1/tags?maxNumber=1` | `GeNativeApi.java:538` |
| IoTDB | REST 通道或未装配 queryService → `GET {base}/ping`（**服务根，不是 `/rest/v2/ping`，后者 404**）；native/JDBC → `SHOW VERSION` | `IotdbRestApi.java:189-199`、`IotdbClient.java:287-299`、`IotdbQueryService.java:375` |

- **`HealthStatus` 字段**：`State`（UP/DOWN）、`getMessage()`、`getLatencyMillis()`、`getCheckedAt()`（`Instant`）、`getCheckedAtDate()`、`getCheckedAtText()`、`withTimeFormat(TimeConfig)`、`isUp()`；工厂 `up(long)` / `down(String, long)`。
- **如实说明**：`common/api/HealthCheck.java` 声明了 `check()` 契约，但**三个驱动客户端都没有 `implements HealthCheck`**，实际探活入口是 `TsdbClient.health()`。把 `HealthCheck` 当作可注入的独立 bean 去用会拿不到实例。
- **未实现**：Spring Boot Actuator 的 `/actuator/health/tsdb` 端点**没有实现**，starter 只注册 `TsdbManager` bean。要接 Actuator 需自己写 `HealthIndicator` 包一层 `client.health()`。

### 3.8 能力集与枚举白名单（矩阵的源码依据）

`Capability` 共 **15** 项。三库声明值：

| Capability | HLS | GE | IoTDB |
|---|---|---|---|
| `TAG_BROWSE` | ✅ | ✅ | ✅ |
| `TAG_SEARCH` | ❌ | ❌ | ❌ |
| `TAG_CRUD` | ❌ | ❌ | ❌ |
| `CURRENT_VALUE` | ✅ | ✅ | ✅ |
| `RAW_HISTORY` | ✅ 1 秒 `BOUND` 桶近似 | ✅ | ✅ |
| `AGGREGATED_HISTORY` | ✅ | ✅ | ✅ |
| `INTERPOLATED_HISTORY` | ❌ | ✅ | 🟡 条件 |
| `SAMPLED_HISTORY` | ✅ | ✅ | 🟡 条件 |
| `TREND` | ❌ | ❌ | ❌ |
| `CUMULATIVE_DELTA` | ✅ | ✅ | 🟡 条件 |
| `WRITE` | ❌ | ❌（原生可写） | 🟡 条件 |
| `DELETE` | ❌ | ❌ | ❌ |
| `ALARM` | ❌ | ❌（原生有 5 个告警端点） | ❌ |
| `SUBSCRIPTION` | ❌ | ❌ | ❌ |
| `SYSTEM_ADMIN` | ❌ | ❌（原生有 25 系统 + 27 采集器端点） | ❌ |

出处：`HlsClient.java:61-67`（6 项）、`GeClient.java:61-67`（7 项）、`IotdbClient.java:165-177`。

IoTDB 的**条件声明**（`IotdbClient.java:165-177`）：

```java
EnumSet<Capability> caps = EnumSet.of(Capability.TAG_BROWSE, Capability.CURRENT_VALUE,
        Capability.RAW_HISTORY, Capability.AGGREGATED_HISTORY);
if (queryService != null) { caps.add(SAMPLED_HISTORY); caps.add(INTERPOLATED_HISTORY); caps.add(CUMULATIVE_DELTA); }
if (config.isWriteAllowed()) { caps.add(Capability.WRITE); }
```

即：基线 4 项；装配了 `IotdbQueryService` 才加 3 项；`allow-write: true` 才加 `WRITE`。
`require-query-service: true`（默认）时装配失败会在**构造期**抛 `TsdbConfigurationException`，不会留下一个能力残缺的客户端。

> `HlsDriver.java:47-49` 只声明 3 项（TAG_BROWSE/CURRENT_VALUE/AGGREGATED_HISTORY），
> 与 `HlsClient` 的 6 项不一致。**以 `HlsClient.capabilities()` 为准**（那是运行期实际返回值）；
> 驱动级声明是发现阶段的粗粒度提示，本轮不改代码，只如实记录这处差异。

**`QueryMode`（5 值）× 三库**：见 §3.4 的 mode 表。

**`Aggregation`（10 值）× 三库**：

| Aggregation | HLS | GE（`calculationMode`） | IoTDB（SQL 函数） |
|---|---|---|---|
| `AVG` | ✅ `TagValue_AVG` | ✅ `1` | ✅ `AVG` |
| `MIN` | ✅ `TagValue_MIN` | ✅ `4` | ✅ `MIN_VALUE` |
| `MAX` | ✅ `TagValue_MAX` | ✅ `5` | ✅ `MAX_VALUE` |
| `SUM` | ❌ 抛 | ✅ `9`（RawTotal，**算术和**） | ✅ `SUM` |
| `COUNT` | ❌ 抛 | ✅ `6` | ✅ `COUNT` |
| `FIRST` | ❌ 抛 | ✅ `17`（FirstRawValue） | ✅ `FIRST_VALUE` |
| `LAST` | ❌ 抛 | ✅ `19`（LastRawValue） | ✅ `LAST_VALUE` |
| `RANGE` | ❌ 抛 | ❌ 抛 | ❌ 抛 |
| `BOUND` | ✅ `TagValue_Bound` | ❌ 抛 | ❌ 抛 |
| `INTERVAL_SUM` | ✅ 两侧 1 分钟 `BOUND` 探测，缺边不出点 | ✅ 走 `17`/`19` **双请求** | ✅ 走 `first_value`+`last_value` **单窗单 SQL** |

出处：`HlsHistoryAdapter.java:110-113`、`GeHistoryAdapter.java:175-200`、`IotdbSeriesAssembler.supported():260-274`、`IotdbSql.java:432-452`。

> GE 厂商的 `SumValue=24` 是**末−首区间累计**而不是算术和，所以**不映射到 `SUM`**；
> `SUM` 映射到 `9`（RawTotal）。这条映射错一次，报表就会把「区间增量」当「总量」用。

**`FillPolicy`（4 值）**：

| FillPolicy | HLS history | HLS delta | GE history | GE delta | IoTDB |
|---|---|---|---|---|---|
| `NONE` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `PREVIOUS` | ❌ 抛 | ✅ | ❌ 抛 | ❌ 抛 | 🟡 仅 SAMPLED 路径 |
| `LINEAR` | ❌ 抛 | ❌ 抛 | ❌ 抛 | ❌ 抛 | 🟡 仅 SAMPLED 路径 |
| `ZERO` | ❌ 抛 | ❌ 抛 | ❌ 抛 | ❌ 抛 | ❌ **未实现** |

出处：HLS history `HlsHistoryAdapter.java:91-92,107`（`RAW` 另在 `:161` 单独校验）、HLS delta `HlsDeltaAdapter.java:78`、GE history `GeHistoryAdapter.java:59-61`、GE delta `GeDeltaAdapter.java:81`、IoTDB `IotdbHistoryAdapter.java:90-92`。

> IoTDB 的 🟡 含义：`FILL(...)` 只在 `mode == SAMPLED && queryService != null` 时才拼进 SQL；
> RAW / AGGREGATED / INTERPOLATED 带非 NONE 填充**直接抛 `TsdbUnsupportedException`**，不是忽略。
> INTERPOLATED 的「线性」是 SDK 内存网格自己算的，与服务端 `FILL(LINEAR)` 是两件事。
> HLS 的 `RAW`（1 秒 `BOUND` 近似）同样只接受 `NONE`，传其它填充直接抛。

**`TimeGranularity`（5 值）**：`SECOND`→`1s`、`MINUTE`→`1m`、`HOUR`→`1h`、`DAY`→`1d`、`MONTH`→`1mo`。
`MONTH` 只有 IoTDB 支持；HLS/GE 的 delta 抛 `TsdbUnsupportedException`。

**`Quality`（5 档）映射**：

| 统一值 | HLS 原始码 | GE 原始码 | IoTDB |
|---|---|---|---|
| `GOOD` | `1` | `3` | 值非空 |
| `UNCERTAIN` | — | `1` | — |
| `BAD` | `0` | `0` | — |
| `STALE` | — | — | — |
| `UNKNOWN` | 其它/缺失 | `2`（NA） | 值为空 |

HLS **没有 `Quality_AVG` 列**，所以 AVG 的质量固定 `UNKNOWN`，并在 `TagSeries.attributes["qualitySource"]="hls_not_provided"` 标注；MIN/MAX/BOUND 用各自质量列。

**`ValueType`（6 档）**：`DOUBLE`/`LONG`/`BOOLEAN`/`STRING`/`BYTES`/`UNKNOWN`，同时保留 `TagInfo.vendorType` 原始类型码。

---

## 4. 驱动客户端类的额外公共方法

这些方法**不在 `TsdbClient` 接口上**，用接口类型拿不到，必须持有具体类型（`unwrap` 或直接构造）。

| 方法 | HLS | GE | IoTDB | 语义 |
|---|---|---|---|---|
| `TimeConfig timeConfig()` | ✅ | ✅ | ✅ | 返回生效的时间解析/展示配置 |
| `Instant parseTimestamp(String text)` | ✅ | ✅ | ✅ | 按 `time.response.*` 把厂商文本时间解析成绝对时刻 |
| `String formatTimestamp(Instant t)` | ✅ | ✅ | ✅ | 按 `time.response.target-zone/pattern` 生成展示文本 |
| `IotdbTagResolver.TagMappingStats tagMappingStats()` | — | — | ✅ | 映射表命中/未命中统计，用于评估白名单兜底比例 |
| `IotdbConfig.ShiftWindow shiftWindow(Instant t)` | — | — | ✅ | 给定时刻所属班次窗口（按 `zone` + `shift-hour`） |

`IotdbClient` 有 3 个构造器；当 `queryService == null && config.isRequireQueryService()` 时抛 `TsdbConfigurationException`——**构造期失败，不留残缺客户端**。

---

## 5. HLS 原生门面 `HlsNativeApi`

位置：`tsdb-sdk-hls/src/main/java/…/hls/internal/HlsNativeApi.java`，**23 个 public 成员**（含构造器与 `close()`）。
取出方式：`client.unwrap(HlsNativeApi.class)`。

### 5.1 厂商形态方法（15 个，对应原 SDK 14 个 + 1 个新增便捷重载）

| # | 签名 | HTTP + 路径 | body 关键字段 |
|---|---|---|---|
| 1 | `JSONObject queryAllTags()` | `GET /ddb/read_alltagname` | 无；响应 `Data.TagNameList[]` |
| 2 | `JSONObject getDDBTagValue(TagNameListRequest request)` | `POST /ddb/read_ddbtagvalue` | `TagNameList:[{TagName:…}]` |
| 3 | `JSONObject getDDBTagValue(Collection<String> tagNames)` | 同上 | 同上（**新增便捷重载**） |
| 4 | `JSONObject getHDBTagValue(HistorianRequest request)` | `POST /hdb/read_hdbtagvalue` | `startTime`/`endTime`/`interval`/`NeedQuery*`/`TagNameList` |
| 5 | `JSONObject getHDBTagValue(Instant start, Instant end, Long intervalSeconds, Boolean needQueryBound, Boolean needQueryAVG, Boolean needQueryMIN, Boolean needQueryMAX, String... tagNames)` | 同上 | 同上 |
| 6–7 | `JSONObject getHDBTagValueBound(Instant, Instant, Long, String...)` / `(Instant, Instant, Long, Collection<String>)` | 同上 | `NeedQueryBound=true`，其余 false |
| 8–9 | `getHDBTagValueMax(...)` ×2 重载 | 同上 | `NeedQueryMAX=true` |
| 10–11 | `getHDBTagValueMin(...)` ×2 重载 | 同上 | `NeedQueryMIN=true` |
| 12–13 | `getHDBTagValueAvg(...)` ×2 重载 | 同上 | `NeedQueryAVG=true` |
| 14–15 | `getHDBTagValueAll(...)` ×2 重载 | 同上 | 四个 `NeedQuery*` 全 true |

签名出处：`HlsNativeApi.java:73,88,98,114,131,148,161,175,188,202,215,229,242,256,269`。

**时间单位是 epoch 秒，不是毫秒**：`readHistory` 用 `getEpochSecond()` 填 `startTime`/`endTime`（`HlsNativeApi.java:420-438`）；解析响应时按数量级兼容——`abs < 100000000000L → ofEpochSecond`，否则 `ofEpochMilli`（`:606-609`）。

**已修缺陷**：原 SDK `HistorianRequest.buildRequest` 把 MAX/MIN 参数写反（原 `HistorianRequest.java:147-148`）。新 DTO 在 `hls/nativeapi/dto` 下重建，字段名与 JSON 结构与原 SDK 一致，但参数顺序修正，并有单测覆盖。

### 5.2 SDK 侧方法（8 个）

| 签名 | 语义 | 端点 |
|---|---|---|
| `HlsNativeApi(HlsConfig config)` | 构造器 | 无调用 |
| `Object rawClient()` | 暴露底层 HTTP 客户端 | 无调用 |
| `Object rawRequest(String method, String path, Object body)` | 任意原生请求，覆盖未来新增端点 | 调用方指定 |
| `void checkHealth()` | 静态 token → `listTags()`；否则 `tokens.refresh()` | `GET /ddb/read_alltagname` 或 `POST /user/get_token` |
| `List<HlsModels.Tag> listTags()` | 全量目录 → 模型 | `GET /ddb/read_alltagname` |
| `List<HlsModels.CurrentValue> readCurrent(Collection<String> tagNames)` | 当前值 → 模型 | `POST /ddb/read_ddbtagvalue` |
| `List<HlsModels.Series> readHistory(Instant start, Instant end, long intervalSeconds, Aggregation aggregation, Collection<String> tagNames)` | 按聚合选列 → 模型 | `POST /hdb/read_hdbtagvalue` |
| `void close()` | 释放资源 | 无调用 |

### 5.3 认证与线缆细节

- token：`POST /user/get_token`，请求头带 `userid`/`secretkey`，JSON body 也带同样字段，`Content-Type` 必须是**精确的** `application/json`（带 charset 会被服务端拒，返回 4002）；失败后回退原 SDK 的 `GET /user/get_token` + 请求头方式。
- token 默认有效期：`token-expire-seconds` 缺省 **1800**（`HlsConfig.java:200`）。
- `endTime` 不得晚于当前时刻，否则服务端返回 `code=3025`。
- 全量目录现场约 **4.3 MB / 199 s**，`getTag` 与 `listTags` 都会触发它，务必缓存。

---

## 6. GE 原生门面 `GeNativeApi` + `GeEndpoint`

位置：`ge/internal/GeNativeApi.java`，**18 个 public 成员**（通用执行器 5 + 类型化方法 13）。端点常量表：`ge/nativeapi/GeEndpoint.java`，**92 个常量**。

### 6.1 通用执行器与参数位置规则

| 签名 | 语义 |
|---|---|
| `GeNativeApi(GeConfig config, GeHttpClient http, GeTokenManager tokens)` | 构造器（`:66`） |
| `Object invoke(GeEndpoint endpoint)` | 无参端点（`:79`） |
| `Object invoke(GeEndpoint endpoint, Map<String,Object> arguments)` | 单 map，按枚举声明的 `Position` 自动分流到 query/path/body（`:91`） |
| `Object invoke(GeEndpoint endpoint, Map<String,Object> pathVariables, Map<String,Object> queryParams, Object body)` | 三处显式分设（`:131`） |
| `Object rawRequest(String method, String relativePath, Object body)` | 绕过枚举，任意端点（`:168`） |

每个 `GeEndpoint` 常量携带：中文说明、path、HTTP method、参数位置（`Position.QUERY` / `PATH` / `BODY` / `NONE`）、返回类型。新增端点只需加枚举常量，不改客户端。

返回值是 fastjson 树（`JSONObject` / `JSONArray` / 标量），按字段读取时用 `JsonText.object(...)` / `JsonText.array(...)` 收敛。

```java
GeNativeApi ge = client.unwrap(GeNativeApi.class);
JSONObject tags = JsonText.object(ge.invoke(GeEndpoint.QUERY_TAGS_BY_PARAMS,
        mapOf("maxNumber", 100, "nameMask", "AI*")));
JSONObject created = JsonText.object(ge.invoke(GeEndpoint.CREATE_TAG_DATA, body));
Object any = ge.rawRequest("GET", "/v1/tags?maxNumber=10", null);
```

### 6.2 类型化方法（13 个）

| 签名 | HTTP + 路径 | 关键参数 |
|---|---|---|
| `List<String> listTags(int maxNumber, String nameMask)`（`:172`） | `GET /v1/tags` | query `maxNumber`/`nameMask` |
| `List<GeModels.Series> readCurrentRawValue(Collection<String> tagNames)`（`:203`） | `POST /v1/datapoints/sampled` | `samplingMode="1"`、`queryModifier=1L`，body 明文 `tagNames` |
| `List<GeModels.Series> readCurrent(Collection<String> tagNames)`（`:222`） | `GET /v1/datapoints/currentvalue` | 遗留 GET 形态，query `tagNames` |
| `List<GeModels.Series> readRaw(Collection<String>, Instant, Instant, Direction, int count)`（`:243`） | `POST /v1/datapoints/raw` | query 标量 + body `tagNames` |
| `readRawGet(...)` 同参（`:268`） | `GET /v1/datapoints/raw` | 全部进 query，URL 编码 |
| `readAggregated(Collection<String>, Instant, Instant, int calculationMode, int count, long intervalMillis)`（`:295`） | `POST /v1/datapoints/calculated` | query `calculationMode`/`count`/`intervalMs` + body |
| `readAggregatedGet(...)` 同参（`:322`） | `GET /v1/datapoints/calculated` | 全部进 query |
| `readInterpolated(Collection<String>, Instant, Instant, int count, long intervalMillis)`（`:348`） | `POST /v1/datapoints/interpolated` | query 标量 + body |
| `readInterpolatedGet(...)` 同参（`:373`） | `GET /v1/datapoints/interpolated` | 全部进 query |
| `readSampled(Collection<String>, Instant, Instant, int samplingMode, long queryModifier, Long intervalMs, Integer count, Integer calculationMode, Direction)`（`:409`） | `POST /v1/datapoints/sampled` | body 明文 `tagNames` + 标量 |
| `Map<String,String> readIntervalSum(Collection<String>, Instant, Instant)`（`:460`） | `POST /v1/datapoints/calculated` **×2** | `calculationMode=17` 然后 `19`，`count=1`、`intervalMs=1` |
| `void write(String tagName, List<GeModels.WritePoint> points)`（`:517`） | `POST /v1/datapoints/create` | `CREATE_TAG_DATA`，body |
| `HealthStatus checkHealth()`（`:538`） | `GET /v1/tags?maxNumber=1` | 内部调 `listTags(1, null)` |

### 6.3 GET → POST 对齐说明

高频批量读全部改 POST，原因是 URL 长度：位号多时 GET 的 query 串会超限。

| 形态 | `tagNames` 位置 | 编码 |
|---|---|---|
| 新 POST（`readRaw`/`readAggregated`/`readInterpolated`/`readSampled`/`readCurrentRawValue`） | **body 明文，分号 `;` 拼接** | 不做 URL 编码 |
| 遗留 GET（`*Get` 后缀） | query 串 | URL 编码 |

两者返回结构一致，`*Get` 方法保留是为了与原 SDK 线缆逐字节对照，不是推荐路径。

### 6.4 认证与错误分级

- OAuth2 password grant：`POST /uaa/oauth/token?grant_type=password&username=..&password=..`，用户名/密码在**查询串**（UTF-8 URL 编码），`Authorization` 头是 `clientId:clientSecret` 的 Basic 编码，请求体为空、`Content-Type: application/x-www-form-urlencoded`。由 `GeTokenManager` 封装，不占 92 个端点之一。
- token 默认有效期：`token-expire-seconds` 缺省 **1800**（`GeConfig.java:153`）。
- `path-prefix` 缺省 `/historian-rest-api`（`GeConfig.java:56,126-131`）。
- **`ErrorCode=11` 且带 payload 时视为成功**（部分位号无数据），不是失败；per-tag 错误落 `TagSample.attributes.errorCode/errorMessage`。

### 6.5 92 个端点按模块分组

计数实测：`GeEndpoint` 常量 **92** 个 = Data 22 + Tags 13 + Systems 25 + Collectors 27 + Alarms 5。OAuth 1 个由 `GeTokenManager` 覆盖，不在枚举内。

#### Alarms（5）

| 常量 | 说明 | 路径 | HTTP | 参数位置 |
|---|---|---|---|---|
| `CREATE_BACKUP` | 创建告警备份 | `/v1/alarms/backup` | POST | BODY |
| `CREATE_ALARM` | 创建告警 | `/v1/alarms/create` | POST | BODY |
| `DELETE_ALARMS` | 清除时间段内的告警 | `/v1/alarms/delete` | POST | BODY |
| `QUERY_ALARMS` | 查询告警和事件数据 | `/v1/alarms/query` | POST | BODY |
| `RESTORE_BACKUP` | 恢复告警备份 | `/v1/alarms/restore` | POST | BODY |

#### Collectors（27）

| 常量 | 说明 | 路径 | HTTP | 参数位置 |
|---|---|---|---|---|
| `SET_AZURE_LOG_LEVEL` | 设置 Azure 调试信息日志级别 | `/v1/collector/azureloglevel` | PUT | BODY |
| `BUFFER_CONTROL` | 删除或移动缓冲区文件 | `/v1/collector/buffercontrol` | PUT | BODY |
| `CREATE_NEW_INSTANCE` | 创建收集器实例 | `/v1/collector/createnewinstance` | POST | BODY |
| `DELETE_COLLECTOR_INSTANCE` | 删除收集器实例 | `/v1/collector/deleteinstance` | PUT | BODY |
| `DELETE_OFFLINE_INSTANCE` | 删除离线收集器实例 | `/v1/collector/deleteofflineinstance` | DELETE | BODY |
| `COLLECTOR_DETAILS` | 查看收集器详细信息 | `/v1/collector/details` | GET | NONE |
| `EDIT_INSTANCE` | 更新收集器实例 | `/v1/collector/editinstance` | PUT | BODY |
| `HISTORIAN_NODE_CHANGE` | 更新收集器的服务器节点 | `/v1/collector/historiannodechange` | PUT | BODY |
| `INSTANCE_DETAILS` | 获取收集器实例详细信息 | `/v1/collector/instancedetails/{interfaceName}` | GET | PATH |
| `MESSAGE_COMPRESSION` | 启用或禁用消息压缩 | `/v1/collector/messagecompression` | PUT | BODY |
| `COLLECTOR_RUNNING_MODE` | 查看收集器运行模式 | `/v1/collector/mode/{interfaceName}` | GET | PATH |
| `PAUSE_COLLECTION` | 暂停收集器数据收集 | `/v1/collector/pausecollection/{interfaceName}` | PUT | PATH |
| `REBUILD_COLLECTORS_LIST` | 重建收集器列表 | `/v1/collector/rebuild/{interfaceName}` | PUT | PATH |
| `RESTART_COLLECTOR` | 重新启动收集器 | `/v1/collector/restart` | PUT | BODY |
| `RESUME_COLLECTION` | 恢复收集器数据收集 | `/v1/collector/resumecollection/{interfaceName}` | PUT | PATH |
| `SET_DEBUG_MODE` | 设置收集器调试模式 | `/v1/collector/setdebugmode` | PUT | BODY |
| `START_COLLECTOR` | 启动收集器 | `/v1/collector/start` | PUT | BODY |
| `COLLECTOR_STATUS` | 获取收集器状态 | `/v1/collector/status/{interfaceName}` | GET | PATH |
| `STOP_COLLECTOR` | 停止收集器 | `/v1/collector/stop` | PUT | BODY |
| `COLLECTOR_VERSION` | 获取收集器的版本号 | `/v1/collector/version` | GET | QUERY |
| `COLLECTOR_VERSION_BY_PATH` | 获取收集器的版本号（路径参数） | `/v1/collector/version/{interfaceName}` | GET | PATH |
| `COLLECTOR_MANAGER_LIST` | 获取收集器关联的代理机器列表 | `/v1/collectormanagerlist` | GET | NONE |
| `INSTALL_COMPONENT_DETAILS` | 获取收集器所在计算机安装组件的详细信息 | `/v1/installcomponentdetails/{collectorType}/{collectorSubType}/{machine}` | GET | PATH |
| `LOCAL_OPC_AE_SERVERS` | 获取指定机器上安装的 OPC 警报和事件服务器列表 | `/v1/localopcaeservers/{machine}` | GET | PATH |
| `LOCAL_OPC_HDA_SERVERS` | 查看指定机器上安装的 OPC HDA 服务器列表 | `/v1/localopchdaservers/{machine}` | GET | PATH |
| `LOCAL_OPC_SERVERS` | 查看指定机器上安装的 OPC 服务器列表 | `/v1/localopcservers/{machine}` | GET | PATH |
| `OFFLINE_COLLECTORS` | 获取离线收集器列表 | `/v1/offlinecollectors` | GET | NONE |

> ⚠️ 这 27 个端点里有**启停采集器、删除实例、改运行模式**等写操作。原生层不阉割意味着它们可达，
> 但生产环境应只读使用；调用前请确认授权与影响面。

#### Data（22）

| 常量 | 说明 | 路径 | HTTP | 参数位置 |
|---|---|---|---|---|
| `GET_CALCULATED_BY_REQUEST_PARAM` | 查询标签列表的计算数据 | `/v1/datapoints/calculated` | GET | QUERY |
| `GET_CALCULATED_BY_REQUEST_PARAM_POST` | 查询标签列表的计算数据 | `/v1/datapoints/calculated` | POST | QUERY |
| `GET_CALCULATED_BY_PATH_VARIABLE_POST` | 查询标签列表的计算数据 | `/v1/datapoints/calculated/{start}/{end}/{calculationMode}/{count}/{intervalMs}` | POST | PATH |
| `GET_CALCULATED_BY_PATH_VARIABLE` | 查询标签列表的计算数据 | `/v1/datapoints/calculated/{tagNames}/{start}/{end}/{calculationMode}/{count}/{intervalMs}` | GET | PATH |
| `CONFIGURE_QUERY_RESULT_BY_REQUEST_PARAM` | 配置查询结果 | `/v1/datapoints/configuration` | PUT | QUERY |
| `CONFIGURE_QUERY_RESULT_BY_PATH_VARIABLE` | 配置查询结果 | `/v1/datapoints/configuration/{maxDataQueryResultSize}` | PUT | PATH |
| `CREATE_TAG_DATA` | 创建数据点位标签数据 | `/v1/datapoints/create` | POST | BODY |
| `GET_CURRENT_VALUE_BY_REQUEST_PARAM` | 查询当前值数据 | `/v1/datapoints/currentvalue` | GET | QUERY |
| `GET_CURRENT_VALUE_POST` | 查询当前值数据 | `/v1/datapoints/currentvalue` | POST | BODY |
| `GET_CURRENT_VALUE_BY_PATH_VARIABLE` | 查询当前值数据 | `/v1/datapoints/currentvalue/{tagNames}` | GET | PATH |
| `GET_INTERPOLATED_BY_REQUEST_PARAM` | 查询标签列表的插值 | `/v1/datapoints/interpolated` | GET | QUERY |
| `GET_INTERPOLATED_BY_REQUEST_PARAM_POST` | 查询标签列表的插值 | `/v1/datapoints/interpolated` | POST | QUERY |
| `GET_INTERPOLATED_BY_PATH_VARIABLE_POST` | 查询标签列表的插值 | `/v1/datapoints/interpolated/{start}/{end}/{count}/{intervalMs}` | POST | PATH |
| `GET_INTERPOLATED_BY_PATH_VARIABLE` | 查询标签列表的插值 | `/v1/datapoints/interpolated/{tagNames}/{start}/{end}/{count}/{intervalMs}` | GET | PATH |
| `GET_RAW_DATA_BY_REQUEST_PARAM` | 查询原始数据 | `/v1/datapoints/raw` | GET | QUERY |
| `GET_RAW_DATA_BY_REQUEST_PARAM_POST` | 查询原始数据 | `/v1/datapoints/raw` | POST | QUERY |
| `GET_RAW_DATA_BY_PATH_VARIABLE_POST` | 查询原始数据 | `/v1/datapoints/raw/{start}/{end}/{direction}/{count}` | POST | PATH |
| `GET_RAW_DATA_BY_PATH_VARIABLE` | 查询原始数据 | `/v1/datapoints/raw/{tagNames}/{start}/{end}/{direction}/{count}` | GET | PATH |
| `GET_SAMPLED_BY_REQUEST_PARAM` | 查询采样数据 | `/v1/datapoints/sampled` | GET | QUERY |
| `GET_SAMPLED_BY_REQUEST_PARAM_POST` | 查询采样数据 | `/v1/datapoints/sampled` | POST | BODY |
| `GET_TREND_DATA_BY_REQUEST_PARAM` | 查询趋势数据 | `/v1/datapoints/trend` | GET | QUERY |
| `GET_TREND_DATA_BY_REQUEST_PARAM_POST` | 查询趋势数据 | `/v1/datapoints/trend` | POST | BODY |

#### Systems（25）

| 常量 | 说明 | 路径 | HTTP | 参数位置 |
|---|---|---|---|---|
| `UPDATE_DATA_STORE` | 更新数据存储 | `/v1/dataStore/{dataStoreName}` | PUT | PATH |
| `DELETE_DATA_STORE` | 删除数据存储 | `/v1/datastore` | DELETE | BODY |
| `GET_DATA_STORES` | 获取数据存储列表 | `/v1/datastores` | GET | QUERY |
| `ADD_DATA_STORE` | 添加数据存储 | `/v1/datastoretostorage` | POST | BODY |
| `GET_DHS_MACHINES` | 获取 DHS 机器列表 | `/v1/dhsmachines` | GET | QUERY |
| `UPDATE_DHS_SERVICE` | 更新 DHS 服务信息 | `/v1/dhsservice/{dHSServiceName}` | PUT | PATH |
| `GET_DHS_SERVICES` | 获取 DHS 服务列表 | `/v1/dhsservices` | GET | QUERY |
| `GET_SERVERS` | 获取指定用户下的服务器信息 | `/v1/getservers/{loginuser}` | GET | PATH |
| `ADD_HISTORIAN_SERVER` | 添加服务器并归当前用户管理 | `/v1/historianserver/addServer` | POST | BODY |
| `DELETE_HISTORIAN_SERVER` | 删除服务器 | `/v1/historianserver/deleteServer` | DELETE | BODY |
| `MODIFY_HISTORIAN_SERVER` | 修复服务器 | `/v1/historianserver/modifyServer` | PUT | BODY |
| `GET_SERVER_TYPE` | 获取服务器类型信息 | `/v1/historianserver/type` | GET | NONE |
| `ADD_MACHINE` | 添加机器 | `/v1/machine` | POST | BODY |
| `DELETE_MACHINE` | 删除机器 | `/v1/machine` | DELETE | BODY |
| `CREATE_MIRROR_GROUP` | 创建镜像组 | `/v1/mirrorgroup` | POST | BODY |
| `RENAME_MIRROR_GROUP` | 更新镜像组 | `/v1/mirrorgroup` | PUT | BODY |
| `DELETE_MIRROR_GROUP` | 删除镜像组 | `/v1/mirrorgroup` | DELETE | BODY |
| `ADD_MIRROR_MACHINE` | 添加镜像机器 | `/v1/mirrormachine` | POST | BODY |
| `REMOVE_MIRROR_MACHINE` | 删除镜像机器 | `/v1/mirrormachine` | DELETE | BODY |
| `GET_PERF_TAG_DATA` | 获取系统的读取速率和接收速率 | `/v1/performancecounter/perftagdata/{perfTagName}/{perfTagType}/{name}/{start}/{end}/{intervalMs}` | GET | PATH |
| `GET_SERVER_PROPERTIES` | 获取服务器属性列表 | `/v1/serverproperties` | GET | NONE |
| `GET_DATA_STORES_BY_STORAGE` | 获取数据存储列表 | `/v1/storage/datastores` | GET | QUERY |
| `UPDATE_DEFAULT_DATA_STORE` | 更新默认数据存储 | `/v1/storage/{storageName}` | PUT | PATH |
| `GET_STORAGES` | 获取存储位置列表 | `/v1/storages` | GET | QUERY |
| `GET_SYSTEM_STATS` | 获取系统统计信息 | `/v1/systemstats` | GET | NONE |

#### Tags（13）

| 常量 | 说明 | 路径 | HTTP | 参数位置 |
|---|---|---|---|---|
| `QUERY_TAGS_BY_PARAMS` | 查询标签列表 | `/v1/tags` | GET | QUERY |
| `ADD_TAG_COMMENT` | 添加标签评论 | `/v1/tags/addcomment` | POST | BODY |
| `ADD_SINGLE_TAG` | 添加单个标签 | `/v1/tags/addtag` | POST | BODY |
| `ADD_BATCH_TAGS` | 批量添加标签 | `/v1/tags/addtags` | POST | BODY |
| `GET_COMMENTS_BY_QUERY` | 查询标签评论 | `/v1/tags/comments` | GET | QUERY |
| `GET_COMMENTS_BY_PATH` | 路径参数查询评论 | `/v1/tags/comments/{tagNames}/{start}/{end}` | GET | PATH |
| `GET_TAG_PROPERTIES_PATH` | GET 方式检索属性 | `/v1/tags/properties/{tagName}` | GET | PATH |
| `GET_TAG_PROPERTIES_PATH_BODY` | POST 方式检索属性 | `/v1/tags/properties/{tagName}` | POST | PATH |
| `UPDATE_TAG_PROPERTIES` | 更新标签属性 | `/v1/tags/properties/{tagName}` | PUT | PATH |
| `RENAME_TAG` | 标签重命名 | `/v1/tags/tagrename/{oldTagName}/{newTagName}` | PUT | PATH |
| `QUERY_TAGS_BY_PATH` | 路径参数查询标签 | `/v1/tags/{nameMask}/{maxNumber}` | GET | PATH |
| `DELETE_TAG` | 删除标签 | `/v1/tags/{tagName}` | DELETE | PATH |
| `ADVANCED_TAG_QUERY` | 高级标签查询 | `/v1/tagslist` | GET | QUERY |

> 公共层只声明 `TAG_BROWSE`，**不声明 `TAG_CRUD` / `ALARM` / `SYSTEM_ADMIN`**。
> 上表中的增删改、告警、系统运维端点全部只在原生层可达——这是「公共层只抽通用能力、原生层不阉割」的分工，不是能力缺失。

---

## 7. IoTDB 原生面：`IotdbRestApi` + `IotdbQueryService`

### 7.1 `IotdbRestApi`（11 个 public 成员）

位置：`iotdb/internal/transport/IotdbRestApi.java`。取出：`client.unwrap(IotdbRestApi.class)`。

| 签名 | REST 路径 / SQL | 说明 |
|---|---|---|
| `IotdbRestApi(IotdbConfig config, IotdbHttpClient http)` | — | 构造器 |
| `JSONObject query(String sql)` | `POST {base}/rest/v2/query`，body `{sql:…}` | **任意查询 SQL**，返回原始列式 JSON，不限制 SQL 类型 |
| `JSONObject nonQuery(String sql)` | `POST {base}/rest/v2/nonQuery` | DDL/DML：`SET`、`CREATE`、`DELETE` 等 |
| `JSONObject insertTablet(JSONObject request)` | `POST {base}/rest/v2/insertTablet` | 直接提交请求体，批量写入 |
| `String ping()` | `GET {base}/ping` | **服务根**；`/rest/v2/ping` 是 404，`path-prefix` 不作用于探活 |
| `List<IotdbModels.Tag> listTags(String nameMask)` | `SHOW TIMESERIES <mask>`；空掩码 → 裸 `SHOW TIMESERIES`（`:79`） | 列式响应需转置 |
| `IotdbModels.Point readCurrent(String path)` | `SELECT LAST <path>` | 单位号 |
| `List<IotdbModels.Point> readRaw(String path, Instant start, Instant end, Direction direction, int limit)` | `SELECT <m> FROM <device> WHERE time … ORDER BY TIME … LIMIT n` | 单位号 |
| `List<IotdbModels.Point> readAggregated(String path, Aggregation aggregation, Instant start, Instant end, long intervalMillis, Direction direction, int limit)` | `SELECT <fn>(<m>) … GROUP BY ([s,e),<lit>)` | 单位号 |
| `void write(String path, List<Sample> samples)` | 写入语义（REST 走 `insertTablet`） | 不受公共层 `allow-write` 约束 |
| `HealthStatus checkHealth()` | `GET {base}/ping`，body 含 `ok` → UP（`:189-199`） | 不抛异常 |

> `IotdbRestApi` **没有 `close()`**：它不持有连接池，生命周期由 `IotdbClient.close()` 统一管理。

```java
IotdbRestApi rest = client.unwrap(IotdbRestApi.class);
JSONObject rows = rest.query("SHOW TIMESERIES root.factory.**");
rest.nonQuery("CREATE TIMESERIES root.factory.device01.pressure WITH DATATYPE=FLOAT");
rest.insertTablet(requestNode);
String pong = rest.ping();
```

### 7.2 `IotdbQueryService`（24 个 public 成员 = 23 个方法/构造器 + 1 个 public 嵌套类）

位置：`iotdb/internal/query/IotdbQueryService.java`。取出：`client.unwrap(IotdbQueryService.class)`。
**三种 transport 共用同一份 SQL**——包括 `AGGREGATED`，它不再单独走 REST，否则「三通道同一份 SQL」这条不变式就有洞。

#### 分块入口

| 签名 | 语义 |
|---|---|
| `List<List<IotdbTagPath>> chunks(Grouping grouping, List<IotdbTagPath> tags)` | 按档位分块，同时受 `query-batch-size`（表达式个数）与内部常量 `MAX_SQL_BYTES = 65536`（UTF-8 字节预算）约束 |
| `chunks(Grouping grouping, List<IotdbTagPath> tags, int batchSize)` | 显式指定块大小；对已分好的一块再调，结果不变 |

`MAX_SQL_BYTES = 65536` 是**客户端保守值，不是实测的服务端限制**——实测 300 个表达式、约 13 KB 的语句可过。

#### 批量原语（7 族）

批量原语的返回形态**分两种**，不要混用：

- `currentBatch` 返回 **`List<IotdbModels.Point>`，与入参同序**，长度恒等于入参长度，无数据的位号对应 `null`（`IotdbQueryService.java:70-82`）。缺失是按 `Timeseries` 列名回填的，**不能按列序对齐**。
- 其余 6 族返回 **`Map<规范绝对路径, 序列>`**，键覆盖全部入参位号（缺数的给 `null` 点占位）。

| 签名 | 每条 SQL 的形态 | 往返 |
|---|---|---|
| `List<IotdbModels.Point> currentBatch(List<IotdbTagPath> paths)` | `SELECT LAST E(p1), E(p2), … FROM root.db` | `ceil(块内位号 / query-batch-size)` |
| `Map<String, List<IotdbModels.Point>> intervalBatch(List<IotdbTagPath> tags, Instant start, Instant end, long stepMillis, Aggregation aggregation, FillPolicy fill)` | 多列 `GROUP BY ([s,e),<lit>) [FILL(…)]` | 每块 1 条 |
| `intervalBatch(List<IotdbTagPath>, Instant, Instant, TimeGranularity granularity, Aggregation, FillPolicy)` | 同上，粒度版（自然日/月按 `zone` 对齐） | 每块 1 条 |
| `Map<String, List<IotdbModels.Point>> aggregatedBatch(List<IotdbTagPath> tags, Aggregation aggregation, Instant start, Instant end, long stepMillis, Direction direction, int maxPoints)` | 多列 `GROUP BY` + 方向 + 行数上限 | 每块 1 条 |
| `Map<String, IotdbModels.Point> intervalSumBatch(List<IotdbTagPath> tags, Instant start, Instant end)` | `SELECT first_value(E(p)), last_value(E(p)), …`（**无 GROUP BY**，1 个逻辑行） | 每块 1 条 |
| `Map<String, List<DeltaPoint>> deltasBatch(List<IotdbTagPath> tags, Instant start, Instant end, TimeGranularity granularity, FillPolicy fill, Direction direction, int maxPoints)` | 桶序列 1 条 + `SELECT LAST_VALUE(E(p)), … WHERE time < start` 1 条 | 每块 **2** 条 |
| `Map<String, List<IotdbModels.Point>> rawBatchCompleted(List<IotdbTagPath> tags, Instant start, Instant end, Direction direction, int maxPoints, Set<String> completed)` | 多列 raw + union `LIMIT`，欠采列逐位号补采 | 见下 |
| `Map<String, Interpolation> interpolateBatch(List<IotdbTagPath> tags, Instant gridStart, long stepMillis, int slots, long toleranceMillis, int rawLimit, Set<String> completed)` | 窗内多列 raw + 左右包围点多列 raw + 补采 | 每块 **3** 条起 |

`completed` 是**出参账本**：回写走过逐位号补采的位号，公共层把它落成 `TagSeries.attributes["rawBatchCompleted"]`。

为什么 RAW 与插值必须补采：**多列 RAW 的 `LIMIT` 是「合并时间戳行数」预算，不是每位号点数**（实测 M9/G6）。同窗四列各要 10 个点时，密集序列可以吃满 10 行、小时级序列只剩 1 个点；不存在的路径**整列消失**，存在但无点的序列保留空列。所以撞预算且没取满的列必须逐位号补采，补采列数超 `query-batch-completion-max`（默认 32）则整块退回逐位号，避免「位号数 × 补采」的往返乘积。

#### 逐位号入口（7 个，普通 public 方法）

| 签名 | 委托到 |
|---|---|
| `List<IotdbModels.Point> raw(String path, Instant start, Instant end, Direction direction, int limit)` | `IotdbRawQueries.raw(...)`（`:221`） |
| `List<IotdbModels.Point> interval(String path, Instant, Instant, long stepMillis, Aggregation, FillPolicy)` | `IotdbSampledQueries.interval(...)`（`:237`） |
| `List<IotdbModels.Point> interval(String path, Instant, Instant, TimeGranularity, Aggregation, FillPolicy)` | `IotdbSampledQueries.interval(...)` 粒度版（`:271`） |
| `List<IotdbModels.Point> aggregated(String path, Aggregation, Instant, Instant, long stepMillis, Direction, int limit)` | `IotdbSampledQueries.aggregated(...)`（`:254`） |
| `List<DeltaPoint> deltas(String path, Instant, Instant, TimeGranularity, FillPolicy, Direction, int maxPoints)` | `IotdbDeltaQueries.deltas(...)`（`:289`） |
| `IotdbModels.Point intervalSum(String path, Instant start, Instant end)` | `IotdbAggregationQueries.intervalSum(...)`（`:301`）；首值或末值任一缺失返回 `null` |
| `Interpolation interpolate(String path, Instant gridStart, long stepMillis, int slots, long toleranceMillis, int rawLimit)` | `IotdbInterpolationQueries.interpolate(...)`（`:317`） |

> **更正一处旧文档口径**：这 7 个方法**没有** `@Deprecated` 注解，也没有 `@deprecated` javadoc 标签
> （`grep -c "@Deprecated" IotdbQueryService.java` = 0）。`tsdb-sdk-iotdb/README.md` 旧版把它们写成
> 「deprecated-but-working」并列出了一个 `current(path)` —— **该方法不存在**，逐位号取当前值请用
> `currentBatch(单元素 List)`。本轮已同步修正该 README。
>
> 这些入口不能删：是 `unwrap` 出口与 `require-query-service: false` 回退档的依赖。

#### 其它成员

| 签名 | 语义 |
|---|---|
| `IotdbQueryService(IotdbConfig config, IotdbQueryExecutor executor)` | 构造器 |
| `List<IotdbModels.Tag> listTags(String prefix)` | `SHOW TIMESERIES`（`:347`） |
| `String serverVersion()` | `SHOW VERSION`（`:375`） |
| `static final class Interpolation` | 插值网格结果载体 |
| `void close()` | 释放资源 |

> `IotdbQueryService.transport()` **已删除**：它只是转发执行器的同名方法，同一事实由 `unwrap(IotdbConfig.class).getTransport()` 提供。留着就是「同一件事有两个入口、两个真相」。

#### 职责拆分（为什么逐位号与批量结果一致）

`IotdbQueryService` 只负责查询编排（分块、批量、并行）；结果列的按名定位与读取在 `internal/query/ResultColumns`，插值网格与累计差分的纯数值推导在 `internal/query/PointMath`。**两条路径共用这两个内核**，保证逐点同口径。

### 7.3 三通道差异

| transport | 连接方式 | 端口 | 需要的 jar | 探活 |
|---|---|---|---|---|
| `rest`（默认） | `POST {base}/rest/v2/query` | HTTP 18080 | 仅公共依赖（hutool + fastjson） | `GET {base}/ping` |
| `native` | `SessionPool` RPC | 6667 | `iotdb-session` 2.0.11 | `SHOW VERSION` |
| `jdbc` | `jdbc:iotdb://host:6667/` | 6667 | `iotdb-jdbc` 2.0.11 | `SHOW VERSION` |

`iotdb-session` 与 `iotdb-jdbc` 在 `tsdb-sdk-iotdb/pom.xml:40-47` 是 **compile 依赖（非 optional）**，所以引 IoTDB 驱动就会带入这两个 jar，即使只用 REST 通道。

REST 服务需在服务端 `conf/iotdb-system.properties` 开启：

```properties
enable_rest_service=true
rest_service_port=18080
```

**不变式**：三通道共用同一份 SQL 与同一个 `IotdbResultTable`。`IotdbProductionIT` 对同一批位号、同一窗口在三通道上逐点比对。

### 7.4 位号路径与引号规则

`internal/sql/IotdbTagPath` 是解析、白名单校验、引号规范化的**唯一入口**，同一个位号串不再被三处各查一遍。

| 传入形态 | 实际路径 |
|---|---|
| `device01.temperature`（相对） | `root.factory.device01.temperature`（补 `default-database`） |
| `root.sdk_it.device01.flow`（全名称） | 原样使用，可跨库 |
| `root.sg` 之后逐节点已带反引号的混合写法 | `root.sg.d1.temperature` |
| 相对位号且未配 `default-database` | 抛 `TsdbDataException` |

完整路径至少四段 `root.db.device.measurement`；是否「全名称」**按节点判定**（前两级必须是 `root` 与库名节点），不用字符串前缀比对。

**层级错配静默返空**（实测 M1）：`FROM` 前缀与 SELECT 表达式的层级必须一致，混组会返回空结果而不是报错。所以一次请求出现多个数据库时，按 `query-batch-scope` **拆成多条语句**，每条只覆盖一个分组；混组在构造语句前就被拒绝，而不是让调用方把空结果当成「那段时间没数据」。

**SQL 注入白名单**：位号节点允许字母（含中文）、数字与 `_ : - + / # % @ ( ) [ ] ~`，长度 ≤ 512；`SHOW TIMESERIES` 掩码额外允许 `*`、`?`（`IotdbSql.isValidMask`）。引号、反引号本身、分号、反斜杠、空白、控制字符、`$`、花括号、逗号都不在放行集内，被反引号包裹后无法闭合逃逸；非法输入抛 `TsdbDataException`。

> 白名单只用于防止**便捷方法拼接 SQL** 时被注入。`query(String sql)` / `nonQuery(String sql)` 是原始透传，不限制 SQL 类型，安全边界由调用方负责。

### 7.5 服务端硬限制（实测，决定原生调用怎么写）

| 限制 | 表现 | 应对 |
|---|---|---|
| `GROUP BY` 结果 **10000 行上限** | 超出 `code=708 Dataset row size exceeded the given max row size (10000)` | 行数只等于**桶数**、与列数无关（实测 M4/G7：3 列 × 120 桶仍是 120 行），所以加宽语句不会缩短可查窗口；细网格长窗口必须切窗。1s 粒度约 2.8 小时、`30s` 约 3.5 天、`5m` 约 34 天 |
| 间隔字面量必须带单位 | 裸毫秒数 → `code=700 no viable alternative` | 按最大单位生成 `Nd`/`Nh`/`Nm`/`Ns`/`Nms`/`Nmo`；实测 `5m`、`30s`、`300000ms`、`250ms`、`1mo` 均正常分桶 |
| SELECT 里不能带 `root.` | `code=701` | 表达式用相对设备路径，`FROM` 给前缀 |
| 多列 RAW 共享 `LIMIT` | 密集列挤掉稀疏列；不存在的路径整列消失 | 逐位号补采 + `completed` 账本 |
| `SELECT LAST` 行序乱 + 丢列 | 应答行序 ≠ 请求序，缺序列被丢弃 | 按 `Timeseries` 列名回填（`SeriesKeyIndex`） |
| 非 ASCII 标识符 | 未声明 charset → Jetty 直接 500 返回 HTML | `IotdbHttpClient` 已按 `application/json; charset=UTF-8` 发送；用 curl/Postman 手工复现时要自己加 |
| `date_bin()` / `sample()` | 2.0.11 树模型下**静默返空** | 不用这两个函数，等间隔走 `GROUP BY` |
| 查不存在的序列 | 返回**空结果而不是异常** | 坏映射表只会让报表静默缺数，所以 `tag-mapping-file` 读不到或映射值非法时 `IotdbDriver.create` 直接抛 `TsdbDataException` |

---

## 8. 配置项 → 生效位置对照

权威模板是仓库根目录的 [`tsdb.example.yml`](../tsdb.example.yml)。下表给每个公开键的**读取位置**与**影响的原生调用**。

### 8.1 公共键（`common/config`）

| 键 | 默认值 | 读取位置 | 影响 |
|---|---|---|---|
| `tsdb.default-instance` | 无（必填） | `TsdbConfigLoader` | `manager.client()` 取哪个实例 |
| `tsdb.fail-fast` | `true` | `TsdbConfigLoader` | 建连失败是否立即抛 |
| `tsdb.security.allow-write` | `false` | `TsdbConfigLoader` | 公共层 `WriteSession.isWritable()`；IoTDB 的 `WRITE` 能力声明 |
| `tsdb.security.allow-insecure-transport` | `false` | `TsdbConfigLoader.java:295` | 是否允许 `http://`；实例级 `options.allow-insecure-transport` 可覆盖 |
| `tsdb.security.verify-hostname` | `true` | `TsdbConfigLoader` | HTTPS 主机名校验 |
| `tsdb.security.audit-enabled` | `true` | `TsdbConfigLoader` | 写/管理操作审计日志 |
| `instances.<n>.type` | 无（必填） | `TsdbConfigLoader` | 选驱动：`hls`/`ge`/`iotdb` |
| `instances.<n>.protocol` | 无 | `TsdbConfigLoader.java:174` | **描述性字段，任何驱动都不读它**（全仓只有 `TsdbInstanceConfig.getProtocol()` 这一个 getter，无调用方）。示例里写 `rest`。传输方式由 `base-url` 的 scheme 与 IoTDB 的 `options.transport` 决定，**改 `protocol` 不会切换通道** |
| `instances.<n>.base-url` | 无（必填） | 各 `XxxConfig` | 全部原生端点的前缀 |
| `instances.<n>.timeout.connect-ms` | 驱动默认 | 各 `XxxConfig` | HTTP 连接超时；**禁止 -1（不限）** |
| `instances.<n>.timeout.read-ms` | 驱动默认 | 各 `XxxConfig` | HTTP 读超时 |
| `instances.<n>.retry.max-attempts` | `1` | 各 `XxxConfig` | 尝试次数上限 |
| `instances.<n>.retry.backoff-ms` | `200` | 各 `XxxConfig` | 退避基数 |
| `instances.<n>.options.time.request.enabled` | `true` | `TimeConfig.java:153` | 是否把 `Instant` 格式化成文本发给厂商 |
| `instances.<n>.options.time.request.zone` | `UTC` | `TimeConfig.java:155,85` | 请求文本的时区 |
| `instances.<n>.options.time.request.pattern` | `yyyy-MM-dd'T'HH:mm:ssXXX` | `TimeConfig.java:157,75` | 请求文本格式 |
| `instances.<n>.options.time.response.enabled` | `true` | `TimeConfig.java:161` | 是否解析厂商文本时间 |
| `instances.<n>.options.time.response.source-zone` | `UTC` | `TimeConfig.java:163` | 无时区文本按此时区解释 |
| `instances.<n>.options.time.response.target-zone` | `UTC` | `TimeConfig.java:166` | `timestampText` 展示时区 |
| `instances.<n>.options.time.response.pattern` | `yyyy-MM-dd HH:mm:ss` | `TimeConfig.java:169,80` | 展示格式 |

`time.*` 支持嵌套 map 与扁平键（`time.request.zone`）两种写法，嵌套优先。pattern 非法时抛 `TsdbConfigurationException`。

> **只有 GE 实际消费文本时间**。HLS 用 epoch 秒、IoTDB 用 epoch 毫秒，`time.request.*` 对它们不生效；
> `time.response.target-zone/pattern` 三库都影响 `timestampText` 展示。

### 8.2 HLS 专有键

| 键 | 默认值 | 读取位置 | 影响 |
|---|---|---|---|
| `auth.user-id` | 无 | `HlsConfig` | `userid` 请求头 + body 字段 |
| `auth.secret-key` | 无 | `HlsConfig` | `secretkey` 请求头 + body 字段 |
| `options.path-prefix` | `""`（空） | `HlsConfig.java:176-182` | 端点前缀；HLS 通常无前缀 |
| `options.token-expire-seconds` | **1800** | `HlsConfig.java:200` | token 缓存有效期 |
| `options.allow-insecure-transport` | 继承 security | `TsdbConfigLoader.java:295` | 现场是 HTTP 时必须显式 `true` |

### 8.3 GE 专有键

| 键 | 默认值 | 读取位置 | 影响 |
|---|---|---|---|
| `auth.username` / `auth.password` | 无 | `GeConfig` | OAuth password grant 的查询串参数 |
| `auth.client-id` | 无 | `GeConfig` | Basic 头左半 |
| `auth.client-secret` | 无 | `GeConfig` | Basic 头右半 |
| `options.path-prefix` | **`/historian-rest-api`** | `GeConfig.java:56,126-131` | 全部 `/v1/...` 端点的前缀 |
| `options.trust-all-certificates` | `false` | `GeConfig.java:175` | 自签名证书联调用；生产应导入根证书并恢复校验 |
| `options.token-expire-seconds` | **1800** | `GeConfig.java:153` | token 缓存有效期 |
| `options.time.*` | 见 §8.1 | `TimeConfig` | GE 是唯一实际消费文本时间的驱动 |

### 8.4 IoTDB 专有键

| 键 | 默认值 | 读取位置 | 影响 |
|---|---|---|---|
| `auth.username` / `auth.password` | 无 | `IotdbConfig` | 三通道认证 |
| `options.transport` | `rest` | `IotdbConfig.java:455` | `rest`/`native`/`jdbc` |
| `options.rpc-port` | **6667** | `IotdbConfig.java:464` | native/JDBC 端口 |
| `options.path-prefix` | **`/rest/v2`** | `IotdbConfig.java:70,414` | **只作用于查询路径**；探活 `/ping` 在服务根 |
| `options.default-database` | 无 | `IotdbConfig.java:465` | 相对位号补全；未配时相对位号抛 `TsdbDataException` |
| `options.tag-mapping-file` | 无 | `IotdbConfig.java:466` | 位号→`root.db.device.measurement` properties 映射表；读不到或值非法 → `IotdbDriver.create` 抛 `TsdbDataException` |
| `options.zone` | **`Asia/Shanghai`** | `IotdbConfig.java:147,484` | **业务日历时区**：自然日/月对齐与班次窗口 |
| `options.shift-hour` | **8** | `IotdbConfig.java:155,485` | 换班小时，0..23 |
| `options.require-query-service` | **`true`** | `IotdbConfig.java:483` | 缺统一 SQL 查询服务时**构造期抛异常** |
| `options.query-batch-size` | **200** | `IotdbConfig.java:89,474` | 单条批量语句覆盖的位号数；取 `1` 是逐位号归因的回退档 |
| `options.query-parallelism` | **4** | `IotdbConfig.java:98,476` | 块内语句并发上限 |
| `options.query-batch-scope` | **`database`** | `IotdbConfig.java:118,477` | `database`/`root`/`device`；`device` 是改造前行为，保留为现场回退档 |
| `options.query-batch-row-budget` | **10000** | `IotdbConfig.java:128,479` | 多列 RAW/插值语句的 `LIMIT`（合并时间戳行数） |
| `options.query-batch-completion-max` | **32** | `IotdbConfig.java:138,481` | 一个块最多逐位号补采几列，超过整块退回逐位号 |
| `options.interpolation-tolerance` | **0** | `IotdbConfig.java:468` | 插值外推容差毫秒，`0` = 不外推 |
| `options.interpolation-raw-limit` | **200000** | `IotdbConfig.java:78,470` | 插值取原始点的行数上限 |

**已改名且不保留别名的键**：

| 旧键 | 新键 | 配错后果 |
|---|---|---|
| `current-batch-size` | `query-batch-size` | 构造期抛 `TsdbConfigurationException`，报错里写明替代键名（`IotdbConfig.java:471`） |
| `current-parallelism` | `query-parallelism` | 同上（`:472`） |

> 为什么不留别名：现网配置里还留着旧键，说明这份配置没被核对过；静默忽略就等于「上限还是旧的那个值」。
> 宁可启动失败，也不让一个没生效的配置悄悄跑起来。

**`query-batch-scope` 默认 `database` 的来历**：同一实例、同一批位号、同一窗口，逐表达式 / `device` / `database` 三种配置逐序列逐点 **0 容差**比对，七个查询族全部 `diffs=0`（`IotdbTierParityIT`，逐族数字见 [docs/14](14-GE迁移IoTDB对照与替换方案.md) §8.3）。`root` 跨库单语句实测可用，不用它作默认是因为受限账号可能只有按库授权——**这条是保守选择，不是实测结论**。

**`options.zone` 与 `options.time.*` 互不影响**：前者管分桶边界，后者管文本解析与展示。把它们配成同一个时区不是必选项。

---

## 9. 完整性与验证

### 9.1 计数（本轮实测）

| 项 | 数字 | 复现方式 |
|---|---|---|
| `GeEndpoint` 常量 | **92**（Data 22 / Tags 13 / Systems 25 / Collectors 27 / Alarms 5） | 数 `ge/nativeapi/GeEndpoint.java` 的枚举常量 |
| HLS 厂商形态方法 | **15**（原 SDK 14 + 1 新增便捷重载） | §5.1 表；`HlsNativeApi` public 成员共 **23** |
| `GeNativeApi` public 成员 | **18** | `grep -c "^    public " GeNativeApi.java` |
| `IotdbRestApi` public 成员 | **11**（无 `close()`） | 同上 |
| `IotdbQueryService` public 成员 | **22** = 21 个方法/构造器（批量 7 族 + `chunks` 2 重载 + 逐位号 7 个 + `listTags`/`serverVersion`/`close`/ctor）+ 1 个 public 嵌套类 `Interpolation`；**无 `@Deprecated`** | 同上 |
| `TsdbClient` 方法 | **11** | `common/api/TsdbClient.java` |
| `common.api` 接口 | **7** | 目录实测 |
| `Capability` / `QueryMode` / `Aggregation` / `FillPolicy` / `TimeGranularity` / `Quality` / `ValueType` / `TsdbErrorCode` | 15 / 5 / 10 / 4 / 5 / 5 / 6 / **9** | 各枚举源码 |

### 9.2 测试基线

| 层 | 数字 | 最近实测 |
|---|---|---|
| L1 离线单测 | **290**（common 77 + hls 20 + ge 28 + iotdb 164 + starter 1），0 failures / 0 errors / 0 skipped | **2026-11-19 09:58**，`mvn -o test` BUILD SUCCESS，25 份当次生成的 surefire XML |
| IoTDB 门控 IT | **45**（Production 11 + Mapping 3 + ContractProbe 24 + TierParity 7），45/45 全绿 | **2026-11-19 09:51**；七族 `diffs=0`，其中「等间隔差分 222」是折叠基线的主证据（`delta` 桶数 42）。注意 surefire 2.22.2 默认不匹配 `*IT`，必须带 `-Dtest=*IT`，否则只跑 164 个单测 |
| GE 门控 IT | **19**（Production 10 + Comparison 9），`MATCH=2021 DIFF=0 OLD_ONLY=0 NEW_ONLY=0 PROBE=434` | **2026-11-19 09:50**，差分折叠与 `count` 推导改动后的全量回归（Comparison 175.5 s、Production 3.1 s） |
| HLS 门控 IT | **10**（9 默认 + 1 差分门控），10/10 全绿 | **2026-11-19 09:42**，438 s（`-Dhls.it.delta=true`）；5 分钟差分 `points=12`（1 小时窗口 = 12 个桶，旧值 13 是 `limit + 1` 越界，已随折叠修正） |

> 上表三行都是 **2026-11-19** 本轮实跑记录（差分基线折叠 + `count` 精确推导之后）：三库生产只读 IT 均已执行并通过，无跳过。
> 差分往返统一为「主查询扩一个间隔折叠基线」，HLS 1 请求 / GE 1 请求 / IoTDB 每块 1 条 SQL，
> 只有扩展桶为空时才各补一条**批量**兜底；输出点数等于调用方 `count`（或区间推导值），补 1 只在实现内部。
> HLS 的 `RAW`/`SAMPLED`/`INTERVAL_SUM`/差分统一基于 `BOUND`（生产同款迂回方案），
> 适用前提与换版本复核见 docs/10 §8.1。

复现命令：

```bash
mvn -o test

mvn -o -pl tsdb-sdk-ge -am test "-Dtest=GeProductionIT,GeComparisonIT" \
  "-DfailIfNoTests=false" "-Dge.it=true"

mvn -o -pl tsdb-sdk-hls -am test "-Dtest=HlsProductionIT" \
  "-DfailIfNoTests=false" "-Dhls.it=true"
# 差分用例历史查询成本高、默认跳过；需要时追加 "-Dhls.it.delta=true"

mvn -o -pl tsdb-sdk-iotdb -am test \
  "-Dtest=IotdbProductionIT,IotdbMappingProductionIT,IotdbContractProbeIT,IotdbTierParityIT" \
  "-DfailIfNoTests=false" "-Diotdb.it=true"
```

注意事项：

- `-pl` 必须带 `-am`，否则依赖模块不会先构建。
- 跨模块只跑 IT 用 **`-DfailIfNoTests=false`**；`-Dsurefire.failIfNoSpecifiedTests=false` 在本项目的 surefire 2.22.2 上**无效**。
- 计数只认**当次生成**的 surefire XML（按 mtime/cutoff 过滤），不做目录求和——`target/` 里会累积历史报告。
- `IotdbProductionIT` 的默认位号会随现场数据老化而变化：默认库 `root.factory` 下
  `device01.temperature` 近 7 天可能已无原始点，其 raw 断言会**环境性失败**；在 `iotdb-it.properties`
  把 `tag` 指到存活位号（本轮用 `root.factory.device02.temperature`）即可三通道全绿。这不是 SDK 回归。
- 生产凭据放被 `.gitignore` 排除的 `ge-it.properties` / `hls-it.properties` / `iotdb-it.properties`，不写入仓库。
  IoTDB 四个 IT 类经 `IotdbItConfig` 读取，优先级为「系统属性 `iotdb.it.<key>` → 环境变量
  `IOTDB_IT_<KEY>` → `iotdb-it.properties`」；必填键 `baseUrl`/`username`/`password`，
  可选 `rpcPort`/`transport`/`defaultDatabase`/`tag`/`tags`。

### 9.3 未执行 / 未确认清单（与 README §9.4 同口径）

| 项 | 状态 | 后果 |
|---|---|---|
| **GE ↔ IoTDB 业务级对数** | **未执行** | 同库三档双跑（`IotdbTierParityIT` 七族 `diffs=0`）只证明 IoTDB 自己跟自己一致，**不证明 GE 与 IoTDB 的业务数值一致**。这是迁移的硬门槛，未完成前不得宣称「可替换」 |
| 重试重放 / 熔断 / 信号量隔离 / 降级缓存 | **未实现** | `retry.max-attempts`/`backoff-ms` 是配置项，不要据此宣称有完整弹性能力 |
| Actuator `/actuator/health/tsdb` | **未实现** | starter 只注册 `TsdbManager` bean |
| 力控 pSpace 驱动 | **未实现** | 没有空壳驱动；Java 包名/Maven 坐标/JDBC URL/REST 路径与鉴权细节尚未拿到 |
| `FillPolicy.ZERO` | **未实现** | IoTDB 上抛 `TsdbUnsupportedException` |
| 中央仓库**发布动作** | **未执行** | 根 pom 的 `url`/`scm`/`developers`/`distributionManagement`（Sonatype s01，`id=central`）与 `release` profile（`central-publishing-maven-plugin` 0.4.0 + gpg + sources/javadoc jar）**均已就绪**；未跑 `mvn -Prelease deploy`，当前需本地 `install` 或私服 |

### 9.4 相关文档

- [docs/08 · 原生 API 完整性对照](08-原生API完整性对照.md)：原厂商 SDK 方法 → 新方法的逐项对照
- [docs/12 · 厂商 API 与公共 API 使用配置指南](12-厂商API与公共API使用配置指南.md)：配置与调用示例
- [docs/14 · GE 迁移 IoTDB 对照与替换方案](14-GE迁移IoTDB对照与替换方案.md)：26 条实测服务端事实、七族双跑对数数字
- [tsdb-sdk-iotdb/README.md](../tsdb-sdk-iotdb/README.md)：IoTDB 驱动细节与批量原语表
- [tsdb-sdk-ge/README.md](../tsdb-sdk-ge/README.md)、[tsdb-sdk-hls/README.md](../tsdb-sdk-hls/README.md)
- 中文 Javadoc：`docs/apidocs/index.html`