# tsdb-sdk-ge —— GE Proficy Historian 驱动

## 1. 模块定位

本模块把 GE Proficy Historian（`/historian-rest-api`）接入统一 SDK。
原生层保留 GE 的 **92 个 REST 端点**，公共层只抽取跨厂商通用的
标签、当前值、原始/聚合/插值查询能力。

## 2. 目录结构

按 2026-11-16 实测绘制：主代码 **14 个类**，分布在 4 个包，每个包都有 `package-info.java`。

```
src/main/java/io/github/forget_the_bright/tsdb/ge
├── GeClient.java                门户：装配适配器、分派能力、unwrap 逃生口
├── GeConfig.java                配置（baseUrl、OAuth、超时、路径前缀、TLS）
├── GeDriver.java                SPI 入口（type=ge）
├── GeMapper.java                厂商模型 → 公共模型（质量码/值类型/时间）
├── internal
│   ├── GeHttpClient.java        Hutool HttpTransport 薄适配器 + 错误映射
│   ├── GeTokenManager.java      OAuth password grant + 缓存（单飞、提前 5 秒失效）
│   ├── GeModels.java            原生响应行模型 ↔ 公共模型换算
│   ├── GeNativeApi.java         原生 API：类型化便捷方法 + invoke 全量逃生口
│   └── adapter/                 门户装配层：public 但**非公共 API**
│       ├── GeSupport.java       共享配置/客户端/令牌/开闭状态/unsupported 异常
│       ├── GeTagCatalogAdapter.java     位号目录
│       ├── GeCurrentValueAdapter.java   最新值
│       ├── GeHistoryAdapter.java        原始/聚合/插值历史
│       └── GeDeltaAdapter.java          累计量差分（17/19 末−首口径）
└── nativeapi
    └── GeEndpoint.java          92 个端点目录（Data/Tags/Systems/Collectors/Alarms）
```

测试：`ge/GeClientTest`（28 例）；门控只读 IT 在 `ge/it`：`GeProductionIT`、`GeComparisonIT`
（`-Dge.it=true` 才跑）。

### 2.1 按任务找类

| 我要改什么 | 去哪儿 |
|---|---|
| 端点路径、HTTP 方法、参数位置 | `nativeapi/GeEndpoint`（92 个端点逐项对照原 SDK） |
| 新增/修改原生调用 | `internal/GeNativeApi.invoke(...)` / `rawRequest(...)` |
| 认证、令牌刷新与缓存 | `internal/GeTokenManager` + `GeConfig` |
| HTTP 细节、状态码→异常 | `internal/GeHttpClient` + `common/internal/http/HttpTransport`、`HttpErrors` |
| 响应行解析、质量码/值类型/时间映射 | `internal/GeModels` + 根包 `GeMapper` |
| 最新值/历史/差分的接口装配 | `internal/adapter/Ge*Adapter` |
| 差分口径（17/19 末−首） | `internal/adapter/GeDeltaAdapter` + `common/model/TimeGranularity.normalizeStart` |
| 不支持能力的异常与上下文 | `internal/adapter/GeSupport.unsupported` + `GeClient` 能力声明 |
| JSON 树解析与类型收敛 | `common/internal/json/Json`、`JsonText` |

## 3. 认证

GE 使用 OAuth2 password grant：

```
POST /uaa/oauth/token?grant_type=password&username=...&password=...
Authorization: Basic base64(clientId:clientSecret)
```

响应 `access_token`、`expires_in`，缓存 TTL 为 `expires_in*1000-5000` 毫秒。
后续请求带 `Authorization: Bearer <token>`；401 时清 token 并重试一次。
也支持直接配置静态 token。

```yaml
instances:
  ge_main:
    type: ge
    base-url: https://172.16.176.9
    auth:
      username: JXTY-176-9.admin
      password: ${GE_PASSWORD}
      client-id: historian_public_rest_api
      client-secret: ${GE_CLIENT_SECRET}
    options:
      path-prefix: /historian-rest-api
```

