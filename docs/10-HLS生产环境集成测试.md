# HLS 生产环境集成测试

本文说明如何对和利时 HLS 真实环境执行只读集成测试。测试类位于
`tsdb-sdk-hls/src/test/java/io/github/forget_the_bright/tsdb/hls/it/HlsProductionIT.java`，
默认不参与常规 `mvn test`，必须显式开启。

> **实测记录（2026-11-10）**：现场直连地址 `http://172.18.11.43:9000` 不能直接
> 调用 API，已通过反向代理 `http://10.10.7.155:9000` 实际跑通，7 个用例全部通过。
> 本次实测暴露并修复了 token 获取方式、`Content-Type`、全量标签目录超时和历史
> 结束时间越界四类问题，详见第 0 节。
>
> **等间隔/差分注意**：HLS 没有 `LAST` 聚合，也没有原生原始点接口，只能用
> `TagValue_Bound` 充当区间边界值。这是现场生产同款迂回方案：`SAMPLED` 取历史值
> 接口的 `BOUND` 列，`RAW` 用 1 秒 `BOUND` 桶近似，差分与 `INTERVAL_SUM` 的基线
> 同样来自 `BOUND`。该方案已按用户 2026-11-18 的定案落地（见 §8.1），不再作为
> 阻塞项；换厂商版本或换现场时按 §8.1 末尾的复核步骤重新确认。
> `readsSampledHistoryAndCumulativeDelta` 因为历史查询成本高（多次请求、单次可达数百秒），
> 默认跳过，需要时加 `-Dhls.it.delta=true` 执行。
>
> **复跑记录（2026-11-16 12:38–12:42，Hutool/fastjson 迁移后）**：命令
> `mvn -o -pl tsdb-sdk-hls -am test "-Dtest=HlsProductionIT" "-DfailIfNoTests=false" "-Dhls.it=true"`。
> 整批结果 `Tests run: 8, Failures: 0, Errors: 1, Skipped: 1`：6 绿、
> `readsSampledHistoryAndCumulativeDelta` 按预期跳过（未开 `-Dhls.it.delta`）、
> `invokesNativeHlsEndpoints` 在整批连跑时 `POST /ddb/read_ddbtagvalue` 返回 **HTTP 502**（约 103 s 后）；
> 单独复跑同一用例 `Tests run: 1, Errors: 0`（223 s，主要耗时在全量标签目录），并打出 BOUND 探针
> `bound=619.2 / avg=625.9 / min=613.9 / max=636.1, time=1794717661`。
> 结论：迁移后线缆与厂商侧一致，502 归因于反向代理抖动，不改代码；BOUND 单点样本落在
> `[min, max]` 内且不等于 AVG，仍**不足以**判定它是否为区间末值，差分口径维持未确认。
>
> **复跑记录（2026-11-16 16:56–17:03，包结构归位轮，纯结构移动）**：同一条命令。
> 整批结果 `Tests run: 8, Failures: 0, Errors: 0, Skipped: 1`，BUILD SUCCESS，总耗时 433 s：
> **7 绿 + 1 跳过**（跳过的仍是 `readsSampledHistoryAndCumulativeDelta`，未开 `-Dhls.it.delta`）。
> **本轮 `invokesNativeHlsEndpoints` 在整批连跑中直接通过（226.9 s），没有再现 502**，
> 因此不需要单跑复核；`browsesRealTagCatalog` 199.2 s（全量目录约 4.3 MB）。
> BOUND 探针 `bound=613.2 / avg=600.3 / min=594.7 / max=613.3, time=1794733048`；
> 16 个点位全部有值、质量 GOOD（vendorQuality=1）。
> 本轮只把 HLS 的 6 个装配层类移到 `hls/internal/adapter`、共享工具 import 换到
> `common.internal.http`/`json`，**未改请求形状、断言与超时**。
> 结论：502 是代理侧间歇故障而非 SDK 回归，这一判断得到本轮支持；但 **BOUND 是否等于区间末值
> 仍未确认**（本次 bound 恰好等于 max，单点样本不能证明语义），差分口径继续维持未确认。
>
> **定案（2026-11-18）**：用户确认 HLS 确实没有 `LAST`/原生原始点，`BOUND` 就是现场生产
> 用来处理该问题的迂回方案，实际生产环境也这么解决。因此：
> ① 关闭“BOUND 未确认 / 差分不得用于生产结算”这一阻塞性表述，改为「生产同款迂回方案 +
> 适用前提 + 换版本时复核」（见 §8.1）；
> ② 公共 `QueryMode.RAW` 在 HLS 上改为调用历史值接口（`interval=1` + `NeedQueryBound=true`），
> 用 1 秒 `BOUND` 桶近似原始点，并在 `TagSeries.attributes` 标注
> `rawSource=hls_history_1s_bound`、`rawApproximate=true`；
> ③ 上面的历史探针记录原样保留，作为升级/换现场时复核的对照基线。
>
> **实跑记录（2026-11-18 17:20–17:27，HLS `RAW` 近似与 `INTERVAL_SUM` 轮）**：命令
> `mvn -o -pl tsdb-sdk-hls -am test "-Dtest=HlsProductionIT" "-DfailIfNoTests=false" "-Dhls.it=true" "-Dhls.it.delta=true"`。
> 结果 `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`，412.41 s，`BUILD SUCCESS`（17:27:41）。
> 关键输出：
> - `RAW`（`readsRawHistoryThroughOneSecondBoundBuckets`）：`samples=300`，
>   first=`2026-11-18 17:10:50`、last=`2026-11-18 17:15:49`，10 分钟窗按 1 秒桶返回，
>   `rawSource=hls_history_1s_bound`、`rawApproximate=true` 断言通过；
> - `INTERVAL_SUM`（`readsIntervalSumFromBoundedEdges`）：`last 24h sum=-9.4`。该位号是指示型
>   （非累计量）点位，整窗末−首没有业务含义，与「`INTERVAL_SUM` 只适用于累计量点位」的说明一致；
>   用例只断言「恰好 1 点、时间戳=窗口起点、值是有限数」；
> - `SAMPLED` + 差分：BOUND 探针 `bound=589.0 / avg=590.1 / min=583.3 / max=595.6`，
>   5 分钟间隔差分 `points=13`，小时等间隔 `samples=25`；
> - 当前值 16/16 点位有值、质量全部 GOOD（vendorQuality=1）。

