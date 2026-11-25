# 16 力控 pSpace Web API 接入与验证

> 文档版本：v1.0（2026-11-24）
> 适用模块：`tsdb-sdk-pspace`，驱动类型 `pspace`
> 验证环境：力控 pSpace 7.1 模拟环境 `http://172.16.176.14:8080`（明文 HTTP）
> 依据：力控 pSpace Web API 中文手册 + 7.1 实测（curl 与集成测试）

---

## 1. 结论与范围

pSpace 走官方 **Web API（REST/JSON）**，不依赖 DLL/JNI。模块 `tsdb-sdk-pspace`
把 23 个 REST 端点 + 8081 WebSocket 订阅全部保留在原生层
（`unwrap(PspaceNativeApi.class)`），公共 API 覆盖：

| 公共能力 | 实现 |
|---|---|
| 目录浏览 / 检索 / 单点解析 | `/PointPage`、`/PointFilter` |
| 当前值 | `/RealData` |
| 原始历史 | `/HistDataPage` |
| 区间聚合 | `/HistAggreDataPage` + `aggfunc` |
| 插值 | `/HistAggreDataPage` + `intve` |
| 等间隔采样 | `/HistAggreDataPage` + `orgintve` |
| 累计量差分 | `/HistAggreDataPage` + `endval` 基线折叠 |
| 写入 | `/UpdRealData`（受 `security.allow-write` 门禁） |

不进入公共 API：建/改/删点、报警与事件、WebSocket 订阅、厂商专有聚合函数
（`AVGTL`、`DURGOOD` 等）——全部通过原生 API 调用，不阉割。

---

## 2. 手册 + 7.1 实测协议事实

| 项 | 事实 |
|---|---|
| 请求方法 | 手册写 GET，现场 7.1 对 GET 一律返回 `[CREST]<GET> ... Parse Json data failed`；**全部读接口实测改为 POST + JSON 请求体**，字段名与手册查询参数同名。驱动统一走 POST |
| 响应外层 | `{code,mesg,data}`；`code=0` 成功；**`code=1` 表示"范围内无数据"并附空 `data`**，按空结果处理；其它码（含 `-1`）抛 `TsdbQueryException` 并携带厂商码 |
| 字符集 | 手册允许 GBK/UTF-8；现场无论传什么，实际都按 GBK 收发。`options.pspace.charset` 默认 `gbk`，请求体编码、响应解码、请求 JSON 的 `charset` 字段三者一致 |
| tagids / tagnames | 互斥，同时出现以 tagids 为准；tagids 支持 `1,2,3`、`1-4`、`1,2,3,5-10` 区间写法 |
| fields / fieldnames | 互斥；7.1 只接受 `fieldnames`，数字 `fields` 报 `property id not exist`，驱动一律只用 fieldnames |
| archived | `1=zlib` 本期不支持：配置或调用出现即抛 `TsdbUnsupportedException`，不静默降级 |
| 分页 | `/PointPage` 的 `pagesize`+`pagenumber` 确实生效，响应 `{total, page:{count=总页数, number, size, values[]}}`，按 `page.count` 翻页；`/PointFilter` 实测**忽略**分页参数，一次返回全部匹配。`page.count` 缺失且当前页已满时驱动抛 `TsdbQueryException`，不静默截断 |
| 缓存未热 | 刚启动时目录只返回根节点、`/PointChildren` 报 `Not find children`、`/RealData` 报 `No Valid Data`。厂商确认解法：先 **POST `/Refresh`（body `{}`）** 再查，驱动已内建（见 §6） |
| 质量码 | `192` 好 / `0` 坏，其余按 `UNCERTAIN` 并保留 `vendorQualityCode`；手册无码表，192 只是 `UpdRealData` 的缺省约定 |
| 时间 | `yyyy-MM-dd HH:mm:ss[.SSS]`，含 `24:00:00`（次日 00:00）；解析失败抛 `TsdbDataException`，不兜底当前时间 |
| 聚合间隔 | `interval` 单位是**秒**；输出时间戳为桶起点，驱动不做偏移 |
| 聚合函数 | `avg/minim/maxim/total/count/staval/endval/range/delta/intve/orgintve`；公共映射 `INTERPOLATED→intve`、`SAMPLED→orgintve` |
| 目录样例 | 7.1 `/PointPage` 实测 35 行：31 个 tag + 4 个目录节点；`/CS/CS1` 为 TagType=1（数字量）/DataType=1（BOOL），`/CS/TAG1..TAG20` 为 TagType=2（模拟量）/DataType=10（FLOAT） |
| **聚合响应与 `pv`** | 7.1 对**有数据桶**正常返回 `pv`；只有**空桶**返回 `{"tm":...,"qy":0}` 且不含 `pv`。10 分钟稠密窗口内 `avg` 的 `pv` 与同桶原始点均值一致（如 10:58 `50.711094`）；24h×3600s 仅 3 个非空桶，曾因此误判为“厂商不返回 `pv`” |
| 聚合时间戳 | `avg/minim/maxim/total/count/range/delta/intve/orgintve` 返回桶起点；`staval`/`endval` 返回桶内真实采样时刻（如 `13:20:34.412`），适配器按桶键归一后再折叠 |
| 插值前提 | 数字量点（TagType=1）在窗口内 `intve` 返回 `code=1`（无可插值数据）；模拟量点（TagType=2）正常返回桶数 |
| 鉴权 | REST 无客户端鉴权，账号在服务端 ini；配置了 `auth` 会在 `validate()` 失败并提示，避免误以为鉴权生效 |
| 订阅 | 8081 WebSocket，请求同 `/RealData`；仅原生 `PspaceNativeApi.subscribe(...)`，不进入公共 API |

