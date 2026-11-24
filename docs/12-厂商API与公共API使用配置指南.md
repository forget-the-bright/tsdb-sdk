# 厂商 API 与公共 API 使用配置指南

> 版本：2026-11-24  
> 适用：`tsdb-sdk` 0.1.2  
> 已实现：HLS、GE、IoTDB、力控 pSpace（Web API）；pSpace 的端点映射与验证记录见
> [docs/16](16-pSpace接入与验证.md)，本文 §9 只保留配置入口。

本文回答三个问题：

1. 每个厂商的原生 API 有哪些、怎么配置、怎么调用；
2. 公共 API 有哪些、怎么配置、怎么调用；
3. 哪些能力是某厂商独有的，切换厂商时哪些能力会降级。

---

## 1. 两套调用路径

SDK 同时保留两条路径，业务代码可以只用公共层，也可以在需要厂商特有能力时下探到原生层。

```text
业务代码
  ├── 公共层：TsdbManager → TsdbClient → TagCatalog / CurrentValueReader / HistoryReader / DeltaReader / WriteSession
  └── 原生层：client.unwrap(XxxNativeApi.class) → 厂商原始方法 / 原始 JSON / 任意 SQL
```

公共层负责“换库不改业务”，原生层负责“能力不阉割”。两者共享同一个连接、认证、超时和配置。

```java
TsdbManager manager = TsdbClients.fromYaml(Paths.get("tsdb.yml"));
TsdbClient client = manager.client();

// 公共层
List<TagSample> current = client.currentValues().read(Arrays.asList("AI-101"));

// 原生层
HlsNativeApi hls = client.unwrap(HlsNativeApi.class);
JSONObject raw = hls.queryAllTags();
```

---

## 2. 公共 API

### 2.1 入口

| 类 | 用途 |
|---|---|
| `TsdbClients` | 静态门面：`fromYaml(Path)`、`fromConfig(TsdbConfig)`、`manager()` |
| `TsdbManager` | 管理实例、懒加载、`switchTo(name)`、`activeInstance()`、`close()` |
| `TsdbConfigLoader` | 读取 YAML，支持 `${ENV:default}` |
| `TsdbClient` | 一个实例的全部公共能力 |

```java
TsdbManager manager = TsdbClients.manager()
        .config(TsdbConfigLoader.load(Paths.get("tsdb.yml")))
        .discoverDrivers()   // ServiceLoader 发现 hls/ge/iotdb
        .build();

TsdbClient client = manager.client();          // 默认实例
manager.switchTo("ge_main");                    // 切到另一个实例
```

`switchTo` 先创建新客户端，成功后才关闭旧客户端；新连接失败时旧客户端仍可用。运行期只保留一个活动客户端，避免现场误连两套库。

### 2.2 能力接口

| 接口 | 方法 | 说明 |
|---|---|---|
| `TagCatalog` | `listTags(TagQuery)`、`getTag(String)` | 标签目录；不支持的条件抛 `TsdbUnsupportedException` |
| `CurrentValueReader` | `read(Collection<String>)` | 当前値，顺序与请求一致；缺失返回 `TagSample(tag, null)` |
| `HistoryReader` | `read(HistoryQuery)` | RAW/AGGREGATED/INTERPOLATED/SAMPLED/TREND 由驱动能力决定 |
| `DeltaReader` | `read(tags,start,end,granularity,aggregation,fill,direction,maxPoints)`、`readByInterval(tags,start,end,intervalMillis,aggregation,fill,direction,maxPoints)`、`intervalDeltas(...)` | 累计量差分（自然粒度 / 任意毫秒间隔） |
| `WriteSession` | `isWritable()`、`write(...)` | 默认只读，需显式开启 |
| `HealthCheck` | `check()` | 返回 `HealthStatus` |

### 2.3 数据模型

