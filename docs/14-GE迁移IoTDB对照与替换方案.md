# 14 · GE → Apache IoTDB 切换：语义对照与替换方案

> 版本：2026-11-13
> 适用：`tsdb-sdk` 0.1.0（IoTDB 驱动 2.0.11 树模型）
> 目标仓库：业务后端 `reference/tl-back-enfi`（本轮**只读**，不改一行业务代码）
> 现场实例：`http://192.168.3.199:18080`，IoTDB 2.0.11，纯树模型，未启用表模型
> 复跑：`mvn -o test`（L1，全离线）；`mvn -o -pl tsdb-sdk-iotdb -am test -Dtest=IotdbProductionIT,IotdbMappingProductionIT,IotdbContractProbeIT,IotdbTierParityIT -DfailIfNoTests=false -Diotdb.it=true`（L2/L3，只读）

---

## 1. 目标与验收口径的变更

### 1.1 为什么另起一篇

`docs/13` 那条线已经定稿：业务从自研 `GE_REST_SDK` 换到本 SDK 的 GE 驱动，**数据源仍是 GE Proficy Historian**，验收口径是生产逐点一致性 `DIFF=0`（`GeComparisonIT` 实测 MATCH=2021 / DIFF=0）。那条结论本轮**不推翻、不重开**。

本轮要回答的是另一个问题：**最终要落到的库是 Apache IoTDB**。IoTDB 与 GE 的底层模型差异大到无法用“逐字节等价”来验收：

| 维度 | GE Proficy Historian | Apache IoTDB 2.0.11 |
|---|---|---|
| 标识符 | 扁平位号 `DL.FCS0123.FI-12102_SUM` | 树模型 `root.<db>.<device>.<measurement>` |
| 质量码 | 逐点 `Quality`（GOOD/BAD/UNCERTAIN…） | **无逐点质量码**，`null` 即无数据 |
| 时间 | 文本，UTC 墙钟 | epoch 毫秒整数 |
| 错误 | per-tag `ErrorCode` 枚举 | 整条 SQL 成功 / 失败；查不存在的序列**返回空** |

所以验收口径改为：**语义等价 + 差异显式分级**。

### 1.2 分级定义（全文与 §3 表格共用）

| 级 | 名称 | 含义 | 处置 |
|---|---|---|---|
| `E` | 等价 | IoTDB 现有实现即可交付同等语义 | 只做回归，不动代码 |
| `A` | SDK 补齐 | 语义可实现，缺的是 SDK 的能力或口径 | 本轮已完成（见 §4） |
| `B` | 业务侧适配 | 能力已有，但业务调用形态/参数需改 | 下一轮，属工作量不属风险 |
| `N` | 声明不支持 | 明确抛 `TsdbUnsupportedException` | 文档标注 + 业务改造绕开 |
| `X` | 不可消除 | 底层数据差别，容差调不出来 | **必须业务裁决签字**（§7.4） |

### 1.3 本轮范围

交付三件：**本文档**、**SDK 必须改项**（§4，10 条，已全部落码）、**L1 单测**（§8.1，离线全绿）。
不在本轮：位号映射表 DDL 的建库执行与生成脚本（§5，设计已定，P2 执行）、业务代码改造（§6.3、§7）、历史数据搬迁裁决（§9）。

### 1.4 一句话结论

**四类接口都能在 IoTDB 上拿到语义等价的读数，SDK 侧的插入口与专用 SQL 已经建好并有单测钉住；卡点不在 SDK，在三件业务侧的事**：位号映射表的 100% 覆盖与存在性校验（§5）、质量码丢失后 18 处业务分支的去留（§7）、历史数据是迁还是不迁（§9）。

---

## 2. IoTDB 2.0.11 底层差异事实表

每条都有出处：**[实测]** = 本轮 `IotdbContractProbeIT` / `IotdbMappingProductionIT` 在生产实例只读跑通；**[L1]** = 离线单测钉住；**[审计]** = `reference/iotdb接入方案` 自己实测记录的结论。

| # | 事实 | 出处 | 对切换的影响 |
|---|---|---|---|
| 2.1 | 路径是树模型 `root.db.device.measurement`；**首节点 `root` 不能加反引号**（`` `root`.`factory` `` 报 `no viable alternative`），其余节点逐节点加反引号 | [实测] [L1]（`IotdbSqlTest#generatedSqlNeverQuotesRootAnchor`） | 所有 SQL 生成走 `IotdbSql.quotePath`，业务不再手拼路径 |
| 2.2 | **查不存在的序列返回空结果，不报错**（HTTP 200，`values:[]`）；不存在的测点也一样 | [实测]（2.4 的另一半） | 映射表写错表现为“数据静默为空” → 覆盖率与存在性校验是硬门槛（§5.5） |
| 2.3 | `SHOW TIMESERIES` 回**列式**响应：`expressions:[]` + `column_names` 11 列 + `values` 为**列优先**数组（首个数组是整列 Timeseries） | [实测]（`IotdbContractProbeIT#columnarShowResponsesAreTransposedAndTimeColumnsParsed`） | 解析必须转置，且列式分支里的 `Time`/`Timestamp` 列必须真解析 |
| 2.4 | `SELECT LAST` 批量回 `expressions:["Timeseries","Value","DataType"]`，`timestamps` **逐序列**给出，`Value` 的 `data_types` 是 **TEXT** | [实测]（`#batchLastDropsMissingMeasurementsAndCarriesPerSeriesTimestamps`） | ①时间戳不能置 0（早先硬置 `0L` 会让所有最新值落到 1970）②数值解析不能假设 JSON number ③**不存在的测点被静默丢弃、不占位**，所以 SDK 必须按入参顺序回填并 `null` 占位 |
| 2.5 | 聚合函数 `COUNT(...)`、`first_value/last_value` 的响应 `timestamps:[]` 但值列有元素 | [实测] | 行数不能按 timestamps 长度算，否则解析成 0 行 |
| 2.6 | `GROUP BY` 间隔**必须带单位字面量**：`5m/30s/1h/1d/300000ms/250ms/1mo` 全部接受；裸毫秒数 `86400000` 报 `code=700 no viable alternative` | [实测]（`#intervalLiteralsAreAcceptedWithinTheServerRowCap`、`#bareMillisecondIntervalIsRejectedAndWideGridsHitTheRowCap`） | SDK 的 `intervalLiteral` 恒发单位，业务任意 `Duration` 都能下推（§4.7） |
| 2.7 | **单次 `GROUP BY` 结果行数上限 10000**，超出报 `code=708 Dataset row size exceeded the given max row size (10000)` | [实测]（同上，30s × 7d 复现） | 细间隔长窗口必须分窗：`1s` 只能跑 2.8 小时，`30s` 只能跑 ~3.5 天，`5m` 可跑 34 天。业务的“逐日报表 + 秒级曲线”必须按此拆窗（§3.5） |
| 2.8 | `LIMIT n OFFSET m` 在树模型可用 | [实测] [审计] | 插值的原始点分页靠它，配合 `interpolation-raw-limit` 控内存 |
| 2.9 | `date_bin()` / `sample()` 在 2.0.11 树模型**静默返回空**，不报错 | [审计]（`IoTDB使用配置文档.md:327,423`） | 等间隔一律 `GROUP BY + FILL`；SDK 不使用这两个函数 |
| 2.10 | 表模型未启用：`SHOW TABLES` 报 700；`DROP DATABASE IF EXISTS` 语法不支持（`mismatched input 'IF'`） | [审计]（`IoTDB能力验证报告.md:28`、`IoTDB使用配置文档.md:424`） | 本轮只读，不建库不建表；写入方案（§9.2）若选建库需 try/catch |
| 2.11 | 探活：`GET {base}/ping` 返回 `{"code":200,"message":"SUCCESS_STATUS"}`；`{base}/rest/v2/ping` **404** | [实测]（`#pingIsServedAtServerRootWhileTheApiPrefixHasNone`） | 健康检查不能挂到 API 前缀下；驱动用 `SHOW VERSION` 判活 |
| 2.12 | 服务端按**请求头声明的 charset** 解码 body；不带 `charset` 时按 ISO-8859-1，路径里的非 ASCII 字符被曲解成 **HTTP 500 + Jetty HTML 错误页**（连 `SHOW TIMESERIES root.sdk_it.\`设备01\`**` 也一样） | [实测]（`#nonAsciiIdentifiersSurviveBecauseTheClientDeclaresUtf8Charset`；curl 去掉 `charset` 立即复现 500） | 驱动的 `IotdbHttpClient` 恒发 `application/json; charset=UTF-8`，因此不受影响；**任何绕开驱动直连 REST 的工具都必须显式声明 charset** |
| 2.13 | 响应 `Content-Type` 不带 charset | [实测] | 驱动固定按 UTF-8 解码响应体 |
| 2.14 | 时间是 epoch 毫秒，协议层无时区概念 | [审计] | 时区只影响“日/月对齐”和文本展示，两件事必须分开（§6） |
| 2.15 | **没有逐点质量码**：坏值只要进了库就是合法数值 | 库本身 | `X` 类差异的总来源（§7） |
| 2.16 | 实例现存规模：`root.factory` 下 64 条序列、2 个设备；`root.sdk_it` 是 IT 专用库 | [实测]（`SHOW TIMESERIES root.**`） | 生产点位（业务侧 1558 个字面量，§5.1）尚未落库，映射表与迁移是前置条件 |

本轮（位号路径模型 + 按数据库批量）追加 10 条实测事实（2.17–2.26），探针编号 P1–P10 落
`IotdbContractProbeIT`（门控 `-Diotdb.it=true`，只读）：

