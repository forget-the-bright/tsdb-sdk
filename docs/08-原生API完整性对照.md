# 08 · 原生 API 完整性对照

本文回答一个问题：**换用本 SDK 后，原厂商 SDK 的能力有没有被阉割？**

结论：没有。公共层（`TsdbClient`）只抽取跨厂商通用能力；每个驱动同时提供
原生层，原生层覆盖参照 SDK 的全部接口。公共层与原生层是两条并列的调用路径：

```
业务代码
├── 公共层  TsdbClient.history()/currentValues()/tagCatalog()
└── 原生层  client.unwrap(XxxNativeApi.class)
             ├── HLS   HlsNativeApi（4 类接口 + 全部重载）
             ├── GE    GeNativeApi.invoke(GeEndpoint)（92 个端点）
             └── IoTDB IotdbRestApi.query/nonQuery/insertTablet
```

原生层返回厂商原始 JSON 或原始 DTO，公共模型不参与，因此不会丢失字段、
不会改变语义。需要厂商特有参数时走原生层；需要跨库切换时走公共层。

---

## 1. HLS：14 个原方法 → 15 个新方法（含 1 个新增便捷重载）

参照 `reference/HLS_REST_SDK/.../service/HLSApiInvoker.java`。

| # | 原方法 | 新方法 | 端点 |
| --- | --- | --- | --- |
| 1 | `queryAllTags()` | `JSONObject queryAllTags()` | `GET /ddb/read_alltagname` |
| 2 | `getDDBTagValue(TagNameListRequest)` | `JSONObject getDDBTagValue(TagNameListRequest)` | `POST /ddb/read_ddbtagvalue` |
| 3 | —（新增便捷重载） | `JSONObject getDDBTagValue(Collection<String>)` | 同上 |
| 4 | `getHDBTagValue(HistorianRequest)` | `JSONObject getHDBTagValue(HistorianRequest)` | `POST /hdb/read_hdbtagvalue` |
| 5 | `getHDBTagValue(Date,Date,Boolean,Boolean,Boolean,Boolean,Long,String...)` | `JSONObject getHDBTagValue(Instant,Instant,Long,Boolean bound,Boolean avg,Boolean min,Boolean max,String...)` | 同上 |
| 6 | `getHDBTagValueBound(String...)` | `JSONObject getHDBTagValueBound(String...)` | 同上 |
| 7 | `getHDBTagValueBound(Collection<String>)` | `JSONObject getHDBTagValueBound(Collection<String>)` | 同上 |
| 8 | `getHDBTagValueMax(String...)` | `JSONObject getHDBTagValueMax(String...)` | 同上 |
| 9 | `getHDBTagValueMax(Collection<String>)` | `JSONObject getHDBTagValueMax(Collection<String>)` | 同上 |
| 10 | `getHDBTagValueMin(String...)` | `JSONObject getHDBTagValueMin(String...)` | 同上 |
| 11 | `getHDBTagValueMin(Collection<String>)` | `JSONObject getHDBTagValueMin(Collection<String>)` | 同上 |
| 12 | `getHDBTagValueAvg(String...)` | `JSONObject getHDBTagValueAvg(String...)` | 同上 |
| 13 | `getHDBTagValueAvg(Collection<String>)` | `JSONObject getHDBTagValueAvg(Collection<String>)` | 同上 |
| 14 | `getHDBTagValueAll(String...)` | `JSONObject getHDBTagValueAll(String...)` | 同上 |
| 15 | `getHDBTagValueAll(Collection<String>)` | `JSONObject getHDBTagValueAll(Collection<String>)` | 同上 |

补充：

- `Object rawRequest(String method, String path, Object body)` 可调用未来新增端点。
- `HistorianRequest.buildRequest(...)` 修正了原 SDK 的 MAX/MIN 写反缺陷；
  字段名、JSON 结构与原 SDK 保持一致。
- 原 SDK 的 `TagNameRequest`、`TagNameListRequest`、`HistorianRequest` 已在
  `nativeapi/dto` 下重建，可直接构造并传入。

---

## 2. GE：92 个端点 → `GeEndpoint` 枚举 + `invoke`

参照 `reference/GE_REST_SDK` 的 5 个 Invoker、6 个枚举。原 SDK 的每个
public static 方法都对应一个 REST 端点；新原生层把它们统一收敛为
`GeEndpoint` 的 92 个常量，由 `GeNativeApi.invoke(...)` 执行。

### 2.1 调用方式

