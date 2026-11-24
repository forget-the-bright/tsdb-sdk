# tsdb-sdk-iotdb —— Apache IoTDB 驱动

## 1. 模块定位

本模块把 IoTDB **Java 原生 Session、JDBC、REST v2** 三种访问方式统一到同一套
SQL 语义和公共模型上，由实例配置 `options.transport` 切换：

- `native`：`SessionPool`，RPC 端口默认 `6667`，适合长期运行和高吞吐；
- `jdbc`：`jdbc:iotdb://host:6667/`，适合已有 JDBC 生态；
- `rest`：`POST /rest/v2/query`，HTTP 端口默认 `18080`，适合只开放 HTTP 的现场。

三种通道执行同一份 SQL，返回同一种 `IotdbResultTable`，再映射为公共
`Sample/TagSeries/DeltaSeries`。公共层只暴露统一接口；原生能力仍可通过
`client.unwrap(IotdbRestApi.class)` 或 `client.unwrap(IotdbQueryService.class)` 取出。

## 2. 目录结构

按 2026-11-16 实测绘制：主代码 **36 个类**，分布在 6 个包，每个包都有 `package-info.java`
（职责 + 可见性契约 + 类清单 + 依赖方向）。

```
src/main/java/io/github/forget_the_bright/tsdb/iotdb
├── IotdbClient.java                    门户：装配适配器、分派能力、unwrap 逃生口
├── IotdbConfig.java                    配置（transport、地址、认证、超时、默认库、批量、时区/班次）
├── IotdbDriver.java                    SPI 入口（type=iotdb）
├── IotdbMapper.java                    查询结果 → 公共模型（质量码/值类型/时间）
├── IotdbTransport.java                 NATIVE / JDBC / REST
└── internal
    ├── IotdbModels.java                厂商 DTO ↔ 公共模型换算（四个子包共用，故留本层根包）
    ├── adapter/                        门户装配层：public 但**非公共 API**
    │   ├── IotdbSupport.java           共享配置/查询服务/执行器/开闭状态/异常上下文
    │   ├── IotdbSeriesAssembler.java   结果表 → Series/Point 序列组装
    │   ├── IotdbTagCatalogAdapter.java     位号目录浏览
    │   ├── IotdbCurrentValueAdapter.java   最新值（含批量）
    │   ├── IotdbHistoryAdapter.java        历史模式分派
    │   ├── IotdbDeltaAdapter.java          累计量差分
    │   └── IotdbWriteAdapter.java          写入（默认只读，需 tsdb.security.allow-write）
    ├── sql/                            位号 → 路径 → SQL 文本，不发请求
    │   ├── IotdbTagPath.java           解析/校验/逐节点反引号（模块内唯一入口）
    │   ├── IotdbTagResolver.java       映射表优先 + 默认库补全 + 兜底清洗计数报警
    │   ├── TagPathMapper.java          可插拔映射（properties 装载，启动期预解析）
    │   ├── Grouping.java               批量档位 DATABASE / ROOT / DEVICE
    │   └── IotdbSql.java               SQL 构造（只接 IotdbTagPath）与标识符白名单
    ├── query/                          查询语义与编排（9 个包私有类整簇同包，不可拆散）
    │   ├── IotdbQueryService.java      统一查询门户，unwrap(IotdbQueryService.class) 入口
    │   ├── IotdbQueryCore.java         分块/并行/异常包装/列对齐/RAW 补采判定
    │   ├── IotdbCurrentQueries.java    批量与单点 LAST
    │   ├── IotdbRawQueries.java        原始点 + 逐位号补采
    │   ├── IotdbSampledQueries.java    等间隔分桶与聚合历史
    │   ├── IotdbAggregationQueries.java 整窗增量与目录类查询
    │   ├── IotdbInterpolationQueries.java 固定网格线性插值
    │   ├── IotdbDeltaQueries.java      分桶末值 + 起点前基线（每块 2 次往返）
    │   ├── PointMath.java              插值网格/差分/reset/missing 的纯内存计算
    │   ├── ResultColumns.java          结果列按名定位与读取（K2 对齐内核）
    │   └── SeriesKeyIndex.java         按规范序列名回填行（K1，只做精确匹配）
    └── transport/                      三通道执行与 REST 原生客户端
        ├── IotdbQueryExecutor.java     通道接口：一条 SQL → 一张结果表
        ├── IotdbNativeQueryExecutor.java   SessionPool（RPC 6667）
        ├── IotdbJdbcQueryExecutor.java     jdbc:iotdb://host:port/
        ├── IotdbRestQueryExecutor.java     REST 列式 JSON → 结果表
        ├── IotdbRestApi.java           原生 REST：query/nonQuery/insertTablet/ping
        ├── IotdbHttpClient.java        Hutool HttpTransport + Basic Auth + 状态码映射
        └── IotdbResultTable.java       三通道统一结果表（时间列单独承载）
```