| # | 事实 | 出处 | 对切换的影响 |
|---|---|---|---|
| 2.17 | **`FROM` 前缀与 SELECT 表达式的层级必须一致**：`FROM root` 要 `库.设备.测点`，`FROM root.<db>` 要 `设备.测点`，`FROM root.<db>.<dev>` 只要 `测点`。**层级错配静默返回 0 行，不报错** | [实测 **M1**]（P1/P2 `everyQuotingShapeOfADatabaseRelativeLastExpressionIsAccepted`） | 两侧必须由同一个路径对象派生。`IotdbSql` 不再接受裸字符串位号，`FROM` 与列表达式同源于 `IotdbTagPath`（§4.1 第 1 条） |
| 2.18 | 表达式里出现 `root.` 前缀直接 `code=701 Path can not start with root in select clause` | [实测 **M3**]（P1，三通道一致） | 与 2.1 的「首节点 `root` 裸写」是两件事：首节点只在 `FROM` 侧裸写，列侧根本不出现它 |
| 2.19 | 库级 `SELECT LAST` **跨设备可用**、**响应行序 ≠ 请求顺序**、**无数据的序列整行丢弃**（64 请求 → 63 返回） | [实测 **M2/G5**]（P1 `databaseLevelLastMixesDevicesAndDropsSeriesWithoutData`、P7 `crossDatabaseLastAcceptsTwoHundredExpressionsInOneStatement`） | 最新值必须按 `Timeseries` 列规范化名回填并 `null` 占位，绝不能按列序对齐（§3.1） |
| 2.20 | `GROUP BY` 多列的**行数只等于桶数**，与列数无关：64 列 × 24 桶正常；2 列 × 20000 个 1s 桶仍撞 2.7 的 10000 行上限 | [实测 **M4/G7**]（P3 `groupedGridKeepsEveryColumnAndFillsEachColumnOnItsOwn`、`groupedGridRowCapCountsBucketsNotColumns`、P9） | 等间隔/聚合批量**不吃行预算**，加宽列数不改变截断口径；`FILL(PREVIOUS)` 按列独立填充、不串列 |
| 2.21 | 同一语句混用不同**聚合函数**可用（`AVG`+`MAX_VALUE`+`SUM`、`LAST_VALUE`+`FIRST_VALUE`）；但 `MAX`/`MIN` 不是聚合函数 → `code=701` 混合查询被拒 | [实测 **M5**]（P4 `aggregateColumnOrderFollowsTheRequestOrderOnEveryTransport`） | 一次请求的多个聚合函数可合成一条 SQL；SDK 现有 `MAX_VALUE/MIN_VALUE` 映射保持不变，不能“顺手”改成 `MAX` |
| 2.22 | **列顺序 == 请求顺序**（raw / GROUP BY / `first_value`+`last_value` 三种形态实测保序），列名携带规范绝对路径或 `FN(绝对路径)`，函数名按 SQL 里的大小写**原样回显**且三通道一致；三个执行器都把时间列剥进 `Row.getTimestamp()`，`getColumns()` 只含数据列 | [实测 **M6/G1/G3**]（P4、P6 `pairAggregatesWithoutGroupByReturnASingleLogicalRow`） | 列名是**主定位手段**而非交叉校验，列数相符时才允许按下标兜底；护栏的列数判据是 `== 发射表达式数`，不是 `+1`。早先 `IotdbSql.intervalSum` 的「不许按列名匹配」按此更正为「按括号内的绝对路径匹配、忽略函数名大小写」 |
| 2.23 | **路径不存在 → 整列消失**（LAST 丢行、基线 2 列变 1 列、raw 投影同样丢列）；**序列存在但无点 → 保留 null 列** | [实测 **G2**]（P5 `batchedBaselineKeepsTheEmptyColumnButDropsAnUnknownPath`、P8 `rawProjectionDropsUnknownColumnsAndKeepsEmptyOnes`） | 对齐护栏必须是「列数不符或列名解析不出来就抛」，而不是就近匹配 —— 丢列出现在中间时按位置对齐就是把别人的值挂到这个位号名下 |
| 2.24 | 聚合函数**不带** `GROUP BY` 时是 1 个逻辑行、`timestamps:[]`、值按列给 | [实测 **M7**]（P6，是 2.5 的推广） | 整窗累计增量与差分基线可整批一条 SQL；REST 解析器要有 6 列 + `timestamps:[]` 的用例 |
| 2.25 | **多列 RAW 的 `LIMIT` 是「合并时间戳行数」预算，不是每位号点数**：同窗 4 序列 `LIMIT 10` 时小时级序列只活 1 个点，而它单查同样 `LIMIT 10` 能拿满 10 个点 | [实测 **M9/G6**]（P8 `multiColumnRawSharesOneLimitAcrossColumnsAndStarvesSparseSeries`） | RAW 直接批量**必然改变结果**。业务侧「raw 与最新值 0 容差」的对数口径要求「一次批量 + 不足列逐位号补采」，补采列数超上限时整块退回逐位号（§3.2、§8.3） |
| 2.26 | 业务形态路径（连字符 + 两级设备，如 `` `RL`.`FCS0107`.`TI-1071815_PV` ``）在 LAST/GROUP BY/raw 三种语句里都能解析；300 个表达式、约 13 KB 的 SQL 被接受；`FROM root` 跨库单语句在 rest/native/jdbc 三通道都通得过 | [实测 **M8/G4/G8**]（P2、P10 `crossDatabaseSingleStatementReachesEveryTransport`） | 批量上限由我们自己设（`query-batch-size`），不是服务端限制；`query-batch-scope: root` 是通道无关的。列值类型仍按数值比较（native/jdbc 回 `Float`、REST 回 `Double`） |

---

## 3. 四类接口的 GE ↔ IoTDB 对照

### 3.1 最新值（`getCurrentValuePost` / sampled `samplingMode=1`）

| | |
|---|---|
| GE | `POST /v2/datapoints/current`，一次 345 / 295 / 248 位号单请求 |
| IoTDB | 按数据库分组：``SELECT LAST `dev1`.`a`, `dev1`.`b`, `dev2`.`c` FROM root.`db` ``；跨库拆多条 |
| SDK 落点 | `IotdbClient.currentValues().read(Collection)` → `IotdbQueryService.currentBatch(List<IotdbTagPath>)`，分组粒度、批量上限与并行度都从 `IotdbConfig` 读；旧的 `currentBatch(Collection<String>, int, int)` 已标废弃但仍可用 |
| 分级 | `A`（本轮已补）；单测 `IotdbClientTest#batchesCurrentValuesPerDatabaseAcrossDevices`（库级）、`#batchesCurrentValuesPerDeviceAndPreservesRequestOrder`（回退档）、`IotdbQueryServiceTest#currentBatchMergesWholeDatabaseIntoOneStatement` + `#currentBatchReturnsRequestOrderWhenDevicesResolveOutOfOrder`（乱序按入参序回填） |

要点：

- 分组用 `LinkedHashMap`/`LinkedHashSet`：分组顺序与位号顺序都取**首次出现序**，重复位号合并，结果按原始入参序回填。
- **缺数必须占位**（事实 2.4 / 2.19）：业务有 36 处 `getData().get(N)` 依赖“返回列表长度 == 入参长度”，返回变短比返回错值更致命。缺失位号给 `sample=null`。服务端行序不等于请求序（实测 M2），所以回填只按 `Timeseries` 列的规范路径，不按列位置。
- 分批由 `options.query-batch-size`（默认 200）控制，块间并行由 `options.query-parallelism`（默认 4）控制；最大单设备 `DL.FCS0202` 有 231 个测点，因此默认值一定会触发一次拆分。
- 分组粒度由 `options.query-batch-scope` 决定（`database` / `root` / `device`）。**当前默认是 `database`**，
  入场券是 §8.3 的同库三档双跑对数（已执行，七个族全部 `diffs=0`）；`device` 保留为现场回退档，
  `query-batch-size: 1` 可退化成一个位号一条 SQL。

### 3.2 原始值 raw（`getRawDataByRequestParamPost`）

| | |
|---|---|
| GE | 左闭右开 + `Direction` + `count`，Backward 反查 |
| IoTDB | 逐位号：``SELECT `dev`.`m` FROM root.`db` WHERE time >= S AND time < E ORDER BY TIME ASC\|DESC LIMIT n``；多位号：同一条 SQL 多列 + **合并时间戳行数**的 union LIMIT |
| SDK 落点 | `IotdbQueryService.rawBatchCompleted(tags, start, end, direction, maxPoints, completedTags)`，内部按 `chunks(query-batch-scope, tags)` 分块 |
| 分级 | `E`（实现形态本来就对齐：`maxPoints+1` 探测 → `truncated` 标志），但**批量前提是靠补采撑住的** |

实测 M9/G6 决定了一件事：多列 RAW 的 `LIMIT` 是**合并时间戳的行数预算**，不是每位号的点数。同窗 4 个序列 `LIMIT 10` 时，小时级序列只活 1 个点，而它单独查同样 `LIMIT 10` 能拿满 10 个点。所以 RAW 的批量形态是「一条批量 + 撞预算且列不足时逐位号补采」，补采列数超过 `options.query-batch-completion-max`（默认 32）时整块退回逐位号，被补采过的位号标 `attributes.rawBatchCompleted=true`。**这是为了让批量后的结果与逐位号逐点相同，不是因为批量更快**——密集采样 × 多位号 × 大 `maxPoints` 的窗口往返下界不变。`LIMIT n OFFSET o` 的分页只保留在逐位号形态（`IotdbSql.raw(String,…)`，已废弃但仍可用）。

唯一 `X` 类残留：GE 的 `Sample.Quality` 在这里没有对应物（§7）。

### 3.3 累计值（`getIntervalValueBySumTag`，业务 40 处）

| | |
|---|---|
| GE 真实语义 | **不是分桶差分**：`count=1, intervalMs=1` 的整窗单值，用 `FirstRawValue=17` / `LastRawValue=19` 两次取“第一个/最后一个**良好**原始样本值”相减（`GE_REST_SDK/.../DataApiInvoker.java:549-589`、`CalculationMode.java:136-154`） |
| IoTDB | 单条 SQL 一次往返：``SELECT first_value(`dev`.`m`), last_value(`dev`.`m`), first_value(`dev`.`m2`), last_value(`dev`.`m2`) FROM root.`db` WHERE time >= S AND time < E``，内存 `BigDecimal` 相减，时间戳取窗口起点（实测 M7：不带 `GROUP BY` 时整批是 1 个逻辑行、`timestamps:[]`） |
| SDK 落点 | `IotdbClient` 的 `AGGREGATED + INTERVAL_SUM` 提前转入 `intervalSumSeries()` → 按块走 `IotdbQueryService.intervalSumBatch(paths,start,end)`（逐位号入口 `intervalSum(path,start,end)` 保留） |
| 分级 | `A`（补齐）+ `X`（GOOD 语义无对应） |

首末值配对**按列名里的函数名判定**（`first_value(...)` / `last_value(...)`，实测 G1：三通道列名一致、函数名按 SQL 大小写原样回显），不靠「偶数列是首值」这种下标约定 —— 序列存在但无点时服务端保留 null 列，而路径不存在时整列消失（实测 G2），下标约定一旦遇到丢列就是静默串位。单测 `IotdbQueryServiceTest#intervalSumBatchPairsFirstAndLastByFunctionNotByPosition` 钉这条。

三条必须写死的边界：