## 4. 原生 API：全量 92 个端点

`GeNativeApi.invoke(...)` 是原生能力的完整入口。返回值是 fastjson 树对象：
`Object` 实际承载 `JSONObject`/`JSONArray`/标量，需要按字段读取时用
`JsonText.object(...)`/`JsonText.array(...)` 做显式收敛（类型不符会抛
`TsdbDataException`，不会静默返回空对象）。

```java
// GET /v1/tags?maxNumber=100&nameMask=AI*
Map<String,Object> query = new LinkedHashMap<>();
query.put("maxNumber", 100);
query.put("nameMask", "AI*");
JSONObject tags = JsonText.object(nativeApi.invoke(GeEndpoint.QUERY_TAGS_BY_PARAMS, query));

// GET /v1/datapoints/raw/{tagNames}/{start}/{end}/{direction}/{count}
Map<String,Object> path = new LinkedHashMap<>();
path.put("tagNames", "AI-101,AI-102");
path.put("start", "2025-01-01T00:00:00Z");
path.put("end", "2025-01-01T01:00:00Z");
path.put("direction", 1);
path.put("count", 100);
JSONObject raw = JsonText.object(nativeApi.invoke(GeEndpoint.GET_RAW_DATA_BY_PATH_VARIABLE, path));
```

`GeEndpoint` 按业务模块分组：

| 模块 | 端点数 | 代表性能力 |
| --- | --- | --- |
| Data | 22 | calculated、currentvalue、interpolated、raw、sampled、trend、create、configuration |
| Tags | 13 | 标签查询、增删改、评论、属性、重命名、高级检索 |
| Systems | 25 | 数据存储、DHS、服务器、机器、镜像组、性能、系统统计 |
| Collectors | 27 | 采集器实例、启停/暂停/恢复、状态、版本、OPC 服务器、离线列表 |
| Alarms | 5 | 创建/删除/查询/备份/恢复告警 |

所有端点均可通过 `invoke(endpoint, pathVars, queryParams, body)` 调用；
路径变量、查询参数、请求体分别处理。若厂商未来新增端点，可用
`rawRequest(method, relativePath, body)` 直接调用。

## 5. 公共层能力

`GeClient` 声明：

- `TAG_BROWSE`（支持 nameMask、limit、offset；不支持 valueType/unit 过滤）
- `CURRENT_VALUE`
- `RAW_HISTORY`
- `AGGREGATED_HISTORY`（AVG/MIN/MAX/SUM/COUNT/FIRST/LAST，`SUM` 对应厂商 `RawTotal=9`；
  另有 `INTERVAL_SUM` 以 17/19 双请求末减首实现；BOUND/RANGE 不支持）
- `INTERPOLATED_HISTORY`
- `SAMPLED_HISTORY`（POST `/v1/datapoints/sampled`，固定 `samplingMode=2`（Interpolated）
  + `queryModifier=1`，此模式忽略 `aggregation`）
- `CUMULATIVE_DELTA`（末值分桶：主查询起点前移一个间隔、`count = 窗口桶数 + 1`，首桶作基线不输出；
  扩展桶为空才补一次批量 raw 基线；内存相邻相减）

GE 的写入接口存在，但当前公共层不声明 `WRITE`，写会话只读；需要写入时
直接调用原生 `GeNativeApi.invoke(GeEndpoint.CREATE_TAG_DATA, body)`，并自行
承担安全审计。

## 6. 映射规则

- 质量码：`0=BAD`、`1=UNCERTAIN`、`2=UNKNOWN`、`3=GOOD`。
- 值：GE 统一返回字符串；按内容推断 `LONG → DOUBLE → BOOLEAN → STRING`。
- 时间：请求按 `options.time.request.zone/pattern` 格式化（默认 UTC、
  `yyyy-MM-dd'T'HH:mm:ssXXX`）；响应文本按 `options.time.response.source-zone`
  解释（默认 UTC），展示按 `target-zone/pattern`（默认 UTC、`yyyy-MM-dd HH:mm:ss`）。
  `GeClient.formatTimestamp(Instant)` 输出到界面时区，公共模型仍是绝对 `Instant`。
  每个 `Sample` 同时带 `timestampDate`（`Date`）和 `timestampText`（已格式化文本），
  前端直接展示文本即可；`HealthStatus` 的 `checkedAtDate/checkedAtText` 同理。
