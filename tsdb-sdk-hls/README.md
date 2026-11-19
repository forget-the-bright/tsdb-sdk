# tsdb-sdk-hls —— 和利时 HLS 驱动

## 1. 模块定位

本模块把和利时 HLS 实时/历史库接入统一 SDK，包含两层：

- **原生层** `io.github.forget_the_bright.tsdb.hls.internal.HlsNativeApi`：
  直接对应原 `HLS_REST_SDK` 的 4 个 REST 接口，返回厂商原始 JSON，
  保留全部字段与扩展能力。
- **公共层** `HlsClient` / `HlsMapper` / `HlsDriver`：把 HLS 数据映射为
  `TagInfo`、`TagSeries`、`Sample` 等公共模型，供统一 API 使用。

## 2. 目录结构

按 2026-11-16 实测绘制：主代码 **17 个类**，分布在 5 个包（`nativeapi` 本身无类，只是命名空间），
每个包都有 `package-info.java`。

```
src/main/java/io/github/forget_the_bright/tsdb/hls
├── HlsClient.java               门户：装配适配器、分派能力、unwrap 逃生口
├── HlsConfig.java               配置解析与校验（地址、凭据、token TTL、超时）
├── HlsDriver.java               SPI 入口（type=hls）
├── HlsMapper.java               厂商模型 → 公共模型（宽表拆长表、质量码、时间）
├── internal
│   ├── HlsHttpClient.java       Hutool HttpTransport 传输 + 错误映射
│   ├── HlsTokenManager.java     token 获取、缓存、失效（POST JSON 优先，回退原生 GET）
│   ├── HlsModels.java           轻量厂商模型 ↔ 公共模型换算
│   ├── HlsNativeApi.java        原生 API（全量 4 接口 + 全部重载）
│   └── adapter/                 门户装配层：public 但**非公共 API**
│       ├── HlsSupport.java      共享配置/客户端/令牌/ensureOpen/unsupported 异常
│       ├── HlsTagCatalogAdapter.java     标签目录与单标签查询
│       ├── HlsCurrentValueAdapter.java   最新值（/ddb/read_ddbtagvalue）
│       ├── HlsHistoryAdapter.java        历史库查询与各历史模式（RAW 用 1 秒 BOUND 桶近似）
│       ├── HlsDeltaAdapter.java          累计量差分（BOUND 桶边界值，生产同款迂回方案）
│       └── HlsWriteAdapter.java          只读占位：isWritable()=false，write 抛不支持
└── nativeapi
    └── dto
        ├── TagNameRequest.java
        ├── TagNameListRequest.java
        └── HistorianRequest.java   buildRequest 修正原 SDK MAX/MIN 写反的缺陷
```

测试：`hls/HlsClientTest`（18 例）；门控只读 IT 在 `hls/it/HlsProductionIT`
（`-Dhls.it=true` 才跑，差分另需 `-Dhls.it.delta=true`）。

### 2.1 按任务找类

| 我要改什么 | 去哪儿 |
|---|---|
| 端点路径、`Content-Type`、请求头 | `internal/HlsNativeApi`（实时库严格 `application/json`） |
| 请求体字段与 JSON 名称、MAX/MIN 组装 | `nativeapi/dto/HistorianRequest`、`TagNameListRequest` |
| token 获取/回退/缓存/失效 | `internal/HlsTokenManager` + `HlsConfig` |
| HTTP 细节、状态码→异常、代理 502 | `internal/HlsHttpClient` + `common/internal/http/HttpTransport`、`HttpErrors`（502 不重试不掩盖） |
| 宽表拆长表、质量码与时间映射 | `internal/HlsModels` + 根包 `HlsMapper` |
| 最新值/历史/差分的接口装配 | `internal/adapter/Hls*Adapter` |
| `RAW` 替代路径、差分/`INTERVAL_SUM` 的 BOUND 口径 | `internal/adapter/HlsHistoryAdapter`、`HlsDeltaAdapter`；原因与复核步骤见 `docs/10` §8.1 |
| 只读驱动的可写性探测 | `internal/adapter/HlsWriteAdapter`（恒抛 `TsdbUnsupportedException`） |
| 不支持能力的异常与开闭状态 | `internal/adapter/HlsSupport.unsupported` / `ensureOpen` |
| 字段大小写不敏感取值、文本工具 | `common/internal/json/JsonText.textIgnoreCase` + `common/internal/Texts` |