| 类型 | 关键字段 |
|---|---|
| `TagInfo` | `name`、`description`、`valueType`、`unit`、`vendorType` |
| `TagQuery` | `nameMask`、`limit`、`offset`、`valueType`、`unit` |
| `Sample` | `timestamp`（`Instant`）、`timestampDate`（`Date`）、`timestampText`（展示文本）、`valueType`、各类值、`quality`、`vendorQualityCode` |
| `TagSample` | `tagName` + `Sample` + `attributes`（per-tag `errorCode`/`errorMessage`/`dataType`/`engineeringUnits`/量程；sample 为空且 errorCode≠0 表示该位号出错） |
| `TagSeries` | `tagName` + `List<Sample>` + `truncated` + `attributes` |
| `HistoryQuery` | `tags`、`start`、`end`、`mode`、`aggregation`、`interval`、`count`、`maxPoints`、`direction`、`fill`、`partialResultAllowed`、`includeQuality` |
| `TimeGranularity` | `SECOND/MINUTE/HOUR/DAY/MONTH`，月为日历月 |
| `DeltaSeries` | `tagName` + `List<DeltaPoint>` |
| `DeltaPoint` | `timestamp`、`timestampDate`、`timestampText`、`delta`、`reset`、`missing` |

约定：

- 时间区间左闭右开 `[start, end)`；
- 空集合返回空列表，不返回 `null`；
- 缺失点位返回 `TagSample(tag, null)`，不伪造 0；
- 质量码统一为 `GOOD/UNCERTAIN/BAD/STALE/UNKNOWN`，同时保留厂商原始码；
- `Sample.timestamp` 永远是绝对时刻；`timestampText` 只是按实例时区格式化后的展示值。

### 2.4 能力枚举

`TAG_BROWSE`、`TAG_SEARCH`、`TAG_CRUD`、`CURRENT_VALUE`、`RAW_HISTORY`、`AGGREGATED_HISTORY`、`INTERPOLATED_HISTORY`、`SAMPLED_HISTORY`、`TREND`、`CUMULATIVE_DELTA`、`WRITE`、`DELETE`、`ALARM`、`SUBSCRIPTION`、`SYSTEM_ADMIN`。调用前可用 `client.capabilities().supports(...)` 判断；不支持时驱动抛 `TsdbUnsupportedException`，不会静默返回空。

### 2.5 公共配置

```yaml
tsdb:
  default-instance: hls_main
  fail-fast: true
  security:
    allow-write: false
    allow-insecure-transport: false
    verify-hostname: true
    audit-enabled: true
  instances:
    hls_main:
      type: hls
      protocol: http
      base-url: http://10.10.7.155:9000
      auth:
        user-id: httpIO
        secret-key: ${HLS_SECRET}
      timeout:
        connect-ms: 5000
        read-ms: 180000
      retry:
        max-attempts: 1
        backoff-ms: 200
      options:
        allow-insecure-transport: true
        token-expire-seconds: 43199
```

所有实例共享 `security`；实例可用 `security` 覆盖。旧版扁平键仍兼容。

---

## 3. 和利时 HLS

### 3.1 配置

```yaml
instances:
  hls_main:
    type: hls
    base-url: http://10.10.7.155:9000
    auth:
      user-id: httpIO
      secret-key: ${HLS_SECRET}
    timeout:
      connect-ms: 5000
      read-ms: 180000
    retry:
      max-attempts: 1
    options:
      allow-insecure-transport: true
      token-expire-seconds: 43199
```

认证：驱动优先 `POST /user/get_token`，请求头带 `userid`/`secretkey`，JSON body 也带同样字段，`Content-Type` 必须是精确的 `application/json`；失败后回退原 SDK 的 `GET /user/get_token` + 请求头方式。现场是 HTTP 时必须显式 `allow-insecure-transport: true`。

### 3.2 原生 API

`HlsNativeApi`：

| 方法 | 说明 |
|---|---|
| `queryAllTags()` | 全量标签，返回 `Data.TagNameList` |
| `getDDBTagValue(TagNameListRequest)` | 实时值 |
| `getDDBTagValue(Collection<String>)` | 实时值便捷重载 |
| `getHDBTagValue(HistorianRequest)` | 历史区间聚合 |
| `getHDBTagValue(start,end,interval,bound,avg,min,max,tags...)` | 历史便捷方法 |
| `getHDBTagValueBound/Max/Min/Avg/All(...)` | 各聚合列的 `String...` 与 `Collection<String>` 重载 |
| `rawRequest(method,path,body)` | 任意原生请求 |

```java
HlsNativeApi hls = client.unwrap(HlsNativeApi.class);
JSONObject tags = hls.queryAllTags();

JSONObject history = hls.getHDBTagValue(
        Instant.now().minus(24, ChronoUnit.HOURS),
        Instant.now(),
        3600L,
        true, true, true, true,
        "E101FIT1001_DACA_PV");
```

