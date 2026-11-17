# IoTDB 三通道、等间隔与累计差分

> 版本：2026-11-13（位号路径模型与按库批量）  
> 适用：`tsdb-sdk-iotdb` 0.1.0，服务端 IoTDB 2.0.11（树模型）

## 1. 目标

IoTDB 驱动不是“再包一层 REST”，而是把 **Java 原生 Session、JDBC、REST v2** 三种访问方式统一到同一套 SQL 语义和公共模型上：

- 现场通过 `options.transport` 选择通道，业务代码不变；
- 三种通道执行同一份 SQL、返回同一种结果表；
- 公共层只暴露 `TsdbClient` 的能力，原生能力仍可通过 `unwrap` 取出；
- 新增等间隔聚合与累计量差分，解决“数据分布不均”和“累计表按周期取差”的问题。

## 2. 三种通道

| transport | 实现 | 连接方式 | 适用场景 |
|---|---|---|---|
| `native` | `IotdbNativeQueryExecutor` | `SessionPool`，RPC `6667` | 高吞吐、长期运行、希望复用连接池 |
| `jdbc` | `IotdbJdbcQueryExecutor` | `jdbc:iotdb://host:6667/` | 已有 JDBC 生态、运维工具、SQL 客户端习惯 |
| `rest` | `IotdbRestQueryExecutor` | `POST /rest/v2/query`，HTTP `18080` | 只开放 HTTP、跨网段/代理、无法直连 RPC |

配置：

```yaml
instances:
  iotdb_main:
    type: iotdb
    base-url: http://192.168.3.199:18080
    auth:
      username: root
      password: root
    options:
      transport: native
      rpc-port: 6667
```

`base-url` 仍然必填：REST 通道直接使用；native/JDBC 从 URL 中取主机名，端口由 `rpc-port` 指定。三者都使用同一个实例级 `TimeConfig` 与安全开关。

## 3. 统一结果表

三种通道的差异只在“取数”，取回后统一转换成 `IotdbResultTable`：

- `columns`：列名；
- `rows`：每行一个 `timestamp` + `Object[] values`；
- REST 的 `values` 是列优先，解析时按列转置；
- native 的 `getColumnNames()` 首列是 `Time`，`RowRecord.getFields()` 不含时间列，取值下标要减 1；
- JDBC 的 `ResultSet` 第 1 列是 `Time`，其余列从 2 开始。

统一结果表让 `IotdbQueryService` 只写一份 SQL 与一份解析逻辑，避免“REST 能查、native 查不出”的切换事故。

## 4. SQL 约束

IoTDB 2.0.11 树模型没有 `date_bin()` / `sample()`，等间隔只能使用 `GROUP BY`：

```sql
SELECT LAST_VALUE(temperature)
FROM root.factory.device02
WHERE time >= 1793491200000 AND time < 1794000000000
GROUP BY ([1793491200000,1794000000000), 1h)
FILL(PREVIOUS)
```

关键约束：

1. `FROM` 的前缀层级与 SELECT 表达式的层级**必须一致**（2026-11-13 实测 M1）：`FROM root.factory`
   配 `` `device01`.`temperature` ``、`FROM root.factory.device01` 配 `` `temperature` `` 都可用；
   层级错配**不报错，只静默返回空结果**。因此两侧由同一个位号路径对象派生，禁止各拼各的；
   库级写法已由探针 P1/P2 在三通道实测通过，表达式里带 `root.` 则被 `code=701` 拒绝（M3）；
2. 多列语句的列名回显**规范绝对路径**，聚合列形如 `last_value(root.factory.device01.temperature)`，
   且三通道一致（实测 G1）；路径不存在时**整列消失**（LAST 是整行丢弃），序列存在但没有点时
   **保留空列**（实测 G2）。所以多列结果必须按列名定位，列数与发射数不符就抛异常，
   绝不能按下标“就近匹配”——丢列出现在中间时，按位置读就是把别人的值挂到这个位号名下；
3. 间隔字面量接受 `<数值><单位>`，单位限 `ms/s/m/h/d/mo`：实测 `5m`、`30s`、`300000ms`、`250ms`、`1mo`
   都能正常分桶；**不带单位的裸毫秒数**（如 `86400000`）被解析器拒绝，报
   `code=700 no viable alternative`；