测试镜像同一套包路径（`internal/sql`、`internal/query`、`internal/transport`），
门控只读集成测试在 `iotdb/it`：`IotdbProductionIT`、`IotdbMappingProductionIT`、
`IotdbContractProbeIT`、`IotdbTierParityIT`（`-Diotdb.it=true` 才跑）。

### 2.1 按任务找类

| 我要改什么 | 去哪儿 |
|---|---|
| SQL 文本、语法、间隔字面量 | `internal/sql/IotdbSql` |
| 位号解析、字符白名单、反引号引用 | `internal/sql/IotdbTagPath` |
| 位号→路径映射、默认库、兜底报警 | `internal/sql/IotdbTagResolver` + `TagPathMapper` |
| 批量档位（`FROM` 与表达式层级同源） | `internal/sql/Grouping` + `options.query-batch-scope` |
| 分块大小、并行度、往返次数 | `internal/query/IotdbQueryCore` + `options.query-batch-size` / `query-parallelism` |
| 最新值顺序、缺数 `null` 占位 | `internal/query/IotdbCurrentQueries` + `SeriesKeyIndex` |
| RAW 点数被密集位号挤掉、补采 | `internal/query/IotdbRawQueries` + `IotdbQueryCore.rawShapedBatch` |
| 等间隔/自然粒度起点对齐 | `internal/query/IotdbSampledQueries` + `common/model/TimeGranularity.normalizeStart`（zone 取自 `IotdbConfig.getZone()`） |
| 整窗增量（`INTERVAL_SUM`） | `internal/query/IotdbAggregationQueries` + `PointMath.intervalSumOf` |
| 插值网格与首点对齐 | `internal/query/IotdbInterpolationQueries` + `PointMath.gridOf` |
| 差分、计数器归零 `reset`、缺数 `missing` | `internal/query/IotdbDeltaQueries` + `PointMath.deltaSeries`；装配在 `internal/adapter/IotdbDeltaAdapter` |
| 结果列错位、列数护栏 | `internal/query/ResultColumns` |
| 序列形状、时间戳与质量码落点 | `internal/adapter/IotdbSeriesAssembler` + 根包 `IotdbMapper` |
| 通道切换、连接、认证、超时 | `internal/transport/*QueryExecutor` + `IotdbHttpClient` + `IotdbConfig` |
| REST 原生端点与原始 JSON | `internal/transport/IotdbRestApi`（经 `unwrap`） |
| 写入开关与写入语义 | `internal/adapter/IotdbWriteAdapter` + `tsdb.security.allow-write` |
| 能力声明、不支持异常的上下文 | `IotdbClient` + `internal/adapter/IotdbSupport` |
| 时区/班次口径 | `IotdbConfig`（`options.zone` / `options.shift-hour`）+ `common/config/TimeConfig` |
| HTTP/JSON/文本通用工具 | `common/internal/http`、`common/internal/json`、`common/internal/Texts` |

## 3. 配置