## 3. 认证与连接

HLS 使用 `userid` + `secretkey` 调 `POST /user/get_token`（JSON body +
`Content-Type: application/json`）换取 token，后续请求把 token 原样放入名为
`token` 的请求头（不是 `Bearer`）。部分老服务只支持 `GET` + 凭证头，
`HlsTokenManager` 会先尝试 POST JSON，失败后自动回退到原生 GET 模式。
token TTL 为 `token-expire-seconds*1000-5000` 毫秒，401 时失效并重试；也支持直接配置静态 token。

```yaml
instances:
  hls_main:
    type: hls
    base-url: https://10.10.7.177:9000
    auth:
      user-id: httpIO
      secret-key: ${HLS_SECRET}
    options:
      token-expire-seconds: 1800
```

## 4. 原生 API（不阉割）

| 原 SDK 方法 | 新原生方法 | 说明 |
| --- | --- | --- |
| `queryAllTags()` | `JSONObject queryAllTags()` | `GET /ddb/read_alltagname` |
| `getDDBTagValue(TagNameListRequest)` | `JSONObject getDDBTagValue(TagNameListRequest)` | `POST /ddb/read_ddbtagvalue` |
| `getDDBTagValue(Collection)` | `JSONObject getDDBTagValue(Collection<String>)` | 便捷重载 |
| `getHDBTagValue(HistorianRequest)` | `JSONObject getHDBTagValue(HistorianRequest)` | `POST /hdb/read_hdbtagvalue` |
| `getHDBTagValue(Date,Date,Boolean×4,Long,String...)` | `JSONObject getHDBTagValue(Instant,Instant,Long,Boolean×4,String...)` | 参数顺序为 bound/avg/min/max |
| `getHDBTagValueBound/Max/Min/Avg/All` | 同名方法，`String...` 与 `Collection<String>` 两种重载 | 共 10 个 |
| 无 | `Object rawRequest(String method,String path,Object body)` | 后续厂商扩展 |

原 SDK 的 `HistorianRequest.buildRequest` 把 `NeedQueryMAX` 与
`NeedQueryMIN` 写反；本模块的 DTO 已修正，并保留同样的字段名与 JSON 结构。

## 5. 公共层能力

`HlsClient` 声明：

- `TAG_BROWSE`（仅 `listTags` / `getTag`，不支持 nameMask、valueType、unit 过滤）
- `CURRENT_VALUE`
- `RAW_HISTORY`（用历史值接口 `interval=1` + `NeedQueryBound=true` 取每个 1 秒桶的
  `TagValue_Bound` 近似原始点；属性 `rawSource=hls_history_1s_bound`、`rawApproximate=true`；
  仅 FORWARD、FillPolicy.NONE）
- `AGGREGATED_HISTORY`（仅 AVG/MIN/MAX/BOUND，FORWARD，FillPolicy.NONE，间隔 ≥1 秒）
- `SAMPLED_HISTORY`（用 BOUND 充当桶边界值，间隔决定分桶）
- `CUMULATIVE_DELTA`（按 BOUND 分桶值相邻相减）

HLS 没有原生原始点、插值、趋势、写入、标签删除能力：`RAW` 是 1 秒 `BOUND` 桶近似，
其余不支持的能力调用时抛 `TsdbUnsupportedException`。`RAW`/`SAMPLED`/差分/`INTERVAL_SUM`
统一基于 `BOUND`，这是生产同款迂回方案；原因、适用前提与换版本复核见 `docs/10` §8.1。

### 质量码

HLS 质量码：`0=BAD`、`1=GOOD`，其他为 `UNKNOWN`。

**重要限制**：`OneTagHDBValue` 只有 `Quality_Bound`、`Quality_MAX`、
`Quality_MIN`，没有 `Quality_AVG`。因此当 `Aggregation.AVG` 时，公共层把
质量置为 `Quality.UNKNOWN`，并在
`TagSeries.attributes["qualitySource"]="hls_not_provided"` 标注，绝不猜测
GOOD。