### 3.3 公共能力

- `TAG_BROWSE`：全量标签；不支持按名称掩码/类型/单位过滤。
- `CURRENT_VALUE`：单点/批量实时值。
- `RAW_HISTORY`：HLS 没有原生原始点接口，用历史值接口（`interval=1` + `NeedQueryBound=true`）取每个 1 秒桶的 `BOUND` 值近似；结果 `attributes` 带 `rawSource=hls_history_1s_bound`、`rawApproximate=true`，仅 `FORWARD`、`FillPolicy` 仅 `NONE`。
- `AGGREGATED_HISTORY`：`AVG/MIN/MAX/BOUND`，`interval` 必须 ≥1 秒，`FillPolicy` 只支持 `NONE`。
- `SAMPLED_HISTORY`：用 `BOUND` 充当每个区间的边界值；`interval` 决定分桶。
- `CUMULATIVE_DELTA`：按 `BOUND` 分桶值做相邻差。这是生产同款迂回方案（HLS 无 `LAST`/原生原始点），适用前提与换版本复核见 [docs/10 §8.1](10-HLS生产环境集成测试.md)。
- 不支持 INTERPOLATED、TREND、WRITE。

> `RAW`/`SAMPLED`/差分/`INTERVAL_SUM` 都基于 `TagValue_Bound`。厂商升级或换现场时，
> 按 docs/10 §8.1 的复核步骤重新确认 BOUND 语义；日常使用不以此作为阻塞条件。

HLS 返回的是宽表：一行包含 `TagValue_AVG/MIN/MAX/Bound` 多列。驱动按 `HistoryQuery.aggregation` 选列，不会固定取 AVG。HLS 没有 `Quality_AVG`，所以 AVG 的质量固定为 `UNKNOWN`，并在 `TagSeries.attributes["qualitySource"]="hls_not_provided"` 标注；MIN/MAX/BOUND 使用各自质量码。

---

## 4. GE Proficy Historian

### 4.1 配置

```yaml
instances:
  ge_main:
    type: ge
    base-url: https://172.16.176.9
    auth:
      username: ${GE_USER}
      password: ${GE_PASSWORD}
      client-id: historian_public_rest_api
      client-secret: ${GE_CLIENT_SECRET}
    timeout:
      connect-ms: 5000
      read-ms: 30000
    options:
      path-prefix: /historian-rest-api
      trust-all-certificates: false   # 仅内网自签名联调时临时 true
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

认证：`POST /uaa/oauth/token?grant_type=password&username=..&password=..`，用户名/密码在**查询串**（UTF-8 URL 编码），`Authorization` 头为 `clientId:clientSecret` 的 Basic 编码，请求体为空、`Content-Type` 为 `application/x-www-form-urlencoded`。自签名证书现场可临时 `trust-all-certificates: true` + `verify-hostname: false`；生产应导入根证书并恢复校验。

### 4.2 原生 API

GE 有 92 个 REST 端点，按模块分：Data 22、Tags 13、Systems 25、Collectors 27、Alarms 5，另有 OAuth 1。全部收敛到 `GeEndpoint` 枚举，用通用执行器调用，避免为 92 个端点写 92 个重复方法。

```java
GeNativeApi ge = client.unwrap(GeNativeApi.class);

// 查询类：参数放 query
JSONObject tags = JsonText.object(ge.invoke(GeEndpoint.QUERY_TAGS_BY_PARAMS,
        mapOf("maxNumber", 100, "nameMask", "AI*")));

// 路径类：参数放 path variables
JSONObject raw = JsonText.object(ge.invoke(GeEndpoint.GET_RAW_DATA_BY_PATH_VARIABLE,
        mapOf("tagNames", "AI-101",
              "start", "2026-11-01T00:00:00Z",
              "end", "2026-11-02T00:00:00Z",
              "direction", 0,
              "count", 1000)));

// 请求体类
JSONObject created = JsonText.object(ge.invoke(GeEndpoint.CREATE_TAG_DATA, body));