```yaml
instances:
  iotdb_main:
    type: iotdb
    base-url: http://192.168.3.199:18080   # REST 必填；native/JDBC 只取主机名
    auth:
      username: root
      password: ${IOTDB_PASSWORD}
    options:
      transport: native          # native / jdbc / rest，默认 rest
      rpc-port: 6667             # native/JDBC 的 RPC 端口
      default-database: root.factory
      path-prefix: /rest/v2      # REST 查询路径前缀（探活 /ping 在服务根，不带此前缀）
      zone: Asia/Shanghai        # 业务日历时区：自然日/月分桶对齐与班次窗口
      shift-hour: 8              # 换班小时（0..23），配合 zone 供 shiftWindow() 使用
      require-query-service: true # 缺统一 SQL 查询服务时构造期抛异常，不静默降级
      query-batch-size: 200      # 单条 SQL 携带的位号表达式上限
      query-parallelism: 4       # 块间并行上限
      query-batch-scope: database # database / root / device：FROM 用库、根还是设备（默认 database）
      query-batch-row-budget: 10000     # RAW/插值批量单条 SQL 的行数预算（= 服务端上限）
      query-batch-completion-max: 32    # 块内最多允许多少位号走逐位号补采，超过整块退回逐位号
      interpolation-tolerance: 0 # 插值网格允许的外推毫秒数，0 表示不外推
      interpolation-raw-limit: 200000  # 单次插值拉取包围原始点的上限
      tag-mapping-file: conf/iotdb-tag-mapping.properties  # 可选，装载失败即启动失败
```

> `current-batch-size` / `current-parallelism` 这两个旧键**已不再接受**（不留别名）：现网配置里
> 还写着旧键的，启动即抛 `TsdbConfigurationException`，而不是被静默忽略后按新默认值跑。
>
> `query-batch-scope` 的默认值是 `database`，也就是本轮的目标形态。这个默认是**同库自对自的
> 双跑对数换来的**：同一实例、同一批位号、同一窗口，`query-batch-size: 1`（逐表达式，等价于
> 改造前的逐位号取数）、`device`、`database` 三种配置各跑一遍，逐序列逐点 0 容差比对，
> 最新值 / RAW / 等间隔 / 聚合 / 整窗增量 / 差分 / 插值七个族全部 `diffs=0`
> （`IotdbTierParityIT`，实测数字记在 docs/14 §8.3）。`device` 保留为现场回退档，
> 它与改造前“一个设备一条 SQL”逐字节相同。单条 SQL 的表达式个数还同时受内部常量
> `MAX_SQL_BYTES = 65536` 约束，**那是客户端的保守预算，不是实测的服务端限制**。
>
> 注意区分：**GE ↔ IoTDB 的业务级对数（映射表 + 两边同窗取数，A/B/C/D 归因）仍未执行**，
> 那是切换的硬门槛，与上面这个决定默认档位的对数不是同一件事。

`options.zone` 与 `options.time.*` 是两件事：前者决定分桶边界和班次窗口，后者只影响
`timestampText` 的解析与展示；两者都改成 UTC 也不会改变 epoch 毫秒值。

`tag-mapping-file` 每行一条 "业务位号 = root.db.device.measurement"；未命中的位号才走
白名单清洗兜底（记 WARN 并计数）。装载失败（文件不可读、映射值不是完整 `root.` 路径）
在 `IotdbDriver.create` 阶段抛 `TsdbDataException`——IoTDB 查不存在的序列只返回空结果，
带着坏映射表启动会让报表静默缺数。

`base-url` 仍必填：REST 直接使用；native/JDBC 从 URL 中取主机名，端口由
`rpc-port` 指定。三个通道共用同一个实例级 `TimeConfig`、超时、重试与安全开关。

REST 服务默认关闭，需要在 `conf/iotdb-system.properties` 中启用：

```properties
enable_rest_service=true
rest_service_port=18080
```

认证使用 HTTP Basic（`Authorization: Basic base64(user:password)`）；`/ping`
不需要认证。

## 4. 点位路径与默认数据库

IoTDB 序列路径必须是 `root.数据库名.后续节点`（`root` 之后的数据库名是固定第二级）。
本模块里**位号文本只有一个解析入口** `internal/sql/IotdbTagPath`：解析一次、校验一次、
按节点重新引用一次，`FROM` 前缀与 SELECT 表达式都由同一个对象派生。

