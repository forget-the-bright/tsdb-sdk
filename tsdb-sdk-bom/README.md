# tsdb-sdk-bom —— 依赖版本管理

## 1. 模块定位

`tsdb-sdk-bom` 是 Maven BOM（Bill of Materials），只包含
`dependencyManagement`，不产生代码。业务项目导入 BOM 后，引入 SDK 各模块
不需要重复写版本号，避免 common、驱动、starter 版本错配。

## 2. 使用方式

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
```

之后按需引入：

```xml
<dependency>
    <groupId>io.github.forget-the-bright</groupId>
    <artifactId>tsdb-sdk-common</artifactId>
</dependency>
<dependency>
    <groupId>io.github.forget-the-bright</groupId>
    <artifactId>tsdb-sdk-hls</artifactId>
</dependency>
<dependency>
    <groupId>io.github.forget-the-bright</groupId>
    <artifactId>tsdb-sdk-ge</artifactId>
</dependency>
<dependency>
    <groupId>io.github.forget-the-bright</groupId>
    <artifactId>tsdb-sdk-iotdb</artifactId>
</dependency>
```

Spring Boot 项目只需：

```xml
<dependency>
    <groupId>io.github.forget-the-bright</groupId>
    <artifactId>tsdb-sdk-spring-boot-starter</artifactId>
</dependency>
```

## 3. 管理范围

BOM 统一管理以下模块的版本（均为 `${project.version}`）：

- `tsdb-sdk-common`
- `tsdb-sdk-hls`
- `tsdb-sdk-ge`
- `tsdb-sdk-iotdb`
- `tsdb-sdk-spring-boot-starter`

后续新增驱动（例如力控）时，在本 BOM 增加对应 `dependencyManagement` 条目
即可，业务项目无需改版本。

## 4. 设计说明

- BOM 的 parent 仍是 `tsdb-sdk-parent`，保证自身版本与聚合工程一致。
- BOM 不引入任何传递依赖，导入它不会把驱动塞进 classpath。
- 发布时 BOM 与各模块一起 deploy，版本号必须同步。