// 任意端点/原始请求
Object any = ge.rawRequest("GET", "/v1/tags?maxNumber=10", null);
```

`invoke(...)`/`rawRequest(...)` 返回 fastjson 树（`JSONObject`/`JSONArray`/标量），
需要按字段读取时用 `JsonText.object(...)`/`JsonText.array(...)` 收敛。

`GeEndpoint` 的每个常量都带中文说明、HTTP 方法和路径；完整清单见 [docs/08](08-原生API完整性对照.md) 与 `docs/apidocs`。

### 4.3 公共能力

- `TAG_BROWSE`：名称掩码、分页；不支持按类型/单位过滤。
- `CURRENT_VALUE`。
- `RAW_HISTORY`、`AGGREGATED_HISTORY`、`INTERPOLATED_HISTORY`。
- `SAMPLED_HISTORY`：走 POST `/v1/datapoints/sampled`（`samplingMode=2`、`queryModifier=1`）按 `interval` 取每桶末值；不再用 `calculated` 的末值近似。
- `CUMULATIVE_DELTA`：末值分桶，查询起点前移一个间隔、`count = 窗口桶数 + 1`，第 0 个桶即基线；扩展桶为空才补一次批量 raw 基线；内存相邻相减。
- 不支持 TREND、WRITE（公共层未声明；原生层可调）。

聚合映射：`AVG→1、MIN→4、MAX→5、COUNT→6、SUM→9（RawTotal 算术和）、FIRST→17、LAST→19`；`INTERVAL_SUM`（区间累计＝末−首）由 `readIntervalSum` 用 `FirstRawValue=17`/`LastRawValue=19` 双请求实现，不走单一 `calculationMode`。厂商 `SumValue=24` 是末−首区间累计而非算术和，故不映射到 `SUM`；`BOUND/RANGE` 不支持。

### 4.4 时间格式

GE 是唯一实际消费文本时间的驱动：

- 请求：按 `time.request.zone/pattern` 把 `Instant` 格式化成字符串；
- 响应：先按显式偏移解析，其次按 `time.response.source-zone` 解释无时区文本；
- 展示：`timestampText` 按 `time.response.target-zone/pattern` 输出。

铜陵项目 GE 返回 UTC、展示 GMT+8 的配置就是上面的示例。若某项目 GE 本身返回 GMT+8，把 `response.source-zone` 改成 `GMT+8` 即可，不需要改代码。

---

## 5. Apache IoTDB

### 5.1 配置

```yaml
instances:
  iotdb_main:
    type: iotdb
    base-url: http://192.168.3.199:18080
    auth:
      username: root
      password: ${IOTDB_PASSWORD}
    options:
      transport: native          # native / jdbc / rest
      rpc-port: 6667
      default-database: root.factory
      path-prefix: /rest/v2      # 只作用于查询路径；探活 /ping 在服务根
      zone: Asia/Shanghai        # 业务日历时区：自然日/月对齐与班次窗口（缺省即此值）
      shift-hour: 8              # 换班小时，0..23（缺省 8）
      require-query-service: true # 缺统一 SQL 查询服务时构造期抛异常（缺省 true）
      query-batch-size: 200          # 单条批量语句覆盖的位号数
      query-parallelism: 4           # 块内语句的并发上限
      query-batch-scope: database    # database / root / device，默认 database（同库三档双跑对数通过后翻的档）
      query-batch-row-budget: 10000  # 多列 RAW/插值语句的合并行数预算
      query-batch-completion-max: 32 # 一个块最多逐位号补采几列，超过就整块退回逐位号
      interpolation-tolerance: 0 # 插值外推容差毫秒，0 = 不外推
      interpolation-raw-limit: 200000
      tag-mapping-file: conf/iotdb-tag-mapping.properties