| 调用方传入 | 实际 SQL 路径 | 说明 |
|---|---|---|
| `device01.temperature` | `root.factory.device01.temperature` | 相对位号补 `default-database` |
| `root.sdk_it.device01.flow` | `root.sdk_it.device01.flow` | 完整路径原样采用，可跨库 |
| `` sg.`d1`.temperature `` | `` root.`factory`.`d1`.`temperature` `` | 已加反引号的文本先解析成节点，再按规范化形态重发 |
| `device01.temperature`（未配默认库） | 抛 `TsdbDataException` | 不猜库名 |

规则：

- **是否全名称按节点判断**：首节点是 `root` 且第二节点等于配置的数据库名节点才算本库全名，
  不用 `startsWith("root." + 库名)` 这种字符串前缀——否则 `root.factoryx.d.m` 会被误认成
  `root.factory` 库的位号；
- 完整路径至少四段：`root.db.device.measurement`；
- **`FROM` 的层级与 SELECT 表达式的层级必须一致**：`FROM root.factory` 配
  `` `RL`.`FCS0107`.`TI-1071815_PV` ``，`FROM root` 才带库名节点。层级错配时服务端
  **静默返回空结果、不报错**，所以这两侧不允许各拼各的；表达式里出现 `root.` 会被服务端
  以 `code=701` 拒绝；
- 首节点 `root` 恒裸写不加引号，其余节点逐个加反引号；节点文本经标识符白名单校验，
  引号、分号、空白、通配符等元字符抛 `TsdbDataException`；
- 多位号分块与 `FROM` 用什么前缀由 `query-batch-scope` 决定（库 / 根 / 设备三档），
  跨库的位号会拆成多条 SQL，不会硬塞进一条语句；
- 返回的 `TagSample/TagSeries/DeltaSeries` 保留调用方传入的原始位号名。

两条硬限制，如实声明：

- **测点名真含反引号的位号不支持**：反引号在本模块里是语法不是内容。未闭合的引号、闭引号
  之后既不紧跟 `.` 也不是字符串结尾的输入（那等于两段引号中间漏了点号）一律抛
  `TsdbDataException`，不做“连续两个反引号表示一个字面反引号”的猜测性解析——实测没钉过，
  猜了就是装作一致；
- **逗号/分号拼接的多位号串直接抛 `TsdbDataException`**，拆分由调用方负责——驱动从来没有
  拆开过这种串，不要指望它替业务做这件事。

## 5. 原生 API（不阉割）

### 5.1 REST 原生客户端

| 方法 | 说明 |
|---|---|
| `JSONObject query(String sql)` | 执行任意查询 SQL，返回原始列式 JSON |
| `JSONObject nonQuery(String sql)` | 执行 DDL/DML（`SET`、`CREATE`、`DELETE` 等） |
| `JSONObject insertTablet(JSONObject request)` | 直接提交 `insertTablet` 请求体 |
| `String ping()` | 健康检查 |
| `listTags/readCurrent/readRaw/readAggregated/write` | 类型化便捷方法 |

```java
IotdbRestApi nativeApi = client.unwrap(IotdbRestApi.class);
JSONObject rows = nativeApi.query("SHOW TIMESERIES root.tongling.**");
JSONObject result = nativeApi.nonQuery(
    "CREATE TIMESERIES root.tongling.device1.temperature WITH DATATYPE=FLOAT");
```

### 5.2 统一查询服务

`IotdbQueryService` 对三种 transport 暴露同一组 SQL，方法分逐位号与批量两层。

逐位号入口（`unwrap` 可达，普通 public 方法，**未标 `@Deprecated`**）：
`raw(path, start, end, direction, limit)`、
`interval(path, start, end, 粒度或 stepMillis, aggregation, fill)`、
`aggregated(...)`、`intervalSum(path, start, end)`、
`deltas(path, start, end, granularity, fill, direction, maxPoints)`、
`interpolate(path, gridStart, stepMillis, slots, toleranceMillis, rawLimit)`、
`listTags(prefix)`、`serverVersion()`、`close()`。