## 6. 时间与聚合

- 请求的 `startTime`/`endTime`、`interval` 均按**秒**发送（原 SDK 字段注释
  写毫秒，但方法参数名为 `intervalBySecond`；现场需再确认）。
- 响应时间戳支持 epoch 秒/毫秒与 `yyyy-MM-dd HH:mm:ss`。
- 宽表按 `HistoryQuery.aggregation` 选择列：AVG→`TagValue_AVG`、
  MIN→`TagValue_MIN`、MAX→`TagValue_MAX`、BOUND→`TagValue_Bound`。
- 时间配置：HLS 协议字段是 epoch，`options.time.*` 不改变请求/响应数值；
  `HlsClient.timeConfig()` 返回实例配置，`parseTimestamp(String)` 按
  `response.source-zone` 解析无时区文本，`formatTimestamp(Instant)` 按
  `response.target-zone/pattern` 输出。即使当前用不上，也与 GE 保持同一套配置，
  便于现场统一展示和未来文本接口接入。
- 返回的 `Sample` 除 `timestamp` 外还带 `timestampDate` 与 `timestampText`
  （按上述配置格式化），前端可直接展示文本，无需在 JS 里换算时区。

## 7. 测试

```bash
mvn -pl tsdb-sdk-hls -am test
```

测试使用 JDK `HttpServer` 模拟 token、标签、实时、历史四个端点，覆盖认证头、
顺序回填、缺失值、质量码、AVG 质量、拒绝不支持的查询、明文 HTTP 拒绝等。

### 7.1 生产环境只读集成测试

`HlsProductionIT` 对真实 HLS 环境执行只读用例：健康检查、标签目录、
16 个现场点位的当前值、24 小时 AVG、四类聚合列、`RAW` 秒级近似、
`INTERVAL_SUM` 边缘探测、原生接口、原生逃生口。
2026-11-10 首批用例经反向代理 `http://10.10.7.155:9000` 实际执行 **7/7 通过**，
16 个点位全部有值，AVG/MIN/MAX/BOUND 各 25 点。
**2026-11-18 17:20–17:27 全量复跑 10/10 通过、0 skipped**（412 s，显式打开 `-Dhls.it.delta=true`）：
`RAW` 走 1 秒 `BOUND` 桶拿到 300 个样本（17:10:50–17:15:49），5 分钟差分 `points=13`，
`INTERVAL_SUM` 落单点、BOUND 探针 589.0/590.1/583.3/595.6，16 个点位质量码全 `GOOD`。

**2026-11-19 09:35–09:42 差分基线折叠轮复跑 10/10 通过**（438 s，`-Dhls.it.delta=true`）：
差分改为一次 `readHistory(start - 桶长, end, …)`，第 0 个桶只作基线不输出，稠密时
**1 次请求**完成；1 小时 / 5 分钟窗口输出 `points=12`（旧记录的 13 是 `limit + 1` 越界，已修正）。
只有样本数对不上或首值为空时才补 1 次批量 `BOUND` 兜底。
`readsSampledHistoryAndCumulativeDelta` 因历史查询在代理侧成本高，默认跳过，
需显式 `-Dhls.it.delta=true`：

```powershell
mvn -q -pl tsdb-sdk-hls -am test `
  "-Dtest=HlsProductionIT" `
  "-DfailIfNoTests=false" `
  "-Dhls.it=true"
```

配置从 `hls.it.*` 系统属性、`HLS_IT_*` 环境变量或 `hls-it.properties` 读取；
示例见 `hls-it.properties.example`，现场凭据文件已被 `.gitignore` 排除。
现场是 HTTP，必须设置 `allowInsecureTransport=true`；现场原始的
`connection-timeout: -1` 不能照搬，测试默认 5 秒连接、180 秒读取（全量标签目录
约 4.3 MB，30 秒会超时），重试次数默认 1。token 接口优先 POST JSON，兼容只接受
精确 `Content-Type: application/json` 的反向代理。

详细说明、点位列表和常见问题见
[docs/10-HLS生产环境集成测试.md](../docs/10-HLS生产环境集成测试.md)。