4. 月桶以 `start` 为基准，必须先归整到当月 1 日 00:00；
5. `FILL(PREVIOUS/LINEAR/NULL)` 只对空桶生效，`NONE` 时不拼 `FILL`；
6. 节点名不能是纯数字；路径在拼 SQL 前经过白名单校验，拒绝引号、分号、空白等元字符；
7. `GROUP BY` 的结果行数受服务端上限约束：实测超过 10000 行返回
   `code=708 Dataset row size exceeded the given max row size (10000)`。行数只等于**桶数**，
   与列数无关（实测 M4/G7）——3 列 × 120 桶仍是 120 行，加宽列数不吃行预算。按此换算，单次查询在
   1s 粒度约可覆盖 2.8 小时、`30s` 约 3.5 天、`5m` 约 34 天。细网格 + 长窗口必须切窗，SDK 不自动切窗，
   以免一次调用悄悄变成 N 次往返；需要长窗口时按 `5m`/`15m` 分粒度取（`IotdbContractProbeIT` 钉住该上限）。

## 5. 等间隔查询

公共入口是 `HistoryQuery` 的 `SAMPLED` 模式：

```java
HistoryQuery query = HistoryQuery.builder()
        .tags("root.factory.device02.temperature")
        .range(start, end)
        .mode(QueryMode.SAMPLED)
        .aggregation(Aggregation.LAST)
        .interval(Duration.ofHours(1))
        .fill(FillPolicy.PREVIOUS)
        .build();
```

驱动不再把粒度卡在四种自然单位上。`IotdbSql.intervalLiteral(long)` 按最大单位归一，
任意 `Duration` 都能下推（`IotdbSql.intervalLiteral`，tsdb-sdk-iotdb `internal/sql/IotdbSql.java:346`）：

| interval 毫秒数 | 字面量 |
|---|---|
| 86400000 的整数倍 | `Nd` |
| 3600000 的整数倍 | `Nh` |
| 60000 的整数倍 | `Nm`（`5m` 曲线即走这条） |
| 1000 的整数倍 | `Ns` |
| 其他 | `Nms`（裸毫秒数会报 `no viable alternative`，故必须带单位） |

所以现场的 9 处 5 分钟曲线与 480 点等分网格都能直接表达；月粒度仍用差分接口的
`TimeGranularity.MONTH`（`Duration` 表达不了自然月）。上限只受上一条的 10000 行约束。

采样查询按分组档位批量发射：一条语句里每位号一个 `last_value(<表达式>)` 列，同一请求混合
多个聚合函数也合成同一条（实测 M5），而**行数只等于桶数**（实测 M4）——加宽列数不吃行预算。
往返数因此是 `ceil(位号数 / options.query-batch-size)`，不再是位号数。

桶起点对齐使用 `options.zone`（缺省 `Asia/Shanghai`），与 `options.time.*` 的文本解析/展示时区
是两件事：后者只影响 `timestampText`，不改变分桶边界。`IotdbClient.shiftWindow(Instant)`
按 `options.zone` + `options.shift-hour`（缺省 8）算出自然班次窗口，换算落点在 SDK 内，
业务侧不需要再打 ±8 小时补丁。

## 6. 累计量差分

接口：

```java
List<DeltaSeries> deltas = client.deltaHistory().read(
        tags, start, end,
        TimeGranularity.DAY,
        Aggregation.LAST,
        FillPolicy.PREVIOUS,
        Direction.FORWARD,
        1000);
```

算法：

1. 按粒度对 `[start, end)` 分桶，每桶取 `LAST`，得到桶末累计值；
2. 查 `time < start` 的最后一个值，作为第一个桶的基线；
3. 内存中相邻相减：`delta = 本桶末值 - 上一桶末值`；
4. 负差视为计数器清零/回绕：`reset=true`，`delta` 取本桶末值；
5. 桶为空：`missing=true`，`delta=null`，并保留上一个有效值继续参与下一桶计算；
6. `Direction.BACKWARD` 时结果反转。

取数是**每个块两条 SQL**：桶序列（每位号一列）+ 基线（同一块的多列 `LAST_VALUE`，
`WHERE time < start`）。差分没有“部分结果”的概念——基线缺失时第一桶就无法判定是否清零，
所以整块失败直接上抛，不伪装成单个位号失败。