```

`current-batch-size` / `current-parallelism` **已改名且不保留别名**：现网配置里还留着旧键，
说明这份配置没被核对过，静默忽略就等于“上限还是旧的那个值”，因此旧键在构造期直接抛
`TsdbConfigurationException`，报错里写明替代键名。

四个批量键各自管一件事，配错的表现不同：

- `query-batch-size`：一条语句覆盖多少位号，同时受内部 `MAX_SQL_BYTES = 65536` 的字节预算
  约束（**这是客户端保守值，不是实测的服务端限制**——实测 300 个表达式、约 13 KB 的语句可过）。
  取 `1` 时任何族都退化成一位号一条语句，是逐位号归因的回退档；
- `query-batch-scope`：一条语句覆盖的范围。**当前默认是 `database`**（同库位号一条语句，
  即本轮目标形态）——这个默认是同库自对自的双跑对数换来的：同一实例、同一批位号、同一窗口，
  逐表达式 / `device` / `database` 三种配置逐序列逐点 0 容差比对，七个查询族全部 `diffs=0`
  （`IotdbTierParityIT`，逐族数字见 `docs/14` §8.3）。`device` 是改造前行为，保留为现场回退档，
  与改造前“一个设备一条 SQL”逐字节相同；`root` 跨库单语句，实测可用，不用它作默认是因为
  受限账号可能只有按库授权——**这条是保守选择，不是实测结论**；
- `query-batch-row-budget`：多列 RAW/插值语句的 `LIMIT`（合并时间戳行数）。实测多列 RAW 的
  `LIMIT` 不是“每位号几个点”，密集列会把稀疏列挤掉，所以撞预算且没取满的列必须逐位号补采；
- `query-batch-completion-max`：补采列数上限，超过则整块退回逐位号，避免“位号数 × 补采”的
  往返乘积。

`options.zone` 管分桶边界，`options.time.*` 只管文本解析与展示，两者互不影响；
把它们配成同一个时区并不是必选项，铜陵口径是 `zone=Asia/Shanghai` + `shift-hour=8`。

`tag-mapping-file` 是位号→`root.db.device.measurement` 的 properties 映射表。未命中才走
白名单清洗兜底并记 WARN；文件读不到或映射值非法时 `IotdbDriver.create` 直接抛
`TsdbDataException`，因为 IoTDB 查不存在的序列返回空结果而不是异常，坏映射表只会让
报表静默缺数。完整对照与切换门槛见 `docs/14` §5。

REST 服务需在服务端 `conf/iotdb-system.properties` 开启：

```properties
enable_rest_service=true
rest_service_port=18080
```

### 5.2 点位路径

`default-database` 用于补全相对位号：

| 传入 | 实际路径 |
|---|---|
| `device01.temperature` | `root.factory.device01.temperature` |
| `root.sdk_it.device01.flow` | 原样使用，可跨库 |
| 部分节点已带反引号的混合写法（`root.sg` 之后逐节点引用） | `root.sg.d1.temperature` |
| 相对位号且未配默认库 | 抛 `TsdbDataException` |

完整路径至少四段：`root.db.device.measurement`；是否「全名称」按节点判定（前两级必须是
`root` 与库名节点），不用字符串前缀比对。解析、白名单校验与引号规范化只有
`IotdbTagPath` 一处，同一个位号串不再被三处各查一遍。

一次请求里出现多个数据库时，**按 `query-batch-scope` 拆成多条语句**，每条只覆盖一个分组：
`FROM` 前缀与 SELECT 表达式的层级必须一致，混组会静默返回空结果（实测 M1），所以混组在
构造语句前就被拒绝，而不是让调用方把空结果当成“那段时间没数据”。

### 5.3 原生 API

```java
IotdbRestApi rest = client.unwrap(IotdbRestApi.class);
JSONObject rows = rest.query("SHOW TIMESERIES root.factory.**");
rest.nonQuery("CREATE TIMESERIES root.factory.device01.pressure WITH DATATYPE=FLOAT");
rest.insertTablet(requestNode);
rest.ping();