```java
GeNativeApi nativeApi = client.unwrap(GeNativeApi.class);

// 1) 纯查询参数
Map<String,Object> query = new LinkedHashMap<>();
query.put("maxNumber", 100);
query.put("nameMask", "AI*");
JSONObject tags = JsonText.object(
        nativeApi.invoke(GeEndpoint.QUERY_TAGS_BY_PARAMS, query));

// 2) 路径变量
Map<String,Object> path = new LinkedHashMap<>();
path.put("tagNames", "AI-101,AI-102");
path.put("start", "2025-01-01T00:00:00Z");
path.put("end", "2025-01-01T01:00:00Z");
path.put("direction", 1);
path.put("count", 100);
JSONObject raw = JsonText.object(
        nativeApi.invoke(GeEndpoint.GET_RAW_DATA_BY_PATH_VARIABLE, path));

// 3) 路径 + 查询 + 请求体
JSONObject result = JsonText.object(nativeApi.invoke(
        GeEndpoint.GET_CALCULATED_BY_PATH_VARIABLE_POST,
        pathVars, queryParams, body));

// 4) 完全自定义
Object custom = nativeApi.rawRequest("GET", "/v1/tagslist", null);
```

`invoke(...)`/`rawRequest(...)` 返回 fastjson 树（`JSONObject`/`JSONArray`/标量），
需要按字段读取时用 `JsonText.object(...)`/`JsonText.array(...)` 显式收敛；
类型不符直接抛 `TsdbDataException`。
`invoke(GeEndpoint)` 用于无参端点；`invoke(GeEndpoint, Map)` 会把参数按
端点声明的 `Position` 自动放到 query 或 body。

### 2.2 端点覆盖表

| 模块 | 端点数 | 原 SDK Invoker | 覆盖内容 |
| --- | --- | --- | --- |
| Data | 22 | `DataApiInvoker`（32 个方法） | calculated 4 变体、configuration 2、create、currentvalue 3、interpolated 4、raw 4、sampled 2、trend 2 |
| Tags | 13 | `TagsApiInvoker`（13 个方法） | 列表/高级检索/按掩码、增删改、评论、属性、重命名 |
| Systems | 25 | `SystemsApiInvoker`（26 个方法） | 数据存储、DTS、DHS、服务器、机器、镜像组、性能计数器、存储、系统统计 |
| Collectors | 27 | `CollectorsApiInvoker`（28 个方法） | 实例管理、启停/暂停/恢复、状态、版本、OPC 服务器、离线列表、组件安装信息 |
| Alarms | 5 | `AlarmApiInvoker`（5 个方法） | 创建、删除、查询、备份、恢复 |
| **合计** | **92** | — | 另有 OAuth token 1 个端点，由 `GeTokenManager` 封装 |

> 方法数比端点数多，是因为原 Invoker 对同一端点提供多个 Java 重载
> （例如 calculated 的 query/path、raw 的 GET/POST）。端点才是能力边界，
> 92 个端点已全部覆盖；重载差异由 `invoke` 的参数位置处理。

### 2.3 Data 端点逐项对照

| 原方法（节选） | GeEndpoint |
| --- | --- |
| `getCalculatedByRequestParam` | `GET_CALCULATED_BY_REQUEST_PARAM` |
| `getCalculatedByRequestParamPost` | `GET_CALCULATED_BY_REQUEST_PARAM_POST` |
| `getCalculatedByPathVariable` | `GET_CALCULATED_BY_PATH_VARIABLE` |
| `getCalculatedByPathVariablePost` | `GET_CALCULATED_BY_PATH_VARIABLE_POST` |
| `configureQueryResultByRequestParam` | `CONFIGURE_QUERY_RESULT_BY_REQUEST_PARAM` |
| `configureQueryResultByPathVariable` | `CONFIGURE_QUERY_RESULT_BY_PATH_VARIABLE` |
| `createTagData` | `CREATE_TAG_DATA` |
| `getCurrentValueByRequestParam` | `GET_CURRENT_VALUE_BY_REQUEST_PARAM` |
| `getCurrentValuePost` | `GET_CURRENT_VALUE_POST` |
| `getCurrentValueByPathVariable` | `GET_CURRENT_VALUE_BY_PATH_VARIABLE` |
| `getInterpolatedByRequestParam` / Post / Path / PathPost | `GET_INTERPOLATED_*` 4 项 |
| `getRawDataByRequestParam` / Post / Path / PathPost | `GET_RAW_DATA_*` 4 项 |
| `getSampledByRequestParam` / Post | `GET_SAMPLED_*` 2 项 |
| `getTrendDataByRequestParam` / Post | `GET_TREND_*` 2 项 |