1. **与 `DeltaReader` 拆成两个方法，不得互相冒充**。`intervalSum` 回答“这段时间一共走了多少”（单点），`deltas()` 回答“每个桶各走了多少”（相邻桶末值差 + 基线）。公共层 `Aggregation.INTERVAL_SUM` 的 Javadoc 本轮已补上“片数由 count/interval 决定，`count=1` 即整窗单值”和“GE 的 first/last 只取 GOOD 点”两句。
2. **锚点缺失时返回空 + `intervalSumMissingAnchor=true`，绝不补 0**。补 0 会把“没有数据”伪装成“这段时间没有消耗”，报表无法分辨（单测 `IotdbClientTest#marksMissingIntervalSumAnchorInsteadOfReturningZero`、`IotdbQueryServiceTest#intervalSumNeverFabricatesZeroWhenAnAnchorIsMissing`）。
3. **`X` 类**：`_PV` 位号被当 `_SUM` 用（`OxygenProductionDailyJob.java:270-272`，同一接口的 `_SUM` 后缀校验已被注释掉）在 GE 侧靠“只取良好值”兜住，IoTDB 侧坏值直接锚定首末读数。调容差消除不了。

### 3.4 插值（`getInterpolatedByRequestParamPost`，业务 81 处 `setQueryModifier(1L)`）

| | |
|---|---|
| GE | 服务端按网格产点，`queryModifier=1` 补窗口边界点 |
| IoTDB | **SDK 内存插值**：按 `start + i*step` 造网格，每槽用左右包围原始点线性内插 |
| SDK 落点 | `QueryMode.INTERPOLATED` → 按块走 `IotdbQueryService.interpolateBatch(tags, gridStart, stepMillis, slots, toleranceMillis, rawLimit, completedTags)`；逐位号入口 `interpolate(path,…)` 保留 |
| 分级 | `A`（补齐）+ `X`（外推容差需业务裁决） |

批量形态下，窗内原始点与网格两侧的包围点都复用 §3.2 那条「多列 raw + 不足列逐位号补采」的原语：**每位号每块 3 条 SQL**（窗内、左包围、右包围），补采分支与 RAW 相同。单测 `IotdbQueryServiceTest#interpolateBatchSendsThreeStatementsPerChunkAndMatchesThePerTagGrid` 钉「批量结果与逐位号网格逐点一致」，`#interpolateBatchCompletesTheTagTheWindowBudgetStarved` 钉撞预算时的补采。

为什么不用 `FILL(LINEAR)`：它只在**已生成的 `GROUP BY` 桶之间**填值，桶数由窗口/间隔决定，与请求的槽位数不等价，也不保证点数——业务的报表是**按网格索引取点**的，少一格就整体错位。

关键不变式（`IotdbClientTest#interpolatesEveryGridSlotAndIgnoresAggregation` 钉住 480 点网格：首点 == `start`、末点 == `start + 479*step`；`IotdbQueryServiceTest#interpolateWeightsByRealSampleTimestamps` 钉住权重必须用**真实采样时刻**而非窗口边界）：

- 插值**忽略** `aggregation`：网格上只有“原始点重合”和“两侧内插”，没有聚合窗口。
- `count=1 且 start==end` 的瞬时插值改判为“±tolerance 内取两侧包围点内插”。
- `options.interpolation-tolerance`（默认 0）= **不外推**。停产或改名后继续画出平直曲线，比缺数更难发现。
- `options.interpolation-raw-limit`（默认 200000）耗尽时置 `interpolationRawTruncated`，与网格截断 `truncated` **分开的两个标志**。

### 3.5 采样 / 聚合（`getCalculatedByRequestParamPost`）

`E`，但带三条本轮修正：

- 间隔白名单（1s/1m/1h/1d）已删除，任意 `Duration` 都下发为带单位字面量——业务的 9×5min 曲线（`MesProductPowerOxygenMainServiceImpl.java:180-287`）与 480 点固定网格（`ZxpsGenJob.java:200-215`）不再被 SDK 自己卡死。
- **新约束**：事实 2.7 的 10000 行上限。`5m` 粒度可覆盖 34 天，`1s` 只能 2.8 小时；超过就必须由调用侧分窗，SDK 不会静默截断（服务端直接报 708）。
- 批量形态：``SELECT last_value(`dev`.`m`), avg(`dev`.`m2`) FROM root.`db` WHERE … GROUP BY ([S,E),<字面量>) [FILL(…)]``。**加宽列数不吃行预算**（实测 M4/G7：行数只等于桶数），所以按库批量后每位号的截断口径与逐位号完全一致，不像 RAW 那样需要补采。同一请求里的多个聚合函数可以合进一条 SQL（实测 M5），但 `MAX`/`MIN` 不是 IoTDB 的聚合函数，SDK 的 `MAX_VALUE/MIN_VALUE` 映射不能“顺手”改掉。

---

## 4. SDK 改造清单与完成状态

模块根：`tsdb-sdk-iotdb/src/main/java/io/github/forget_the_bright/tsdb/iotdb/`

### 4.1 必须改（15 条，全部已落码）

| # | 改造项 | 落点 | 验证 |
|---|---|---|---|
| 1 | 反引号逐节点引用、首节点 `root` 不加引号、白名单放宽、`intervalSum(path,start,end)`、批量 LAST 生成 | `internal/sql/IotdbSql.java` | `IotdbSqlTest` 23 例（含 `acceptsQuotedTagTextAndReEmitsItCanonically`、`generatedSqlNeverQuotesRootAnchor`、`selectExpressionsNeverCarryTheRootAnchor`、`rejectsSqlMetacharactersInTagPath`） |
| 2 | 位号→路径映射可插拔：映射表优先、未命中才 sanitize 兜底（WARN 去重 + 命中计数）；**多位号串（逗号/分号）直接抛 `TsdbDataException`，拆分由调用方负责**（本节早先写的“逗号多值拆条按原名合并”是假话：生产里 `resolve` 对分隔符一直是直接抛，从没生效过，本轮按事实改口）；中文占位与 `${expr}` 伪点显式拒绝 | `internal/sql/TagPathMapper.java`（新增）、`internal/sql/IotdbTagResolver.java` | `IotdbTagResolverTest` 17 例 |
| 3 | 放开 `INTERPOLATED`，委托 `interpolate()`；能力集新增 `INTERPOLATED_HISTORY` | `IotdbClient.java`、`IotdbDriver.java` | `IotdbClientTest#declaresInterpolatedHistoryOnlyWhenQueryServiceIsPresent` 等 |
| 4 | `INTERVAL_SUM` 从拒绝白名单移出，走专用 SQL | `IotdbClient.intervalSumSeries()` | `IotdbClientTest`、`IotdbQueryServiceTest` 各 2 例 |
| 5 | 批量最新值替换逐位号循环，配 `query-batch-size` / `query-parallelism`（原名 `current-*`，本轮改名且**不留别名**） | `IotdbQueryService.currentBatch()` | `IotdbClientTest#batchesCurrentValuesPerDeviceAndPreservesRequestOrder` + 服务层 3 例 |
| 6 | REST 列式分支不再把 timestamp 硬置 `0L`，按 `Time`/`Timestamp` 列解析；无时间列的聚合按值列算行数 | `internal/transport/IotdbRestQueryExecutor.java` | `IotdbRestQueryExecutorTest` 6 例 + L2 `#columnarShowResponsesAreTransposedAndTimeColumnsParsed` |
| 7 | 间隔放开到任意 `Duration`（5m / 30m / 480 点网格），字面量带单位 | `internal/sql/IotdbSql.intervalLiteral` | `IotdbSqlTest#supportsArbitraryIntervalLiterals`、`IotdbClientTest#readsSampledHistoryWithArbitraryInterval`、L2 `#intervalLiteralsAreAcceptedWithinTheServerRowCap` |
| 8 | 两参构造器不再静默降级：缺查询服务默认构造期抛异常，`options.require-query-service=false` 才放行 | `IotdbClient` 构造器 | `IotdbClientTest#failsFastWhenQueryServiceIsMissing` |
| 9 | `AGGREGATED` 统一走 `queryService.aggregated()`，不再恒走 REST 直连 | `IotdbClient.readHistory()` | `IotdbClientTest#routesAggregatedHistoryThroughQueryService` |
| 10 | 时间与班次口径落进 SDK：`options.zone` / `options.shift-hour`、`shiftWindow()`、`normalizeStart` 一律用业务 zone | `IotdbConfig`、`IotdbQueryService`、`IotdbClient.shiftWindow` | `IotdbClientTest` 2 例 + `IotdbQueryServiceTest` 3 例 + L2 `IotdbMappingProductionIT`（真库三通道验证 8h 平移） |
| 11 | **位号路径模型收一**：新增 `IotdbTagPath` 作为模块内唯一的位号解析/校验/引用入口，支持「带库全名 / 不带库相对名（补 `default-database`）/ 已加反引号」三态；同一串不再被校验三遍 | `internal/sql/IotdbTagPath.java`（新增） | `IotdbTagPathTest` 24 例（§3.1 的拒绝全表、`root.factoryx` 边界、<4 段、通配符） |
| 12 | **按数据库分组批量，全族覆盖**：`Grouping = database\|root\|device`，LAST/GROUP BY/聚合/整窗增量/差分基线/RAW/插值一律走多列 SQL；`FROM` 与列表达式同源于路径对象，M1 层级错配与 M3 的 `root.` 前缀变成结构性不可能 | `internal/sql/IotdbSql.java`、`IotdbQueryService.chunks()` | `GroupingTest` 9 例 + `IotdbSqlTest` 库级形态 + `IotdbQueryServiceTest#currentBatchMergesWholeDatabaseIntoOneStatement`、`#intervalBatchCarriesEveryTagOfOneDatabaseInOneStatement`、`#deltasBatchSendsTwoStatementsPerChunk` |
| 13 | **结果对齐护栏**：K1 按 `Timeseries` 规范名回填 + `null` 占位；K2 先按列名定位、列数与发射数相符才允许按下标兜底，列名解析不出来或列数不符一律抛 | `internal/query/SeriesKeyIndex.java`（新增）、`IotdbQueryService` | `SeriesKeyIndexTest` 6 例 + `batchReadsColumnsByTheirPathLabelRatherThanByPosition`、`batchLeavesADroppedColumnEmptyInsteadOfShiftingTheNeighbours`、`batchRejectsAColumnThatWasNeverRequested`、`batchFallsBackToPositionsOnlyWhenNoColumnCarriesAPath` |
| 14 | **RAW/插值的批量 + 逐位号补采**：多列 `LIMIT` 是合并行数预算（M9），撞预算且列不足时逐位号补采，补采列数超 `query-batch-completion-max` 时整块退回逐位号 | `IotdbQueryService.rawBatchCompleted/interpolateBatch`、`IotdbClient` | `rawBatchCompletionRefillsTheColumnTheRowBudgetStarved`、`rawBatchCompletionFallsBackToTheWholeChunkPastTheCap`、`rawBatchWithoutAFullRowBudgetTrustsTheBatchAndDropsNullCells`、`IotdbClientTest#marksTheTagsThatNeededAPerTagCompletionPass` |
| 15 | **块级失败归因**：一条 SQL 覆盖 k 个位号时，块失败就给这 k 个位号写同样的 `vendorError` + `vendorErrorScope=chunk` + 失败 SQL；k==1 时不冒充块级范围 | `IotdbClient.readInChunks/assemble` | `IotdbClientTest#marksEveryTagOfAFailedChunkWithTheSameScopedError`、`#keepsPerTagFailureAttributionWhenBatchSizeIsOne` |