---

## 3. 原生 API 端点映射

`PspaceNativeApi` 每个端点提供原始 `String` 入口，适配器需要的地方再包类型化方法。

| 端点 | 方法 | 原生方法 |
|---|---|---|
| `/Refresh` | POST（body `{}`） | `refresh()` |
| `/ServerInfo` | POST | `serverInfo()` |
| `/PointChildren` | POST | `pointChildren(parentId)` |
| `/PointChildrenPage` | POST | `pointChildrenPage(parentId, size, page)` |
| `/PointPage` | POST | `pointPage(size, page)` → `parsePoints(...)` |
| `/Point` | POST | `pointsByIds(ids)` / `pointsByNames(names)` |
| `/PointFilter` | POST | `filterPoints(mode, field, value, ignoreCase, fieldNames)` |
| `/Property` | POST | `property(ids, fieldNames)` |
| `/RealData` | POST | `realData(names)` |
| `/HistData` | POST | `histData(ids, btime, etime)` |
| `/HistDataPage` | POST | `histDataPage(ids, btime, etime, size, page)` → `parseHistRows(...)` |
| `/HistAggreData` | POST | `histAggreData(ids, aggfunc, intervalSec, btime, etime)` |
| `/HistAggreDataPage` | POST | `histAggreDataPage(...)` |
| `/HistDataByAggre` | POST | `histDataByAggre(ids, aggfuncs, intervalSec, btime, etime)` |
| `/EventData` / `/EventDataPage` | POST | `eventData(...)` / `eventDataPage(...)` |
| `/AlarmData` / `/AlarmDataPage` | POST | `alarmData(...)` / `alarmDataPage(...)` |
| `/AddPoint` | POST | `addPoint(jsonBody)` |
| `/AlarmAck` | POST | `alarmAck(jsonBody)` |
| `/UpdPoint` | PUT | `updPoint(query, jsonBody)` |
| `/UpdRealData` | PUT | `updRealData(jsonBody)` |
| `/DelPoint` | DELETE | `delPoint(tagIds)` |
| 8081 订阅 | WebSocket | `subscribe(requestJson, Consumer<String>)` → `AutoCloseable` |

统一处理：envelope/裸数据两种响应、非 2xx、`code!=0` 映射异常、GBK 解码、
`archived=1` 拒绝。`/Point` 在部分版本返回裸数组、`items` 可能为对象或数组
（多行时），解析两种都兼容。

### 3.1 代码结构与阅读路线

主代码 16 个类 + 3 个 `package-info`（19 个源文件），依赖方向单向，禁止反向：

```text
pspace/                 PspaceDriver / PspaceClient / PspaceConfig（公共入口）
└── internal/
    ├── adapter/        PspaceSupport + 5 个 *Adapter（公共接口实现）
    │      ↓
    ├── PspaceNativeApi  23 个 REST 端点 + 刷新/重试 + 请求体拼装
    │      ↓
    ├── PspaceHttp       传输、信封、GBK、错误映射
    ├── PspaceResponseParser  点位/实时/历史/分页解析（包内可见）
    │      ↓
    └── PspaceModels / PspaceMapper / PspaceTagResolver / PspaceSubscription
```