Tags/Systems/Collectors/Alarms 的逐项常量见
`tsdb-sdk-ge/src/main/java/io/github/forget_the_bright/tsdb/ge/nativeapi/GeEndpoint.java`，
每个常量都有中文 Javadoc，注明路径与 HTTP 方法。

### 2.4 与原 SDK 的差异

- 原 SDK 的 `ApiClient` 使用静态配置、同一 JVM 只能有一个实例；新原生层
  是实例对象，可同时持有多个实例，互不覆盖。
- 位号列表的编码分两条路径：公共读路径改 POST 后，请求体里的 `tagNames` 用**明文**分号拼接（`joinTagsPlain`，与旧 SDK 一致，不编码）；仅保留的遗留 GET 变体（`readRawGet`/`readAggregatedGet`/`readInterpolatedGet`/`readCurrent`）把位号放进查询串时才做 URL 编码（`encodeTags`）。其余标量 query 参数（如 `nameMask`）一律 URL 编码，避免特殊字符截断。
- 原 SDK 只校验 HTTP 200，不校验业务 `ErrorCode`；新实现对 `Data` 查询
  校验 `ErrorCode`，非 0 抛 `TsdbQueryException`。
- 原 SDK 的 OAuth token 缓存与 401 重试逻辑保留，但改为实例级。

### 2.5 公共 API → GE 端点映射（本轮 GET→POST 对齐）

公共层的读路径已统一落到 POST 端点，位号列表进请求体明文分号拼接，不受 URL 长度限制；
GET 变体保留为原生逃生口与生产对比基准。

| 公共调用 | 底层方法 | GeEndpoint | 端点路径 | HTTP | 参数位置 |
|---|---|---|---|---|---|
| `currentValues().read()` | `readCurrentRawValue` | `GET_SAMPLED_BY_REQUEST_PARAM_POST` | `/v1/datapoints/sampled`（samplingMode=1, queryModifier=1） | POST | body（明文 tagNames） |
| `history(RAW)` | `readRaw` | `GET_RAW_DATA_BY_REQUEST_PARAM_POST` | `/v1/datapoints/raw` | POST | query 标量 + body tagNames |
| `history(AGGREGATED)` | `readAggregated` | `GET_CALCULATED_BY_REQUEST_PARAM_POST` | `/v1/datapoints/calculated` | POST | query 标量（含 calculationMode/intervalMs）+ body |
| `history(AGGREGATED+INTERVAL_SUM)` | `readIntervalSum` | `GET_CALCULATED_BY_REQUEST_PARAM_POST` ×2 | `/v1/datapoints/calculated`（17 与 19，count=1、intervalMs=1） | POST | 两次请求，末值−首值 |
| `history(INTERPOLATED)` | `readInterpolated` | `GET_INTERPOLATED_BY_REQUEST_PARAM_POST` | `/v1/datapoints/interpolated` | POST | query 标量 + body |
| `history(SAMPLED)` | `readSampled` | `GET_SAMPLED_BY_REQUEST_PARAM_POST` | `/v1/datapoints/sampled`（samplingMode=2, queryModifier=1） | POST | body（明文 tagNames） |
| `deltaHistory().read()` | `readAggregated`(19) + 批量 `readRaw` | `GET_CALCULATED_*_POST` + `GET_RAW_*_POST` | `/v1/datapoints/calculated` + `/v1/datapoints/raw` | POST | 分桶末值 + 起点前基线 |
| `tagCatalog().listTags()` / `getTag()` | `listTags` | `QUERY_TAGS_BY_PARAMS` / `QUERY_TAGS_BY_PATH` | `/v1/tags`、`/v1/tags/{nameMask}/{maxNumber}` | GET | query / path |

> 聚合与采样模式号对照：`AVG=1`、`MIN=4`、`MAX=5`、`COUNT=6`、`SUM=9`(RawTotal)、
> `FIRST=17`、`LAST=19`、`INTERVAL_SUM`→17/19 双请求；`samplingMode=1` 当前值、`2` 插值采样。

---

## 3. IoTDB：REST v2 / Native / JDBC 全量能力