### 4.2 建议改

| 项 | 状态 |
|---|---|
| per-tag 失败原因写入 `TagSeries.attributes.vendorError` | **已做**。`IotdbClientTest#marksEveryTagOfAFailedChunkWithTheSameScopedError`（块级：块内每个位号都带同样错误 + `vendorErrorScope=chunk` + 失败 SQL）与 `#keepsPerTagFailureAttributionWhenBatchSizeIsOne`（一条 SQL 一个位号时不冒充块级范围）钉住两态；`partialResultAllowed=false`（默认）时整条查询中断抛异常 |
| `options.quality-strategy=assume-good\|range-check`（按映射表量程越界产 `Quality.UNCERTAIN`） | **未做**。它依赖 §5 的映射表带 `range_low/high` 列，属 P2 之后的增量；在表落地前先不要伪造质量码 |

### 4.3 不改，只在文档声明

`FillPolicy.ZERO`（业务 0 处使用）、`Capability.TREND/ALARM/DELETE`、`date_bin()`/`sample()`（事实 2.9）、逐点真实质量码（`X` 类，§7）。

---

## 5. 位号映射表设计

### 5.1 规模：先说可复跑的数字，并作废方案初稿里的估计值

方案初稿写的是“891 个字面位号、779 个含 `-`”，**该数无法用任何一条命令复现，本轮作废**。以下命令在 `reference/tl-back-enfi` 根目录跑，是本文唯一采信口径：

```bash
grep -rhoE '"(LS|DL|RL|DJ|ZX|BGZH)\.[A-Za-z0-9_]+(\.[A-Za-z0-9_\-]+)?"' --include=*.java . \
  | tr -d '"' | sort -u            > /tmp/tags.txt
wc -l /tmp/tags.txt                       # 1558  去重后的位号字面量
grep -c -- '-' /tmp/tags.txt              # 813   含连字符
awk -F. '{print NF}' /tmp/tags.txt | sort | uniq -c
#   1530 个三段位号（device.measurement） + 28 个两段（只到设备，前缀查询用）
awk -F. '$3 ~ /^[0-9]/ {c++} END{print c}' /tmp/tags.txt    # 419  测量段以数字开头
awk -F. '{print $1"."$2}' /tmp/tags.txt | sort -u | wc -l    # 44   设备前缀
```

结构特征（决定“必须建表、不能机械 sanitize”）：

| 特征 | 数量 | 例子 | 后果 |
|---|---|---|---|
| 测量段含 `-` | 813 | `DJ.FCS0204.TE-301-302_PV` | 未加引号即语法错误；且 `-` 在段内而非段分隔 |
| 测量段以数字开头 | 419 | `DL.FCS0202.1AI001_PV` | 非合法标识符首字符，必须反引号 |
| 仅大小写不同的重复位号 | 6 组 | `RL.FCS0110.FIQ-12306_PV` / `..._pv` | IoTDB 测点名区分大小写，映射表若做了归一会静默命中错序列 |
| 设备前缀 | 44 个 | `DL.FCS0202`（231 个测点，最大） | 决定 `query-batch-scope: device` 回退档的往返数；库级/根级批量下只影响 SQL 表达式形态，单条上限由 `query-batch-size` 与 `MAX_SQL_BYTES` 定 |
| 运行时拼接（`StrUtil.format`、枚举分段） | 33 处模板，如 `DJ.FCS0207.{}_PV` | 未计入上表 | 静态扫描拉不全，必须有运行期 fallback 计数（§5.4） |

**注意后缀是 `_PV` / `_SUM`，不是 `-PV`**；`-` 位于测量段内部。若把锚点从“六位号前缀”改成“`_PV`/`_SUM` 后缀”，同一命令得到 1071 / 457，即口径敏感，引用时必须连命令一起引用。

### 5.2 三个来源与优先级

| 来源 | 规模 | 位置 |
|---|---|---|
| 数据库字段 | 23 处 / 4 文件 | `mes_base_indicator.related_point_address`（`MesBaseIndicatorServiceImpl.java` 12 处） |
| `@PointParam` 注解 | 98 处 / 5 文件 | `enfi-module-common/.../electrolysis/entity/` |
| 枚举与散写字面量 | 约 23 个枚举 / 111 文件含位号 | `enums/**` 只有 6 个文件带位号，大头在 service/job |

冲突裁决：`DB_FIELD > ENUM > ANNOTATION`，且**启动期 fail-fast 出差异清单**，不做运行时静默覆盖。另有 `@EnumParam` 11 处 / 2 文件（`CommonGeController.java:337`、`MesRefineFurnaceInfoController.java:60`）参与枚举侧。

### 5.3 表结构（设计定稿，DDL 执行属 P2）

```sql
CREATE TABLE tsdb_point_mapping (
  tag_name      VARCHAR(128) PRIMARY KEY,  -- GE 侧原始位号，逐字节保留（含 - 与大小写）
  iotdb_path    VARCHAR(256) NOT NULL,     -- 规范路径 root.db.device.measurement
  device        VARCHAR(128) NOT NULL,     -- 冗余存设备段：device 回退档分组用，也便于人工核对
  measurement   VARCHAR(128) NOT NULL,
  value_type    VARCHAR(16),
  unit          VARCHAR(32),
  range_low     DECIMAL(18,4),             -- 供 §4.2 的 range-check 质量策略
  range_high    DECIMAL(18,4),
  enabled       TINYINT DEFAULT 1,
  source        VARCHAR(16) NOT NULL,      -- DB_FIELD / ENUM / ANNOTATION / LITERAL
  remark        VARCHAR(256),
  checked_by    VARCHAR(64),               -- 业主核对人
  checked_at    DATETIME
);
```

主键是**原始位号逐字节**，不做大小写归一、不做 `-`→`_` 清洗：上表 6 组大小写差异说明归一会静默命中错序列。

### 5.4 生成流程：我们自动拉基线 + 业主逐条核对（已定策略）

1. 用 GE `queryTagsByParams` 拉全量位号基线（含 device/measurement 切分建议）；
2. 静态扫描补齐 1558 个字面量（§5.1 命令），运行时拼接模板单独列出；
3. 三来源归一，按 §5.2 优先级解决冲突并出差异清单；
4. 交业主逐条确认 `checked_by/checked_at`；
5. 导出 `iotdb-tag-mapping.properties`（`tag=full.path` 一行一条）或数据库实现。

SDK 侧插入口本轮已就位：

- 接口 `internal/sql/TagPathMapper`：`canonicalPath(String tag)`、`size()`，工厂 `none()` / `of(Map)` / `fromPropertiesFile(path)`；
- 配置项 `options.tag-mapping-file` 由 `IotdbDriver` 装载；也可用四参构造器 `new IotdbClient(config, api, queryService, mapper)` 注入数据库实现；
- 未命中时 `IotdbTagResolver` 退回 `default-database` 前缀补全，打**去重 WARN**（上限 4096 条），并计数；
- 覆盖率通过 `IotdbClient.tagMappingStats()` 读取（`mappedTotal` / `fallbackTotal` / `fallbackTags` / `mappedRatio`）。

### 5.5 切换硬门槛

事实 2.2 决定了这不是“最好有”，而是硬门槛：

1. `mappedRatio == 100%`（`fallbackTags` 必须为空清单）；
2. 每条 `iotdb_path` 用 `SHOW TIMESERIES <path>` 做**存在性校验**，逐条列出不存在的；
3. 两份清单（未命中位号、不存在路径）作为业主签字件。

---

## 6. 时间与班次口径

### 6.1 三层必须分开

| 层 | 载体 | 语义 | 影响 |
|---|---|---|---|
| 线缆层 | epoch 毫秒 | IoTDB 协议只有绝对时刻 | 与时区无关 |
| 日历层 | `options.zone`（默认 `Asia/Shanghai`）、`options.shift-hour`（默认 `8`，0..23） | 自然日/月对齐、班次窗口 | **改变分桶边界**，即改变报表数值 |
| 展示层 | `options.time.*`（请求/响应的 enabled、zone、pattern） | 文本解析与格式化 | 只改展示，不改分桶与数值 |

本轮修掉的正是日历层与展示层的误用：`TimeGranularity.normalizeStart` 原先吃的是 `timeConfig.getResponseTargetZone()`（未配置即 **UTC**），于是 `DAY`/`MONTH` 的日界整体差 8 小时。现在两处调用点一律传 `config.getZone()`。

证据（`IotdbQueryServiceTest`，全离线）：

- `alignsDayStartToBusinessZoneInsteadOfDisplayZone`：起点 `2026-11-11T20:00Z`（= 本地 11-12 04:00）→ 桶起点 `1794412800000`（本地 11-12 00:00+08），并**反向断言**不等于 `1794355200000`（误用展示时区会得到它）；
- `alignsDayStartToConfiguredZone`：同一改成 `zone=UTC` → `1794355200000`；
- `alignsMonthStartToFirstDayAndUsesMonthLiteral`：月首同理钉 `1793462400000`。

真库验证（L2，三通道全通过）：`IotdbMappingProductionIT` 先探测测试序列自身的首末样本时刻，再把窗口锚到首样本所在日界，断言 7 个日桶起点全部落在 `Asia/Shanghai` 日界（UTC 16:00），并只用 `zone=UTC` 再读一遍，证明两者恰好相差 8 小时。

### 6.2 班次窗口是 SDK 的一等公民

```java
IotdbConfig.ShiftWindow w = client.shiftWindow(Instant.parse("2026-11-10T23:59:59Z"));
w.getStart();  // 2026-11-10T00:00:00Z  = 本地 11-10 08:00
w.getEnd();    // 2026-11-11T00:00:00Z  = 本地 11-11 08:00（右开）
```

规则：`zone` 下当日 00:00 + `shift-hour`；若时刻早于该点则回退一天；长度恒 24 小时，`[start, end)`。配置校验：非法 zone 与 `shift-hour` 越界在**构造期**抛 `TsdbConfigurationException`（`honorsConfiguredZoneAndShiftHourForShiftWindow` 覆盖）。

这与业务现状一致：`ReportSmeltClassJob.java` 的本地日以 08:00 起算（白班 08:00–15:00、小班 →23:59、大班 →次日 07:xx）。

### 6.3 业务侧的 ±8h 补丁（本轮只统计，不改；换算落点已在 SDK）

方案初稿说“业务仅一处”，实测**远不止**：