推荐阅读顺序：`PspaceDriver` → `PspaceClient` → `PspaceConfig` →
`internal/adapter/*Adapter` → `PspaceNativeApi` → `PspaceHttp` /
`PspaceResponseParser` → `PspaceModels`。

| 要改什么 | 去哪儿 |
|---|---|
| 端点路径、请求方法、JSON 字段 | `PspaceNativeApi`（读接口一律 POST + JSON body，类注释有端点索引） |
| 响应外层、字段别名、`items` 解析 | `PspaceResponseParser` |
| 信封、`code=1`、GBK、zlib 拒绝 | `PspaceHttp` |
| TagType/DataType/质量/时间（含 `24:00:00`） | `PspaceMapper` |
| 缓存未热的 `/Refresh` 与限速 | `PspaceNativeApi.withAutoRefresh` + `PspaceConfig` |
| 目录分页、过滤、`getTag` 正则转义 | `internal/adapter/PspaceTagCatalogAdapter` |
| 历史四模式与聚合函数映射 | `internal/adapter/PspaceHistoryAdapter` |
| 差分基线折叠与复位 | `internal/adapter/PspaceDeltaAdapter` |
| 写入值/质量分派与逐条结果 | `internal/adapter/PspaceWriteAdapter` |
| WebSocket 订阅 | `PspaceSubscription`（仅原生） |

三个 `package-info` 里有相同导航，改代码前先看类注释即可定位。

---

## 4. 公共 API 映射

### 4.1 目录（`TagCatalog`）

- `listTags` → `/PointPage` **服务端分页**：按 `offset/limit` 计算起始页与页内偏移，
  只读取需要的页；`limit=0` 时按服务端 `page.count` 翻到末页，不设人为页数上限；
  `offset` 超过末页返回空列表，不抛异常；
- `nameMask` → `/PointFilter` 的 `match` 模式：按公共契约把 shell 通配符
  `*`/`?` 转成锚定正则（如 `TAG1` → `^TAG1$`、`压力*` → `^压力.*$`），
  不再把原样字符串当 pSpace 正则，因此不会误匹配 `TAG10..TAG19`；
  `/PointFilter` 不支持分页，这条路径一次取全部匹配后本地截断；
- `getTag(name)` → `/PointFilter` + `filtermode=match`，LongName 中的
  `\ . * + ? ( ) [ ] { } ^ $ |` 逐字符转义，再按字符串全等校验，未命中返回 `null`；
- `valueType` / `unit` 服务端不支持，逐页过滤并按“匹配条数”累计 `offset`，
  达到 `limit` 立即停止，不再预读整目录；
- 两条路径解析出的点位都会写入 `PspaceTagResolver` 缓存，后续
  `getTag`/实时值/历史/写入解析直接命中，少一次 `/Point`；
- `TagInfo.tagName=LongName`、`description=Desc`、`unit=EngUnit`，
  `attributes` 保留 `tagId/tagType/dataType/parentId/isNode/hisIsSave` 原始值；
  目录节点也返回（`isNode=true`），是否展示由调用方决定。

### 4.2 当前值（`CurrentValueReader`）

`/RealData` 只传 `tagnames`（避免与 tagids 的互斥歧义）。返回按 `pid` 回填 LongName；
请求中未返回的位号补 `TagSample(tagName, null)`；`No Valid Data` 视为全空，不抛。

### 4.3 历史（`HistoryReader`）

| 公共模式 | 端点 | 说明 |
|---|---|---|
| RAW | `/HistDataPage` | 按 tagid 分组；`maxPoints` 截断置 `truncated` |
| AGGREGATED | `/HistAggreDataPage` | 聚合映射见下；`count` 且无 interval 时按 `(end-start)/count` 换算整秒，不足 1 秒抛不支持 |
| INTERPOLATED | 同上，`aggfunc=intve` | 只支持 `FillPolicy.NONE` |
| SAMPLED | 同上，`aggfunc=orgintve` | 同上 |
| TREND | — | 抛 `TsdbUnsupportedException` |

聚合映射：`AVG→avg`、`MIN→minim`、`MAX→maxim`、`SUM→total`、`COUNT→count`、
`FIRST→staval`、`LAST→endval`、`RANGE→range`、`INTERVAL_SUM→delta`、`BOUND→intve`；
其余抛不支持。厂商专有函数留在原生 API。

聚合时间戳：

| 聚合函数 | 返回时间戳 |
|---|---|
| `avg/minim/maxim/total/count/range/delta/intve/orgintve` | 桶起点 |
| `staval/endval` | 桶内真实采样时刻（适配器原样保留） |