IotdbQueryService service = client.unwrap(IotdbQueryService.class);
String version = service.serverVersion();                           // SHOW VERSION
IotdbTransport transport = client.unwrap(IotdbConfig.class).getTransport();  // NATIVE/JDBC/REST
```

`IotdbQueryService` 三条读路径与公共 API 共用同一份 SQL——包括 `AGGREGATED` 模式，它不再单独走 REST。
本轮新增的批量入口按 `List<IotdbTagPath>` 发射，返回结果以**规范绝对路径**为键：

- `chunks(Grouping, tags)`：分块（同时受 `query-batch-size` 与字节预算约束），对已分好的一块再调
  结果不变；
- `currentBatch` / `intervalBatch` / `aggregatedBatch` / `intervalSumBatch` / `deltasBatch` /
  `interpolateBatch` / `rawBatchCompleted`：各族的批量原语，`rawBatchCompleted` 与
  `interpolateBatch` 的最后一个出参是“走过逐位号补采”的位号账本；
- 逐位号形态（`current`/`raw`/`interval`/`aggregated`/`deltas`/`intervalSum`/`interpolate`）仍可用，
  但已标 `@Deprecated` 并委托到 `Grouping.DEVICE` 的批量形态——它们是 `unwrap` 出口与
  `require-query-service: false` 回退档的依赖，不能一声不吭地改坏。

`IotdbQueryService.transport()` 已删除：它只是转发执行器的同名方法，同一事实由
`unwrap(IotdbConfig.class).getTransport()` 提供，留着就是“同一件事有两个入口、两个真相”。

### 5.4 等间隔与累计差分

```java
// 按天取末值，空桶用前值补
List<TagSeries> daily = client.history().read(HistoryQuery.builder()
        .tags("device01.flow")
        .range(start, end)
        .mode(QueryMode.SAMPLED)
        .aggregation(Aggregation.LAST)
        .interval(Duration.ofDays(1))
        .fill(FillPolicy.PREVIOUS)
        .maxPoints(1000)
        .build());

// 每天的累计流量 = 当天末值 - 前一天末值
List<DeltaSeries> deltas = client.deltaHistory().read(
        Collections.singletonList("device01.flow"),
        start, end,
        TimeGranularity.DAY,
        Aggregation.LAST,
        FillPolicy.PREVIOUS,
        Direction.FORWARD,
        1000);

// 现场更常用：最近 8 小时、每 5 分钟一个差值；count 自动算，网格锚在 begin
List<DeltaSeries> fiveMinute = client.deltaHistory()
        .intervalDeltas(Collections.singletonList("device01.flow"), end,
                8, TimeUnit.HOURS, 5, TimeUnit.MINUTES,
                FillPolicy.NONE, Direction.FORWARD, 1000);