取当前值**只有批量入口** `currentBatch(List<IotdbTagPath>)`，没有逐位号的 `current(path)`；
单个位号传一个单元素 `List` 即可，返回 `List` 与入参同序、缺数为 `null`。
原 `transport()` 已删除——它是纯转发，同一事实由 `unwrap(IotdbConfig.class).getTransport()` 提供。

批量入口返回 `Map<位号绝对路径, 序列>`，键**覆盖全部入参位号**（缺数的给 `null` 点占位）：

| 方法 | 每条 SQL 的形态 | 往返 |
|---|---|---|
| `currentBatch(List<IotdbTagPath>)` | `SELECT LAST E(p1), E(p2) FROM root.db` | `ceil(块内位号 / query-batch-size)` |
| `intervalBatch` / `aggregatedBatch` | 多列 `GROUP BY ([s,e),<lit>) [FILL(...)]` | 同上一块一条 |
| `intervalSumBatch` | `SELECT first_value(E(p)), last_value(E(p)), …`（无 GROUP BY，1 个逻辑行） | 同上一块一条 |
| `deltasBatch` | 主查询 1 条 `GROUP BY`（起点前移一个间隔，首桶即基线）；扩展桶未命中才补 1 条 `SELECT LAST_VALUE(E(p)), … WHERE time < start` | 每块 **1 条**，缺桶 2 条 |
| `rawBatchCompleted` | 多列 raw + union `LIMIT`，欠采列逐位号补采 | 见 §7 |
| `interpolateBatch` | 窗内多列 raw + 左右包围点多列 raw + 补采 | 每块 3 条起 |
| `chunks(grouping, tags[, batchSize])` | 不查库，只给出分块 | — |

`chunks(...)` 是各族共用的分块入口，同时受 `query-batch-size`（表达式个数）与内部常量
`MAX_SQL_BYTES = 65536`（UTF-8 字节预算）约束；两个批量方法的 `completed` 出参回写走过
逐位号补采的位号，公共层把它落成 `TagSeries.attributes["rawBatchCompleted"]`。

SQL 注入防护：位号节点白名单在 `IotdbTagPath`（字母含中文、数字与 `_ : - + / # % @ ( ) [ ] ~`，
长度 ≤ 512），`SHOW TIMESERIES` 掩码白名单在 `IotdbSql.isValidMask`（额外允许 `*`、`?`）。
引号、反引号本身、分号、反斜杠、空白、控制字符、`$`、花括号、逗号都不在放行集内，被反引号
包裹后无法闭合逃逸；非法输入抛 `TsdbDataException`。

## 6. 公共层能力

`IotdbClient` 声明：

- `TAG_BROWSE`（支持 nameMask、limit、offset、valueType；不支持 unit）
- `CURRENT_VALUE`（`SELECT LAST`）
- `RAW_HISTORY`
- `AGGREGATED_HISTORY`（AVG/MIN_VALUE/MAX_VALUE/SUM/COUNT/FIRST_VALUE/LAST_VALUE，
  以及 `INTERVAL_SUM`——整窗单点末值减首值，走 SELECT first_value/last_value 专用 SQL，
  与 `CUMULATIVE_DELTA` 的逐桶差分是两套语义，不可互相顶替）
- `SAMPLED_HISTORY`（等间隔，`GROUP BY ([s,e),间隔字面量)`；字面量由 `Duration` 生成，
  单位限 ms/s/m/h/d/mo，`5m`、`30s`、`300000ms` 均可，不带单位的裸毫秒数会被服务端拒绝）
- `INTERPOLATED_HISTORY`（SDK 内存网格 start + i*step，前后包围原始点线性内插，
  **不依赖** `FILL(LINEAR)`：后者只在已生成的桶之间填，不保证点数）
- `CUMULATIVE_DELTA`（累计量差分）
- `WRITE`（仅 `security.allow-write=true` 时声明并开放）

`SAMPLED_HISTORY` 的公共入口：

```java
List<TagSeries> series = client.history().read(HistoryQuery.builder()
        .tags("device01.flow")
        .range(start, end)
        .mode(QueryMode.SAMPLED)
        .aggregation(Aggregation.LAST)
        .interval(Duration.ofDays(1))
        .fill(FillPolicy.PREVIOUS)
        .maxPoints(1000)
        .build());
```

