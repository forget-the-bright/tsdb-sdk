# tsdb-sdk-spring-boot-starter —— Spring Boot 自动装配

## 1. 模块定位

把 `TsdbManager` 注册为 Spring Bean，让业务代码通过构造器注入即可使用统一
时序库 API；换库只需修改 `tsdb.default-instance`，不需要改 Java 代码。

本模块只做装配与配置绑定，不包含任何驱动逻辑。驱动仍由
`META-INF/services` 的 `ServiceLoader` 发现。

## 2. 使用方式

### 2.1 引入依赖

```xml
<dependency>
    <groupId>io.github.forget-the-bright</groupId>
    <artifactId>tsdb-sdk-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2.2 配置

```yaml
tsdb:
  default-instance: hls_main
  security:
    allow-write: false
    allow-insecure-transport: false
  instances:
    hls_main:
      type: hls
      base-url: https://10.10.7.177:9000
      auth:
        user-id: httpIO
        secret-key: ${HLS_SECRET}
    iotdb_main:
      type: iotdb
      base-url: http://10.30.1.10:18080
      auth:
        username: root
        password: ${IOTDB_PASSWORD}
```

### 2.3 注入

```java
@Service
public class TrendService {

    private final TsdbManager manager;

    public TrendService(TsdbManager manager) {
        this.manager = manager;
    }

    public TagSeries query() {
        return manager.client().history().read(query);
    }
}
```

`TsdbManager` 的 `destroyMethod` 已配置为 `close`，容器关闭时自动释放连接。

## 3. 自动装配内容

`TsdbAutoConfiguration`：

- `@ConditionalOnClass(TsdbManager.class)`
- `@EnableConfigurationProperties(TsdbProperties.class)`
- `@Bean(destroyMethod = "close") @ConditionalOnMissingBean`
- 内部执行 `TsdbClients.manager().config(props.toConfig()).discoverDrivers().build()`

`TsdbProperties` 的字段与 `tsdb.*` 一一对应，`toConfig()` 转换为 common 的
纯 POJO `TsdbConfig`。common 不依赖 Spring，`@ConfigurationProperties`
只存在于本模块。

## 4. 覆盖默认 Bean

如果应用需要自定义驱动注册或指标实现，自行声明 `TsdbManager` Bean 即可，
自动配置会因 `@ConditionalOnMissingBean` 退让：

```java
@Bean(destroyMethod = "close")
public TsdbManager tsdbManager() {
    return TsdbClients.manager()
            .config(myConfig)
            .driver(new MyDriver())
            .metrics(myMetrics)
            .build();
}
```

## 5. 测试

```bash
mvn -pl tsdb-sdk-spring-boot-starter -am test
```

测试用 `AnnotationConfigApplicationContext` + `MapPropertySource` 验证
`tsdb.default-instance` 与 `tsdb.instances.*` 能绑定并创建 `TsdbManager`。