`DeltaPoint` 同时带 `timestamp`、`timestampDate`、`timestampText`，前端可直接展示 `timestampText`。

## 7. 点位路径与默认数据库

IoTDB 的序列路径必须是 `root.数据库名.后续节点`，其中 `root` 与「数据库名」这两级是固定的。
现场可能传入完整路径，也可能只传相对位号（如 `device01.temperature`），还可能传入已经加好
反引号的名称。驱动不再分三处校验同一个字符串：`internal/sql/IotdbTagPath` 是**唯一**的解析与校验
入口，一次算全 `nodes`/`absolutePath`/`database`/`devicePath`/`measurement`，SQL 需要的两侧写法
（`FROM` 前缀与 SELECT 表达式）由同一个对象派生，因此 M1 的层级错配与 M3 的 `root.` 前缀
在结构上就不可能出现。

```yaml
options:
  default-database: root.factory
```

| 调用方传入 | 规范绝对路径（拼 SQL 时逐节点加引号） | 说明 |
|---|---|---|
| `device01.temperature` | `root.factory.device01.temperature` | 相对位号补默认库 |
| `root.sdk_it.device01.flow` | `root.sdk_it.device01.flow` | 完整路径原样使用，可跨库 |
| 部分节点带引号的混合写法（`root.sg` 之后逐节点引用） | `root.sg.d1.temperature` | 已加引号的名称不二次处理，按节点解析后规范化重发 |
| 带引号的相对名称（`RL.FCS0107` 下的测点名） | `root.factory.RL.FCS0107.TI-1071815_PV` | 相对名称里可以带引号，默认库补在最前 |
| `device01.temperature`（未配默认库） | 抛 `TsdbDataException` | 不允许猜库名 |

规范化后的 SQL 文本只有这一份来源：首节点 `root` **裸写**，其余节点一律单反引号包裹。

```sql
SELECT LAST `RL`.`FCS0107`.`TI-1071815_PV`, `RL`.`FCS0107`.`TI-1071816_PV` FROM root.`factory`
```

规则：

- 是否「全名称」按**节点**判定（`nodes[0]=="root"` 且 `nodes[1]` 等于默认库的库名节点），
  不用 `startsWith("root." + db)` 字符串前缀——否则 `root.factoryx.d.m` 会被误判成
  `root.factory` 库的位号；
- 完整路径至少四段：`root.db.device.measurement`；相对位号只有在配置了 `default-database`
  时才补全；
- **批量按数据库分组**（`options.query-batch-scope`，见 `docs/12` §5.1）：同库的位号合进一条
  语句，`FROM root.<库>`、表达式为「设备.测点」；跨库的位号拆成多条语句，每条仍只覆盖一个分组，
  由分组枚举当场拒绝混组；
- 掩码/前缀解析（目录浏览）不走映射表：映射表只解决「业务名字 → 序列」的对应关系，
  用它改写掩码会让浏览结果随映射表变化而失去可预期性；
- 返回的 `TagSample/TagSeries/DeltaSeries` 仍保留调用方传入的原始位号名，不因补全而改名。

两条实测边界（不是实现疏漏，无法靠调配置绕过）：

- **测点名本身真含反引号的位号不支持**。反引号在 IoTDB 里是语法而不是内容，实测没有钉过
  双写反引号的解析规则，把它做成读写不对称的猜测就是装作一致；这类位号在解析期
  抛 `TsdbDataException`（未闭合引号、闭引号后紧跟非 `.` 字符、空段同为此类硬错误）。
- **逗号分隔的多位号串直接抛 `TsdbDataException`**，不自动拆分：生产路径上拆分从来没生效过
  （解析器对分隔符就是拒绝），需要多位号时由调用方传 `tags(a, b, c)`。

实测：测试库中创建了 `root.sdk_it`，`device01.flow` 写入 7 天整点累计流量（每小时 +12.5），`device01.temperature` 写入 7 天温度。`device01.flow` 按天取末值得到 `1275, 1575, ..., 3087.5`，相邻完整天差值为 300；当天未结束的最后一个桶只包含已发生的小时，差值允许 0~300。