**非 RAW 空桶**：pSpace 对没有数据的桶只返回 `tm` 与 `qy=0`，不含 `pv`。
这些桶**不会**出现在 `TagSeries.getSamples()` 中，改为在 `attributes` 暴露：

| 属性 | 含义 |
|---|---|
| `valueMissingReason` | 固定 `pspace_aggregation_bucket_without_pv` |
| `valueMissingCount` | 整个查询区间内的空桶数，不受 `maxPoints` 截断影响 |
| `valueMissingFirstAt` / `valueMissingLastAt` | 首个/最后一个空桶时间（按实例时间格式） |
| `valueMissingAll` | 本次返回的桶全部为空时置 `true` |

RAW 的 null 值仍保留为 `Sample.ofNull`，不写上述属性。调用方若需要空桶的精确
时间序列，应缩小查询窗口或直接使用原生 API。

### 4.4 差分（`DeltaReader`）

与 HLS/GE 已定稿算法一致，用 `endval` 做**基线折叠**：

1. 桶网格锚定 `start`，多取 1 桶（`btime = start - interval`）作基线；
2. `endval` 返回桶内真实采样时刻，先按
   `btime + floor((tm - btime) / interval) * interval` 归一到桶键，再逐桶末值相减；
   首桶无基线值 → 该点 missing；
3. 末值回落 → 差值取本桶末值并置 `reset=true`；
4. 输出只含 `[start,end)`，`maxPoints` 截断置 `truncated`。

`readByInterval` 要求间隔为整秒（pSpace 无毫秒级聚合），非整秒抛不支持。

**为什么不用厂商 `aggfunc=delta`**：2026-11-24 15:37–15:40 对模拟环境 `/CS/TAG1`
（TagId=15）做只读对比，窗口 `10:55–11:05`、`interval=60s`：

| 桶 | 厂商 `delta` | `endval` | 桶内原始首值 | 关系 |
|---|---|---|---|---|
| 10:57 | -44.493001 | 5.184999 | 49.678 | `5.184999 - 49.678 = -44.493001` |
| 10:58 | 11.092999 | 21.284999 | 10.192 | `21.284999 - 10.192 = 11.092999` |

即厂商 `delta` 是**每个桶内末值−首值**，不是 `DeltaReader` 契约的
“本桶末值−上一桶末值”。同一窗口 10:58 的契约差分应为
`21.284999 - 5.184999 = 16.1`，与 `delta=11.092999` 不同；负值只是桶内回落，
不能据此置 `reset=true`，delta 也无法表达首桶无基线的 `missing`。

累计量每秒累加时同样不能直接用桶内差值。把 60 秒窗口切成 6 个 10 秒桶、
计数器每秒 +1：

| 桶 | 桶内首值 | 桶内末值 | 厂商 `delta`（末−首） | 契约差分（本桶末−上桶末） |
|---|---|---|---|---|
| 第 1 桶 | 101 | 110 | 9 | 110 − 100（基线）= 10 |
| 第 2 桶 | 111 | 120 | 9 | 120 − 110 = 10 |

每桶都漏掉“上桶末到本桶首”的跨边界增量，6 桶累计少 6；每桶只有一个采样点时
`delta` 恒为 0。GE 与 IoTDB 也都是“每桶 LAST + 上一桶末值基线”，pSpace 若改用
桶内 `delta`，同一 `DeltaReader` 会在不同驱动下口径不一致。

因此 `PspaceDeltaAdapter` 坚持 `endval` + 多取一桶基线折叠（只发 1 次
`/HistAggreDataPage`，改 delta 也省不了请求）。例外是
`AGGREGATED + INTERVAL_SUM`：它映射 `delta`，但**强制整窗单桶**（忽略调用方的
`interval/count`），此时 `delta = 窗口末值−首值`，正好符合 `INTERVAL_SUM` 契约。

### 4.5 写入（`WriteSession`）

- `security.allow-write=false` 时 `isWritable()=false`，`write` 抛
  `TsdbUnsupportedException`（与 IoTDB 同口径）；
- `/UpdRealData`：`tagid` 经 resolver 解析；`qy` 映射 GOOD→192、BAD→0，
  `UNCERTAIN`/缺失直接 rejected（无可靠码，不猜）；
- `pv`：DOUBLE/LONG→JSON number，BOOLEAN→`true/false`，STRING→字符串，
  NULL/BYTES→rejected；
- 逐条 `code/mesg` 汇总为 `WriteResult(accepted, rejected, errors)`。

