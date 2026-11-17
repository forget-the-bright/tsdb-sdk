# tsdb-sdk-common —— 公共接入层

## 1. 模块定位

`tsdb-sdk-common` 是整个 SDK 的“公共基座”。它只依赖 JDK、Hutool、fastjson
与 SLF4J，不依赖 Spring、不依赖任何厂商 SDK。所有厂商驱动
（HLS、GE、IoTDB，以及后续力控）都实现这里定义的接口，因此业务代码可以在
不修改调用逻辑的前提下切换时序库。

设计原则：

1. **公共层零厂商依赖**：`common` 的编译类路径中不出现 `org.apache.iotdb`、
   `io.github.forget_the_bright.tsdb.hls` 等厂商包。
2. **一库一模块**：每个数据库一个 Maven 模块，驱动之间互不依赖。
3. **能力协商**：不是所有库都支持原始点、插值、写入。客户端通过
   `capabilities()` 声明能力，调用不支持的能力时抛
   `TsdbUnsupportedException`，而不是静默降级。
4. **单活动实例**：现场通常只有一套时序库；`TsdbManager` 同时只持有一个
   活动客户端，`switchTo` 先创建新客户端、成功后再关闭旧客户端。
5. **原生逃生口**：公共层不追求覆盖厂商 100% 接口；需要完整能力时通过
   `client.unwrap(XxxNativeApi.class)` 直接调用厂商原生 API。

## 2. 包结构

按 2026-11-16 实测绘制：**57 个类**，9 个包，每个包都有 `package-info.java`
（职责 + 可见性契约 + 类清单 + 依赖方向）。

```
io.github.forget_the_bright.tsdb.common
├── (2)          TsdbClients（静态门面）、TsdbManager（实例管理与切换）
├── api      (7) TsdbClient、TagCatalog、CurrentValueReader、HistoryReader、
│                DeltaReader、WriteSession、HealthCheck
├── model   (20) 刻意平铺，分类索引见该包 package-info
│                枚举 8：Quality、ValueType、Aggregation、QueryMode、
│                        TimeGranularity、Direction、FillPolicy、Capability
│                查询 2：HistoryQuery、TagQuery
│                结果 8：Sample、TagSample、TagSeries、TagInfo、DeltaPoint、
│                        DeltaSeries、HealthStatus、CapabilitySet
│                写入 2：WriteRequest、WriteResult
├── config   (8) TsdbConfig、TsdbConfigLoader、TsdbInstanceConfig、AuthConfig、
│                SecurityConfig、TimeConfig、TimeoutConfig、RetryConfig
├── exception(9) TsdbException、TsdbErrorCode 及 7 个语义子类
├── spi      (3) TsdbDriver、DriverContext、Metrics
└── internal (1) Texts（trimToNull / abbreviate）        # 以下均**非公共 API**
    ├── http (5) HttpTransport（Hutool）、HttpResult、HttpErrors、
    │            BasicAuth、TrustAllSsl
    └── json (2) Json（fastjson 安全初始化）、JsonText（树取值与类型收敛）
```

`internal` 三个包不承诺兼容，签名随内部重构变化；宿主应用请只用 `api`/`model`/`config`/
`exception`/`spi` 与根包两个门面。驱动模块复用 `internal` 的 HTTP/JSON/文本工具，
不要各自再写一套。

## 3. 核心接口

### 3.1 TsdbClient

一个客户端对应一个已配置的实例。所有实现线程安全，`close()` 释放资源。

```java
TsdbClient client = manager.client("hls_main");
CapabilitySet caps = client.capabilities();
if (caps.supports(Capability.RAW_HISTORY)) {
    TagSeries series = client.history().read(
        HistoryQuery.builder()
            .tags("AI-101")
            .range(start, end)
            .mode(QueryMode.RAW)
            .maxPoints(5000)
            .build());
}
```

### 3.2 数据模型

- 时间统一用 `Instant`（UTC），查询区间为左闭右开 `[start, end)`。
- `Sample` 用一个对象承载 double/long/boolean/string/bytes 五种值，
  由 `ValueType` 指明当前有效字段；缺失值为 `Sample.ofNull()`。
- `Quality` 是归一化质量：`GOOD/UNCERTAIN/BAD/STALE/UNKNOWN`。
  厂商原始质量码保留在 `vendorQualityCode`。