> 说明：`device02` 的 `insertTablet` 返回 HTTP 500，未纳入测试数据；这不影响 `device01` 的映射、等间隔和差分验证。

## 8. 与公共层的关系

- `Capability.INTERPOLATED_HISTORY`、`SAMPLED_HISTORY`、`CUMULATIVE_DELTA` 依赖统一 SQL 查询服务，
  `IotdbDriver.create` 总是注入它；只有显式配 `options.require-query-service=false` 走兼容构造器时
  才会缺少这三项能力，此时构造期即抛 `TsdbConfigurationException`，不再静默降级；
- 旧构造器 `(IotdbConfig, IotdbRestApi)` 保留，兼容既有 REST 单元测试；
- `unwrap(IotdbQueryService.class)` 可取到统一 SQL 服务，便于执行 `SHOW VERSION` 与直接复用批量原语；
  当前通道由 `unwrap(IotdbConfig.class).getTransport()` 读取——`IotdbQueryService.transport()`
  是纯转发，同一事实已能从配置拿到，本轮已删除；
- 写入仍走 REST `insertTablet`，不随查询 transport 切换；写入默认关闭，需要显式 `allow-write=true`。

## 9. 实测记录

测试环境：`192.168.3.199`，RPC `6667`，HTTP `18080`，`root/root`，IoTDB `2.0.11`。

门控 IT 共 45 项（`-Diotdb.it=true` 才连现场，全程只读）：

- `IotdbProductionIT`（11 项）：三种 transport × 最新值/标签目录/原始+小时等间隔/小时累计差分（9 项），
  外加 native 单通道的多位号批量 RAW + 小时等间隔（1 项）与 `SHOW VERSION` = `2.0.11`（1 项）；
- `IotdbMappingProductionIT`（3 项）：按业务时区取「序列自身首末点」锚定的整周窗口，
  验证自然日对齐、7 个日桶差分（每桶 +300）、以及 `options.zone=UTC` 时桶起点整体平移 8 小时。
  窗口不取 `now - N 天`——数据一老化，「首桶无基线」这类断言就会假失败，这是该用例改过的原因；
- `IotdbContractProbeIT`（24 项）：6 项原有线缆契约（批量 `SELECT LAST` 缺测点不占位、`Value`
  列为 TEXT、时间戳逐序列返回；`SHOW` 列式响应转置与时间列解析；`5m/30s/1h/1d/300000ms/250ms/1mo`
  逐个在行数上限内可用；裸毫秒被拒 + 细网格撞 10000 行上限；非 ASCII 标识符因客户端声明
  `charset=UTF-8` 而存活；探活只在服务根 `/ping`，`/rest/v2/ping` 是 404）+ 本轮新增的
  P1–P10（库级 `SELECT LAST` 跨设备并按三通道参数化、引号形态、多列 `GROUP BY` 的列独立填充、
  聚合列名取值、基线空列 vs 丢列、无 `GROUP BY` 的成对聚合一逻辑行、`FROM root` 跨库 200 表达式、
  多列 RAW 共享 `LIMIT` 饿死稀疏序列、多列降序 `LIMIT`、跨库单语句在三通道是否可用）；
- `IotdbTierParityIT`（7 项）：**同库自对自的双跑对数**。同一实例、同一批位号、同一窗口，
  用 `query-batch-size: 1`（一条 SQL 一个表达式，等价于改造前的逐位号取数）、
  `query-batch-scope: device`、`query-batch-scope: database` 三种配置各取一遍，
  逐序列逐点比对，**0 容差**；七个族分别覆盖最新值、原始值、等间隔、区间聚合、整窗增量、
  累计差分、插值。位号集合故意包含一个「序列存在但无点」和一个「路径不存在」，
  因为这两种缺值形态（保留空列 vs 整列消失，实测 G2）正是对齐护栏最容易处理错的形状。

**这一栏的实测状态必须如实记**：上列 24 项探针最早那次全绿跑的是**改造前**的代码，而其中
P10 一类会走 SDK 生成的语句，所以改造后必须复跑。2026-11-13 对 `192.168.3.199:18080` 实跑三次：