| 形态 | 数量 | 代表位置 |
|---|---|---|
| `offsetHour(..., ±8)` | **37 处 / 14 文件** | `ZxwknsGenJob.java:205`、`ZxpsGenJob.java:153`、`SmeltMainServiceImpl.java:100`、`PourMainServiceImpl.java:1595`、`ConsoleUtil.java:43` |
| 毫秒直接减 8 小时 | 3 处 | `OxygenProductionDailyJob.java:372`（`- 8*60*60*1000`） |
| `minusHours(8)` 单点 | 1 处 | `ReportSmeltClassJob.java:115` |

复现：`grep -rn "offsetHour(" --include=*.java . | grep -E ",\s*-?\s*8\b"`。

结论：这 41 处是 **B 类**（业务侧适配）工作量的主要来源之一；SDK 已提供 `zone`/`shift-hour`/`shiftWindow`，业务改造时应删除而不是保留补丁。

---

## 7. 质量码与 errorCode 的处置

### 7.1 质量码：输入源消失

事实 2.15。业务侧真实分布（本轮复测，修正方案初稿的“18 处 / 12–13 文件”）：

| 关键字 | 出现 / 文件 |
|---|---|
| `Quality.` | 22 / 13 |
| `getQuality()` | 19 / 12 |
| `GOOD` | 11 / 8 |
| `BAD` | 8 / 5 |
| `UNCERTAIN` | 1 / 1 |
| **并集** | **61 / 15 文件** |

复现：`grep -rlE "Quality\.|getQuality\(\)|\bGOOD\b|\bBAD\b|UNCERTAIN" --include=*.java . | wc -l`。

最值得注意的一处是**业务自己在伪造质量码**：`BurdenMainOperatingConsoleServiceImpl.java:789` 在序列零样本时注入 `Quality.UNCERTAIN` + 值 `"0.0"`；`GeUtil.java:519` 伪造 `GOOD`。切到 IoTDB 后这些分支的输入恒为 `GOOD`/`UNKNOWN`（`IotdbMapper`：非 null→GOOD，null→UNKNOWN），分支会**恒定走某一侧**——这是静默行为变更，必须在 L4 报表对数里显式覆盖该文件。

### 7.2 errorCode：82 处塌缩成两态

复测分布（修正初稿“64 成功门 + 14 抛异常”）：

| 形态 | 数量 |
|---|---|
| `.getErrorCode()` 总出现 | **82 / 51 文件** |
| 局部变量门 `ErrorCode errorCode = x.getErrorCode();` | 63 处 / 42 文件，随后 `errorCode == ErrorCode.SUCCESS` 63 处 |
| `!= ErrorCode.SUCCESS` | 15 处 |
| 判定后直接 `throw` | **9 处 / 5 文件** |

IoTDB 侧没有 per-tag 枚举，塌缩为“整条 SQL 成功”或“失败/空结果”。承接方式两条腿：

1. 整条失败 → `TsdbQueryException`，对应业务 9 处 throw 分支；
2. 位号级失败（映射缺失、路径非法、服务端拒绝 SQL）→ 允许部分结果时写进 `TagSeries.attributes.vendorError`，**且此时失败位号是“空序列 + vendorError”，与“确实没数据”同形**，调用方必须读属性才分得开。本轮按库批量后归因有两种粒度：一条 SQL 覆盖 k 个位号时，服务端一个错误就影响这 k 个位号，驱动给块内每位号写同样的 `vendorError` 并加 `vendorErrorScope=chunk` 与失败 SQL；一条 SQL 只覆盖 1 个位号时（`query-batch-size: 1` 就是这个回退档）不带 `vendorErrorScope`。用例：`IotdbClientTest#marksEveryTagOfAFailedChunkWithTheSameScopedError`、`#keepsPerTagFailureAttributionWhenBatchSizeIsOne`（见 §7.5）。

### 7.3 写入链路与质量码三档（与 §9.2 同表，此处给数值影响）

| 档 | 描述 | 对报表数值的影响 | 验证方法 |
|---|---|---|---|
| ① 数采侧已写、我们只读 | 质量由采集器决定是否入库 | 若坏值也入库，`first_value/last_value` 与 AVG 直接被污染，且**无法事后过滤** | L3 双跑逐例归因 C 类；抽样比对同一时刻 GE `Quality` 与 IoTDB 数值 |
| ② SDK 补写入 + 伴生 `_Q` measurement | 值与质量分两条序列写 | 需要在 SDK 加质量伴生列的读取与过滤，报表侧才能恢复“只取良好值” | 需写入改造（本轮不做），先在 §9.2 决策后另排期 |
| ③ 无质量信息，只能值域校验 | 按映射表 `range_low/high` 判越界 | 只能缓解，不能等价：既漏报（量程内的漂移坏值）又误报（合法但坏） | 依赖 §4.2 的 `quality-strategy=range-check`，P2 之后 |

### 7.4 不可消除差异裁决清单（`X` 类，禁止用容差掩盖，需业务签字）

| # | 差异 | 影响面（本轮复测） | 能否靠容差消除 | 业务裁决 |
|---|---|---|---|---|
| X-1 | IoTDB 无逐点质量码；坏值以合法数值入库并污染 `first_value/last_value` 与 AVG | 累计值 40 处 + 全部聚合曲线 | **否** | 待签字 |
| X-2 | 业务质量分支失去输入（含 `BurdenMainOperatingConsoleServiceImpl.java:789` 的伪造 UNCERTAIN） | 61 处 / 15 文件 | **否**（分支恒走一侧是语义变更） | 待签字 |
| X-3 | per-tag `ErrorCode` 塌缩为“失败/空”两态 | 82 处 / 51 文件 | **否** | 待签字（vendorError 只是承接，不等价） |
| X-4 | 查不存在的序列返回空而非异常 | 全表 | **否**，只能靠门槛（§5.5）挡住 | 待签字（接受门槛流程即视为签字） |
| X-5 | 插值不外推（`interpolation-tolerance=0`）与 GE 边界补点行为不同 | 81 处网格 | 部分：调 tolerance 可逼近，但会引入虚假趋势 | 待签字 |
| X-6 | 按库批量后，一条 SQL 的失败影响面从 1 个位号放大到块内的 k 个位号。GE 侧整条请求失败同样是批级影响，但它另有 per-tag `ErrorCode` 可区分单个位号，IoTDB 批量后连这层区分也没有 | 全表；默认 200 位号/块，345 位号 → 2 块 | **否**：这是批量本身的语义，只能靠档位（§7.5）控制影响面 | 待签字 |

### 7.5 批量后的失败归因口径

一条 SQL 覆盖 k 个位号，服务端一个错误就影响这 k 个位号。SDK 的做法是**如实标注范围**，不做任何掩饰：

| 场景 | `attributes` |
|---|---|
| 块内多位号共用一条 SQL 且该 SQL 失败 | `vendorError=<服务端消息 + 失败 SQL>`、`vendorErrorScope=chunk` |
| 一条 SQL 只覆盖 1 个位号（`query-batch-size: 1`，或该块只剩一个位号） | 只有 `vendorError`，**不带** `vendorErrorScope` |
| 位号解析失败（映射缺失、路径非法） | `vendorError`，逐位号粒度 |
| `partialResultAllowed=false`（默认） | 直接抛 `TsdbQueryException`，不产出部分结果 |

口径：**不许把影响面说小**（不伪装成“只有那一个位号失败”），也**不许把影响面说大**（k==1 时不标 `chunk`，否则现场会按这个数去核对不存在的位置）。回退到今天的逐位号归因只改一个键：`query-batch-size: 1`。

---

## 8. 验证：四层与容差分档

### 8.1 L1 —— SDK 离线单测（纳入 `mvn -o test`）

当前全绿：**reactor 290 例**（common 77 / hls 20 / ge 28 / iotdb 164 / spring-boot-starter 1）。
差分基线折叠轮新增：`HistorianUnitTest` 的 `countOf`/`resolveCount`、`IotdbQueryServiceTest` 的
扩展桶兜底与月粒度折叠、`HlsClientTest` 的稠密单请求/缺桶兜底、`GeClientTest` 的折叠请求形态与
`count` 推导/冲突，各驱动旧用例按「1 次主查询」重写期望。
iotdb 模块 164 例 = `IotdbClientTest` 32 + `IotdbQueryServiceTest` 39 + `IotdbSqlTest` 23 +
`IotdbTagPathTest` 24 + `IotdbTagResolverTest` 17 + `IotdbConfigTest` 8 + `GroupingTest` 9 +
`SeriesKeyIndexTest` 6 + `IotdbRestQueryExecutorTest` 6。基线（第一轮改造前）102 例，上轮定稿
157 例，位号路径模型 + 按库批量后 234 例，2026-11-16 Hutool/fastjson 迁移 + 门户拆分后 258 例
（common 新增 `Texts`/`BasicAuth`/`JsonText`/`HttpTransport`/`HttpErrors`/`TsdbInstanceConfig` 用例），
2026-11-18 HLS `RAW` 轮 279 例，2026-11-19 差分折叠轮 **290 例**。

方案 §3 要求的 L1 覆盖点逐项对号：

| 要求 | 用例 |
|---|---|
| 480 点网格首点==start、末点==start+479·step | `IotdbClientTest#interpolatesEveryGridSlotAndIgnoresAggregation` |
| `intervalSum` 单 SQL 解析 | `IotdbQueryServiceTest#intervalSumSubtractsWindowAnchorsInOneRoundTrip`、`IotdbSqlTest#intervalSumIsOneRoundTripWithFirstThenLastColumn` |
| 批量 LAST 顺序回填含缺数占位 | `IotdbClientTest#batchesCurrentValuesPerDatabaseAcrossDevices`（库级）、`#batchesCurrentValuesPerDeviceAndPreservesRequestOrder`（设备档）、`IotdbQueryServiceTest#currentBatchDeduplicatesTagsAndChunksByMeasurementLimit` |
| 反引号中文/纯数字段 round-trip | `IotdbSqlTest#acceptsQuotedTagTextAndReEmitsItCanonically`、`#generatedSqlNeverQuotesRootAnchor`、`#selectExpressionsNeverCarryTheRootAnchor`、`IotdbTagPathTest` 24 例 |
| 08:00 班次窗口换算 | `IotdbClientTest#computesShiftWindowFromDefaultCalendarZoneAndShiftHour`、`#honorsConfiguredZoneAndShiftHourForShiftWindow` |
| 日/月对齐用业务时区 | `IotdbQueryServiceTest` 3 例（含反向断言） |
| 静默降级已封死 | `IotdbClientTest#failsFastWhenQueryServiceIsMissing`、`#routesAggregatedHistoryThroughQueryService` |
| 三档分组各自的 `FROM`/表达式配对、跨组拒绝 | `GroupingTest` 9 例、`IotdbSqlTest#batchRejectsTagsFromDifferentGroups`、`#currentBatchAtRootLevelCarriesTheDatabaseNode` |
| K1 乱序/丢行按名回填 | `SeriesKeyIndexTest` 6 例、`IotdbQueryServiceTest#currentBatchReturnsRequestOrderWhenDevicesResolveOutOfOrder` |
| K2 列名定位 + 列数护栏（6-vs-8 列必须抛） | `#batchReadsColumnsByTheirPathLabelRatherThanByPosition`、`#batchLeavesADroppedColumnEmptyInsteadOfShiftingTheNeighbours`、`#batchRejectsAColumnThatWasNeverRequested`、`#batchFallsBackToPositionsOnlyWhenNoColumnCarriesAPath` |
| RAW/插值补采三分支（未撞预算 / 撞预算且列满 / 撞预算且列不足） | `#rawBatchCompletionRefillsTheColumnTheRowBudgetStarved`、`#rawBatchWithoutAFullRowBudgetTrustsTheBatchAndDropsNullCells`、`#rawBatchCompletionFallsBackToTheWholeChunkPastTheCap` |
| 两个回退档能复现改造前行为 | `IotdbQueryServiceTest#batchSizeOneReproducesThePreChangeStatementPerTag`、`IotdbSqlTest#currentBatchAtDeviceLevelKeepsTheLegacyShape` |
| 旧 `current-*` 键必须启动失败、`query-batch-scope` 非法值拒绝 | `IotdbConfigTest` 8 例 |