> **实跑记录（2026-11-19 09:35–09:42，差分基线折叠轮）**：同一条命令，
> `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`，438.17 s，`BUILD SUCCESS`。
> 关键变化：
> - 5 分钟差分 `points=12`：1 小时窗口 = 12 个桶，扩展窗取回的「起点前最后一个桶」只作基线、
>   不输出；旧记录的 13 是 `limit + 1` 越界，已修正；
> - 稠密网格下整个差分只有 **1 次** `readHistory`（起点 = 窗口起点 − 桶长），
>   只有样本数对不上或首值为空时才追加 1 次批量 `BOUND` 兜底；
> - 其余 9 个用例（含 `RAW` 300 个 1 秒桶、`INTERVAL_SUM` 单点）断言未改，全部通过。

---

## 0. 本次实测结论（反向代理环境）

| 项 | 结果 |
|---|---|
| 测试地址 | `http://10.10.7.155:9000`（反向代理到 `172.18.11.43:9000`） |
| 结果 | `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS |
| 当前值 | 16/16 点位全部有值，质量均为 GOOD（vendorQuality=1） |
| 历史 | 首个点位 24h AVG/MIN/MAX/BOUND 各 25 点 |
| 标签目录 | 可返回全量目录，约 4.3 MB，耗时可达 60s 以上 |

采样值（2026-11-10 11:43:47）：`E101FIT1001_DACA_PV=609.6`、
`E101FIT2001_DACA_PV=588.4`、`E101FIT3001_DACA_PV=709.4`、
`E131FIT1003_DACA_PV=321.3`、`E102FIT1016_lastvalue_pv=223.173`。

### 0.1 本次修复的四个问题

1. **token 获取方式**：该代理只接受
   `POST /user/get_token` + 请求头 `userid`/`secretkey` + JSON body
   `{"userid":"...","secretkey":"..."}`，且 `Content-Type` 必须是精确的
   `application/json`（带 `; charset=UTF-8` 也返回 `4002 Content type is not json`）。
   驱动已改为“先 POST JSON，失败再回退原生 GET 头模式”。
2. **业务请求的 Content-Type**：同一代理对 `read_ddbtagvalue`、`read_hdbtagvalue`
   也要求精确 `application/json`，否则当前值全部为空。`HlsHttpClient` 已统一使用
   精确值；GET 也显式带上该头。
3. **全量标签目录超时**：`GET /ddb/read_alltagname` 返回约 4.3 MB，30s 读取超时
   会触发 `TsdbTimeoutException`；生产 IT 默认读取超时已放宽到 180s，重试次数降为 1。
4. **历史结束时间越界**：HLS 对 `endTime > 当前时间` 返回
   `code=3025 Start time or end time is later than current time`。测试的结束时间
   改为 `anchor.minusSeconds(1)`，不再使用 `anchor.plus(5min)`。

### 0.2 与原生 HLS SDK 的对比

原生 `HLS_REST_SDK` 的 `TokenHolder` 用
`GET /user/get_token`，凭证放在 `userid`/`secretkey` 请求头（`ParamPosition.HEADER`）。
在这个反向代理下它同样只能得到 `code=4002 Content type is not json`，即
**原生 SDK 也存在该 token 兼容问题**，不是本 SDK 引入的。业务接口
`read_ddbtagvalue`/`read_hdbtagvalue` 的请求体字段与原生 SDK 一致；本 SDK 额外
兼容了大小写字段名，并修正了原生 `HistorianRequest` 把 MAX/MIN 写反的 bug。

---

## 1. 测试目标

与 GE 的 `GeProductionIT` 类似，但按 HLS 的真实能力裁剪：

| 用例 | 覆盖内容 | 默认跑 |
|---|---|---|
| `healthAndCapabilities` | 健康检查、能力声明（含 `RAW_HISTORY`）、只读约束 | 是 |
| `browsesRealTagCatalog` | 全量标签目录、精确查标签 | 是 |
| `readsConfiguredPointsCurrentValues` | 批量读取现场点位当前值，逐点输出值/质量/时间 | 是 |
| `readsAggregatedHistoryForFirstPointWithValue` | 24h AVG 区间聚合 | 是 |
| `readsAllFourAggregationColumns` | AVG / MIN / MAX / BOUND 四列，验证 AVG 无质量码的标注 | 是 |
| `readsRawHistoryThroughOneSecondBoundBuckets` | `history().read(RAW)`：1 秒 `BOUND` 桶近似，校验 `rawSource` / `rawApproximate` 标注与样本时间/值 | 是 |
| `readsIntervalSumFromBoundedEdges` | `AGGREGATED + INTERVAL_SUM`：两侧 1 分钟 `BOUND` 边缘探测；窗口边缘无值时按 `Assumptions` 记录跳过 | 是 |
| `invokesNativeHlsEndpoints` | 原生 `queryAllTags`、`getDDBTagValue`、`getHDBTagValueAll`、`rawRequest` | 是 |
| `unwrapsNativeApi` | 原生逃生口 | 是 |
| `readsSampledHistoryAndCumulativeDelta` | `SAMPLED` 与 `CUMULATIVE_DELTA`（含 5 分钟任意间隔差分） | 否，需 `-Dhls.it.delta=true` |

HLS 没有插值、趋势、写入、告警、采集器、系统管理等接口，因此没有复制 GE 的
interpolated/trend/system 用例；`RAW` 也不是协议级原始点，而是 1 秒 `BOUND` 桶近似
（见 §8.1），测试校验的是替代路径与来源标注，不声称与原始点等价。这不是漏测，
而是 HLS 厂商接口本身的能力边界。

---

## 2. 配置

测试按以下优先级读取配置：

1. 系统属性 `-Dhls.it.xxx=...`
2. 环境变量 `HLS_IT_XXX`
3. `hls-it.properties`（当前目录、`tsdb-sdk-hls/`、用户主目录）
4. 代码内默认值（仅超时、时间格式、点位等非敏感项）

示例文件：[hls-it.properties.example](../tsdb-sdk-hls/hls-it.properties.example)。

| 键 | 说明 | 示例 |
|---|---|---|
| `baseUrl` | HLS REST 根地址 | `http://10.10.7.155:9000` |
| `userId` | 动态 token 用户名 | `httpIO` |
| `secretKey` | 动态 token 密钥 | 现场提供 |
| `token` | 静态 token（可选，填了就忽略 userId/secretKey） | — |
| `connectTimeoutMs` | 连接超时，必须为正数 | `5000` |
| `readTimeoutMs` | 读取超时，必须为正数；全量标签目录需放宽 | `180000` |
| `maxAttempts` | 请求重试次数；生产 IT 默认 1，避免超时翻倍 | `1` |
| `tokenExpireSeconds` | token 本地缓存秒数 | `43199` |
| `allowInsecureTransport` | 现场是 http，必须为 true | `true` |
| `verifyHostname` | HTTPS 主机名校验 | `true` |
| `responseSourceZone` | 响应文本按哪个时区解析；HLS 是 epoch，实际不影响数值 | `GMT+8` |
| `responseTargetZone` | `timestampText` 展示时区 | `GMT+8` |
| `responsePattern` | `timestampText` 模板 | `yyyy-MM-dd HH:mm:ss` |
| `historyHours` | 历史窗口小时数 | `24` |
| `intervalSeconds` | 聚合步长（秒） | `3600` |
| `points` | 逗号/分号/空白分隔的点位 | 见示例 |