| 时刻 | 内容 | 结果 | 当时默认档位 |
|---|---|---|---|
| 16:05 | 改造后的 L2 复跑（11 + 3 + 24） | 38 全绿、0 skipped | `device` |
| 16:12 | `IotdbTierParityIT` 同库双跑对数 | 7 全绿，七个族 `diffs=0` | `device` |
| 16:19 | 默认翻档后整体复跑 45 项 | 45 全绿、0 skipped | `database` |
| 2026-11-16 12:25 | Hutool/fastjson 迁移 + 门户拆分后整体复跑 45 项 | 42 绿；`IotdbProductionIT#readsRawAndHourlyHistory` 3 例失败是默认位号 `root.factory.device01.temperature` 近 7 天无原始点，改 `-Diotdb.it.tag=root.simdemo.boiler_01.temperature` 后 `IotdbProductionIT` **11/11 全绿**（raw=100 / hourly=168 / deltas=169）；`IotdbTierParityIT` 七族仍 `diffs=0` | `database` |
| 2026-11-16 16:50 | 包结构归位轮（纯移动）整体复跑 45 项 | **45 全绿、0 skipped**，默认位号 `root.factory.device01.temperature` 本次有原始点（raw=4 / hourly=200 / deltas=169），**无需 `-Diotdb.it.tag` 覆盖**；七族 `diffs=0` | `database` |

> 表中 2026-11-13 与 12:25 三行的项数已按 2026-11-16 的 surefire XML 复核更正：原文写 37/44，
> 漏计 `IotdbProductionIT#readsBatchRawAndHourlyHistory`（native 单通道）。测试源除 import 外未改，
> 故那几轮实际执行的也是 38/45 例。

双跑对数通过的这一次，就是 `options.query-batch-scope` 默认值从 `device` 翻成 `database` 的
入场券（`docs/14` §8.3 记了逐族数字）。仍然要守住两条：

- 探针的输出是本轮批量设计的**前提**，不是本轮代码的**证据**——引用结论时必须说清是哪一次跑；
- **GE ↔ IoTDB 的业务级对数（映射表 + 两边同窗取数、A/B/C/D 归因）尚未执行**，那是切换的
  硬门槛，与默认档位无关。现场连不上时一律写「未执行」，不伪造通过。

命令：

```powershell
mvn -q -pl tsdb-sdk-iotdb -am test `
  "-Dtest=IotdbProductionIT,IotdbMappingProductionIT,IotdbContractProbeIT,IotdbTierParityIT" `
  "-DfailIfNoTests=false" `
  "-Diotdb.it=true"
```

没有 `-Diotdb.it=true` 时这些测试整体跳过，不访问现场。地址、账号、点位按
「系统属性 → 环境变量 → `iotdb-it.properties`」读取，凭据不进仓库。

## 10. 已知限制

- 写入未按 transport 分流，仍是 REST `insertTablet`；
- IoTDB 没有逐点质量码，非空值为 `GOOD`，空值为 `UNKNOWN`；由此带来的累计值与报表口径差异
  不可通过调容差消除，见 `docs/14` §7.4 的裁决清单；
- `GROUP BY` 结果 10000 行上限（§4 第 7 条），细网格长窗口需要业务侧切窗；
- 查询不存在的序列返回空结果而不是异常，映射表写错会表现为「数据静默为空」；
- 月粒度只支持差分接口，`HistoryQuery` 的 `Duration` 不能表达自然月（自然月对齐本身可用 `1mo`）；
- HLS/GE 已按同一 `DeltaReader` 契约接入：GE 用 `calculated LastRawValue=19` 并通过生产测试；HLS 用 `BOUND` 近似桶末值，语义待现场确认；
- **RAW 与插值的批量只减少“取满”的那部分往返**：多列 RAW 的 `LIMIT` 是合并时间戳的行数预算，
  被挤掉点的位号逐位号补采（结果与逐位号查询逐点相同，补采过的位号带 `rawBatchCompleted=true`）；
  补采列数超过 `options.query-batch-completion-max` 时整块退回逐位号。密集采样 × 多位号 × 大
  `maxPoints` 的窗口往返下界与改造前相同，**不能据“已支持按库批量”推算时延收益**；
- **测点名本身真含反引号的位号不支持**，解析期直接抛 `TsdbDataException`，理由见 §7。