| 能力 | 原接口 | 新原生方法 |
| --- | --- | --- |
| 任意查询 | REST `/rest/v2/query`，native/JDBC SQL | `IotdbRestApi.query(String)`、`IotdbQueryService` 内部统一执行 |
| DDL/DML | REST `/rest/v2/nonQuery`，native/JDBC SQL | `JSONObject nonQuery(String sql)` |
| 批量写入 | REST `/rest/v2/insertTablet` | `JSONObject insertTablet(JSONObject request)` |
| 健康检查 | REST 服务根 `/ping`（实测 200 + `SUCCESS_STATUS`；`/rest/v2/ping` 是 404，
  所以 `path-prefix` 不作用于探活）；native/JDBC 用 `SHOW VERSION` | `String ping()`、`serverVersion()` |
| 标签/实时/历史 | `SHOW TIMESERIES`（列式响应需转置）、`SELECT LAST`、`SELECT m ... ORDER BY TIME` | `listTags/readCurrent/readRaw`、`IotdbQueryService.currentBatch(...)`（按 `query-batch-scope` 档位一条 SQL 带多位号，默认档为按数据库） |
| 位号路径模型 | 树模型 `root.db.device.measurement`，`FROM` 层级与表达式层级必须一致（实测错配静默返空） | `internal/sql/IotdbTagPath`：解析、白名单校验、引号规范化的唯一入口；全名称／相对名称补默认库／已加反引号三态输入 |
| 批量原语（全族） | 多列 `SELECT`/`GROUP BY`/`first_value`+`last_value`，返回列名带规范绝对路径 | `intervalBatch`/`aggregatedBatch`/`intervalSumBatch`/`deltasBatch`/`interpolateBatch`/`rawBatchCompleted`：结果以规范绝对路径为键，`rawBatchCompleted`/`interpolateBatch` 另交出参账本记录走过逐位号补采的位号 |
| 等间隔 | `GROUP BY ([s,e),间隔字面量)`，字面量单位限 ms/s/m/h/d/mo，任意 `Duration` 可用 | `IotdbQueryService.interval(...)` |
| 累计差分 | 末值分桶 + 基线相减 | `IotdbQueryService.deltas(...)` |
| 整窗累计（首末差） | `SELECT first_value(m), last_value(m)` 单窗单值 | `IotdbQueryService.intervalSum(...)` |

三种 transport 共用同一份 SQL 和结果表（`AGGREGATED` 也走 `IotdbQueryService`，不再单独
落到 REST，否则“三通道同一份 SQL”这条不变式就有洞）；`IotdbQueryService` 是新的原生查询面，
`IotdbRestApi` 仍保留原始 JSON 透传能力。`IotdbQueryService` 只负责查询编排（分块、批量、
并行），结果列的按名定位与读取拆到 `internal/query/ResultColumns`（K2 对齐内核），插值网格与累计
差分的纯数值推导拆到 `internal/query/PointMath`；逐位号与批量两条路径共用这两个内核，保证两种
形态逐点同口径。

三条实测线缆契约必须记住，它们决定原生调用怎么写：

- 服务端 `GROUP BY` 结果有 **10000 行上限**，超出返回 `code=708 Dataset row size exceeded
  the given max row size (10000)`；不带单位的裸毫秒间隔则是 `code=700 no viable alternative`。
- 路径含中文等非 ASCII 标识符时，请求必须声明 `charset=UTF-8`，否则 Jetty 直接 500 返回 HTML。
  `IotdbHttpClient` 已按 `application/json; charset=UTF-8` 发送，用 curl/Postman 手工复现时
  要自己加上，这不是 SDK 缺陷而是工具默认值差异。
- **多列 RAW 的 `LIMIT` 是「合并时间戳行数」预算，不是每位号点数**（实测 M9/G6）：同窗四列
  各要 10 个点时，密集序列可以吃满 10 行、小时级序列只剩 1 个点；不存在的路径**整列消失**，
  存在但无点的序列保留空列。`SELECT LAST` 的**应答行序也不等于请求序**（实测 M2）。
  所以原生调用直接批量发多列 RAW 会改变结果——SDK 侧的按库 RAW 必须配逐位号补采，
  最新值必须按 `Timeseries` 名回填。

与参考实现相比，新原生层没有限制 SQL 类型，也没有隐藏 `insertTablet`；
公共层的只读/写开关只作用于 `TsdbClient`，不影响原生调用。安全边界由
调用方自行负责，`IotdbSql` 的标识符校验只用于防止便捷方法拼接 SQL 时被注入。

---

## 4. 完整性验证方法