---

## 5. 类型 / 质量 / 时间映射

### 5.1 数据类型

| pSpace DataType | 公共 `ValueType` |
|---|---|
| BOOL | BOOLEAN |
| INT8/16/32/64、UINT8/16/32/64 | LONG |
| FLOAT/DOUBLE | DOUBLE |
| TIME | LONG（epoch 毫秒） |
| STRING/WSTRING | STRING |
| BLOB | BYTES |
| VARIANT | 运行时值推断 |
| 未知 | STRING，`attributes.unknownType=true`，`vendorType` 保留原值 |

### 5.2 质量

| `qy` | 公共 `Quality` |
|---|---|
| 192 | GOOD |
| 0 | BAD |
| 其它 | UNCERTAIN（保留 `vendorQualityCode`） |

手册没有质量码表，192 只是缺省好值约定；IT 中再校准，不声称权威。

### 5.3 时间

- 请求与响应默认 `yyyy-MM-dd HH:mm:ss.SSS`，时区 `GMT+8`，可用
  `time.request.*` / `time.response.*` 覆盖；
- 解析器在标准模式失败时特判 `24:00:00` 为次日 `00:00:00`；
- 解析失败抛 `TsdbDataException`，不兜底当前时间。

---

## 6. 缓存未热自动 `/Refresh`（厂商确认）

pSpace Web 服务启动后点位缓存可能尚未加载。驱动内建兜底，不要求调用方手工处理：

| 项 | 行为 |
|---|---|
| 触发条件 | 目录/点位查询返回空集合、`getTag` 解析不到、`/RealData` 为空或命中 `Not find children` / `No Valid Data` |
| 动作 | `auto-refresh=true` 时 POST `/Refresh` 一次，然后**重试原请求一次**；仍空/仍失败按正常语义返回，不循环刷新 |
| 限速 | 进程内 `refresh-min-interval-ms`（默认 1000ms）限制两次刷新的最小间隔；限速期内不重复发刷新，只重试读取 |
| 不做全局锁 | 只用一个短临界区保护刷新时间戳，不阻塞整个客户端 |
| 写路径 | 仅 `/UpdRealData` 前的位号解析允许触发；建点/改点/删点不自动刷新 |
| 创建/探测 | `create()`/`probe()` 不发刷新，保持只读、无副作用 |

`/Refresh` 是服务端缓存刷新（非数据写入），但仍有瞬时负载，因此默认只在失败时触发、
限速、每请求最多一次，不在启动时主动刷新。

---

## 7. 配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `options.pspace.charset` | `gbk` | `gbk`/`utf-8` |
| `options.pspace.page-size` | `1000` | 分页读取每页条数；按服务端 `page.count` 结束翻页 |
| `options.pspace.auto-refresh` | `true` | 缓存未热时自动 `/Refresh` 并重试一次 |
| `options.pspace.refresh-min-interval-ms` | `1000` | 两次自动刷新的最小间隔 |
| `options.pspace.archived` | `0` | 只接受 0；1 校验即失败 |
| `time.request.pattern` | `yyyy-MM-dd HH:mm:ss.SSS` | 未配置时由 `PspaceConfig` 注入 |
| `time.request.zone` | `GMT+8` | 同上 |
| `time.response.source-zone` | `GMT+8` | 同上 |
| `time.response.pattern` | `yyyy-MM-dd HH:mm:ss.SSS` | 同上 |

分页不设人为页数上限。若服务端 `page.count` 缺失且当前页已满，驱动抛
`TsdbQueryException`，不静默返回部分结果。`pspace.max-pages` 已移除，
不再识别、不生效。

配置了 `auth`（用户名/密码/token）时 `validate()` 直接失败，提示
"pSpace REST 无客户端鉴权，账号在服务端 ini"。明文 HTTP 仍由
`security.allow-insecure-transport` 门禁，与 HLS/GE 一致。

示例见仓库根目录 `tsdb.example.yml` 的 `pspace_main` 段。

---

## 8. 已知限制（调用即抛，不静默降级）

- `archived=1`（zlib）；
- `TREND`；
- 聚合间隔非整秒；
- `FillPolicy` 非 `NONE`（插值/采样/差分）；
- 日历月粒度差分；
- 未知聚合函数；
- 公共 API 无位号 CRUD / 报警 / 订阅（走原生）。

---

## 9. 测试与验证

### 9.1 单元测试