测试替身的分派依据本轮也从“按 SQL 前缀猜”改成了**显式的「SQL 文本 → 结果表」脚本表**：库级形态
与设备级形态的开头都是 `SELECT LAST`，批量最新值和差分基线又都是 `SELECT LAST…`，靠前缀派发
既脆又会**假绿**——分支判错时用例照样拿到结果、断言照样通过。现在夹具按完整 SQL 文本匹配，
匹配不上直接失败，改一条 SQL 就会让对应用例报错而不是静默走错分支。

### 8.2 L2 —— 契约测试（生产 `192.168.3.199`，只读，门控 `-Diotdb.it=true`）

`IotdbContractProbeIT` **24 例**（原 6 条线缆契约 + 本轮 P1–P10 共 18 例）+ `IotdbMappingProductionIT` 3 例 + `IotdbProductionIT` 11 例 = **38 例**。
门控用例总数为 **45 例**，余下 7 例是 §8.3 的 `IotdbTierParityIT`（同库双跑对数），不属契约层。每项都有实测结论：

> **连接配置（2026-11-18 起）**：四个 IT 类的地址、账号、RPC 端口、测试点位全部经
> `IotdbItConfig` 读取，优先级为「系统属性 `iotdb.it.<key>` → 环境变量 `IOTDB_IT_<KEY>` →
> 模块根 `iotdb-it.properties`（回退仓库根同名文件）」，取不到必填键直接报错并指出键名。
> 测试源码里不再有硬编码的现场地址或账号；`iotdb-it.properties` 被 `.gitignore` 排除。

> **计数更正（2026-11-16 包结构归位轮，按当次 surefire XML 复核）**：本文与模块 README 早先写的
> 「门控 44 / 契约层 37 / `IotdbProductionIT` 10」漏计了
> `IotdbProductionIT#readsBatchRawAndHourlyHistory`（`@ValueSource(strings = {"native"})`，只跑 native 通道）。
> 实际是 **45 / 38 / 11**。该测试源在本轮只改了一行 import（与改造前备份逐行 diff 核实），
> 所以历史那几轮跑的也是 45 例，旧数字是文档漏计，不是用例增减。

| 契约项 | 实测结论 | 钉住的用例 |
|---|---|---|
| 批量 LAST 三种形态择优 | 批量 ``SELECT LAST `a`,`b` FROM dev`` **可用**，且是唯一带回“每条序列自身时间戳”的形态；单测点 `SELECT LAST \`m\`` 与 `LAST_VALUE(m)` 的 `timestamps:[]` | `#batchLastDropsMissingMeasurementsAndCarriesPerSeriesTimestamps` |
| `GROUP BY 5m` / `Nms` 是否被接受 | `5m/30s/1h/1d/300000ms/250ms/1mo` 接受；裸毫秒 `86400000` 报 `no viable alternative` | `#intervalLiteralsAreAcceptedWithinTheServerRowCap`、`#bareMillisecondIntervalIsRejectedAndWideGridsHitTheRowCap` |
| GROUP BY 结果行上限 | **10000 行**，超出 `code=708` | 同上 |
| timestamp 列解析 | 列式响应转置正确，窗口查询 3 行时间戳均 > 0 且保序 | `#columnarShowResponsesAreTransposedAndTimeColumnsParsed` |
| charset | 请求必须声明 `charset=UTF-8`，否则非 ASCII 路径 500 HTML；驱动已恒发 | `#nonAsciiIdentifiersSurviveBecauseTheClientDeclaresUtf8Charset` |
| 探活 | `{base}/ping` 200；`{base}/rest/v2/ping` 404；健康检查走 `SHOW VERSION` | `#pingIsServedAtServerRootWhileTheApiPrefixHasNone` |
| 日界对齐 | 真库上 `Asia/Shanghai` 日界 = UTC 16:00；`zone=UTC` 平移 8h；完整日桶增量恒 300，首桶无基线标 missing | `IotdbMappingProductionIT#resolvesRelativeTagsAndReadsDailyDelta` |
| **P1** 库级 `SELECT LAST` 跨设备（三通道各一次） | 可用；64 请求回 63 行，**行序 ≠ 请求序**，无数据序列整行丢弃 | `#databaseLevelLastMixesDevicesAndDropsSeriesWithoutData` |
| **P2** 库相对表达式的两种引号形态 | 全节点引号与只引测点（业务里已写好的形态）都通过解析；业务形态路径（连字符 + 两级设备）在 LAST/GROUP BY/raw 三种语句里都可用 | `#everyQuotingShapeOfADatabaseRelativeLastExpressionIsAccepted` |
| **P3** 库级 `GROUP BY … FILL(PREVIOUS)` 多列 | 列数不影响行数（行数=桶数），`FILL` 按列独立填充不串列，空列保留 | `#groupedGridKeepsEveryColumnAndFillsEachColumnOnItsOwn`、`#groupedGridRowCapCountsBucketsNotColumns`、`#groupedGridHonoursDescendingOrderAndLimitWithSeveralColumns` |
| **P4** 聚合列名在三通道的实际取值 | 三通道一致，形如 `last_value(root.db.device.measurement)`，函数名大小写原样回显；**列序 == 请求序** | `#aggregateColumnOrderFollowsTheRequestOrderOnEveryTransport` |
| **P5** 库级 `LAST_VALUE … WHERE time < start` 含无历史列 | 序列存在但无点 → **保留空列**；路径不存在 → **整列消失**。差分基线可批量，但护栏必须识别“哪一列不见了” | `#batchedBaselineKeepsTheEmptyColumnButDropsAnUnknownPath` |
| **P6** 成对 `first_value/last_value` 不带 GROUP BY | 整批 1 个逻辑行、`timestamps:[]`、值按列给 → 整窗增量一次往返 | `#pairAggregatesWithoutGroupByReturnASingleLogicalRow` |
| **P7** `FROM root` 跨库 200 表达式 | 可用，批量应答集合与逐位号问答集合一致（这是“批量不改变答案”的证据） | `#crossDatabaseLastAcceptsTwoHundredExpressionsInOneStatement` |
| **P8** 多列 raw 的 union LIMIT | **是合并时间戳行数预算**：10 Hz 列拿满 10 行、1 Hz 列只拿 1 个点，而它单查能拿满 10 个点 → RAW 必须逐位号补采 | `#multiColumnRawSharesOneLimitAcrossColumnsAndStarvesSparseSeries`、`#rawProjectionDropsUnknownColumnsAndKeepsEmptyOnes` |
| **P9** 多列 `GROUP BY + ORDER BY TIME DESC LIMIT n` | 可用，倒序与 LIMIT 对多列同时生效，行数仍只等于桶数 | `#groupedGridHonoursDescendingOrderAndLimitWithSeveralColumns` |
| **P10** `query-batch-scope: root` 在 native/JDBC | 三通道同一条跨库语句都通得过 | `#crossDatabaseSingleStatementReachesEveryTransport` |

> **诚实声明**：上表的结论 2026-11-13 首次跑出时，探针类**跑在批量改造之前**，那只能算设计
> 的前提；本轮改造落码后已用改造后的字节码**复跑过一次 L2**（37 例全绿，16:05），结论与复跑
> 前一致，前提与证据这段空窗已封住。连不上现场时一律写「未执行」，不伪造通过。

**L2 基础设施教训**（写死在用例注释里）：IT 窗口不能按“现在往前推 N 天”取。本轮 `IotdbMappingProductionIT` 就是这样失效的——合成数据是固定区间，8 天后整个窗口落进数据内部，“首桶无基线”自然失败。改为先探测序列自身首末样本、再锚定日界，断言与数据龄期无关。

### 8.3 L3 —— 双跑对数：同库三档（已执行）与 GE ↔ IoTDB（未执行）

本轮批量改造有一道**同库自对自**的前置闸门，落在 `IotdbTierParityIT`（7 例，门控 `-Diotdb.it=true`）：
同一 IoTDB 实例、同一批位号、同一窗口，三个配置各跑一遍——`query-batch-size: 1`（逐位号，改造前
形态）、`query-batch-scope: device`（按设备批量）、`query-batch-scope: database`（按库批量）——
逐序列逐点比对，raw 与最新值 **0 容差**。它专门盯批量改造唯一可能引入的错误：K1/K2 对齐串位
（把 A 位号的值挂到 B 位号名下），这类错误 GE↔IoTDB 对数未必抓得住（两边都可能“看起来有数”）。
位号清单固定含 1 个**有库无点**的序列与 1 个**路径不存在**的位号，正是 G2 那两种“缺一列”的形态。

2026-11-13 16:12 实跑（窗口锚在序列自身末样本 `2026-11-12T01:43:55.728Z`，7 个位号里 5 个有数）：

| 族 | 比对量 | 逐位号补采列数 | 差异 |
|---|---|---|---|
| `current` | 5 个样本 | — | `diffs=0` |
| `raw` | 916 个点 | 1 | `diffs=0` |
| `interpolated` | 91 个网格槽 | 2 | `diffs=0` |
| `sampled` | 222 个网格槽 | — | `diffs=0` |
| `aggregated` | 42 个网格槽 | — | `diffs=0` |
| `intervalSum` | 4 个窗口 | — | `diffs=0` |
| `delta` | 42 个桶 | — | `diffs=0` |

`rawBatchCompletedColumns` 非零，说明「批量 + 逐位号补采」这条分支在生产数据上真的被走过，
不是只在夹具里绿的死代码。**这一档全绿就是把默认档位从 `device` 翻成 `database` 的唯一入场券**，
翻档后重跑的门控 45 例（16:19，全绿）已完成；哨兵用例
`IotdbConfigTest#batchScopeDefaultsToDatabaseNowThatTheDualRunPassed` 双向钉住——无依据地翻回
`device` 或翻成 `root` 都会在这里撞红。