累计量差分：

```java
List<DeltaSeries> deltas = client.deltaHistory().read(
        Collections.singletonList("device01.flow"),
        start, end,
        TimeGranularity.DAY,
        Aggregation.LAST,
        FillPolicy.PREVIOUS,
        Direction.FORWARD,
        1000);
```

差分语义：每个桶取末值，减去上一个桶末值；第一个桶用起点前的最后一个值作
基线，没有基线时 `missing=true`；计数器回绕/清零导致负差时 `reset=true`，
该桶直接取本桶末值。月粒度用 `TimeGranularity.MONTH`，起点自动归整到当月
1 日 00:00。

不支持 `TREND`、`BOUND`、`RANGE`；调用时抛 `TsdbUnsupportedException`，不静默返回空。
`FillPolicy.ZERO` 在 IoTDB 侧未实现，需要补点请用 `INTERPOLATED` 或 `FillPolicy.PREVIOUS`。

## 7. 查询语义

- 位号表达式相对 `FROM` 前缀（§4），三种 `query-batch-scope` 档位生成的只是**前缀层级**差别：
  `database` → `FROM root.db` + 库相对表达式，`root` → `FROM root` + 带库名表达式，
  `device` → `FROM root.db.device` + 裸测点。
- 原始查询：`SELECT E(p1), E(p2) FROM <前缀> WHERE time >= s AND time < e ORDER BY TIME ... LIMIT n`。
- 聚合查询：`SELECT <FN>(E(p)) FROM <前缀> WHERE ... GROUP BY ([s,e),间隔字面量) ...`，
  字面量由 `IotdbSql.intervalLiteral(millis)` 按最大单位生成（Nd/Nh/Nm/Ns/Nms）。
- 等间隔：`GROUP BY ([s,e),1h)`，空桶按 `FILL(PREVIOUS/LINEAR/NULL)` 处理；
  **行数只等于桶数、与列数无关**（实测 M4/G7），`FILL(PREVIOUS)` 按列独立填充不串列，
  所以加宽列数不吃行预算；但总行数仍受服务端上限 10000 约束（超出报 code=708），
  细网格长窗口必须切窗。
- 批量最新值：一条 `SELECT LAST` 覆盖一块位号，返回的 `Timeseries` 列是规范绝对路径，
  据此**按名回填**成调用顺序（实测响应行序 ≠ 请求顺序，绝不按列序对齐）；服务端对缺数
  序列**整行丢弃、不占位**，驱动补 `null` 以保持长度与顺序恒等于入参（36 处按下标取值的
  业务代码依赖这一不变式）。
- 多列族（等间隔/聚合/整窗增量/差分基线/RAW）按**列名**定位，列名形如
  `last_value(root.db.device.measurement)`，去掉函数包裹后按绝对路径比对（函数名大小写
  按 SQL 原样回显，不参与判定）；只有列数恰好等于发射表达式数时才允许按下标兜底。
  列数不符、或结果里混进本次没请求的路径列时抛 `TsdbQueryException`，**不做就近匹配**：
  实测不存在的位号会让整列消失（G2），消失列在中间时按位置对齐就是把别人的值挂到这个
  位号名下。
- RAW 的批量必须配补采：多列 RAW 的 `LIMIT` 是**合并时间戳后的行数预算**，同窗内密集位号
  会把稀疏位号挤出结果集（实测 M9/G6，10 个点只剩 1 个）。驱动先按块发一条多列 SQL，
  撞预算且该列取数不足时对这位号发逐位号 SQL 补齐；需补采的列数超过
  `query-batch-completion-max` 时整块退回逐位号。**这是为了让批量后的结果与逐位号逐点
  相同，不是因为批量更快**——密集采样 × 多位号 × 大 `maxPoints` 的窗口往返下界不变。
- 一块位号共用一条 SQL，一个服务端错误就影响块内每一位号：`partialResultAllowed=true` 时
  块内每个位号都写同样的 `vendorError`，并带 `vendorErrorScope=chunk` 与失败 SQL；
  块内只有 1 个位号时不标 `chunk`。