本地 `com.sun.net.httpserver.HttpServer` 仿真，覆盖 envelope/裸数据、错误码、
GBK 字节、zlib 拒绝、分页、`items` 对象/数组、`24:00:00`、聚合映射、
差分折叠、写入门禁与逐条结果、自动刷新与限速。

最近一次运行：2026-11-24 16:06 `mvn -o test`（全仓），pspace 模块 **68 项通过，
0 failures / 0 errors / 0 skipped，BUILD SUCCESS**（以当次 surefire XML 为准；
`target/` 里的门控 IT 旧报告不计入）。

### 9.2 集成测试门禁

| 门禁 | 开关 | 作用 |
|---|---|---|
| 只读 | `-Dpspace.it=true` | 打开只读 IT |
| 写/造数 | `-Dpspace.it.allow-write=true` | 额外打开写门禁；host 还必须在 {127.0.0.1, localhost, 172.16.176.14} 白名单内 |
| 配置来源 | `-Dpspace.it.*` / `PSPACE_IT_*` / `pspace-it.properties`（gitignored） | 后者可参考 `tsdb-sdk-pspace/pspace-it.properties.example` |

```bash
# 只读（8 例；聚合/差分两例依赖窗口内稠密数据，缺失时按 Assumption 跳过）
mvn -o -pl tsdb-sdk-pspace -am -Dpspace.it=true -Dtest=PspaceReadOnlyIT test

# 写/造数（环境已有点位时自动跳过，不建点）
mvn -o -pl tsdb-sdk-pspace -am -Dpspace.it=true -Dpspace.it.allow-write=true \
    -Dtest=PspaceWriteIT -DfailIfNoTests=false test
```

只读 IT 的策略：先 `/Refresh` 再翻页探测现有点位；**已有可用点位就直接读，
不建点、不写数、不删点**。仅当探测为空才进入造数分支（写门禁 + 白名单）。

### 9.3 7.1 模拟环境实测（2026-11-24）

| 项 | 结果 |
|---|---|
| 连通与健康 | UP |
| 目录 | `/Refresh` 后 `/PointPage` 返回 35 行（31 tag + 4 目录节点） |
| 当前值 | 5 个位号 `/RealData` 有值，顺序与入参一致 |
| 原始历史 | 24h 窗口 200 点（按 `maxPoints` 截断），`pv` 正常、`qy=192` |
| 区间聚合 AVG | 10 分钟稠密窗口 / 60s：9 桶全部有 `pv`，其中 7 桶与同桶原始点均值对数一致（容差 1e-3） |
| 插值 INTVE / 采样 ORGINTVE | 按窗口返回桶；数字量点 `/CS/CS1` 的 `intve` 返回 `code=1`，按空结果处理 |
| 差分折叠 | 10 桶中 9 桶非 missing，9 桶与同窗口原始点末值折叠结果对数一致（容差 1e-3）；endval 时间戳已归一到桶起点 |
| 只读 IT | **8/8 通过**（14:10 实跑）；16:09 复跑为 6 通过 + 2 跳过：窗口内无可核对数值的稠密采样，按 `Assumption` 写明「未执行」，不是失败 |
| 写 IT | 1 项 skip——环境已有 35 个点位，按约定不造数 |

### 9.4 未执行（明确挂起，等现场确认）

- 聚合数值与**力控趋势客户端**显示值的一致性：**未执行**——本次只验证
  “聚合值与同窗口原始点自洽”；
- 写入链路（`/UpdRealData` 是否落历史、`/AddPoint`、`/DelPoint`）：**未执行**——
  环境已有点位，按约定不造数；
- 100 个标签实时值延迟对比：**未执行**——模拟环境仅 31 tag；
- WebSocket 订阅推送：未做 IT；
- 《力控 pSpace 接入 POC 报告》：待现场数据补齐后成文。
- **GE ↔ IoTDB 业务级数值对数仍未执行**，不因本次 pSpace 验证通过而声称跨库一致。

---

## 10. 现场落地检查清单

1. `type: pspace`、`base-url` 填现场 Web API 地址，优先 HTTPS/TLS 代理；
2. `options.pspace.charset` 与现场一致（默认 gbk，先小样本验证中文位号）；
3. 只读账号 + `security.allow-write=false`；确需写入时单独评审并记录审计；
4. 启动后第一次查询允许一次自动 `/Refresh`，观察服务端负载；
5. 用 `capabilities()` 判定插值/采样/差分是否可用，不要假设数字量点可插值；
6. 换库只改 `tsdb.yml` 的 `default-instance`，业务代码保持 `TsdbClient`。