### 与现场原始配置的差异

现场配置里：

```yaml
connection-timeout: -1
read-timeout: -1
cache-model: Redis
```

- SDK 为保证可诊断性，要求超时必须为正数；集成测试默认 5s 连接 / 180s 读取，不能直接照搬 `-1`。
- `cache-model: Redis` 是参照 SDK 的 token 缓存实现细节；本 SDK 的 HLS 驱动使用进程内 token 缓存，不读取该键，配置了也不会生效。
- `token-expire-seconds: 43199` 会作为 token 本地缓存时长使用。

---

## 3. 现场点位

默认点位来自用户提供的矿山列表，已写入
`hls-it.properties`（该文件被 `.gitignore` 排除）：

```text
E101FIT1001_DACA_PV
E101FIT2001_DACA_PV
E101FIT3001_DACA_PV
E131FIT1003_DACA_PV
E131FIT2003_DACA_PV
E131FIT3003_DACA_PV
E102FIT1013_DACA_PV
E102FIT2013_DACA_PV
E102FIT1016_lastvalue_pv
E102FIT1017_lastvalue_pv
E102FIT2016_lastvalue_pv
E102FIT2017_lastvalue_pv
E102FIT3016_lastvalue_pv
E102FIT3017_lastvalue_pv
E405FIT0007_lastvalue_pv
E405FIT0008_lastvalue_pv
```