- 时间统一为 epoch 毫秒；区间左闭右开。
- `maxPoints` 通过多查一条判断截断，返回 `TagSeries.truncated=true`。
- IoTDB 没有质量码：非空值为 `GOOD`，空值为 `UNKNOWN`。
- 返回的 `Sample` 除 `timestamp` 外还带 `timestampDate` 与 `timestampText`，
  前端可直接展示文本，无需在 JS 里换算时区。

## 8. 写入

默认只读。只有显式配置：

```yaml
security:
  allow-write: true
```

`IotdbClient.writeSession().isWritable()` 才为 `true`。写入走
`insertTablet`，按设备分组、按测量列组织 `values`，返回成功条数。
生产环境建议在公共层做限流与审计。

## 9. 测试

```bash
mvn -o test
```

L1 离线单测：reactor 363 个（common 82、hls 20、ge 28、pspace 68、spring-boot-starter 1、**iotdb 164**）。
本模块 164 个的分解：`IotdbQueryServiceTest` 39、`IotdbClientTest` 32、`IotdbSqlTest` 23、
`IotdbTagPathTest` 24、`IotdbTagResolverTest` 17、`GroupingTest` 9、`IotdbConfigTest` 8、
`IotdbRestQueryExecutorTest` 6、`SeriesKeyIndexTest` 6。

覆盖面除既有的标签分页与过滤、当前值顺序与缺失、RAW/AGGREGATED/SAMPLED SQL、差分
基线/reset/缺失桶、月对齐、默认库与相对位号、拒绝不支持能力、默认写禁用、明文 HTTP 拒绝、
`unwrap` 之外，新增：位号文本三态解析与拒绝全表（未闭合引号、闭引号后漏点号、连续点、
通配符、超长、`root.factoryx` 边界）、三档分组与各批量族的 SQL 文本、`query-batch-size: 1`
与 `query-batch-scope: device` 两个回退档可复现今天行为、K1 乱序回填 + 缺行补 `null`、
K2 列数护栏（列数不符或混进未请求的列即抛）、RAW 补采三条分支与整块退回、
整窗增量按函数名成对配对、批量后块级失败归因、旧 `current-*` 键启动失败。

生产只读测试（默认跳过）：

```bash
mvn -o -pl tsdb-sdk-iotdb -am test \
  "-Dtest=IotdbProductionIT,IotdbMappingProductionIT,IotdbContractProbeIT,IotdbTierParityIT" \
  "-DfailIfNoTests=false" \
  "-Diotdb.it=true"
```

连接配置与 HLS/GE 同口径：四个 IT 类都经 `IotdbItConfig` 读取，优先级为
「系统属性 `iotdb.it.<key>` → 环境变量 `IOTDB_IT_<KEY>` → 模块根 `iotdb-it.properties`
（回退仓库根同名文件）」，可用 `iotdb.it.config` / `IOTDB_IT_CONFIG` 指向任意 properties 文件。
必填键为 `baseUrl`、`username`、`password`；可选键 `rpcPort`、`transport`、
`defaultDatabase`、`tag`、`tags`。**测试源码内没有硬编码的现场地址或账号**，
`iotdb-it.properties` 被 `.gitignore` 排除，不进仓库。

门控用例共 45 个：`IotdbProductionIT` 11（`rest/native/jdbc` 三通道各验证当前值、标签、
RAW、1 小时等间隔和小时差分 = 9 项，另加 native 单通道的多位号批量 RAW 1 项与 `SHOW VERSION` 1 项）、
`IotdbMappingProductionIT` 3（`root.sdk_it` 的 7 天模拟数据，
验证默认库、相对位号、日等间隔和日差分）、`IotdbContractProbeIT` 24（P1–P10 契约探针：
库级 `SELECT LAST` 三通道、两种引号形态、多列 `GROUP BY + FILL`、聚合列名取值、基线是否丢列、
无 `GROUP BY` 的成对首末值、跨库 200 表达式、多列 raw 的 union LIMIT 与丢列、
多列 `ORDER BY TIME DESC LIMIT`、`FROM root` 在 native/JDBC 的可用性）、
`IotdbTierParityIT` 7（同库自对自的双跑对数，见下）。

