# tsdb-sdk-pspace —— 力控 pSpace Web API 驱动

## 1. 模块定位

本模块把力控 pSpace 的 Web API（REST/JSON）接入统一 SDK，包含两层：

- **原生层** `io.github.forget_the_bright.tsdb.pspace.internal.PspaceNativeApi`：
  23 个 REST 端点 + 8081 WebSocket 订阅，返回厂商原始 JSON / 类型化模型，
  能力不被公共层阉割。
- **公共层** `PspaceClient` / `PspaceConfig` / `PspaceDriver`：把目录、当前值、
  历史、聚合、插值、采样、差分与写入映射为公共模型。

驱动类型 `type = "pspace"`，通过 Java SPI（`META-INF/services`）自动发现。

## 2. 目录结构

主代码 **16 个类 + 3 个 package-info**（共 19 个源文件），3 个包。

```
src/main/java/io/github/forget_the_bright/tsdb/pspace
├── PspaceClient.java            门户：装配适配器、健康检查、unwrap、时间解析
├── PspaceConfig.java            配置解析与校验（字符集、分页、刷新、archived、时间默认值）
├── PspaceDriver.java            SPI 入口（type=pspace、能力集、validate/create）
├── internal
│   ├── PspaceNativeApi.java     23 个 REST 端点 + subscribe() + withAutoRefresh
│   ├── PspaceResponseParser.java 点位/实时/历史/分页响应解析（包内可见）
│   ├── PspaceSubscription.java  8081 java.net.http.WebSocket 原生订阅
│   ├── PspaceHttp.java          envelope/裸数据兼容、code=1、GBK、archived 拒绝、错误映射
│   ├── PspaceModels.java        Point/RealValue/HistValue/HistRow/Page 载体
│   ├── PspaceMapper.java        TagType/DataType/质量/值/时间映射
│   ├── PspaceTagResolver.java   LongName → 点位 缓存
│   └── adapter/                 门户装配层：public 但**非公共 API**
│       ├── PspaceSupport.java
│       ├── PspaceTagCatalogAdapter.java   目录：/PointPage、/PointFilter
│       ├── PspaceCurrentValueAdapter.java 当前值：/RealData
│       ├── PspaceHistoryAdapter.java      RAW/AGGREGATED/INTERPOLATED/SAMPLED
│       ├── PspaceDeltaAdapter.java        endval 基线折叠差分
│       └── PspaceWriteAdapter.java        /UpdRealData（受 allow-write 门禁）
```

测试：单测 **68 例**（本地 `HttpServer` 仿真）；门控 IT 在 `pspace/it/`：
`PspaceReadOnlyIT`（8 例，`-Dpspace.it=true`）与 `PspaceWriteIT`
（另需 `-Dpspace.it.allow-write=true`，环境已有点位时自动跳过）。

### 2.1 代码阅读路线

`PspaceDriver` → `PspaceClient` → `PspaceConfig` → `internal/adapter/*Adapter`
→ `internal/PspaceNativeApi` → `internal/PspaceHttp` / `PspaceResponseParser`
→ `internal/PspaceModels`。三个 `package-info` 里有类清单与依赖方向。

### 2.2 按任务找类

| 我要改什么 | 去哪儿 |
|---|---|
| 端点路径、请求方法、JSON 字段 | `internal/PspaceNativeApi`（读接口一律 POST + JSON body） |
| 响应外层/字段别名/`items` 解析 | `internal/PspaceResponseParser` |
| 响应信封、`code=1`、GBK、zlib 拒绝 | `internal/PspaceHttp` |
| TagType/DataType/质量/时间（含 `24:00:00`） | `internal/PspaceMapper` |
| 缓存未热的自动 `/Refresh` 与限速 | `internal/PspaceNativeApi.withAutoRefresh` + `PspaceConfig` |
| 目录分页、过滤、`getTag` 正则转义 | `internal/adapter/PspaceTagCatalogAdapter` |
| 历史四模式与聚合函数映射 | `internal/adapter/PspaceHistoryAdapter` |
| 差分基线折叠与复位 | `internal/adapter/PspaceDeltaAdapter` |
| 写入值/质量分派与逐条结果 | `internal/adapter/PspaceWriteAdapter` |
| WebSocket 订阅 | `internal/PspaceSubscription`（仅原生） |

## 3. 认证、字符集与刷新

- **鉴权**：pSpace REST 没有客户端鉴权，账号在服务端 ini；配置了 `auth` 会校验失败。
- **字符集**：`options.pspace.charset` 默认 `gbk`，请求体、响应、JSON 字段三者一致；
  7.1 现场无论传什么实际按 GBK 收发。
- **缓存未热**：目录/点位/实时值首次为空或命中 `Not find children` / `No Valid Data`
  时，自动 POST `/Refresh`（body `{}`）后重试一次；由 `refresh-min-interval-ms` 限速，
  不循环刷新、不做全局锁。`create()`/`probe()` 不触发刷新。

```yaml
instances:
  pspace_main:
    type: pspace
    base-url: http://172.16.176.14:8080
    timeout:
      connect-ms: 5000
      read-ms: 30000
    options:
      pspace:
        charset: gbk
        page-size: 1000
        auto-refresh: true
        refresh-min-interval-ms: 1000
        archived: 0
      time:
        request:
          pattern: "yyyy-MM-dd HH:mm:ss.SSS"
          zone: GMT+8
        response:
          source-zone: GMT+8
          pattern: "yyyy-MM-dd HH:mm:ss.SSS"
```

## 4. 能力与限制

声明：`TAG_BROWSE`、`TAG_SEARCH`、`CURRENT_VALUE`、`AGGREGATED_HISTORY`、
`INTERPOLATED_HISTORY`、`SAMPLED_HISTORY`、`CUMULATIVE_DELTA`、`WRITE`。

不声明（原生可调）：`TAG_CRUD`、`DELETE`、`ALARM`、`SUBSCRIPTION`、`SYSTEM_ADMIN`、`TREND`。

调用即抛 `TsdbUnsupportedException`：`archived=1`（zlib）、非整秒聚合间隔、
`FillPolicy` 非 `NONE`、日历月粒度差分、未知聚合函数。

已知语义边界：7.1 聚合的**有数据桶**正常返回 `pv`，只有空桶无 `pv`；空桶不进入样本
列表，改由 `valueMissing*` 属性计数。AVG/`endval`/差分已在只读 IT 中与同窗口原始点
对数（AVG 9 桶、差分 9/10 桶非 missing）；与厂商趋势客户端的一致性、写入落历史、
`/AddPoint`、`/DelPoint` 因环境已有点位按约定**未执行**。

**厂商 `delta` ≠ 累计差分**：`aggfunc=delta` 返回“每桶末值−桶内首值”，不是
`DeltaReader` 的“本桶末值−上一桶末值”。累计量每秒累加时，桶内相减会漏掉跨桶边界的
增量（每桶只有一个采样点时恒为 0），也无法表达首桶 `missing` 与回落 `reset`。
因此 `PspaceDeltaAdapter` 坚持 `endval` + 多取一桶基线折叠；`INTERVAL_SUM`
虽映射 `delta`，但强制整窗单桶（忽略调用方的 `interval/count`），此时
`delta` 才等于“窗口末值−首值”。

细节与实测记录见 [docs/16 · pSpace 接入与验证](../docs/16-pSpace接入与验证.md)。