HLS 对不存在/无值的点位不会报错，而是少返回一条。驱动会按请求顺序补齐为
`sample == null` 的 `TagSample`，因此：

- 批量请求不会因为个别点位不存在而整体失败；
- `readsConfiguredPointsCurrentValues` 要求至少有一个点位返回当前值，否则测试失败并打印完整点位列表；
- 每个有值点位都会打印值、质量码、时间和格式化文本，便于现场核对。

---

## 4. 运行

### PowerShell

```powershell
mvn -q -pl tsdb-sdk-hls -am test `
  "-Dtest=HlsProductionIT" `
  "-DfailIfNoTests=false" `
  "-Dhls.it=true"
```

### cmd.exe

```bat
mvn -q -pl tsdb-sdk-hls -am test "-Dtest=HlsProductionIT" "-DfailIfNoTests=false" "-Dhls.it=true"
```

注意 PowerShell 会把 `-Dtest=...` 里的点号误解析，必须加引号；这也是
GE 生产测试曾经踩过的坑。

### 不带 `-Dhls.it=true`

测试会被 JUnit `Assumptions` 跳过，不会访问网络：

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 1
```

这是正常的，用于保证 `mvn test` 不会误连生产。

---

## 5. 预期输出

连接成功后会先打印一行：

```text
HLS production IT: baseUrl=http://10.10.7.155:9000, points=16, first tag with value=..., anchor=...
```

随后每个有值点位打印：

```text
HLS point E101FIT1001_DACA_PV: value=123.45, quality=GOOD, vendorQuality=1, time=2026-11-10 10:20:00
```

历史用例打印点数和首点时间。不含差分时最终 surefire 应为：

```text
Tests run: 10, Failures: 0, Errors: 0, Skipped: 1
```

（10 个用例里 9 个默认跑 + 差分用例按 `-Dhls.it.delta=true` 门控跳过；加 `-Dhls.it.delta=true`
后 Skipped 为 0，本轮实测就是 10/10 全绿。）