**2026-11-13 对 `192.168.3.199:18080` 三次实跑，全部绿、0 skipped**：

| 时刻 | 范围 | 默认档位 |
|---|---|---|
| 16:05 | 38 个（三通道生产用例 + 默认库映射 + 24 项契约探针） | 仍是 `device`（改造后复跑） |
| 16:12 | 7 个 `IotdbTierParityIT` 双跑对数 | 仍是 `device` |
| 16:19 | 45 个整体复跑 | 已翻成 `database` |

**2026-11-16 16:50 包结构归位轮（纯结构移动）复跑：45/45 全绿、0 skipped**，默认档位 `database`。
默认位号 `root.factory.device01.temperature` 本次在 now−30d 窗内有原始点
（三通道 raw=4 / hourly=200 / deltas=169，tags=8、current=25.6），**不需要 `-Diotdb.it.tag` 覆盖**；
七族双跑对数仍 `diffs=0`（`rawBatchCompletedColumns`：raw=1、interpolated=2）。

**2026-11-18 17:29 连接配置统一后首跑：45/45 全绿、0 skipped**（27.4 s）。四个 IT 类已全部改走
`IotdbItConfig`，类内不再有地址/账号；本次 `iotdb-it.properties` 指定
`tag=root.factory.device02.temperature`，三通道 raw=1 / hourly=200 / deltas=169、
`serverVersion=2.0.11`，`IotdbTierParityIT` 七族仍 `diffs=0`（raw 916 点、插值 91 槽、等间隔 222 槽）。

**2026-11-19 09:51 差分基线折叠轮复跑：45/45 全绿、0 skipped**。本轮 `deltasBatch` /
`deltasBatchByInterval` 把基线折进 `[start - 间隔, end)` 的第 0 个桶，稠密时每块 **1 条 SQL**；
`IotdbTierParityIT` 七族仍 `diffs=0`（等间隔 222 槽、差分 42 桶），证明折叠后的基线与
「先桶序列、再 `LAST_VALUE … WHERE time < start`」的旧口径数值一致。

> 计数更正：上表与本文早先写的「37 / 44 / `IotdbProductionIT` 10」漏计了
> `readsBatchRawAndHourlyHistory`（`@ValueSource(strings = {"native"})`）。按当次 surefire XML 复核，
> 实际是 **38 / 45 / 11**；该测试源本轮只改了一行 import，故历史那几轮跑的也是 45 例。

最后一次是关键：默认档位翻档之后，三通道生产用例与契约探针验证的才是新默认下真正发出的语句。
双跑对数的实测输出（0 容差，逐序列逐点）：

```text
L3-PARITY anchor last=2026-11-12T01:43:55.728Z populated=5 of 7
L3-PARITY family=current     series=7 samples=5   diffs=0
L3-PARITY family=raw         series=7 points=916  rawBatchCompletedColumns=1 diffs=0
L3-PARITY family=sampled     series=7 slots=222   diffs=0
L3-PARITY family=aggregated  series=7 slots=42    diffs=0
L3-PARITY family=intervalSum series=7 slots=4      diffs=0
L3-PARITY family=delta       series=7 buckets=42   diffs=0
L3-PARITY family=interpolated series=7 slots=91   rawBatchCompletedColumns=2 diffs=0
```

`rawBatchCompletedColumns` 非零说明逐位号补采这条分支在生产数据上真的被走过，
不是只在夹具里绿。

> 纪律仍然有效，两条都要守：
>
> - 探针的输出是本轮批量设计的**前提**，不是本轮代码的**证据**——第一次 24 项全绿跑在改造
>   之前，上表那次才是改造后的复跑；
> - **GE ↔ IoTDB 的业务级对数（映射表 + 两边同窗取数、A/B/C/D 归因，docs/14 §8.3）尚未执行**，
>   它是切换的硬门槛。连不上现场时一律写「未执行」，不伪造通过。