```

`intervalDeltas` 只输出 `floor(total/interval)` 个完整间隔，时间戳取驱动返回的桶样本时间（不做起终点偏移；IoTDB `GROUP BY` 返回桶起点，HLS/GE 桶时间起终点未统一确认）；它与参考 GE SDK 的
`getHistorianIntervalValueBySampled` 不等价（不做插值、不用实时值补齐、不把 BAD 当 0、不保留小数）。
HLS 只接受整秒间隔（≥1s），GE/IoTDB 接受任意毫秒间隔；月差分仍走 `read(..., MONTH, ...)`。

驱动差异：

- **IoTDB**：任意 `Duration` 都能下推，字面量按最大单位生成（`Nd`/`Nh`/`Nm`/`Ns`/`Nms`），
  实测 `5m`、`30s`、`300000ms`、`250ms`、`1mo` 均正常分桶；**不带单位的裸毫秒数**报
  `code=700 no viable alternative`。填充只有**可用的 `SAMPLED` 路径**（`mode=SAMPLED` 且已装配
  `queryService`）能把 `FILL` 下推给 SQL，此时 `PREVIOUS/LINEAR/NULL` 均可、`ZERO` 未实现；
  其余路径（含 `RAW`、`AGGREGATED`、`INTERPOLATED`，以及 `queryService` 缺失时的 `SAMPLED`）
  遇到非 `NONE` 的填充一律抛 `TsdbUnsupportedException`（`IotdbHistoryAdapter.java:86-93`）。
  `INTERPOLATED` 的等间隔补点是 SDK 在内存里按原始点算的，不是服务端 `FILL(LINEAR)`。
  自然日/月按 `options.zone` 对齐。**服务端 `GROUP BY` 有 10000 行上限**（超出 `code=708`），
  行数只等于**桶数**、与列数无关（实测 M4/G7：3 列 × 120 桶仍是 120 行），所以把一条语句加宽到
  更多位号不会缩短可查窗口；1s 粒度约可查 2.8 小时、`30s` 约 3.5 天、`5m` 约 34 天，
  细网格长窗口必须切窗。
- **GE**：`SAMPLED` 走 POST `/v1/datapoints/sampled`（`samplingMode=2`、`queryModifier=1`）；累计差分用 `calculated` 的 `LastRawValue=19` 取每桶末值，查询起点前移一个间隔并让服务端多返回一个桶（`count = 窗口桶数 + 1`），首桶即基线；只有扩展桶为空才补一次批量 POST `raw`（Backward、count=1）。差分只支持 `Aggregation.LAST`、`FillPolicy.NONE`，不支持 `MONTH`。
- **HLS**：没有原生原始点接口。`RAW` 用历史值接口（`interval=1` + `NeedQueryBound=true`）
  取每 1 秒桶的 `BOUND` 值近似，结果 `attributes` 带 `rawSource=hls_history_1s_bound`、
  `rawApproximate=true`，仅 `FORWARD`、填充仅 `NONE`；`SAMPLED` 与差分同样用 `BOUND`
  充当桶边界值，不支持 `MONTH`；差分只接受整秒间隔。
  填充口径两条路不同：
  **history 只接受 `FillPolicy.NONE`**，传其它值抛 `TsdbUnsupportedException`
  （`HlsHistoryAdapter.java:91-92,107`，`RAW` 在 `:161`）；**差分接受 `NONE/PREVIOUS`**（`HlsDeltaAdapter.java:78`）。
  `AGGREGATED + INTERVAL_SUM` 用起点后/终点前各最多 1 分钟的 `BOUND` 探测实现整窗末−首，
  缺任一侧返回空序列，不按 0 计算；单点结果忽略 `direction`。
  以上是生产同款迂回方案，适用前提与换版本复核见 [docs/10 §8.1](10-HLS生产环境集成测试.md)。

差分的第一个桶没有基线时 `missing=true`；负差视为清零，`reset=true` 并取本桶末值。月粒度自动归整到 1 号 00:00，SQL 用 `1mo`。

### 5.5 公共能力

- `TAG_BROWSE`（SQL `SHOW TIMESERIES`）
- `CURRENT_VALUE`（`SELECT LAST`）
- `RAW_HISTORY`
- `AGGREGATED_HISTORY`
- `SAMPLED_HISTORY`（任意间隔，受服务端 10000 行上限约束）
- `INTERPOLATED_HISTORY`（SDK 内存网格 + 前后包围原始点线性内插，不用 `FILL(LINEAR)`）
- `CUMULATIVE_DELTA`
- `WRITE`（仅 `allow-write: true`）

`Aggregation.INTERVAL_SUM` 走 `SELECT first_value(m), last_value(m)` 单窗单值（末-首），
与 `CUMULATIVE_DELTA` 的逐桶差分语义不同，不可互相顶替；锚点缺失时返回 null，不伪造 0。

不支持 TREND、BOUND、RANGE，调用抛 `TsdbUnsupportedException`。IoTDB 没有质量码：
非空为 `GOOD`，空为 `UNKNOWN`；per-tag 失败原因只有在 `partialResultAllowed=true` 时才
落进 `TagSeries.attributes.vendorError`，否则整条查询抛异常。以上取舍的后果见 `docs/14` §7。

---

## 6. 能力对照

| 能力 | HLS | GE | IoTDB |
|---|---|---|---|
| 标签目录 | ✅ 全量 | ✅ 掩码/分页 | ✅ SQL |
| 当前值 | ✅ | ✅ | ✅ |
| 原始历史 | ✅ 1 秒 `BOUND` 桶近似（带来源标注） | ✅ | ✅ |
| 区间聚合 | ✅ AVG/MIN/MAX/BOUND | ✅ | ✅ |
| 插值 | ❌ | ✅ | ✅ SDK 内存网格（不外推） |
| 等间隔 | ✅ `BOUND` 列充当桶边界值 | ✅ POST sampled（samplingMode=2） | ✅ 任意间隔；服务端 GROUP BY 上限 10000 行 |
| 累计差分 | ✅ 基于 `BOUND`（生产同款迂回方案），扩窗折叠基线 | ✅ 末值 + 扩窗折叠基线（缺桶批量 raw 兜底） | ✅ 扩窗折叠基线（缺桶批量 `LAST_VALUE` 兜底） |
| 写入 | ❌ | 原生可调 | ✅ 默认关闭 |
| 质量码 | GOOD=1/BAD=0，无 AVG 质量 | 0/1/2/3 | 无（非空=GOOD，空=UNKNOWN） |

“❌”表示公共层未声明；原生层仍可能有厂商接口，调用前请查对应模块 README 与 `docs/08`。

---

## 7. 切换与降级

1. 业务代码只依赖 `TsdbClient` 与公共模型；
2. 换库时改 `tsdb.default-instance` 和驱动依赖，不重写业务；
3. 能力不一致时先 `capabilities().supports(...)` 判断，或捕获 `TsdbUnsupportedException`；
4. 原生产能差异（如 HLS 的 `RAW` 只能秒级近似、GE 无公共 delta）必须在选型时明确，不能假设“公共层会补齐”；
5. 运行期切换用 `manager.switchTo(name)`；失败会保留旧客户端。

---

## 8. 测试与验证

| 模块 | 单测 | 生产只读测试 |
|---|---|---|
| common | 77 | — |
| HLS | 20 | 10（反向代理 `10.10.7.155:9000`；新增 `RAW` 近似与 `INTERVAL_SUM`，差分需 `-Dhls.it.delta=true`） |
| GE | 28 | 10 + 对比 9（`172.16.176.9`，含等间隔/差分，另 `GeComparisonIT` 逐点一致性） |
| IoTDB | 164 | 45（`IotdbProductionIT` 11 三通道 + `IotdbMappingProductionIT` 3 时区/班次 + `IotdbContractProbeIT` 24 线缆契约与 P1–P10 批量形态探针 + `IotdbTierParityIT` 7 同库双跑对数） |
| starter | 1 | — |
| 合计 | **290** | **74** |

```bash
mvn test