如果某个点位没有历史数据，`readsAggregatedHistoryForFirstPointWithValue`
会失败并明确提示该点位在最近 N 小时没有 AVG 历史；可把 `historyHours`
调大，或把 `points` 改成有历史数据的点位。

---

## 6. 只读与安全

- 测试类只调用查询接口：token、标签目录、DDB 当前值、HDB 区间聚合。
- HLS 驱动本身没有写入能力，`writeSession().isWritable()` 恒为 false；测试也断言了这一点。
- `hls-it.properties` 含现场凭据，已被 `.gitignore` 排除；不要提交到仓库。
- 生产账号应只授予读权限；不要在共享终端或日志中输出 `secretKey`。
- 现场是明文 HTTP。测试显式设置 `allowInsecureTransport=true` 仅用于内网联调；
  长期方案应通过 TLS 代理或厂商支持的 HTTPS 接入。

---

## 7. 常见问题

| 现象 | 原因/处理 |
|---|---|
| 连接超时 | 未连 VPN，或地址不可达；先用 `Test-NetConnection <host> -Port 9000` 确认 |
| `code=4002 Content type is not json` | 代理要求精确 `application/json`，且 token 接口要 POST JSON + 两个凭证头；驱动已兼容 |
| `code=3025 Start time or end time is later than current time` | 历史查询结束时间不能晚于服务器当前时间；用 `anchor.minusSeconds(1)` |
| 标签目录 502 / 读取超时 | 全量目录约 4.3 MB，代理可能限流；把 `readTimeoutMs` 调到 180000，或改用已缓存的标签列表 |
| `TsdbAuthenticationException` / 401 | `userId`/`secretKey` 错误，或 token 过期；核对现场账号 |
| `plaintext HLS transport is disabled` | 没设 `allowInsecureTransport=true` |
| `timeout must be > 0` | 照搬了现场 `-1`；改成正数毫秒 |
| 所有点位都是 `no value` | 点位名不在 HLS 中、点位无当前值，或账号无权读取该点位 |
| AVG 历史为空 | 点位在该时间段没有历史数据；调大 `historyHours` 或换点位 |
| `getTag` 很慢 | HLS 没有单标签查询接口，`getTag` 会拉全量目录后线性扫描；测试只调用一次 |

---

## 8. 与 GE 生产测试的差异

| 项 | GE | HLS |
|---|---|---|
| 传输 | HTTPS，自签名证书需 trust-all | HTTP，需显式允许明文 |
| 认证 | OAuth password grant | user-id/secret-key 换 token |
| 原生接口 | 92 个端点，`GeEndpoint` + invoke | 4 类接口，DTO + 固定路径 |
| 历史能力 | RAW/AGGREGATED/INTERPOLATED/SAMPLED/TREND | 区间聚合 AVG/MIN/MAX/BOUND + `SAMPLED`；`RAW` 用 1 秒 `BOUND` 桶近似 |
| 写入 | 有接口但测试禁用 | 驱动本身无写入 |
| 告警/系统/采集器 | 有只读查询 | 无 |
| 质量码 | 0/1/2/3 | 0=BAD、1=GOOD |

---

## 8.1 `BOUND` 语义与本 SDK 的处理方式（生产同款迂回方案）

### 是什么

HLS 历史接口（`/hdb/read_hdbtagvalue`）对每个时间桶返回四个统计列，而不是原始点：

| 列 | 含义（厂商侧） |
|---|---|
| `TagValue_AVG` / `Quality_AVG` | 桶内平均及其质量（注意：厂商不返回 `Quality_AVG`） |
| `TagValue_MIN` / `Quality_MIN` | 桶内最小及其质量 |
| `TagValue_MAX` / `Quality_MAX` | 桶内最大及其质量 |
| `TagValue_Bound` / `Quality_Bound` | **边界值**，厂商文档未写明取桶首、桶末还是插值边界 |

HLS 没有 GE 的 `LAST` 聚合，也没有原生原始点接口。SDK 需要「桶的边界值」来代表该桶的
累计量读数，因此统一用 `TagValue_Bound` 顶替。这是**现场生产同款做法**——用户
2026-11-18 明确确认「实际生产环境也是这么解决的」，因此本 SDK 照此实现，并把
「为什么用历史值接口代替 raw」写进代码注释（`HlsHistoryAdapter` / `HlsDeltaAdapter`）。

### 三处用法