1. **端点计数**：`GeEndpoint` 常量数 = 92；分组为 Data 22、Tags 13、
   Systems 25、Collectors 27、Alarms 5。
2. **方法计数**：`HLSApiInvoker` 的 14 个 public static 方法均有同名或等价
   新方法（见第 1 节表）。
3. **枚举对照**：原 SDK 的 6 个 `*ApiEnum` 与 `GeEndpoint` 逐项对照；
   OAuth 由 `GeTokenManager` 覆盖。
4. **契约测试**：全仓 363 个离线单元测试保证映射语义（GE 线新增 GET→POST 线缆、per-tag
   `ErrorCode`/`attributes`、`SUM→9`、`INTERVAL_SUM` 的 17/19 双请求、`SAMPLED` 走 sampled
   端点、`HistoryQuery.count`/`includeQuality`、`GeDriver` 与 `GeClient` 能力集一致；IoTDB 线新增
   位号路径模型 `IotdbTagPath`（全名称／相对名称补默认库／已加反引号三态、非法引号形态拒绝）、
   `database`/`root`/`device` 三档分组与跨库拆多条、`SELECT LAST` 按 `Timeseries` 名回填、
   多列结果按列名定位 + 列数护栏、RAW/插值的逐位号补采与 `rawBatchCompleted` 留痕、
   块级失败归因 `vendorErrorScope=chunk`、任意间隔字面量、反引号逐节点引用、
   `INTERVAL_SUM` 单窗单 SQL、内存网格插值、`zone`/`shift-hour` 班次窗口、
   `require-query-service` 构造期失败、旧 `current-*` 键启动失败、per-tag `vendorError` 两态）；
   原生层用例分别位于
   `GeClientTest`（`endpointCatalogCoversAllGeModules`、`invokesNative*`）、
   `HlsClientTest`（`exposesNativeApiWithAllAggregationOverloads`、
   `nativeCurrentValueCarriesDateAndFormattedText`）、
   `IotdbClientTest`（32 项，含 `exposesRawSqlAndInsertTablet`）、`IotdbQueryServiceTest`
   （35 项，含自然日按业务时区对齐）、`IotdbSqlTest`、`IotdbTagPathTest`、`GroupingTest`、
   `SeriesKeyIndexTest`、`IotdbConfigTest`，以及门控 IT
   `IotdbProductionIT` / `IotdbMappingProductionIT` / `IotdbContractProbeIT` /
   `IotdbTierParityIT`（共 45 项，只读），
   验证 query/path/body、DTO JSON 字段、SQL 透传、三通道一致性、默认库/相对位号、日差分、
   时区与班次，以及上面两条线缆契约。
5. **Javadoc**：`docs/apidocs` 中的中文 API 文档由 `scripts/gen-javadoc.ps1`
   生成；**每个包**（全仓 25 个）的 `package-info.java` 说明职责、可见性契约、
   本包类清单与「改什么找哪个类」以及依赖方向，native 包每个
   端点和 public 方法都有中文说明。

---

## 5. 中文 Javadoc 生成

已生成聚合文档：[docs/apidocs/index.html](apidocs/index.html)。

离线环境推荐使用脚本（把所有模块源码放进同一 sourcepath，不依赖先 install）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\gen-javadoc.ps1
```

脚本产物为 `docs/apidocs/index.html`，使用 UTF-8、`zh_CN` locale、
`-Xdoclint:none`。父 pom 也配置了 `maven-javadoc-plugin:3.4.1`，在依赖模块
已安装到本地仓库后可用：

> `-Xdoclint:none` 会连「`{@link}` 解析不到」一起静音。要校验跨包链接，另跑一次
> 同样 classpath/sourcepath 的 `javadoc -Xdoclint:reference`（输出到临时目录，不覆盖
> `docs/apidocs`）：2026-11-16 包结构归位轮实测 **0 警告**。注意包私有类不会被 javadoc
> 收录，跨包 `{@link}` 只能指向 `public` 类型，包私有类要写 `{@code}`。

```bash
mvn -q -DskipTests install
mvn -q -DskipTests javadoc:javadoc
```

> 直接执行 `mvn javadoc:javadoc` 时，Maven 需要从本地仓库解析
> `tsdb-sdk-common` 等模块的 jar；未执行 `install` 会报
> `Could not find artifact ...tsdb-sdk-common:jar`，这是 Maven 多模块
> 直接调用 goal 的限制，不是源码或配置错误。