mvn -pl tsdb-sdk-ge -am test "-Dtest=GeProductionIT" \
  "-DfailIfNoTests=false" "-Dge.it=true"

mvn -pl tsdb-sdk-hls -am test "-Dtest=HlsProductionIT" \
  "-DfailIfNoTests=false" "-Dhls.it=true"

# 差分用例历史查询成本高、默认跳过；需要时追加 "-Dhls.it.delta=true"

mvn -pl tsdb-sdk-iotdb -am test \
  "-Dtest=IotdbProductionIT,IotdbMappingProductionIT,IotdbContractProbeIT,IotdbTierParityIT" \
  "-DfailIfNoTests=false" "-Diotdb.it=true"
```

> **门控复跑提示（2026-11-18）**：`IotdbProductionIT` 的默认位号会随数据老化而变化——
> 默认库 `root.factory` 下 `device01.temperature` 近 7 天可能已无原始点，其 raw 断言会
> 环境性失败；在 `iotdb-it.properties` 把 `tag` 指到存活位号（本轮用
> `root.factory.device02.temperature`）即可三通道全绿。
> 这不是 SDK 回归，详见 [docs/14](14-GE迁移IoTDB对照与替换方案.md) §8.3 的复跑记录。
>
> 本轮三库均已实跑：IoTDB 45/45（27.4 s）、GE 19/19（32.9 s）、HLS 10/10（412 s，含差分门控）。

生产测试凭据放在被 `.gitignore` 排除的 `ge-it.properties` / `hls-it.properties` /
`iotdb-it.properties`，不写入仓库。IoTDB 的键为 `baseUrl`、`username`、`password`、
`rpcPort`、`transport`、`defaultDatabase`、`tag`、`tags`，读取优先级为
「系统属性 `iotdb.it.<key>` → 环境变量 `IOTDB_IT_<KEY>` → `iotdb-it.properties`」，
测试类内不再有硬编码地址或账号。

---

## 9. 力控 pSpace（已实现，Web API）

`tsdb-sdk-pspace`，`type: pspace`，走 pSpace Web API（REST/JSON）。要点：

- 读接口在 7.1 必须 **POST + JSON body**（手册写 GET，实测会 `Parse Json data failed`）；
- 响应用 `{code,mesg,data}` 信封，`code=1` 表示范围内无数据；
- `options.pspace.charset` 默认 `gbk`；配置 `auth` 会校验失败（账号在服务端 ini）；
- 缓存未热时驱动自动 POST `/Refresh` 后重试一次（`auto-refresh` + `refresh-min-interval-ms`）；
- `archived=1`（zlib）、非整秒聚合间隔、非 `NONE` 填充、`MONTH` 粒度差分均抛
  `TsdbUnsupportedException`。

完整端点映射、类型/质量/时间表、IT 门禁与未执行项见
[docs/16](16-pSpace接入与验证.md)；配置模板见 [tsdb.example.yml](../tsdb.example.yml)。