**2026-11-16 结构优化复跑（只读）**：把 `IotdbQueryService`（1629 行）按职责拆成查询编排 +
`internal/query/ResultColumns`（K2 列定位与读取）+ `internal/query/PointMath`（插值网格与累计差分的纯数值
推导），并删除 `HlsMapper` 两个零调用死方法（`instantOrNull`/`isEmpty`）；公共 API 与 `unwrap`
入口不变。复跑结果：L1 离线 **234 例全绿**（计数不变）；门控 IoTDB 45 例重新执行——
`IotdbContractProbeIT` 24、`IotdbMappingProductionIT` 3、`IotdbTierParityIT` 7（七族 `diffs=0`）
全绿；`IotdbProductionIT` 默认位号 `root.factory.device01.temperature` 有 3 例 raw 断言失败，
**经直连只读探针核实是生产数据变化而非回归**：device01 最后一点约 2026-11-07，已落在该用例
「now−7d」滚动窗口（约 2026-11-09 起）之外；改用存活位号 `root.factory.device02.temperature`
（最后一点约 2026-11-10）经 `-Diotdb.it.tag` 覆盖复跑，`IotdbProductionIT` **11/11 全绿**
（raw=1 / hourly=168 / deltas=169）。即拆分前后行为一致，45 例在存活数据上全部通过。GE/HLS
生产 IT 本轮未重跑（历史报告，L1 已覆盖其映射语义）。

**2026-11-16 Hutool/fastjson 迁移 + 门户拆分后复跑（只读，12:25–12:42）**：L1 离线
**278 例全绿**（common 74 / hls 17 / ge 24 / iotdb 162 / starter 1）。门控三层全部重跑：
GE `GeProductionIT` 10 + `GeComparisonIT` 9 **19 例全绿**，逐点归一化对比 `MATCH=520 DIFF=0
OLD_ONLY=0 NEW_ONLY=0`（14 项 PROBE 仍是 17/19 与 24h 口径差异，非 SDK 分歧）；IoTDB 45 例中
**42 绿**，`IotdbProductionIT#readsRawAndHourlyHistory` 3 例失败仍是默认位号
`root.factory.device01.temperature` 近 7 天无原始点（同一实例的存活位号
`root.simdemo.boiler_01.temperature` 复跑 **11/11 全绿**，raw=100 / hourly=168 / deltas=169），
判定为环境数据而非回归；`IotdbTierParityIT` 7 例七族 `diffs=0` 依旧全绿。HLS
`HlsProductionIT` 8 例：6 绿、1 跳过（差分实验性，未开 `-Dhls.it.delta`）、1 例
`invokesNativeHlsEndpoints` 在整批连跑时 `POST /ddb/read_ddbtagvalue` 返回 **HTTP 502**，
单独复跑同一用例（223 s）**通过**并打出 BOUND 探针 `bound=619.2 / avg=625.9 / min=613.9 /
max=636.1`——归因于现场反向代理抖动（与第 2 项风险一致），不换请求形状、不改代码。

**2026-11-16 包结构按职责归位轮复跑（只读，16:50–17:03）**：本轮是**纯结构移动**——
`common/internal` 拆 `http`/`json`、`iotdb/internal` 拆 `sql`/`query`/`transport`、
18 个装配层类下沉 `<vendor>/internal/adapter`、新增 12 个 `package-info.java`；
不改逻辑、断言、配置键与 SQL 语义。复跑结果：

- L1 离线 `mvn -o test` **278 全绿**（common 74 / hls 17 / ge 24 / iotdb 162 / starter 1，
  按当次 23 个 surefire XML 求和，0 fail / 0 err / 0 skip）；
- IoTDB 门控 **45/45 全绿、0 skipped**（默认档位 `database`）。默认位号
  `root.factory.device01.temperature` 本次在 now−30d 窗内有原始点，三通道
  raw=4 / hourly=200 / deltas=169、tags=8、current=25.6，**不需要 `-Diotdb.it.tag` 覆盖**；
  `IotdbTierParityIT` 七族仍 `diffs=0`（`rawBatchCompletedColumns`：raw=1、interpolated=2）；
- GE 门控 **19/19 全绿**，`MATCH=2021 DIFF=0 OLD_ONLY=0 NEW_ONLY=0 PROBE=483`（见 `docs/09` §6.3）；
- HLS 门控 **8 例：7 绿 / 1 跳过 / 0 失败**，`invokesNativeHlsEndpoints` 整批连跑通过（226.9 s），
  未再现 502（见 `docs/10` 抬头复跑记录）；
- 公开面 `javap` 对基线核对：`common.api`+`common.model` **29 个类块逐字一致**；
  下沉的 18 个类**只有 18 个类声明 + 18 个构造器 + 2 个方法**变 public，其余成员 0 处变化。

**这一轮不构成下面这道门槛的证据**：结构移动只证明「搬完还能跑通同样的语句」，
不证明 GE 与 IoTDB 两边取数一致。

**2026-11-18 HLS `RAW` 近似轮复跑（只读，17:20–17:29）**：驱动侧只改 HLS（`RAW` 走历史值接口的
1 秒 `BOUND` 桶），IoTDB 只把生产 IT 的连接配置统一到 `IotdbItConfig` + `iotdb-it.properties`，
**查询语义未改**。三库实跑：

- L1 离线 **279 全绿**（common 74 / hls 18 / ge 24 / iotdb 162 / starter 1）；
- IoTDB 门控 **45/45 全绿、0 skipped**（27.4 s，配置统一后首跑），三通道 raw=1 / hourly=200 /
  deltas=169、`serverVersion=2.0.11`，`IotdbTierParityIT` 七族仍 `diffs=0`；
- GE 门控 **19/19 全绿**，`MATCH=2021 DIFF=0 OLD_ONLY=0 NEW_ONLY=0 PROBE=29`（见 `docs/09` §6.4）；
- HLS 门控 **10/10 全绿**（`-Dhls.it.delta=true`，412 s），`RAW` 300 个 1 秒桶样本、5 分钟差分
  `points=13`（见 `docs/10` 实跑记录）。

**尚未执行的是另一半：GE ↔ IoTDB 的业务级对数**——同窗同位号两边取数，**每条差异必须归因打标**，
只允许 D 类用容差：

| 归因 | 含义 | 容差 | 要求 |
|---|---|---|---|
| A | 映射错（位号→路径） | 无 | 必须清零 |
| B | 时间口径错（zone / shift-hour / 边界） | 无 | 必须清零 |
| C | 底层数据差别（= `X` 类） | 不适用 | 逐例归因 + 业务签字 |
| D | 浮点精度 | 相对 1e-6 | 允许 |

分接口口径：raw 与最新值 **0 容差**；AVG/MIN/MAX/SUM 相对 1e-6；`INTERVAL_SUM` **0 容差 + 逐例归因**；插值**按网格索引逐点比**，不做“时间戳最近匹配”（那会把错格掩盖成小差异）。
它的前置是 §9.3 的位号对照表，**状态：未执行，且仍是切换的硬门槛**——同库三档全绿只证明
“批量没有改变答案”，不证明“IoTDB 里的答案等于 GE 里的答案”。

### 8.4 L4 —— 报表级整表 diff

| 报表 | 为什么选它 |
|---|---|
| 电解日报、硫酸日报、动力日报 | 三类口径各有代表 |
| `ZxpsGenJob` | 480 点固定网格，对格位最敏感 |
| `MesVitriolReportWaterInfoServiceImpl` | 逐日 `parallelStream`，同时测并发与顺序 |
| `BurdenMainOperatingConsoleServiceImpl` | 质量分支最重，且伪造 `UNCERTAIN`（§7.1） |

通过标准：非 `X` 类差异清零；`X` 类逐例走 §7.4 签字。

---

## 9. 前置阻塞项与决策

### 9.1 历史数据迁移

- **主方案：不迁移，按时间窗路由。** `start` 早于切换日 → 走 GE（只读保留），否则走 IoTDB。SDK 已支持多实例与 `switchTo`，回滚 = 改 yml 的 `type: ge`。
- **备选（若决定迁移）需先回答四件事**：导出速率与停机窗口、分片乱序与去重键、断点续传检查点、迁移后逐点对数（等于把 §8.3 全量重跑一遍）。
- 依据：`reference/iotdb接入方案/IoTDB模块API对齐设计.md:277` 自己写明“目前设计只覆盖「查询新库」，不含数据搬迁”。
- 状态：**待业务裁决**。

### 9.2 写入链路与质量码

三档并列见 §7.3。`reference/iotdb接入方案/IoTDB模块API对齐设计.md:217` 记的是“本期只读”。状态：**待业务裁决**（选②则 SDK 需新增伴生 `_Q` 读写，属下一轮）。

### 9.3 位号对照表

策略已定（自动拉基线 + 业主逐条核对，§5.4）。**状态：待执行，且是切换硬门槛**——不是“最好有”，而是事实 2.2 决定的：路径写错不报错，只表现为数据为空。

---

## 10. 排期、灰度与回滚

| 阶段 | 内容 | 工期 | 出口判据 |
|---|---|---|---|
| P0 | 决策周：本文 §2 事实表复核 + §9 三项裁决 | 1 周 | 三项有结论；L2 已复跑（本轮已完成） |
| P1 | SDK 必须改项 + L1 单测 | 2 周 | `mvn -o test` 全绿（**本轮已完成**） |
| P2 | 映射表建设（DDL、归并脚本、覆盖率报告，硬依赖业主核对） | 1 周（与 P1 并行） | `mappedRatio=100%`、存在性校验清单为空 |
| P3 | L2 复跑 + L3 双跑对数 | 1 周 | 同库三档对数**本轮已完成**（`diffs=0`，默认已翻成 `database`）；GE↔IoTDB 侧 A/B 类清零，阻塞在 P2 |
| P4 | L4 报表对数 | 1 周 | 非 X 差异清零，X 类签字 |
| P5 | 灰度与定稿 | 0.5 周 | `mode=shadow` 双跑 7 天无新增差异 |

关键路径：**P0 → P1 → P3 → P4**（P2 与 P1 并行但阻塞 P3）。

灰度按**接口族**推进，不按模块：raw / 最新值（`E`+`A`，风险最低）→ 聚合 → 插值 / 累计值（`X` 类集中在这里，最后放）。每族独立可回退：yml 实例 `type` 改回 `ge`，或用多实例 `switchTo` 切回 GE 实例。

人时模型：本文**不沿用** `docs/13` 的 22.8 人日。那一模型是 GE → 公共 API 的口径；IoTDB 线要另计三块——映射表建设（P2）、质量方案与分支处置（§7）、双跑对数（P3/P4），且都依赖业主侧时间。