- `TagInfo.valueType` 恒为 `UNKNOWN`（GE 的 `DataType` 不是统一枚举）；
  原始 `DataType`、`vendorErrorCode`、`vendorErrorMessage` 放在
  `TagSeries.attributes`。

```yaml
options:
  time:
    request:  { enabled: true, zone: UTC, pattern: "yyyy-MM-dd'T'HH:mm:ssXXX" }
    response: { enabled: true, source-zone: UTC, target-zone: GMT+8, pattern: "yyyy-MM-dd HH:mm:ss" }
```

铜陵现场请求/响应都是 UTC、界面要 GMT+8；若 GE 本身用 GMT+8，把
`request.zone` 与 `response.source-zone` 改成 `GMT+8` 即可。

## 7. 测试

```bash
mvn -pl tsdb-sdk-ge -am test
```

测试使用 JDK `HttpServer` 模拟 OAuth、tags、currentvalue、raw、calculated、sampled，
覆盖 Bearer 头、顺序、缺失值、质量码、RAW/AGGREGATED/SAMPLED 参数、历史读取的 POST
请求体与 URL 编码、`SUM` 映射到 `RawTotal=9`、`INTERVAL_SUM` 的 17/19 双请求末减首、
拒绝不支持能力、明文 HTTP 拒绝、原生 `unwrap`、时间格式（默认 UTC 与 GMT+8 展示）。

### 7.1 生产环境只读集成测试

`GeProductionIT` 直接访问真实 Historian，默认跳过；配置见
[`docs/09-GE生产环境集成测试.md`](../docs/09-GE生产环境集成测试.md)。

```powershell
Copy-Item tsdb-sdk-ge\ge-it.properties.example tsdb-sdk-ge\ge-it.properties
# 填入现场只读账号后：
mvn -q -pl tsdb-sdk-ge -am test `
  "-Dtest=GeProductionIT" `
  "-DfailIfNoTests=false" `
  "-Dge.it=true"
```

> PowerShell 中带点的 `-D` 参数建议加引号，避免被拆成两个参数。

覆盖健康检查、标签目录、当前值、原始/聚合/插值历史、等间隔与累计差分，以及原生
`GeEndpoint` 的 tags/currentvalue/raw/tag-properties、systems、collectors、
alarms 查询。测试只读，不会修改现场数据；凭据文件已被 `.gitignore` 排除。

2026-11-12 已对 `172.16.176.9` 实际执行：10 个用例全部通过（0 失败、0 跳过），
其中 `readsRealSampledHistoryAndCumulativeDelta` 验证 1 小时等间隔与累计差分。

**2026-11-19 09:47–09:50 差分折叠与 `count` 推导轮复跑：19/19 全绿**
（`GeProductionIT` 10 + `GeComparisonIT` 9），`MATCH=2021 DIFF=0 OLD_ONLY=0 NEW_ONLY=0 PROBE=434`。
等间隔/累计差分逐点 `MATCH`，说明折叠基线（首桶作锚点）与旧的「一次批量 raw 基线」数值一致。

`GeComparisonIT` 是同一开关下的新旧逐点一致性对比：用同一批位号、同一时间窗同时跑
参照 SDK `GE_REST_SDK`（基准）与本模块，逐点比对时间戳、值与厂商原始质量码，
出现任一 DIFF 即失败；只读，不会修改现场数据。运行方式与上面相同，只需把
`-Dtest` 换成 `GeComparisonIT`。2026-11-12 已对 `172.16.176.9` 实际执行：
9 个用例全部通过（0 失败、0 跳过）。