| 公共入口 | 厂商请求 | 说明 |
|---|---|---|
| `history().read(RAW)` | `POST /hdb/read_hdbtagvalue`，`interval=1`、`NeedQueryBound=true` | 每 1 秒桶取 `TagValue_Bound` 作为该秒的代表值，**近似**原始点；仅 `FORWARD`，`FillPolicy` 仅 `NONE`；结果 `attributes` 带 `rawSource=hls_history_1s_bound`、`rawApproximate=true` |
| `history().read(SAMPLED)` | 同上，按调用方 `interval` | 取 `BOUND` 列作为桶边界值 |
| `deltaHistory().read(...)` / `INTERVAL_SUM` | 同上，差分基线取起点前 1 桶；`INTERVAL_SUM` 用窗口首/末 1 分钟各探一次 | 差值口径为「本桶边界值 − 上桶边界值」，归属时间戳沿用公共层规则 |

### 适用前提（不是阻塞项）

- 适用于累计量点位（流量总量、电量等）的差分与整窗增量；瞬时量差分没有物理意义。
- `RAW` 是秒级近似：亚秒变化会被桶内代表值吸收；需要真正原始点时应接厂商 Historian
  客户端，本 SDK 不声称等价。
- 时间戳与数值精度受 `BOUND` 与秒桶限制，跨库比对前先统一精度口径（见 README §7.4）。
- 上述限制已写入 `TagSeries.attributes` 与公共 Javadoc，调用方在数据里就能看见来源。

### 现场探针记录（保留作复核基线）

| 时间 | bound | avg | min | max | 观察 |
|---|---|---|---|---|---|
| 2026-11-16 12:42 | 619.2 | 625.9 | 613.9 | 636.1 | 落在 [min,max] 内，不等于 AVG |
| 2026-11-16 17:03 | 613.2 | 600.3 | 594.7 | 613.3 | 恰好等于 MAX |
| 2026-11-16（首个样本） | 613.2 | 600.3 | 594.7 | 613.3 | 同上 |

单桶单点无法区分「桶末恰好是最高点」与「BOUND 是 MAX 的别名」；该歧义不影响
本 SDK 的既定口径（生产同款方案），但换版本/换现场时必须复核。

### 换厂商版本 / 换现场时的复核步骤（只读）

这不是使用前置条件，而是升级时的回归检查：

1. 选一个在 1 分钟内会产生多个原始点的变化型位号（流量、电流、液位），避开常量位号。
2. 取窗口 `[t0, t0+5min)`，用 `interval=60` 请求历史，记录 5 个桶的
   `AVG/MIN/MAX/BOUND` 与质量码。
3. 在同一窗口用 `interval=1` 请求历史（秒级 `BOUND` 序列），作为原始点的近似；
   若能拿到厂商 Historian 客户端的原始值，优先用它交叉验证。
4. 对每个 60 秒桶，从秒级序列里取桶内**首值**和**末值**，与 60 秒桶的 `BOUND` 比较：
   - `BOUND == 桶内末值` → 与 IoTDB/GE 的桶末口径一致，无需调整；
   - `BOUND == 桶内首值` → 差分整体前移一个桶，`INTERVAL_SUM` 的边缘探测也要改；
   - 两者都不等 → 第三种语义（插值边界/保持值），需厂商确认公式后重新定义差分口径。
5. 用至少 3 个不同位号、2 个不同窗口重复第 2–4 步，避免偶然命中极值。
6. 结论变化时：同步更新本节、README 能力矩阵与 docs/15，并复跑
   `readsSampledHistoryAndCumulativeDelta`（`-Dhls.it.delta=true`）、
   `readsRawHistoryThroughOneSecondBoundBuckets` 与 `readsIntervalSumFromBoundedEdges`。

> 秒级 `BOUND` 只是原始点的近似：若位号存在亚秒变化，第 3 步的交叉验证会失真，
> 此时必须以厂商 Historian 客户端读到的原始值为准。

---

## 9. 现场执行后建议记录

运行完成后，把以下信息补到本文或现场记录：

- 实际返回当前值的点位数量、失败点位；
- 首个有值点位的值、质量、时间；
- AVG/MIN/MAX/BOUND 各自点数；
- 是否出现 token 刷新、超时或权限问题；
- 若 `getTag` 全量扫描过慢，现场标签总量大约多少。

这些结果决定后续是否需要为 HLS 增加本地标签目录缓存。