---

## 11. 附录：符号映射字典

### 11.1 接口族

下表由 `python3 scripts/gen-migration-doc.py --iotdb-appendix` 直接打印（数据源是同一份
`CALL_MAP` + `IOTDB_CALL_MAP`，`docs/13` §2.7 与本文共用这批符号），**不要手抄改值**：
`iotdb` 列写 IoTDB 的真实 SQL/调用形态，`delta` 列写 §1.2 的分级。

| GE 符号 | 类别 | IoTDB 落地 | 分级 |
|---|---|---|---|
| `DataApiInvoker.getSampledByRequestParamPost` | 实时值 / 采样曲线 / 整窗累计（多形态） | 按 samplingMode/calculationMode 分流：CurrentValue→SELECT LAST（按数据库批量）；Interpolated→SDK 内存网格 start+i*step + 包围原始点线性内插；Calculated+SumValue(count=1)→first_value/last_value 整窗末−首；多桶 SumValue→公共层无等价（GROUP BY 只能出 LAST，不是桶内末−首） | A + X（24 与 17/19 数值不等属 X，需业务裁决） |
| `DataApiInvoker.getCurrentValuePost` | 实时值 | SELECT LAST `dev`.`a`, `dev`.`b` FROM root.db 按数据库分组（跨库拆多条），结果按 Timeseries 列建索引后按入参顺序回填；缺数补 null 占位 | A（X：无逐点质量码） |
| `DataApiInvoker.getInterpolatedByRequestParamPost` | 插值曲线 | SDK 内存网格 + 前后包围原始点线性内插（不用 FILL(LINEAR)，它只在已生成桶之间填）；批量形态每块三条多列 SQL，取数不足逐位号补采 | A + X（窗口外不外推，interpolation-tolerance=0） |
| `DataApiInvoker.getIntervalValueBySumTag` | 区间累计（首末差） | SELECT first_value(`dev`.`m`), last_value(`dev`.`m`), … FROM root.db WHERE time>=s AND time<e（每位号一对列，整批一次往返），内存 BigDecimal 末-首，每位号单点 | A + X（GE 的 17/19 只取 GOOD 点，IoTDB 无此前提） |
| `DataApiInvoker.getHistorianDataByInterpolated` | 插值曲线（便捷重载） | 同上：SDK 内存网格插值，点数由 count/interval 表达 | A |
| `DataApiInvoker.getRawDataByRequestParamPost` | 原始值反查 | SELECT `dev`.`m`, `dev`.`m2` FROM root.db WHERE time>=s AND time<e ORDER BY TIME [DESC] LIMIT<合并行数预算>；多列共享 LIMIT 会饿死稀疏位号，故不足列逐位号补采（maxPoints+1 探测截断，分页 OFFSET 仅逐位号形态） | E（质量码部分 X） |
| `DataApiInvoker.getHistorianIntervalValueByInterpolated` | 区间累计差值列表（插值型） | 旧方法返回逐间隔差值，不是插值曲线：IoTDB 用 GROUP BY ([s,e),`<lit>`) 取 LAST + 起点前基线，相邻相减；不做边界插值、实时值补齐、BAD=0、两位小数 | B（语义不等价，需业务裁决；要旧数值只能保留业务侧后处理） |
| `DataApiInvoker.getHistorianIntervalValueBySampled` | 区间累计差值列表（采样型） | 同族差值列表：last_value 分桶 + 基线相邻相减（`intervalDeltas`），桶网格锚在 begin；参考实现的插值/实时补齐/两位小数不实现 | B + X（语义与时间戳均不等价） |
| `DataApiInvoker.getCalculatedByRequestParamPost` | 服务端聚合 | SELECT `<FN>`(`dev`.`m`), `<FN>`(`dev`.`m2`) FROM root.db GROUP BY ([s,e),`<lit>`)；SumValue=24（末−首）改走 first_value/last_value 单窗单值 | E（INTERVAL_SUM 为 A + X） |
| `DataApiInvoker.getInterpolatedByRequestParam` | 插值曲线（旧 GET） | 同插值族：SDK 内存网格内插，无 GET 线缆对应物 | A |
| `DataApiInvoker.configureQueryResultByPathVariable` | 原生逃生口 | 无等价：IoTDB 结果上限是服务端配置（实测 GROUP BY 10000 行），只读切换不允许改配置 | N |
| `DataApiInvoker.getTrendDataByRequestParamPost` | 原生逃生口（TREND） | 无 TREND 能力：公共层调用即抛 TsdbUnsupportedException，不静默降级 | N |
| `TagsApiInvoker.queryTagsByPath` | 标签查询 | SHOW TIMESERIES root.db.dev.**（列式响应转置为行） | E |
| `TagsApiInvoker.queryTagsByParams` | 标签查询 | SHOW TIMESERIES `<前缀>`.**，nameMask→路径前缀，limit→内存截断 | E（多条件过滤属 B） |
| `TagsApiInvoker.getTagPropertiesPath` | 原生逃生口（属性） | IoTDB 只有 DataType/编码/压缩，无工程单位与量程；量程需落 tsdb_point_mapping | B |
| `TagsApiInvoker.advancedSearchTags` | 原生逃生口（高级检索） | SHOW TIMESERIES + 内存按路径/数据类型过滤 | B |

以下两行**不在 `CALL_MAP` 内**（不是旧 SDK 的方法符号，而是公共模型字段），因此手写：

| 对照项 | GE | IoTDB | 分级 |
|---|---|---|---|
| per-tag `ErrorCode` | 82 处 / 51 文件逐位号返回码 | 整条查询异常，或 `TagSeries.attributes.vendorError`（需 `partialResultAllowed=true`） | `X`（塌缩成两态，§7.2） |
| `Sample.Quality` | 逐点 GOOD/BAD/UNCERTAIN | 非 null→GOOD，null→UNKNOWN；可选 `quality-strategy=range-check` | `X`（§7.1） |

### 11.2 配置项（本轮新增/变更的 IoTDB 选项）

| 键 | 默认 | 校验 | 用途 |
|---|---|---|---|
| `require-query-service` | `true` | 只接受 `true`/`false`，其他写法抛 `TsdbConfigurationException` | 缺 `IotdbQueryService` 时构造期失败，杜绝静默降级 |
| `zone` | `Asia/Shanghai` | 非法 zone id 拒绝 | 自然日/月对齐与班次窗口 |
| `shift-hour` | `8` | 0..23 | 换班小时 |
| `query-batch-size` | `200` | >0 | 单条 SQL 的位号表达式上限。**原 `current-batch-size`，本轮改名且不留别名**，配旧键直接启动失败 |
| `query-parallelism` | `4` | >0 | 块间并行上限。**原 `current-parallelism`，同上改名** |
| `query-batch-scope` | `database` | `database`/`root`/`device`，大小写不敏感，其他值抛 `TsdbConfigurationException` | 分组档位：`FROM` 用库/根/设备。默认 `database` 的依据是 §8.3 同库三档双跑对数全绿；`device` 是改造前行为，保留为现场回退档；`root` 不默认是权限边界而非性能 |
| `query-batch-row-budget` | `10000` | >0 | RAW/插值批量单条 SQL 的行数预算，等于实测服务端上限；撞预算且列不足时逐位号补采 |
| `query-batch-completion-max` | `32` | >0 | 一个块里最多允许多少位号走逐位号补采，超过则整块退回逐位号 |
| `interpolation-tolerance` | `0` | ≥0 毫秒 | 0 表示不外推 |
| `interpolation-raw-limit` | `200000` | >0 | 插值窗口内原始点拉取上限 |
| `tag-mapping-file` | 无 | 读不到即抛 `TsdbDataException` | 装载 `TagPathMapper` |
| `transport` | `rest` | `native\|jdbc\|rest` | 三通道选择；三条读路径共用同一份 SQL |

### 11.3 本文档的生成与同步

- 业务侧计数由上文明令可复跑（`reference/tl-back-enfi` 只读）；实测结论由 §8.2 用例可复跑。
- `scripts/gen-migration-doc.py` 新增 `IOTDB_CALL_MAP`，与 `CALL_MAP` 同符号地提供 `iotdb`（IoTDB SQL/调用形态）与
  `delta`（`E/A/B/N/X`）两列，装载时合并进 `CALL_MAP` 并做双向覆盖断言（漏一条符号即 `AssertionError`）。
  因此 §11.1 的主表可由 `python3 scripts/gen-migration-doc.py --iotdb-appendix` 逐字复打，本文表体即该命令的输出；
  `docs/13` §2.7 与本文共用同一批符号与调用点数据，但**人时模型不共用**（那是 GE → 公共 API 的口径，IoTDB 线另计三块，见 §10）。
  `--iotdb-appendix` 只写 stdout、不落文件，也不改 `docs/13` 产物（重跑生成器后 `diff` 为空）。
- 随本文一并同步（口径以本文实测结论为准）：`docs/12` §5（IoTDB 配置项与能力，含 INTERPOLATED/INTERVAL_SUM）、
  `docs/11`（三通道、任意间隔字面量、`GROUP BY` 行上限、时区与班次口径）、`docs/08` §3（IoTDB 原生能力与缺口）、
  `tsdb-sdk-iotdb/README.md`（能力集与 `options` 清单）。

---

## 附：本轮实测的原始证据片段

```text
# 批量 SELECT LAST：缺的测点不占位、Value 是 TEXT、时间戳逐序列
POST /rest/v2/query  {"sql":"SELECT LAST `flow`, `nope_zzz` FROM root.sdk_it.device01"}
{"expressions":["Timeseries","Value","DataType"],"data_types":["TEXT","TEXT","TEXT"],
 "timestamps":[1794441600000],"values":[["root.sdk_it.device01.flow"],["3087.5"],["FLOAT"]]}

# GROUP BY 行上限（30s × 7d = 20160 格）
{"code":708,"message":"Dataset row size exceeded the given max row size (10000)"}

# 裸毫秒间隔
{"code":700,"message":"line 1:94 no viable alternative at input '... GROUP BY ([..., ...), 86400000'"}

# 非 ASCII 路径：请求头不带 charset 时（Jetty 而非 IoTDB 报的错）
HTTP 500  Content-Type: text/html;charset=iso-8859-1
# 同样语句带 charset=UTF-8 时
{"expressions":[],"column_names":[],"data_types":[],"timestamps":[],"values":[]}

# 探活
GET  /ping            -> 200 {"code":200,"message":"SUCCESS_STATUS"}
POST /rest/v2/ping    -> 404

# 列式响应（SHOW）：values 是列优先
{"expressions":[],"column_names":["Timeseries","Alias","Database", ...11 列],
 "values":[["root.factory.device02.temperature", ... 64 项], ...]}
```
