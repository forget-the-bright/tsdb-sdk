# tsdb-sdk —— 工业时序数据库统一 SDK

一套 Java API 统一访问 **和利时 HLS**、**GE Proficy Historian**、**Apache IoTDB**；
换底层数据库时通用查询代码不改，厂商特有能力通过原生逃生口完整保留。
默认**只读**：写入只有 IoTDB 在显式开启 `allow-write: true` 后才在公共层可用。

`Java 17+` · `Maven 多模块` · `Spring Boot 2.7 自动装配（可选）` · `Apache-2.0` · `离线单测 258 项全绿`

**怎么读这份文档**

| 你是 | 直接看 |
|---|---|
| 第一次用，想跑通一次查询 | [2. 引入到项目](#2-引入到项目) → [3. 配置](#3-配置) → [4.1 快速上手](#41-纯-java-快速上手) |
| Spring Boot 项目 | [2.3 starter](#23-方式-bspring-boot-starter) → [3.2 配置示例](#32-完整配置示例) → [4.2 注入](#42-spring-boot-注入) |
| 要换底层数据库 | [5. 三库底层区别](#5-三种时序库的底层区别) → [6. 能力矩阵](#6-能力支持矩阵) → [7. 换库指南](#7-换库指南给需要切换底层数据库的人) |
| 要查某个方法落到哪条厂商端点 | [6.5 精简对照](#65-公共方法--原生对照精简版) → [docs/15 逐方法参考](docs/15-API参考与原生对照.md) |
| 要用厂商特有能力 | [4.6 原生逃生口](#46-原生逃生口) → [docs/15](docs/15-API参考与原生对照.md) |
| 想知道哪些还没做 | [9.4 未执行 / 未确认清单](#94-未执行--未确认清单) |

---

## 1. 项目介绍

### 1.1 概述

`tsdb-sdk` 把三套风格完全不同的工业时序库收敛成一组公共接口：标签目录、当前值、历史查询、
累计量差分、写入、探活。业务代码只依赖 `TsdbClient` 与公共数据模型，换库时**通用查询部分**改配置即可；
质量码、时间语义、聚合白名单等不可消除的差异必须改业务，分界清单见 [§5.5](#55-换库时会变什么) 与 [§7.4](#74-不可消除的差异需业务签字不用容差掩盖)。

同时它**不做能力阉割**：每个驱动都提供原生门面，通过 `client.unwrap(...)` 取出后可以直接调
厂商原始端点、原始 JSON、任意 SQL。GE 的 92 个 REST 端点、HLS 的全部聚合重载、IoTDB 的
`query`/`nonQuery`/`insertTablet` 都在原生层可达。

适合两类使用者：

- **初学者**：照着第 2–4 章就能在自己的项目里跑通第一次查询；
- **需要切换底层数据库的人**：第 5–7 章给出三库的底层差异、能力矩阵与迁移门槛，
  哪些是「改配置就行」、哪些是「必须改业务」写得很清楚。

### 1.2 主要功能

1. **统一公共 API**：`TagCatalog` / `CurrentValueReader` / `HistoryReader` / `DeltaReader` /
   `WriteSession` / `HealthCheck` 六个能力接口，三库共用同一套方法签名与数据模型。
2. **一库一驱动，按需引入**：`tsdb-sdk-hls`、`tsdb-sdk-ge`、`tsdb-sdk-iotdb` 各自独立，
   通过 `ServiceLoader` SPI 自动发现；只用一个库时不必引入另外两个。
3. **能力协商，不静默降级**：`client.capabilities().supports(...)` 先判断，不支持的操作抛
   `TsdbUnsupportedException`（错误码 `TSDB-UNSUPPORTED`），**不会返回空结果冒充成功**。
4. **单活动实例 + 运行期热切换**：`manager.switchTo(name)` 先建新客户端、成功后才关旧的；
   新连接失败时旧客户端仍可用，不会把应用切进一个连不上的库。
5. **原生逃生口，能力不阉割**：`unwrap(HlsNativeApi.class)` / `unwrap(GeNativeApi.class)` /
   `unwrap(IotdbRestApi.class)` / `unwrap(IotdbQueryService.class)`，与公共层共享同一份连接与认证。
6. **统一质量码与时间模型**：质量码归一到 `GOOD/UNCERTAIN/BAD/STALE/UNKNOWN` 并保留厂商原始码；
   时间统一 `Instant`（绝对时刻），另给 `timestampDate` 与 `timestampText` 两种便利形态。
7. **IoTDB 三通道 + 批量查询**：REST v2 / Native SessionPool / JDBC 共用同一份 SQL；
   按库分组的批量原语把「逐位号 N+1 次往返」压成「一块一条 SQL」。
8. **Spring Boot 自动装配**：引 starter 后注入 `TsdbManager` 即可，容器销毁时自动 `close()`。

### 1.3 解决了哪些问题

| 使用者的痛点 | 本 SDK 的回答 |
|---|---|
| 三库 API 风格不一：HLS 是宽表 REST、GE 是长表 REST + OAuth、IoTDB 是 SQL | 公共层统一为 `TagSeries{tagName, samples[]}`，宽表在驱动内拆列，SQL 结果在驱动内转置 |
| 质量码 `GOOD` 在 HLS 是 `1`、在 GE 是 `3`，直接比对会把好数据判成坏数据 | `QualityNormalizer` 映射到统一枚举，同时保留 `vendorQualityCode` 供追溯 |
| 时间混乱：HLS 用 epoch 秒、GE 用本地时间字符串、IoTDB 用 epoch 毫秒 | 入参出参一律 `Instant`；协议时间、业务日历时区、文本展示时区**三层分开配置** |
| 参照 SDK 是静态单例，同 JVM 不能连两套库，测试互相污染 | 全部实例化；`TsdbManager` 管理多实例，`client(name)` 可同时取两个客户端做对数 |
| 换库要重写业务代码 | 业务只依赖公共接口；换库改 `default-instance` + 驱动依赖 |
| 封装层把厂商能力阉割了，遇到特有参数只能绕过 SDK | 原生层完整保留：GE 92 端点、HLS 15 个厂商形态方法、IoTDB 任意 SQL 与 `insertTablet` |
| 位号一多，GET 的 query 串超 URL 长度限制 | GE 高频批量读全部改 POST，`tagNames` 放 body 明文分号拼接 |
| 逐位号查询产生 N+1 次网络往返，报表慢 | IoTDB 按 `query-batch-scope` 分块，一块一条多列 SQL；最新值按列名回填而非按序 |
| 多列批量查询把稀疏位号的数据挤掉了，报表静默缺数 | 多列 RAW 的 `LIMIT` 是合并行数预算，SDK 对欠采列**逐位号补采**并留 `completed` 账本 |
| 错误只有厂商文案，调用方无法分类处理 | 9 个统一错误码 + `isRetryable()` + 实例/类型/操作/厂商码上下文字段 |

### 1.4 优点与取舍

**优点**

1. **可切换**：公共层语义在三库上一致，区间左闭右开、空集合返回空列表、缺失位号不伪造 0。
2. **不阉割**：原生层与公共层并列，不是「封装后只剩几个方法」。
3. **失败响亮**：不支持就抛、配置写错就启动失败、映射表坏了就拒绝创建驱动，不做静默兜底。
4. **可验证**：258 项离线单测 + 三库门控生产只读 IT + IoTDB 同库三档双跑对数（七族 `diffs=0`）。
5. **中文文档齐全**：15 篇设计/使用文档 + 6 个模块 README + 中文 Javadoc（`docs/apidocs`）。

**取舍（明确不承诺的事）**

1. **不承诺性能收益数字**：批量原语减少往返次数是结构事实，但具体延迟收益取决于现场网络与数据密度，
   本 README 不给「快 N 倍」这类未实测结论。
2. **不静默降级**：HLS 没有原始历史就是没有，不会用聚合值假装原始点。
3. **公共层只抽通用能力**：GE 的告警/采集器/系统运维端点、HLS 的宽表多列、IoTDB 的任意 SQL
   都只在原生层，公共层不声明 `ALARM` / `SYSTEM_ADMIN` / `TAG_CRUD`。

### 1.5 支持哪些库

| 库 | 驱动模块 | 状态 |
|---|---|---|
| 和利时 HLS（HolliSys 实时/历史库） | `tsdb-sdk-hls` | ✅ 已实现，生产只读验证 |
| GE Proficy Historian（REST API） | `tsdb-sdk-ge` | ✅ 已实现，生产只读验证 + 逐点对比 |
| Apache IoTDB 1.x / 2.x（树模型） | `tsdb-sdk-iotdb` | ✅ 已实现，三通道生产只读验证 |
| 力控 pSpace | — | ❌ **未实现，也没有空壳驱动**。Java 包名、Maven 坐标、JDBC 驱动类/URL、REST 路径与鉴权细节尚未拿到；接入步骤见 [docs/07](docs/07-新增驱动接入指南.md) |

---

## 2. 引入到项目

### 2.1 坐标与版本现状

| 项 | 值 |
|---|---|
| `groupId` | `io.github.forget-the-bright` |
| `version` | **`0.1.0`**（根 pom 与六个模块的 parent 引用一致） |
| Java 包根 | `io.github.forget_the_bright.tsdb`（注意：包名用**下划线**，坐标用**连字符**） |
| 字节码基线 | Java 8（`maven.compiler.release=8`） |
| 许可证 | Apache-2.0 |

六个模块的 `artifactId`：

| artifactId | 用途 |
|---|---|
| `tsdb-sdk-bom` | 依赖清单（`dependencyManagement`），统一版本 |
| `tsdb-sdk-common` | 公共 API、数据模型、配置加载、异常体系、SPI |
| `tsdb-sdk-hls` | 和利时 HLS 驱动 |
| `tsdb-sdk-ge` | GE Proficy Historian 驱动 |
| `tsdb-sdk-iotdb` | Apache IoTDB 驱动 |
| `tsdb-sdk-spring-boot-starter` | Spring Boot 自动装配 |

> ⚠️ **`0.1.0` 尚未发布到 Maven 中央仓库。** 版本号已经是正式版号（不是 `SNAPSHOT`），
> 但中央仓库里还没有这个坐标，现在直接写它去中央仓库拉会失败。当前请先本地安装或推私服：
>
> ```bash
> git clone <your-repo-url> tsdb-sdk && cd tsdb-sdk
> mvn -DskipTests install
> ```
>
> 发布到中央仓库后**坐标与版本号都不变**，只需删掉本地 install 步骤，业务代码与配置不用改。
> 发布工程**已经在根 pom 里就绪**：`url`/`scm`/`developers`、`distributionManagement`
> （Sonatype s01 staging，`id=central`）、以及 `release` profile 下的
> `central-publishing-maven-plugin` 0.4.0 + gpg 签名 + sources/javadoc jar 都已配好。
> **尚未执行的是发布动作本身**（需要 Central token 与 GPG 密钥，跑 `mvn -Prelease deploy`），
> 所以中央仓库现在还查不到这个坐标。

### 2.2 方式 A：BOM + 单驱动（推荐，只用一个库）

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.forget-the-bright</groupId>
      <artifactId>tsdb-sdk-bom</artifactId>
      <version>0.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- 公共层必引 -->
  <dependency>
    <groupId>io.github.forget-the-bright</groupId>
    <artifactId>tsdb-sdk-common</artifactId>
  </dependency>
  <!-- 只引你实际要连的那一个驱动 -->
  <dependency>
    <groupId>io.github.forget-the-bright</groupId>
    <artifactId>tsdb-sdk-iotdb</artifactId>
  </dependency>
</dependencies>
```

把 `tsdb-sdk-iotdb` 换成 `tsdb-sdk-ge` 或 `tsdb-sdk-hls` 即可。驱动 jar 里带
`META-INF/services/io.github.forget_the_bright.tsdb.common.spi.TsdbDriver`，
`TsdbManager.Builder.build()` 在没显式注册驱动时会自动 `discoverDrivers()`，无需手工装配。

### 2.3 方式 B：Spring Boot starter

```xml
<dependency>
  <groupId>io.github.forget-the-bright</groupId>
  <artifactId>tsdb-sdk-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

**实测依赖事实，引入前请知道**：

- starter 对 `common` + `hls` + `ge` + `iotdb` 都是 **compile 依赖**，所以引一个 starter
  会把**三个驱动全部带进来**；
- `tsdb-sdk-iotdb` 对 `iotdb-session` 2.0.11 与 `iotdb-jdbc` 2.0.11 也是 **compile（非 optional）**，
  即使你只用 REST 通道，这两个 jar 仍会进入 classpath；
- `spring-boot-autoconfigure` 2.7.13 是 `optional`，由你的 Boot 版本提供。

只连一个库、在意包体积时，用方式 A 而不是 starter。

starter 的装配机制是 `META-INF/spring.factories`（Spring Boot 2.7 风格），
**面向 Spring Boot 2.7.x**。Boot 3.x 改用 `AutoConfiguration.imports`，本仓库当前没有该文件，
需要自己 `@Import(TsdbAutoConfiguration.class)`。

### 2.4 方式 C：纯 Java，无 Spring

```xml
<dependency>
  <groupId>io.github.forget-the-bright</groupId>
  <artifactId>tsdb-sdk-common</artifactId>
  <version>0.1.0</version>
</dependency>
<dependency>
  <groupId>io.github.forget-the-bright</groupId>
  <artifactId>tsdb-sdk-ge</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
TsdbManager manager = TsdbClients.manager()
        .config(TsdbConfigLoader.load(Paths.get("tsdb.yml")))
        .discoverDrivers()
        .build();
```

### 2.5 依赖与运行环境

| 项 | 版本 / 要求 |
|---|---|
| JDK | 8 及以上（字节码基线 Java 8） |
| Maven | 3.6+ |
| HTTP 客户端 | hutool-http + hutool-core **5.8.25** |
| JSON | fastjson **1.2.83**（AutoType 关闭、safeMode 开启、只做树解析） |
| YAML | snakeyaml **1.30** |
| 日志门面 | slf4j-api **1.7.36**（实现由你的应用提供） |
| IoTDB 客户端 | iotdb-session **2.0.11**、iotdb-jdbc **2.0.11**（仅 IoTDB 驱动需要） |
| Spring Boot | **2.7.13**（仅 starter，optional） |
| 单元测试 | junit-jupiter **5.8.2**（test scope） |

 Jackson 已完全移除，不会与你的应用抢 Jackson 版本。

离线构建（依赖已在本地仓库时）：

```bash
mvn -o -DskipTests install
mvn -o test
```

### 2.6 发布到中央仓库后会怎样

坐标（`groupId` / `artifactId`）与版本号 `0.1.0` 都不变，只需删掉本地 install 步骤，
让 Maven 直接从中央仓库解析。公共 API 与配置键不因发布而改变。

---

## 3. 配置

### 3.1 两种配置形态

| 形态 | 文件 | 加载方式 | 适用 |
|---|---|---|---|
| 独立 YAML | `tsdb.yml` | `TsdbConfigLoader.load(Paths.get("tsdb.yml"))` | 纯 Java 应用 |
| Spring 配置 | `application.yml` | starter 的 `TsdbProperties`（`@ConfigurationProperties(prefix = "tsdb")`）→ `toConfig()` | Spring Boot 应用 |

两者的**键结构完全相同**，都是 `tsdb.*`。仓库根目录的 [`tsdb.example.yml`](tsdb.example.yml)
是权威模板，复制为 `tsdb.yml` 后按现场修改（`tsdb.yml` 已被 `.gitignore` 排除，不会误提交凭据）。

### 3.2 完整配置示例

下面这份覆盖全部公开键，地址用文档专用占位值，凭据用环境变量占位符。

```yaml
tsdb:
  # 运行时只启用这一个实例；换库改这里，或调用 manager.switchTo(...)
  default-instance: iotdb_main
  # 建连失败是否立即抛异常。false 时允许先启动、后失败
  fail-fast: true

  # 全部实例共享的安全默认；单个实例可用 options 覆盖
  security:
    allow-write: false               # 默认只读。开启写入必须显式评审
    allow-insecure-transport: false  # 默认禁止明文 HTTP
    verify-hostname: true            # HTTPS 主机名校验
    audit-enabled: true              # 写/管理操作审计日志

  instances:

    # ---------- 和利时 HLS ----------
    hls_main:
      type: hls
      protocol: rest                 # 描述性字段，驱动不读它（见 3.3 说明）
      base-url: https://hls.example.com:9000
      auth:
        user-id: ${HLS_USER}         # 走 userid 请求头 + body 字段
        secret-key: ${HLS_SECRET}    # 走 secretkey 请求头 + body 字段
      timeout:
        connect-ms: 5000             # 连接超时；禁止 -1（不限）
        read-ms: 30000               # 读超时。全量目录很慢时需要放大
      retry:
        max-attempts: 2
        backoff-ms: 200
      options:
        path-prefix: ""              # HLS 通常无前缀
        token-expire-seconds: 1800   # token 缓存有效期，默认 1800
        # 现场是明文 HTTP 时必须显式放行，否则构造期报错
        # allow-insecure-transport: true
        # HLS 协议时间本身是 epoch 秒；time.* 不改变请求/响应数值，
        # 只供 parseTimestamp/formatTimestamp 与 timestampText 展示
        time:
          response:
            source-zone: GMT+8
            target-zone: GMT+8
            pattern: "yyyy-MM-dd HH:mm:ss"

    # ---------- GE Proficy Historian ----------
    ge_main:
      type: ge
      protocol: rest
      base-url: https://historian.example.com
      auth:
        username: ${GE_USER}         # OAuth password grant，放查询串
        password: ${GE_PASSWORD}
        client-id: ${GE_CLIENT_ID}   # Basic 头左半
        client-secret: ${GE_CLIENT_SECRET}
      timeout:
        connect-ms: 5000
        read-ms: 30000
      retry:
        max-attempts: 1
        backoff-ms: 200
      options:
        path-prefix: /historian-rest-api   # 默认值，全部 /v1/... 端点的前缀
        token-expire-seconds: 1800
        trust-all-certificates: false      # 仅内网自签名联调时临时 true
        # GE 是唯一实际消费文本时间的驱动。
        # 下例是「GE 侧 UTC、界面要 GMT+8」的口径：
        #   请求：Instant 按 request.zone 格式化成 GE 要求的字符串
        #   响应：无时区的 TimeStamp 先按 source-zone 解释成绝对时刻
        #   展示：timestampText 按 target-zone + pattern 输出
        # 若某项目 GE 本身就用 GMT+8，把 request.zone 与 response.source-zone 都改成 GMT+8
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

    # ---------- Apache IoTDB ----------
    iotdb_main:
      type: iotdb
      protocol: rest
      base-url: http://192.0.2.30:18080   # REST 服务端口
      auth:
        username: ${IOTDB_USER}
        password: ${IOTDB_PASSWORD}
      timeout:
        connect-ms: 5000
        read-ms: 180000
      retry:
        max-attempts: 1
        backoff-ms: 200
      options:
        path-prefix: /rest/v2        # 只作用于查询路径；探活 /ping 在服务根
        transport: rest              # rest / native / jdbc，默认 rest
        rpc-port: 6667               # native 与 jdbc 用这个端口
        # 业务可只传 device01.temperature，驱动补成 root.factory.device01.temperature
        # 不配置时，调用方必须传完整的 root.数据库.设备.测量点 路径
        default-database: root.factory
        # 业务日历时区：自然日/月分桶边界与班次窗口对齐用它
        # 与下面 time.* 是两件事（time.* 只影响文本解析与展示）
        zone: Asia/Shanghai
        shift-hour: 8                # 换班小时 0..23，供 shiftWindow() 使用
        # 装配不到统一 SQL 查询服务时构造期抛异常，不静默退回逐位号 REST
        require-query-service: true
        query-batch-size: 200        # 单条 SQL 携带的位号表达式上限；设 1 即逐位号
        query-parallelism: 4         # 块间并行上限（一条 SQL 内部不再切）
        # 批量分组档位：database（FROM 用库）/ root（跨库合成一条）/ device（改造前行为）
        query-batch-scope: database
        query-batch-row-budget: 10000    # RAW/插值批量单条 SQL 的合并行数预算
        query-batch-completion-max: 32   # 一块内最多几位号走逐位号补采，超过整块退回
        interpolation-tolerance: 0       # 插值外推容差毫秒，0 = 不外推
        interpolation-raw-limit: 200000  # 单次插值拉取包围原始点的点数上限
        # 可选：位号 → 规范路径映射表，每行 "业务位号 = root.库.设备.测点"
        # 文件读不到或映射值不是完整 root. 路径，驱动 create 阶段直接启动失败
        # tag-mapping-file: conf/iotdb-tag-mapping.properties
        # 明文 HTTP 需显式放行
        allow-insecure-transport: true
        # IoTDB 协议时间本身是 epoch 毫秒；time.* 不改变 SQL 与响应数值
        time:
          response:
            source-zone: GMT+8
            target-zone: GMT+8
            pattern: "yyyy-MM-dd HH:mm:ss"
```

> `protocol` 是**描述性字段**：全仓只有 `TsdbInstanceConfig.getProtocol()` 这一个 getter，
> 没有任何驱动读它。传输方式由 `base-url` 的 scheme 和 IoTDB 的 `options.transport` 决定，
> **改 `protocol` 不会切换通道**。

### 3.3 三库各自的关键 options

**HLS**

| 键 | 默认 | 含义 / 配错后果 |
|---|---|---|
| `path-prefix` | `""` | 端点前缀，HLS 通常为空 |
| `token-expire-seconds` | `1800` | token 缓存有效期 |
| `allow-insecure-transport` | 继承 `security`（`false`） | 现场是 HTTP 时必须显式 `true`，否则拒绝建连 |

**GE**

| 键 | 默认 | 含义 / 配错后果 |
|---|---|---|
| `path-prefix` | `/historian-rest-api` | 配错则全部端点 404 |
| `trust-all-certificates` | `false` | 自签名联调临时 `true`；生产应导入根证书并恢复校验 |
| `token-expire-seconds` | `1800` | token 缓存有效期 |
| `time.request.*` / `time.response.*` | 见 3.5 | **GE 是唯一实际消费文本时间的驱动**，配错直接导致时间错位 |

**IoTDB**

| 键 | 默认 | 含义 / 配错后果 |
|---|---|---|
| `transport` | `rest` | `rest`/`native`/`jdbc`；后两者用 `rpc-port` |
| `rpc-port` | `6667` | native/JDBC 端口 |
| `path-prefix` | `/rest/v2` | 只作用于查询路径；`/rest/v2/ping` 是 404，探活走服务根 `/ping` |
| `default-database` | 无 | 未配时传相对位号抛 `TsdbDataException` |
| `zone` | `Asia/Shanghai` | 业务日历时区，决定自然日/月分桶边界 |
| `shift-hour` | `8` | 换班小时 0..23 |
| `require-query-service` | `true` | 装配失败时**构造期抛异常**；设 `false` 会退回 REST 兜底且能力集缩小 |
| `query-batch-size` | `200` | 一条 SQL 覆盖多少位号，另受内部 `MAX_SQL_BYTES = 65536` 字节预算约束（**客户端保守值，非实测服务端限制**） |
| `query-parallelism` | `4` | 块内语句并发上限 |
| `query-batch-scope` | `database` | `device` 是改造前行为，保留为现场回退档；`root` 跨库单语句实测可用，不作默认是因为受限账号可能只有按库授权（**保守选择，非实测结论**） |
| `query-batch-row-budget` | `10000` | 多列 RAW/插值的 `LIMIT`（合并时间戳行数）。撞预算且没取满的列必须逐位号补采 |
| `query-batch-completion-max` | `32` | 补采列数上限，超过则整块退回逐位号 |
| `interpolation-tolerance` | `0` | 插值外推容差毫秒，`0` = 不外推 |
| `interpolation-raw-limit` | `200000` | 插值取原始点的行数上限 |
| `tag-mapping-file` | 无 | 位号→路径映射表；读不到或值非法 → `IotdbDriver.create` 抛 `TsdbDataException`（因为 IoTDB 查不存在的序列返回空而非异常，坏映射表只会让报表静默缺数） |

**已改名且不保留别名的键**：`current-batch-size` → `query-batch-size`、
`current-parallelism` → `query-parallelism`。旧键残留会在**构造期直接抛
`TsdbConfigurationException`**，报错里写明替代键名。

> 为什么不留别名：配置里还留着旧键，说明这份配置没被核对过；静默忽略就等于「上限还是旧的那个值」。
> 宁可启动失败，也不让一个没生效的配置悄悄跑起来。

### 3.4 环境变量占位符

`TsdbConfigLoader` 支持 `${ENV}` 与 `${ENV:default}`，在**解析期**替换：

```yaml
auth:
  password: ${IOTDB_PASSWORD}          # 未设置该环境变量 → 配置解析失败
  username: ${IOTDB_USER:root}         # 未设置时用 root
```

凭据一律外置。生产只读测试的凭据放 `ge-it.properties` / `hls-it.properties` /
`iotdb-it.properties`，这三个文件与 `tsdb.yml`、`application-local.yml` 都被 `.gitignore` 排除。

### 3.5 时间配置三层（最容易配错的地方）

| 层 | 键 | 管什么 | 三库实际情况 |
|---|---|---|---|
| ① 协议时间 | 无（驱动内部） | 厂商线缆上的时间表示 | HLS = epoch **秒**；GE = **时间字符串**；IoTDB = epoch **毫秒** |
| ② 业务日历 | `options.zone` + `options.shift-hour` | 自然日/月分桶边界、班次窗口 | **仅 IoTDB 消费** |
| ③ 文本解析与展示 | `options.time.request.*` / `options.time.response.*` | 请求文本格式化、响应文本解析、`timestampText` 展示 | 请求侧**只有 GE 实际消费**；展示侧三库都生效 |

三层互不影响。把 `zone` 与 `time.response.target-zone` 配成同一个时区**不是必选项**。

`time.*` 的默认值：`request.enabled=true`、`request.zone=UTC`、
`request.pattern=yyyy-MM-dd'T'HH:mm:ssXXX`、`response.enabled=true`、
`response.source-zone=UTC`、`response.target-zone=UTC`、`response.pattern=yyyy-MM-dd HH:mm:ss`。
支持嵌套 map 与扁平键（`time.request.zone`）两种写法，嵌套优先；pattern 非法抛 `TsdbConfigurationException`。

**GE 的 UTC → GMT+8 示例**就是 3.2 里 `ge_main` 的那段：GE 返回 UTC 文本，
先按 `source-zone: UTC` 解释成绝对时刻，再按 `target-zone: GMT+8` 生成 `timestampText`。
若某项目 GE 本身返回 GMT+8，只改 `response.source-zone` 即可，**不需要改代码**。

### 3.6 安全默认

- **默认只读**：`security.allow-write: false`，公共层 `write(...)` 抛 `TsdbUnsupportedException`。
- **默认 TLS 校验**：`verify-hostname: true`、`allow-insecure-transport: false`；明文需显式放行。
- **fastjson 1.2.83**：AutoType 关闭 + safeMode 开启 + 只做树解析，不做自动类型反序列化。
- **审计**：`audit-enabled: true` 记录写/管理操作。
- **原生层不受写开关约束**：`allow-write` 只作用于公共层 `TsdbClient`；`unwrap` 出去的写方法
  （GE `write`、IoTDB `nonQuery`/`insertTablet`）仍可达，安全边界由调用方负责。

---

## 4. 在项目中怎么用

### 4.1 纯 Java 快速上手

```java
import io.github.forget_the_bright.tsdb.common.TsdbClients;
import io.github.forget_the_bright.tsdb.common.TsdbManager;
import io.github.forget_the_bright.tsdb.common.api.TsdbClient;
import io.github.forget_the_bright.tsdb.common.model.*;

import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

public class QuickStart {
    public static void main(String[] args) {
        TsdbManager manager = TsdbClients.fromYaml(Paths.get("tsdb.yml"));
        try {
            TsdbClient client = manager.client();

            // 1) 探活
            System.out.println("health = " + client.health().isUp());

            // 2) 标签目录（三库都支持只给 limit 的浏览）
            List<TagInfo> tags = client.tagCatalog()
                    .listTags(TagQuery.builder().limit(20).build());

            // 3) 当前值：返回顺序与请求一致，缺失位号 sample 为 null
            List<TagSample> now = client.currentValues()
                    .read(Arrays.asList("device01.temperature", "device01.flow"));
            for (TagSample ts : now) {
                if (ts.getSample() == null) {
                    System.out.println(ts.getTagName() + " 无数据");
                    continue;
                }
                System.out.println(ts.getTagName() + " = " + ts.getSample().getValue()
                        + " @" + ts.getSample().getTimestampText()
                        + " quality=" + ts.getSample().getQuality());
            }

            // 4) 历史：最近 1 小时原始点（HLS 不支持 RAW，会抛 TsdbUnsupportedException）
            Instant end = Instant.now();
            Instant start = end.minus(1, ChronoUnit.HOURS);
            List<TagSeries> series = client.history().read(HistoryQuery.builder()
                    .tags("device01.temperature")
                    .range(start, end)
                    .mode(QueryMode.RAW)
                    .maxPoints(5000)
                    .build());
        } finally {
            manager.close();     // 释放 HTTP 连接 / IoTDB SessionPool
        }
    }
}
```

### 4.2 Spring Boot 注入

`application.yml` 用 3.2 的同一份 `tsdb.*` 结构，然后：

```java
@Service
public class TagService {

    private final TsdbManager tsdbManager;

    // 构造器注入；容器销毁时自动调用 TsdbManager.close()
    public TagService(TsdbManager tsdbManager) {
        this.tsdbManager = tsdbManager;
    }

    public List<TagSample> current(List<String> tags) {
        return tsdbManager.client().currentValues().read(tags);
    }
}
```

装配细节（`TsdbAutoConfiguration`）：

- `@ConditionalOnClass(TsdbManager.class)`：classpath 上有 common 才生效；
- `@Bean(destroyMethod = "close")` + `@ConditionalOnMissingBean`：你自定义了 `TsdbManager` bean 时不覆盖；
- **客户端是懒加载的**，所以应用启动不会因为数据库暂时不可达而失败；第一次 `client()` 才建连。

**未实现**：Actuator 的 `/actuator/health/tsdb` 端点没有实现，starter 只注册 `TsdbManager` bean。
要接 Actuator 需自己写一个 `HealthIndicator` 包一层 `client.health()`。

### 4.3 查历史：四种模式

```java
Instant start = Instant.parse("2026-11-01T00:00:00Z");
Instant end   = Instant.parse("2026-11-02T00:00:00Z");

// RAW —— 原始点。HLS 不支持
client.history().read(HistoryQuery.builder()
        .tags("device01.temperature").range(start, end)
        .mode(QueryMode.RAW).direction(Direction.FORWARD).maxPoints(10000).build());

// AGGREGATED —— 区间聚合。三库都支持，但可用函数不同（见 6.3）
client.history().read(HistoryQuery.builder()
        .tags("device01.temperature").range(start, end)
        .mode(QueryMode.AGGREGATED).aggregation(Aggregation.AVG)
        .interval(Duration.ofMinutes(5)).build());

// SAMPLED —— 等间隔取每桶末值。三库都支持，实现路径不同
client.history().read(HistoryQuery.builder()
        .tags("device01.flow").range(start, end)
        .mode(QueryMode.SAMPLED).aggregation(Aggregation.LAST)
        .interval(Duration.ofDays(1)).fill(FillPolicy.PREVIOUS).build());

// INTERPOLATED —— 等间隔线性插值。HLS 不支持
client.history().read(HistoryQuery.builder()
        .tags("device01.temperature").range(start, end)
        .mode(QueryMode.INTERPOLATED)
        .interval(Duration.ofMinutes(1)).count(1440).build());
```

参数必填规则：

| 模式 | 必填 | 说明 |
|---|---|---|
| `RAW` | `tags` + `range` | `maxPoints` 默认 10000 |
| `AGGREGATED` | `tags` + `range` + `aggregation` + `interval` | IoTDB 缺 `interval` 会被拒绝 |
| `SAMPLED` | `tags` + `range` + `interval` | `aggregation` 可省，IoTDB 默认 `LAST` |
| `INTERPOLATED` | `tags` + `range` + `interval` 或 `count` | IoTDB 是 SDK 内存网格，**不外推**（`interpolation-tolerance: 0`） |
| `TREND` | — | **三库公共层都不支持**，抛 `TsdbUnsupportedException` |

### 4.4 累计量差分

```java
List<DeltaSeries> deltas = client.deltaHistory().read(
        Collections.singletonList("device01.flow"),
        start, end,
        TimeGranularity.DAY,        // SECOND/MINUTE/HOUR/DAY/MONTH
        Aggregation.LAST,           // 三库都只支持 LAST
        FillPolicy.PREVIOUS,        // GE 只支持 NONE
        Direction.FORWARD,
        1000);                      // maxPoints

for (DeltaSeries s : deltas) {
    for (DeltaPoint p : s.getPoints()) {
        if (p.isMissing()) { continue; }   // 首桶无基线
        System.out.println(p.getTimestampText() + " 增量=" + p.getDelta()
                + (p.isReset() ? " (计数器清零)" : ""));
    }
}
```

语义：本桶末值 − 上一桶末值。首桶无基线 → `missing=true`；负差视为清零 → `reset=true` 并取本桶末值；
月粒度自动归整到 1 号 00:00。

三库差异（详见 [docs/15 §3.5](docs/15-API参考与原生对照.md)）：

| 约束 | HLS | GE | IoTDB |
|---|---|---|---|
| `MONTH` 粒度 | ❌ | ❌ | ✅ |
| 填充 | `NONE`/`PREVIOUS` | **仅 `NONE`** | `NONE`/`PREVIOUS`/`LINEAR` |
| 基线来源 | `BOUND` 分桶值 | 一次批量 POST `raw`（Backward、count=1）取起点前末值 | `SELECT LAST_VALUE(...) WHERE time < start` |

> ⚠️ **HLS 的差分不得用于生产结算**：它建立在「`TagValue_Bound` 等于区间末值」这个假设上，
> 而该语义**尚未在现场确认**。门控 IT 里对应用例默认跳过，需显式 `-Dhls.it.delta=true` 才跑。
> 确认前请改用原生接口或暂不启用。
>
> `Aggregation.INTERVAL_SUM`（整窗末−首，单值）与 `CUMULATIVE_DELTA`（逐桶差分，序列）
> **语义不同，不可互相顶替**。

### 4.5 能力协商

```java
if (client.capabilities().supports(Capability.RAW_HISTORY)) {
    // 走原始点
} else {
    // 明确选择：改走聚合，或提示用户，或跳过
    // 不要指望 SDK 帮你「自动降级」——它不会
}
```

不支持的操作抛 `TsdbUnsupportedException`（`TsdbErrorCode.UNSUPPORTED`，`isRetryable() == false`）。
**重试它没有意义**，要改的是查询方式或换库。

### 4.6 原生逃生口

```java
// HLS：厂商原始 JSON，四个聚合列一次拿全
HlsNativeApi hls = client.unwrap(HlsNativeApi.class);
JSONObject history = hls.getHDBTagValueAll(start, end, 3600L, "E101FIT1001_DACA_PV");

// GE：92 个端点通用执行器
GeNativeApi ge = client.unwrap(GeNativeApi.class);
JSONObject tags = JsonText.object(ge.invoke(GeEndpoint.QUERY_TAGS_BY_PARAMS,
        mapOf("maxNumber", 100, "nameMask", "AI*")));
JSONObject created = JsonText.object(ge.invoke(GeEndpoint.CREATE_TAG_DATA, body));
Object any = ge.rawRequest("GET", "/v1/tags?maxNumber=10", null);

// IoTDB：任意 SQL + 批量原语
IotdbRestApi rest = client.unwrap(IotdbRestApi.class);
JSONObject rows = rest.query("SHOW TIMESERIES root.factory.**");
rest.nonQuery("CREATE TIMESERIES root.factory.device01.pressure WITH DATATYPE=FLOAT");

IotdbQueryService service = client.unwrap(IotdbQueryService.class);
String version = service.serverVersion();
IotdbTransport transport = client.unwrap(IotdbConfig.class).getTransport();
```

原生门面都在 `internal` 包下：**类是 `public`，但属「非公共 API，不承诺兼容」**，
只能通过 `unwrap(...)` 取出。IoTDB **没有** `nativeapi` 包，它的原生面就是
`IotdbRestApi` 与 `IotdbQueryService`。逐方法端点对照见 [docs/15](docs/15-API参考与原生对照.md)。

### 4.7 换库与热切换

```java
// 方式一：改配置（重启生效）
// tsdb.default-instance: ge_main  →  iotdb_main

// 方式二：运行期切换（不重启）
manager.switchTo("iotdb_main");
```

`switchTo` **先创建新客户端，成功后才关闭旧客户端**；新连接失败时旧客户端仍可用，
不会把应用切进一个连不上的库。运行期只保留一个活动客户端，避免现场误连两套库。

需要同时读两套库做对数时，用 `manager.client("ge_main")` 与 `manager.client("iotdb_main")`
显式取两个客户端，而不是靠 `switchTo` 来回切。

### 4.8 异常与错误码

全部异常继承 `TsdbException extends RuntimeException`。

| 错误码 | 常量 | `isRetryable()` | 专用异常类 | 典型场景 |
|---|---|---|---|---|
| `TSDB-CONFIG` | `CONFIG` | `false` | `TsdbConfigurationException` | 配置缺键、旧键残留、pattern 非法、缺查询服务 |
| `TSDB-CONN` | `CONNECTION` | **`true`** | `TsdbConnectionException` | 网络不可达、连接被拒 |
| `TSDB-AUTH` | `AUTH` | `false` | `TsdbAuthenticationException` | 账号密码错、token 获取失败 |
| `TSDB-TIMEOUT` | `TIMEOUT` | **`true`** | `TsdbTimeoutException` | 连接/读超时 |
| `TSDB-RATE-LIMIT` | `RATE_LIMIT` | **`true`** | **无专用子类**，用 `new TsdbException(TsdbErrorCode.RATE_LIMIT, msg)` | 厂商限流 |
| `TSDB-UNSUPPORTED` | `UNSUPPORTED` | `false` | `TsdbUnsupportedException` | 能力/模式/聚合/填充不在白名单 |
| `TSDB-QUERY` | `QUERY` | `false` | `TsdbQueryException` | 服务端拒绝查询、per-tag 失败且不允许部分结果 |
| `TSDB-DATA` | `DATA` | `false` | `TsdbDataException` | 位号非法、映射表坏了、相对位号缺默认库 |
| `TSDB-INTERNAL` | `INTERNAL` | `false` | `TsdbException`（基类默认码） | 未归类内部错误 |

上下文字段：`getCode()`、`getCodeString()`、`getInstance()`、`getType()`、`getOperation()`、
`getVendorCode()`、`isRetryable()`；驱动用 `withContext(instance, type, operation, vendorCode)` 填充。

```java
try {
    client.history().read(query);
} catch (TsdbException e) {
    // 按错误码判断，别抓文案——文案会随驱动版本变化
    if (e.getCode() == TsdbErrorCode.UNSUPPORTED) {
        // 换查询方式，不是重试
    } else if (e.isRetryable()) {
        // 退避后重试
    }
    log.warn("instance={} type={} op={} vendorCode={}",
            e.getInstance(), e.getType(), e.getOperation(), e.getVendorCode(), e);
}
```

### 4.9 返回模型怎么读

| 类型 | 关键字段 |
|---|---|
| `TagInfo` | `getTagName()`、`getDescription()`、`getValueType()`、`getUnit()`、`getVendorType()`、`getAttributes()` |
| `TagSample` | `getTagName()`、`getSample()`、`getAttributes()`；`sample == null` 且 `errorCode != 0` 表示该位号出错 |
| `Sample` | `getTimestamp()`（`Instant`，绝对时刻）、`getTimestampDate()`（`Date`）、`getTimestampText()`（展示文本）、`getValueType()`、`getValue()`、`getDoubleValue()`/`getLongValue()`/`getBooleanValue()`/`getTextValue()`/`getBytesValue()`、`getQuality()`、`getVendorQualityCode()`、`isNull()` |
| `TagSeries` | `getTagName()`、`getSamples()`、`isTruncated()`、`getAttributes()` |
| `DeltaSeries` / `DeltaPoint` | `getTagName()`、`getPoints()`；点的 `getDelta()`、`isReset()`、`isMissing()` |
| `WriteResult` | `accepted`、`rejected`、`errors`、`isFullyAccepted()` |
| `HealthStatus` | `isUp()`、`getMessage()`、`getLatencyMillis()`、`getCheckedAt()`/`getCheckedAtDate()`/`getCheckedAtText()` |

`TagSample.attributes` 的键常量：`errorCode`、`errorMessage`、`dataType`、`engineeringUnits`、
`hiEngineeringUnits`、`loEngineeringUnits`。

**`timestampText` 只是展示值**，按实例时区格式化，不要拿它做计算或跨库比对；比对一律用 `timestamp`。

---

## 5. 三种时序库的底层区别

### 5.1 一张总表

| 维度 | HLS | GE Proficy Historian | Apache IoTDB |
|---|---|---|---|
| 数据模型 | 扁平位号 + 描述 | 扁平标签 + 属性/注释/量程 | **树模型** `root.库.设备.测点` |
| 协议与端口 | HTTP REST（现场 9000） | HTTPS REST | REST v2（18080）/ RPC（6667）/ JDBC（6667） |
| 认证 | `userid`/`secretkey` 请求头 → token | OAuth2 password grant + Basic 头 | 用户名/密码 |
| token 默认有效期 | 1800 s | 1800 s | 无 token（会话/连接池） |
| 协议时间 | epoch **秒** | **时间字符串**（时区可配） | epoch **毫秒** |
| 返回结构 | **宽表**：一行含 `TagValue_AVG/MIN/MAX/Bound` + 三个质量列 | **长表**：`Data[] → Samples[]`，每样本一行 | **列式**：`SHOW`/`SELECT` 结果需转置，按列名定位 |
| 质量码 | `GOOD=1`、`BAD=0`；**无 AVG 质量列** | `BAD=0`、`UNCERTAIN=1`、`NA=2`、`GOOD=3` | **无质量码**：非空=GOOD、空=UNKNOWN |
| 历史能力 | 仅区间聚合（AVG/MIN/MAX/BOUND） | 原始/聚合/插值/采样/趋势/计算 | 原始/聚合/插值/采样 + 任意 SQL |
| 写入 | ❌ 无写接口 | ✅ 原生 `POST /v1/datapoints/create` | ✅ 默认关闭，`allow-write: true` 开启 |
| 运维接口面 | 4 类接口 | **92 个端点**：Data 22 / Tags 13 / Systems 25 / Collectors 27 / Alarms 5 | 任意 SQL（含 DDL/DML） |
| 服务端硬限制 | 全量目录约 4.3 MB；`endTime` 不得晚于当前 | `ErrorCode=11` 带 payload 视为成功 | `GROUP BY` 10000 行上限；多列 RAW 共享 `LIMIT` |
| 驱动额外依赖 | 无（仅公共依赖） | 无（仅公共依赖） | `iotdb-session` + `iotdb-jdbc` 2.0.11 |

### 5.2 数据模型差异

**HLS**：位号是扁平字符串（如 `E101FIT1001_DACA_PV`），目录只有名称 + 描述，
**没有服务端掩码/分页/类型过滤**，只能拉全量。

**GE**：标签也是扁平字符串，但有属性、注释、量程、重命名、高级检索等 13 个 Tags 端点。
公共层只用了浏览能力，其余在原生层。

**IoTDB**：树模型，完整路径至少四段 `root.db.device.measurement`。三种输入形态：

| 传入 | 实际路径 |
|---|---|
| `device01.temperature`（相对） | 补 `default-database` → `root.factory.device01.temperature` |
| `root.sdk_it.device01.flow`（全名称） | 原样使用，可跨库 |
| `root.sg` 之后逐节点已带反引号的混合写法 | 规范化为 `root.sg.d1.temperature` |
| 相对位号且未配 `default-database` | 抛 `TsdbDataException` |

是否「全名称」**按节点判定**（前两级必须是 `root` 与库名节点），不用字符串前缀比对。
解析、白名单校验、引号规范化只有 `IotdbTagPath` 一处入口。

> ⚠️ **层级错配会静默返回空结果**（实测）：`FROM` 前缀与 SELECT 表达式的层级必须一致。
> 所以一次请求出现多个数据库时，SDK 按 `query-batch-scope` **拆成多条语句**，
> 混组在构造语句前就被拒绝——而不是让你把空结果当成「那段时间没数据」。

### 5.3 时间与质量码差异

| 库 | 协议时间 | 质量码映射 |
|---|---|---|
| HLS | epoch **秒**（解析响应时按数量级兼容毫秒：`abs < 1e11` 视为秒） | `1→GOOD`、`0→BAD`；**AVG 无质量列 → `UNKNOWN`**，并标 `attributes.qualitySource = hls_not_provided` |
| GE | 时间字符串，按 `time.request/response.*` 解析与格式化 | `3→GOOD`、`1→UNCERTAIN`、`0→BAD`、`2→UNKNOWN` |
| IoTDB | epoch **毫秒** | **无质量码**：值非空 → `GOOD`，值为空 → `UNKNOWN` |

**IoTDB 没有质量码的后果**：从 GE 迁到 IoTDB 后，`UNCERTAIN`/`NA` 这两档信息**不可恢复地消失**。
如果业务用质量码做结算过滤或报警抑制，这是必须业务签字的差异，不能靠 SDK 补齐。

### 5.4 服务端硬限制（实测，会影响你怎么写查询）

**IoTDB**

| 限制 | 表现 | 应对 |
|---|---|---|
| `GROUP BY` 结果 **10000 行上限** | 超出 `code=708` | 行数只等于**桶数**、与列数无关（3 列 × 120 桶仍是 120 行），所以加宽语句不会缩短可查窗口；1s 粒度约可查 2.8 小时、`30s` 约 3.5 天、`5m` 约 34 天，细网格长窗口必须切窗 |
| 间隔字面量必须带单位 | 裸毫秒数 → `code=700 no viable alternative` | SDK 按最大单位生成 `Nd`/`Nh`/`Nm`/`Ns`/`Nms`/`Nmo` |
| SELECT 里不能带 `root.` | `code=701` | 表达式用相对设备路径，`FROM` 给前缀 |
| 多列 RAW 共享 `LIMIT` | 密集列吃满行数、稀疏列只剩几个点；不存在的路径**整列消失** | SDK 对欠采列逐位号补采，并交 `completed` 账本 |
| `SELECT LAST` 行序乱 + 丢列 | 应答行序 ≠ 请求序，缺序列被丢弃 | 按 `Timeseries` 列名回填 |
| 非 ASCII 标识符 | 未声明 charset → Jetty 直接 500 返回 HTML | SDK 已按 `application/json; charset=UTF-8` 发送；用 curl/Postman 手工复现时要自己加 |
| `date_bin()` / `sample()` | 2.0.11 树模型下**静默返空** | 不用这两个函数，等间隔走 `GROUP BY` |
| 查不存在的序列 | 返回**空结果而非异常** | 坏映射表只会让报表静默缺数，所以映射表非法时驱动创建直接失败 |

**HLS**

- 全量目录现场约 **4.3 MB / 199 s**：`listTags()` 与 `getTag()` 都会触发它，务必缓存，别放循环里。
- `endTime` 晚于当前时刻 → `code=3025`。
- 取 token 的 `Content-Type` 必须是**精确的** `application/json`（带 charset 会被拒，返回 4002）；
  SDK 先试 POST，失败回退原 SDK 的 GET + 请求头方式。

**GE**

- `ErrorCode=11` **且带 payload** 时视为成功（部分位号无数据），不是失败；
  per-tag 错误落 `TagSample.attributes.errorCode/errorMessage`。
- 厂商 `SumValue=24` 是**末−首区间累计**而非算术和，所以 `SUM` 映射到 `9`（RawTotal），不映射到 24。

### 5.5 换库时会变什么

**改配置就行**

- `base-url` / `auth.*` / `type` / `default-instance`
- GE 的时区口径（`time.request.zone`、`time.response.source-zone`）
- IoTDB 的 `default-database`、`zone`、`shift-hour`、批量档位

**必须改业务**

| 变化 | 影响 |
|---|---|
| HLS 没有 `RAW_HISTORY` / `INTERPOLATED_HISTORY` | 依赖原始点或插值的报表要改查询方式 |
| HLS 目录不支持 `nameMask` | 掩码检索要改成「拉全量 + 本地缓存 + 本地过滤」 |
| GE / HLS 差分不支持 `MONTH` | 月结算要改成按日差分再聚合，或换 IoTDB |
| GE 差分只支持 `FillPolicy.NONE` | 依赖前值补空的逻辑要自己补 |
| IoTDB 无质量码 | `UNCERTAIN`/`NA` 信息消失，结算过滤与报警抑制逻辑要重新设计 |
| 位号命名从扁平串变成树路径 | 需要 `tag-mapping-file` 或 `default-database`，并核对映射命中率 |
| GE 的告警/采集器/系统运维端点 | IoTDB 与 HLS 没有对应能力，这部分功能不可迁移 |

---

## 6. 能力支持矩阵

图例：✅ 公共层声明并实现；🟡 有条件；❌ 公共层未声明（调用抛 `TsdbUnsupportedException`）。
**❌ 不代表厂商没有该能力**——原生层可能仍可调，见 [docs/15](docs/15-API参考与原生对照.md)。

### 6.1 15 项 Capability × 3 库

| Capability | HLS | GE | IoTDB |
|---|---|---|---|
| `TAG_BROWSE` | ✅ | ✅ | ✅ |
| `TAG_SEARCH` | ❌ | ❌ | ❌ |
| `TAG_CRUD` | ❌ | ❌ | ❌ |
| `CURRENT_VALUE` | ✅ | ✅ | ✅ |
| `RAW_HISTORY` | ❌ | ✅ | ✅ |
| `AGGREGATED_HISTORY` | ✅ | ✅ | ✅ |
| `INTERPOLATED_HISTORY` | ❌ | ✅ | 🟡 ① |
| `SAMPLED_HISTORY` | ✅ | ✅ | 🟡 ① |
| `TREND` | ❌ | ❌ | ❌ |
| `CUMULATIVE_DELTA` | ✅ ② | ✅ | 🟡 ① |
| `WRITE` | ❌ | ❌ ③ | 🟡 ④ |
| `DELETE` | ❌ | ❌ | ❌ |
| `ALARM` | ❌ | ❌ ⑤ | ❌ |
| `SUBSCRIPTION` | ❌ | ❌ | ❌ |
| `SYSTEM_ADMIN` | ❌ | ❌ ⑤ | ❌ |

脚注：

- **①** IoTDB 基线只声明 4 项（`TAG_BROWSE`/`CURRENT_VALUE`/`RAW_HISTORY`/`AGGREGATED_HISTORY`）；
  **装配了 `IotdbQueryService` 才加** `SAMPLED_HISTORY`/`INTERPOLATED_HISTORY`/`CUMULATIVE_DELTA`。
  `require-query-service: true`（默认）时装配失败会在构造期抛异常。
- **②** HLS 的 `SAMPLED` 与 `CUMULATIVE_DELTA` 都建立在 `TagValue_Bound` 上，
  **该语义尚未现场确认**，差分不得用于生产结算。
- **③** GE 公共层**未声明 `WRITE`**，但原生 `GeNativeApi.write(...)` → `POST /v1/datapoints/create` 可用。
- **④** IoTDB 只有 `allow-write: true` 才声明 `WRITE`。
- **⑤** GE 原生层有 5 个告警端点、25 个系统端点、27 个采集器端点，公共层不声明。

出处：`HlsClient.java:56-60`、`GeClient.java:61-67`、`IotdbClient.java:165-177`。

### 6.2 QueryMode × 3 库

| QueryMode | HLS | GE | IoTDB |
|---|---|---|---|
| `RAW` | ❌ | ✅ `POST /v1/datapoints/raw` | ✅ 多列 `SELECT … ORDER BY TIME LIMIT` + 补采 |
| `AGGREGATED` | ✅ `POST /hdb/read_hdbtagvalue` | ✅ `POST /v1/datapoints/calculated` | ✅ 多列 `GROUP BY ([s,e),<lit>)` |
| `INTERPOLATED` | ❌ | ✅ `POST /v1/datapoints/interpolated` | ✅ SDK 内存网格 + 线性内插（不外推） |
| `SAMPLED` | 🟡 用 `BOUND` 当桶边界值 | ✅ `POST /v1/datapoints/sampled`（`samplingMode=2`） | ✅ 多列 `GROUP BY` + `LAST_VALUE` |
| `TREND` | ❌ | ❌ | ❌ |

> IoTDB 的 `SAMPLED` / `INTERPOLATED` 与 §6.1 脚注 ① 同条件：**必须装配了 `IotdbQueryService`**。
> `queryService` 缺失时这两个模式不走采样/插值分支，而是落到 REST 兜底，且能力集不声明它们
> （`IotdbHistoryAdapter.java:82-87`）。`TREND` 三库公共层都未实现，调用抛 `TsdbUnsupportedException`。

### 6.3 Aggregation × 3 库

| Aggregation | HLS | GE（`calculationMode`） | IoTDB（SQL 函数） |
|---|---|---|---|
| `AVG` | ✅ | ✅ `1` | ✅ `AVG` |
| `MIN` | ✅ | ✅ `4` | ✅ `MIN_VALUE` |
| `MAX` | ✅ | ✅ `5` | ✅ `MAX_VALUE` |
| `SUM` | ❌ | ✅ `9`（RawTotal，算术和） | ✅ `SUM` |
| `COUNT` | ❌ | ✅ `6` | ✅ `COUNT` |
| `FIRST` | ❌ | ✅ `17` | ✅ `FIRST_VALUE` |
| `LAST` | ❌ | ✅ `19` | ✅ `LAST_VALUE` |
| `RANGE` | ❌ | ❌ | ❌ |
| `BOUND` | ✅ | ❌ | ❌ |
| `INTERVAL_SUM` | ❌ | ✅ `17`/`19` **双请求** | ✅ `first_value`+`last_value` **单窗单 SQL** |

### 6.4 FillPolicy / 粒度 / 方向

| 约束 | HLS | GE | IoTDB |
|---|---|---|---|
| history 填充 | **仅 `NONE`** | **仅 `NONE`** | 只有「`SAMPLED` 且装配了 queryService」能下推 `FILL`；其余路径非 `NONE` 一律抛；`ZERO` **未实现** |
| history 方向 | **仅 `FORWARD`** | 支持 | 支持 |
| history 间隔 | 必须 ≥ 1 秒 | `intervalMs` | 任意 `Duration`，字面量必须带单位 |
| delta 粒度 | 不支持 `MONTH` | 不支持 `MONTH` | **支持 `MONTH`** |
| delta 聚合 | 仅 `LAST` | 仅 `LAST` | `LAST` |
| delta 填充 | `NONE`/`PREVIOUS` | **仅 `NONE`** | `NONE`/`PREVIOUS`/`LINEAR` |

### 6.5 公共方法 → 原生对照（精简版）

| 公共方法 | HLS | GE | IoTDB |
|---|---|---|---|
| `tagCatalog().listTags()` | `GET /ddb/read_alltagname`（全量，客户端裁剪） | `GET /v1/tags` | `SHOW TIMESERIES <prefix>` |
| `tagCatalog().getTag()` | 全量目录线性扫描 | `GET /v1/tags`（名字当掩码，上限 1000） | `SHOW TIMESERIES <device>` |
| `currentValues().read()` | `POST /ddb/read_ddbtagvalue` | `POST /v1/datapoints/sampled`（`samplingMode=1`） | `SELECT LAST E(p1), E(p2) … FROM <库>` |
| `history()` RAW | ❌ | `POST /v1/datapoints/raw` | 多列 raw + 逐位号补采 |
| `history()` AGGREGATED | `POST /hdb/read_hdbtagvalue` | `POST /v1/datapoints/calculated` | 多列 `GROUP BY` |
| `history()` SAMPLED | 同上（`NeedQueryBound=true`） | `POST /v1/datapoints/sampled`（`samplingMode=2`） | 多列 `GROUP BY` + `LAST_VALUE` |
| `history()` INTERPOLATED | ❌ | `POST /v1/datapoints/interpolated` | 窗内 raw + 包围点 raw + 内存网格 |
| `deltaHistory().read()` | `POST /hdb/read_hdbtagvalue`（BOUND） | `calculated`(19) + 批量 `raw` 基线 | 桶序列 + `LAST_VALUE WHERE time < start` |
| `writeSession().write()` | ❌ | ❌ 公共层（原生 `POST /v1/datapoints/create`） | `POST /rest/v2/insertTablet` |
| `health()` | `GET /ddb/read_alltagname` 或 token 刷新 | `GET /v1/tags?maxNumber=1` | `GET {base}/ping` 或 `SHOW VERSION` |

**逐方法完整对照**（含每个原生门面的全部方法、92 个 GE 端点清单、7 族 IoTDB 批量原语的 SQL 形态与往返次数、
配置键→生效位置）见 **[docs/15 · API 参考与原生对照](docs/15-API参考与原生对照.md)**。

---

## 7. 换库指南（给需要切换底层数据库的人）

### 7.1 五步走

1. **评估能力矩阵**：对照第 6 章，列出业务用到的每个能力在目标库上是否可用。
   不可用的项要么改业务，要么放弃迁移——**不要指望公共层补齐**。
2. **配新实例**：在 `tsdb.instances` 下加一个目标库实例，先不动 `default-instance`。
3. **位号映射**：IoTDB 需要 `tag-mapping-file` 或 `default-database`（见 7.2）。
4. **双跑对数**：用 `manager.client("旧实例")` 与 `manager.client("新实例")` 同时读同一批位号、
   同一窗口，逐序列逐点比对（见 7.3）。
5. **切 `default-instance`**：对数通过后再切；或用 `manager.switchTo(name)` 灰度。

### 7.2 位号映射

```properties
# conf/iotdb-tag-mapping.properties
AI-101 = root.factory.device01.temperature
AI-102 = root.factory.device01.flow
```

- 命中映射表 → 直接用规范路径；
- 未命中 → 走白名单清洗兜底并记 **WARN**；
- 文件读不到或映射值不是完整 `root.` 路径 → `IotdbDriver.create` 抛 `TsdbDataException`。

用 `((IotdbClient) client).tagMappingStats()` 查命中/未命中统计，评估兜底比例。
兜底比例高说明映射表没覆盖全，报表可能静默缺数。

### 7.3 对数口径

| 对数 | 状态 |
|---|---|
| IoTDB **同库三档双跑**（逐表达式 / `device` / `database`） | ✅ **已执行**：同一实例、同一批位号、同一窗口，逐序列逐点 **0 容差**比对，七个查询族全部 `diffs=0`（`IotdbTierParityIT`，逐族数字见 [docs/14 §8.3](docs/14-GE迁移IoTDB对照与替换方案.md)） |
| IoTDB **三通道一致性**（REST / Native / JDBC） | ✅ **已执行**：`IotdbProductionIT` 同批位号同窗口逐点比对 |
| **GE ↔ IoTDB 业务级对数** | ❌ **未执行** |

> ⚠️ **这是迁移的硬门槛。** 同库双跑只证明 IoTDB 自己跟自己一致，
> **不证明 GE 与 IoTDB 的业务数值一致**。GE↔IoTDB 业务级对数未完成前，
> 不得宣称「GE 可被 IoTDB 替换」。对数口径与替换门槛见 [docs/14](docs/14-GE迁移IoTDB对照与替换方案.md)。

### 7.4 不可消除的差异（需业务签字，不用容差掩盖）

| 差异 | 为什么不能靠 SDK 消除 |
|---|---|
| IoTDB 无质量码 | GE 的 `UNCERTAIN`/`NA` 信息在写入 IoTDB 时就没了，读不回来 |
| HLS 质量码只有两态 | 无法表达 `UNCERTAIN`/`STALE`，映射只能保留原始码 |
| HLS `BOUND` 语义未确认 | 差分结果可能整体错位，**不是容差问题** |
| GE 的告警/采集器/系统运维 | 目标库没有对应能力，功能不可迁移 |
| 时间精度 | HLS 秒级、GE 文本精度取决于 pattern、IoTDB 可到毫秒/纳秒；跨库比对要先统一精度口径 |

### 7.5 回滚

- `manager.switchTo(name)` 失败时**保留旧客户端**，不会把应用切进连不上的库；
- 配置回退档位：IoTDB 的 `query-batch-scope: device` 与改造前「一个设备一条 SQL」逐字节相同；
  `query-batch-size: 1` 让任何族都退化成一位号一条语句，是逐位号归因的回退档；
- `default-instance` 改回旧实例名即可整体回退。

---

## 8. 项目模块与目录结构

### 8.1 六个模块

| 模块 | 职责 | 源文件数 |
|---|---|---|
| `tsdb-sdk-bom` | 依赖清单，统一版本 | — |
| `tsdb-sdk-common` | 公共 API、模型、配置、异常、SPI、共享 HTTP/JSON 工具 | 66 |
| `tsdb-sdk-hls` | HLS 驱动 + 原生门面 + 厂商 DTO | 22 |
| `tsdb-sdk-ge` | GE 驱动 + 原生门面 + 92 端点枚举 | 18 |
| `tsdb-sdk-iotdb` | IoTDB 驱动 + 三通道 + SQL 生成 + 批量查询编排 | 42 |
| `tsdb-sdk-spring-boot-starter` | 自动装配 + 配置绑定 | 3 |

合计 **151 个 `.java` 文件 = 126 个类 + 25 个 `package-info.java`，25 个包**。

### 8.2 目录结构（包级，括号内为文件数）

```text
tsdb-sdk-common/src/main/java/io/github/forget_the_bright/tsdb/common/
├── TsdbClients / TsdbManager / package-info            (3)  公共入口
├── api/            (8)  TsdbClient + 6 个能力接口 + package-info
├── model/         (21)  Sample/TagSample/TagSeries/HistoryQuery/枚举等
├── config/         (9)  TsdbConfig/TsdbConfigLoader/TimeConfig/Security…
├── exception/      (10) TsdbException + 7 个子类 + TsdbErrorCode
├── spi/            (4)  TsdbDriver/DriverContext/Metrics
└── internal/       (2)  Texts
    ├── http/       (6)  HttpTransport/HttpResult/HttpErrors/BasicAuth/TrustAllSsl
    └── json/       (3)  Json/JsonText

tsdb-sdk-hls/…/hls/
├── HlsClient / HlsConfig / HlsDriver / HlsMapper        (5)  公共入口
├── nativeapi/dto/  (4)  HistorianRequest/TagNameListRequest/TagNameRequest
├── nativeapi/      (1)  package-info
└── internal/       (5)  HlsNativeApi/HlsHttpClient/HlsTokenManager/HlsModels
    └── adapter/    (7)  CurrentValue/History/Delta/TagCatalog/Write + Support

tsdb-sdk-ge/…/ge/
├── GeClient / GeConfig / GeDriver / GeMapper            (5)  公共入口
├── nativeapi/      (2)  GeEndpoint（92 个端点常量）
└── internal/       (5)  GeNativeApi/GeHttpClient/GeTokenManager/GeModels
    └── adapter/    (6)  CurrentValue/History/Delta/TagCatalog + Support

tsdb-sdk-iotdb/…/iotdb/
├── IotdbClient / IotdbConfig / IotdbDriver / IotdbMapper / IotdbTransport  (6)
└── internal/       (2)  IotdbModels
    ├── sql/        (6)  IotdbTagPath/IotdbSql/Grouping/IotdbTagResolver/TagPathMapper
    ├── query/     (12)  IotdbQueryService + 6 个族查询 + QueryCore/ResultColumns/
    │                    PointMath/SeriesKeyIndex
    ├── transport/  (8)  IotdbRestApi + REST/Native/JDBC 执行器 + IotdbResultTable
    └── adapter/    (8)  CurrentValue/History/Delta/TagCatalog/Write +
                         SeriesAssembler/Support

tsdb-sdk-spring-boot-starter/…/spring/                   (3)
├── TsdbAutoConfiguration / TsdbProperties / package-info
└── resources/META-INF/spring.factories
```

完整逐文件树见 [docs/03 · 总体架构与模块设计](docs/03-总体架构与模块设计.md)。

### 8.3 包可见性约定

| 包 | 承诺 |
|---|---|
| 根包（`common`、`hls`、`ge`、`iotdb`） | **只放公共入口**：`TsdbClients`/`TsdbManager`/`XxxClient`/`XxxConfig`/`XxxDriver` |
| `common.api` / `model` / `config` / `exception` / `spi` | 公共 API，语义稳定 |
| `*.internal.*` | 类是 `public`，但**非公共 API，不承诺兼容**；通过 `unwrap(...)` 使用 |
| `*.internal.adapter` | **装配层**：把公共接口调用翻译成原生调用，public 但非公共 API |

依赖方向：`internal.adapter` → 职责包（`sql`/`query`/`transport`）→ 共享工具（`common.internal`）。
反向依赖不允许。

### 8.4 按任务找类

| 我要做 X | 看哪个类 |
|---|---|
| 加载配置、建管理器 | `TsdbClients`、`TsdbConfigLoader`、`TsdbManager` |
| 查位号目录 | `TagCatalog`、`TagQuery`、`TagInfo` |
| 读当前值 | `CurrentValueReader`、`TagSample`、`Sample` |
| 读历史 | `HistoryReader`、`HistoryQuery`、`TagSeries`、`QueryMode`、`Aggregation` |
| 算累计增量 | `DeltaReader`、`DeltaSeries`、`DeltaPoint`、`TimeGranularity` |
| 判断能力 | `Capability`、`CapabilitySet` |
| 处理错误 | `TsdbException`、`TsdbErrorCode` |
| 调厂商原始接口 | `HlsNativeApi` / `GeNativeApi` + `GeEndpoint` / `IotdbRestApi` + `IotdbQueryService` |
| 配时间与时区 | `TimeConfig`、`IotdbConfig`（`zone`/`shift-hour`） |
| 写新驱动 | `TsdbDriver`、`DriverContext`、[docs/07](docs/07-新增驱动接入指南.md) |

---

## 9. 测试与验证

### 9.1 离线单测

```bash
mvn -o test
```

**258 项，0 failures / 0 errors / 0 skipped**（2026-11-17 10:05 实测，`BUILD SUCCESS`，46.7 s，
统计口径为当次生成的 23 份 surefire XML）：

| 模块 | 用例数 |
|---|---|
| `tsdb-sdk-common` | 61 |
| `tsdb-sdk-hls` | 14 |
| `tsdb-sdk-ge` | 22 |
| `tsdb-sdk-iotdb` | 160 |
| `tsdb-sdk-spring-boot-starter` | 1 |
| **合计** | **258** |

> 计数只认**当次生成**的 surefire XML（按 mtime 过滤），不做目录求和——`target/` 里会累积历史报告。

### 9.2 生产只读 IT

门控开关默认关闭，需显式打开；凭据放被 `.gitignore` 排除的 `*-it.properties`。

| 库 | 门控开关 | 用例数 | 最近实测 |
|---|---|---|---|
| GE | `-Dge.it=true` | **19**（Production 10 + Comparison 9） | 2026-11-16 16:55，`MATCH=2021 DIFF=0 PROBE=483` |
| HLS | `-Dhls.it=true` | **8** → 7 绿 / 1 跳过 / 0 失败 | 2026-11-16 17:03，433 s（跳过的是差分，需 `-Dhls.it.delta=true`） |
| IoTDB | `-Diotdb.it=true` | **45**（Production 11 + Mapping 3 + ContractProbe 24 + TierParity 7），45/45 全绿 | 2026-11-16 16:50 |

```bash
mvn -o -pl tsdb-sdk-ge -am test "-Dtest=GeProductionIT,GeComparisonIT" \
  "-DfailIfNoTests=false" "-Dge.it=true"

mvn -o -pl tsdb-sdk-hls -am test "-Dtest=HlsProductionIT" \
  "-DfailIfNoTests=false" "-Dhls.it=true"

mvn -o -pl tsdb-sdk-iotdb -am test \
  "-Dtest=IotdbProductionIT,IotdbMappingProductionIT,IotdbContractProbeIT,IotdbTierParityIT" \
  "-DfailIfNoTests=false" "-Diotdb.it=true"
```

注意事项：

- `-pl` 必须带 `-am`，否则依赖模块不会先构建。
- 跨模块只跑 IT 用 **`-DfailIfNoTests=false`**；`-Dsurefire.failIfNoSpecifiedTests=false`
  在本项目的 surefire 2.22.2 上**无效**。
- `IotdbProductionIT` 默认位号 `root.factory.device01.temperature` 已无近 7 天数据
  （最后一点约 2026-11-07），其 raw 断言会**环境性失败**；用
  `-Diotdb.it.tag=root.factory.device02.temperature` 指向存活位号即可三通道全绿。这不是 SDK 回归。
- 全部 IT 都是**只读**：不写、不删、不改配置、不启停采集器。

### 9.3 中文 Javadoc

`docs/apidocs/index.html` —— 229 个文件 / 197 个 HTML / 25 个 package-summary
（2026-11-16 包结构归位后重新生成）。

生成脚本放在 `scripts/` 下，该目录被 `.gitignore` 排除，**不在公开仓库内**；
克隆本仓库后 `docs/apidocs` 是已生成好的静态文件，可直接打开。需要重新生成时用
`mvn -o javadoc:javadoc`（根 pom 已配 `doclint=none`、`locale=zh_CN`）。

### 9.4 未执行 / 未确认清单

| 项 | 状态 |
|---|---|
| **GE ↔ IoTDB 业务级对数** | **未执行**——迁移硬门槛，未完成前不得宣称可替换 |
| **HLS `TagValue_Bound` 是否等于区间末值** | **未确认**——差分不得用于生产结算 |
| 重试重放 / 熔断 / 信号量隔离 / 降级缓存 | **未实现**（`retry.*` 是配置项，不要据此宣称有完整弹性能力） |
| Actuator `/actuator/health/tsdb` | **未实现**（starter 只注册 `TsdbManager` bean） |
| 力控 pSpace 驱动 | **未实现**，无空壳驱动 |
| `FillPolicy.ZERO` | **未实现** |
| 中央仓库**发布动作**（`mvn -Prelease deploy`） | **未执行**（pom 的 `url`/`scm`/`developers`/`distributionManagement` 与 `release` profile 已就绪，缺的是 Central token 与 GPG 密钥） |
| Spring Boot 3.x 自动装配（`AutoConfiguration.imports`） | **未提供**，需自行 `@Import` |

---

## 10. 文档索引

**使用与参考（外部使用者优先读这几篇）**

| 文档 | 内容 |
|---|---|
| [docs/15 · API 参考与原生对照](docs/15-API参考与原生对照.md) | **逐方法**签名 + 三库原生端点/SQL + 92 端点清单 + 配置键生效位置 |
| [docs/12 · 厂商 API 与公共 API 使用配置指南](docs/12-厂商API与公共API使用配置指南.md) | 三库配置与调用示例、能力对照、切换与降级 |
| [docs/08 · 原生 API 完整性对照](docs/08-原生API完整性对照.md) | 原厂商 SDK 方法 → 新方法的逐项对照，证明能力未阉割 |
| [docs/04 · 公共 API 与数据模型规范](docs/04-公共API与数据模型规范.md) | 公共接口与模型的规范定义 |
| [docs/07 · 新增驱动接入指南](docs/07-新增驱动接入指南.md) | 写一个新驱动的步骤与模板 |
| [tsdb.example.yml](tsdb.example.yml) | 权威配置模板 |

**迁移与现场验证**

| 文档 | 内容 |
|---|---|
| [docs/14 · GE 迁移 IoTDB 对照与替换方案](docs/14-GE迁移IoTDB对照与替换方案.md) | 26 条实测服务端事实、七族双跑对数数字、替换门槛 |
| [docs/13 · 业务替换方案](docs/13-业务替换方案.md) | 业务侧替换口径 |
| [docs/11 · IoTDB 三通道与等间隔差分](docs/11-IoTDB三通道与等间隔差分.md) | 三通道一致性与等间隔/差分语义 |
| [docs/09 · GE 生产环境集成测试](docs/09-GE生产环境集成测试.md) / [docs/10 · HLS 生产环境集成测试](docs/10-HLS生产环境集成测试.md) | 现场只读验证记录 |

**设计与规划**

| 文档 | 内容 |
|---|---|
| [docs/01 · 需求规格说明书](docs/01-需求规格说明书.md) | 需求与范围 |
| [docs/02 · 现状分析与选型建议](docs/02-现状分析与选型建议.md) | 参照 SDK 解剖与差异分析（含历史选型章节，已标注范围变更） |
| [docs/03 · 总体架构与模块设计](docs/03-总体架构与模块设计.md) | 架构、包结构、完整文件树 |
| [docs/05 · 实施路线图与验收标准](docs/05-实施路线图与验收标准.md) | 阶段与验收 |
| [docs/06 · 范围变更与力控接入](docs/06-范围变更与力控接入.md) | 范围变更记录与力控现场确认清单 |

**模块 README**

[common](tsdb-sdk-common/README.md) · [hls](tsdb-sdk-hls/README.md) · [ge](tsdb-sdk-ge/README.md) ·
[iotdb](tsdb-sdk-iotdb/README.md) · [bom](tsdb-sdk-bom/README.md) ·
[spring-boot-starter](tsdb-sdk-spring-boot-starter/README.md)

**其它**

- 中文 Javadoc：`docs/apidocs/index.html`
- 许可证：[LICENSE](LICENSE)
- CI：`.github/workflows/ci.yml`
- `MEMORY.md` 是**内部交接笔记**，外部使用者可跳过。

---

## 11. 许可证

[Apache License 2.0](LICENSE)