- `TagSeries.attributes` 承载厂商扩展信息，例如 HLS 的
  `qualitySource=hls_not_provided`、GE 的 `dataType`。

### 3.3 能力枚举

`Capability` 共 15 项，覆盖标签目录/搜索/CRUD、当前值、原始/聚合/插值/采样/趋势历史、
累计差分、写入、删除、报警、订阅、系统管理等。驱动必须在 `capabilities()` 中如实声明；
`TsdbManager` 不会替驱动“补齐”能力。

## 4. 配置

`TsdbConfigLoader` 读取 YAML，支持两种写法：

```yaml
# 直接以 tsdb 为根
default-instance: hls_main
instances:
  hls_main:
    type: hls
    base-url: https://10.10.7.177:9000
    auth:
      user-id: httpIO
      secret-key: ${HLS_SECRET}
```

或包在 `tsdb:` 下。字符串支持 `${ENV}` 与 `${ENV:default}`，先查环境变量，
再查系统属性。`instances` 的键会被 `String.valueOf` 转换，因此 YAML 1.1 把
`on/off/yes/no` 解析成布尔值时不会抛 `ClassCastException`，但建议实例名避免
这些词。

### 4.1 时间格式（TimeConfig）

`Sample.timestamp` 永远是绝对时刻 `Instant`；时区只影响请求字符串和展示：

```yaml
options:
  time:
    request:
      enabled: true
      zone: UTC                 # 请求按 UTC 格式化
      pattern: "yyyy-MM-dd'T'HH:mm:ssXXX"
    response:
      enabled: true
      source-zone: UTC          # 无时区响应文本按 UTC 解释
      target-zone: GMT+8        # 展示为北京时间
      pattern: "yyyy-MM-dd HH:mm:ss"
```

- `TimeConfig.from(instanceConfig)` 读取嵌套或扁平键；
- `formatRequest(Instant)` 生成厂商请求字符串；
- `parseResponse(String)` 解析响应：显式 `Z`/偏移优先，其次 pattern，最后按 `source-zone`；
- `formatResponse(Instant)` 只用于展示，不改变模型中的绝对时间；
- 返回值同时带 `timestamp`（`Instant`，JSON 为 ISO 字符串）、`timestampDate`（`java.util.Date`）与 `timestampText`（按上面的 `response.target-zone/pattern` 格式化），前端直接展示 `timestampText` 即可；`HealthStatus` 的 `checkedAt/checkedAtDate/checkedAtText` 同理；
- 默认全部 UTC，不假设东八区；铜陵 GE 现场显式配 `target-zone: GMT+8`。

## 5. 生命周期与切换

```java
TsdbManager manager = TsdbClients.fromYaml(Paths.get("tsdb.yml"))
        .discoverDrivers()
        .build();

TsdbClient hls = manager.client();          // 默认实例
TsdbClient iotdb = manager.switchTo("iotdb_main"); // 先开新、后关旧
manager.close();                            // 幂等
```

- `switchTo` 失败时旧客户端保持可用，`activeInstance()` 不变。
- `close()` 之后再调用 `client()/switchTo()` 抛
  `TsdbConfigurationException("tsdb manager is closed")`。
- `discoverDrivers()` 使用 `ServiceLoader` 发现
  `META-INF/services/...TsdbDriver`。

## 6. 异常体系

`TsdbException` 携带 `code/retryable/instance/type/operation/vendorCode`。
错误码：

| 错误码 | 含义 |
| --- | --- |
| `TSDB-CONFIG` | 配置缺失或非法 |
| `TSDB-CONN` | 连接/网络失败 |
| `TSDB-AUTH` | 认证失败 |
| `TSDB-TIMEOUT` | 超时 |
| `TSDB-RATE-LIMIT` | 限流 |
| `TSDB-UNSUPPORTED` | 当前驱动不支持该能力 |
| `TSDB-QUERY` | 查询/语句执行失败 |
| `TSDB-DATA` | 数据格式或映射失败 |
| `TSDB-INTERNAL` | 未预期内部错误 |

## 7. 测试

```bash
mvn -pl tsdb-sdk-common -am test
```

测试覆盖 YAML 加载、环境变量替换、默认实例、切换、切换失败保留旧实例、
禁用实例、未知驱动、关闭后使用、YAML 布尔键，以及 `TimeConfig` 的
UTC/GMT+8 解析与展示、显式偏移优先、非法时区等场景。
