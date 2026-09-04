---
name: officia-setup
description: |
  把 Officia 引进自己的项目：Maven / Gradle 坐标、JDK 基线、聚合包 vs 单模块选择、
  本地仓库安装、离线环境部署、打成可执行 jar（shade）与"零依赖"到底意味着什么。

  触发场景：
  - 要在自己的项目里引入 officia，不知道写什么坐标
  - 报 "Could not resolve dependencies plus.ruoyi:officia-all"
  - 内网 / 离线环境怎么装
  - 只用一个能力，要不要引全家桶
  - 打包部署时 officia 要怎么处理

  触发词：引依赖、依赖、maven、gradle、坐标、pom、版本、JDK、安装、install、本地仓、离线、内网、打包、jar、shade、部署
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# 把 Officia 引进你的项目

## 概述

Officia 以标准 Maven 产物发布，坐标前缀 `plus.ruoyi`。**运行时零第三方依赖**——引进来只会多出 officia 自己的几个 jar，不会带任何传递依赖进你的依赖树。

## 环境基线

| 项 | 要求 | 校验 |
|---|---|---|
| JDK | **17+**（Officia 以 Java 17 为基线） | `java -version` |
| 构建工具 | Maven 或 Gradle | `mvn -v` |
| 网络 | 不需要（officia 运行期不联网，授权也是离线验签） | — |

## 一、引依赖

### 推荐：聚合包 `officia-all`（对标 hutool-all）

```xml
<dependency>
  <groupId>plus.ruoyi</groupId>
  <artifactId>officia-all</artifactId>
  <version>1.1.0</version>
</dependency>
```

```groovy
implementation 'plus.ruoyi:officia-all:1.1.0'
```

**`officia-all` 已聚合以下模块**（核实自 `../officia/officia-all/pom.xml`）：
`officia-words`、`officia-cells`、`officia-slides`、`officia-pdf`、`officia-barcode`、`officia-imaging`、`officia-email`、**`officia-license`**。

> 💡 所以引了 `officia-all` **就已经有授权客户端了**，不必再单独引 `officia-license`。

### 🔴 Maven Central 上只有 `officia-all` 这一个构件

发布到 Central 的是 shade 出来的**单一 uber-jar**（632 个类，含全部能力模块 + `officia-license`）。
**`officia-license`、`officia-pdf`、`officia-words` 等子模块坐标在 Central 上不存在**，引用会解析失败：

```
Could not resolve dependencies
  → plus.ruoyi:officia-license:jar:1.1.0 was not found in https://repo1.maven.org/maven2
```

核实命令（自己验一下，别信记忆）：

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  https://repo1.maven.org/maven2/plus/ruoyi/officia-all/1.1.0/officia-all-1.1.0.pom      # 200
curl -s -o /dev/null -w "%{http_code}\n" \
  https://repo1.maven.org/maven2/plus/ruoyi/officia-license/1.1.0/officia-license-1.1.0.pom  # 404
```

> 本 demo 的 `pom.xml` 曾多写一条 `officia-license`——本机 `mvn install` 后能解析，客户照抄就 404。
> 2026-08-09 已删除并加注释说明（见其 pom）。**回答客户"要引哪些依赖"时只说 `officia-all` 一个。**

### 下面这些模块坐标仅存在于源码构建（Central 上没有）

只有**自行 clone officia 源码并 `mvn install`** 后，本地仓才有这些子模块坐标，才谈得上单引：

```xml
<dependency>
  <groupId>plus.ruoyi</groupId>
  <artifactId>officia-pdf</artifactId>   <!-- 仅本地 install 后可用，Central 上是 404 -->
  <version>1.1.0</version>
</dependency>
```

Maven 会自动带上该模块依赖的底座（如 `officia-pdf` → `officia-render-pdf` + `officia-common`），你不用手写。

**源码构建后可单引的能力模块**：

| artifactId | 门面 | 能力 |
|---|---|---|
| `officia-words` | `OfficiaWords` | Word 转换 + 模板填充 |
| `officia-cells` | `OfficiaCells` | Excel / CSV / 公式 |
| `officia-slides` | `OfficiaSlides` | PPT 转换 |
| `officia-pdf` | `OfficiaPdf` | PDF 工具箱 |
| `officia-barcode` | `OfficiaBarCode` | 条码 / 二维码 |
| `officia-imaging` | `OfficiaImaging` | 图像处理 |
| `officia-email` | `OfficiaEmail` | 邮件 EML |
| `officia-license` | `OfficiaLicense` | 授权加载与门控 |

底座模块（`officia-common` / `officia-ooxml` / `officia-cfb` / `officia-engine` / `officia-render-pdf`）**不用手动引**，由能力模块传递带入。

> **选型建议**：直接用 `officia-all`——从 Central 拿包时它本来也是唯一选择，且 officia 零第三方依赖，
> 全引不会污染依赖树。只有"自行源码构建 + 对产物体积极度敏感"这一种情况才谈得上单引。

## 二、让本地仓库有 officia

**通常不用做这一步**——`officia-all:1.1.0` 自 2026-08-09 起已在 Maven Central，
`mvn package` 会自动拉取（首次需联网）。

本节适用于两种情况：**① 要用本地构建版本**（如 dev 版：门控可开关、未混淆，便于做授权前后对比）；
**② 离线 / 内网环境**。在 officia 源码目录装进本地仓：

```bash
# 在 ../officia 目录执行（联网环境）
mvn install -DskipTests

# 验证
ls ~/.m2/repository/plus/ruoyi/officia-all/1.1.0/
```

装完后，你的项目 `mvn -o package`（离线模式）就能解析到。

### 离线 / 内网环境

`mvn install` 需要下载插件时会卡住。绕过办法：用 JDK 自带 `jar` 手工把各模块 `target/classes` 打成 jar，按 Maven 仓库布局拷进 `~/.m2/repository/plus/ruoyi/<artifactId>/1.1.0/`，并补一个 `.pom` 文件（可从源码模块的 `pom.xml` 拷贝）。

布局要求：

```
~/.m2/repository/plus/ruoyi/officia-all/1.1.0/
├── officia-all-1.1.0.jar
└── officia-all-1.1.0.pom
```

> 内网私服（Nexus / Artifactory）场景：把上述 jar + pom 用 `mvn deploy:deploy-file` 传到私服的 hosted 仓库，团队即可正常拉取。

## 三、常见依赖问题

| 症状 | 原因 | 处置 |
|---|---|---|
| `Could not resolve dependencies ... plus.ruoyi:officia-all:jar:1.1.0` | 本地仓没有 | 去 `../officia` 跑 `mvn install -DskipTests` |
| `class file has wrong version 61.0` | JDK < 17 | 升到 JDK 17+，或检查 `maven.compiler.target` |
| 编译期找不到 `OfficiaWords` | 只引了 `officia-pdf` 等单模块 | 改引 `officia-all`，或补引 `officia-words` |
| 离线 `mvn -o` 报插件缺失 | 插件未缓存 | 联网跑一次完整构建预热，或在 `pluginManagement` 固定已缓存版本（本 demo `pom.xml` 就这么做的） |
| 依赖树里多出陌生的第三方包 | **不是 officia 带的** | `mvn dependency:tree` 看来源；officia 运行时零第三方依赖 |

核实"officia 到底带了什么"：

```bash
mvn dependency:tree -Dincludes=plus.ruoyi
```

## 四、打包部署

### 普通 Web 应用（Spring Boot 等）

不需要任何特殊处理——officia 就是普通 jar 依赖，`spring-boot-maven-plugin` 会正常打进 fat jar。

### 打成可直接 `java -jar` 的自包含 jar

本 demo 用 `maven-shade-plugin` 演示（见 `pom.xml`）：

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <version>3.6.0</version>
  <executions>
    <execution>
      <phase>package</phase>
      <goals><goal>shade</goal></goals>
      <configuration>
        <createDependencyReducedPom>false</createDependencyReducedPom>
        <transformers>
          <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
            <mainClass>你的主类全限定名</mainClass>
          </transformer>
        </transformers>
        <filters>
          <filter>
            <artifact>*:*</artifact>
            <excludes>
              <exclude>META-INF/*.SF</exclude>
              <exclude>META-INF/*.DSA</exclude>
              <exclude>META-INF/*.RSA</exclude>
              <exclude>module-info.class</exclude>
            </excludes>
          </filter>
        </filters>
      </configuration>
    </execution>
  </executions>
</plugin>
```

> shade 是**构建期**插件，不改变"运行时只依赖 JDK + officia"这一事实。

### Docker

```dockerfile
FROM eclipse-temurin:17-jre
COPY target/app.jar /app/app.jar
# 中文文档转 PDF 需要系统中文字体，见 officia-chinese-font
RUN apt-get update && apt-get install -y fonts-wqy-zenhei && rm -rf /var/lib/apt/lists/*
# 授权文件挂载进来，不要打进镜像
ENV OFFICIA_LICENSE=/etc/officia/officia.lic
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

> ⚠️ **不要把 `.lic` 打进镜像层**——用挂载或环境变量注入。见 `officia-license`。

## 五、"零依赖"到底意味着什么

| 说法 | 事实 |
|---|---|
| 运行时零第三方依赖 | ✅ officia 只用 JDK 标准库，不引 POI / PDFBox / iText / Aspose |
| 你的项目也必须零依赖 | ❌ **不需要**。那是 officia 库自身的开发铁律，与使用方无关，你想引什么引什么 |
| 不需要 JDK 之外的运行时 | ✅ JDK 17+ 即可，不需装 LibreOffice / Word / 字体服务 |
| 完全不需要字体文件 | ❌ 中文 PDF 需要能找到中文 TTF，见 `officia-chinese-font` |

## 验证接入成功

写一个最小验证（不需要任何素材文件，条码是现造的）：

```java
import plus.ruoyi.officia.barcode.OfficiaBarCode;

public class OfficiaSmokeTest {
    public static void main(String[] args) {
        byte[] png = OfficiaBarCode.qrPng("hello officia");
        // PNG 魔数 89 50 4E 47
        System.out.println("ok=" + (png.length > 0 && (png[0] & 0xFF) == 0x89 && png[1] == 'P'));
    }
}
```

跑通即说明依赖、JDK、类加载都正常。

## 相关技能

| 接下来 | 用 |
|---|---|
| 输出有水印要去掉 | `officia-license` |
| 不知道调哪个方法 | `officia-capability-map` |
| 集成进 Spring Boot | `officia-spring-integration` |
| 升级版本 | `officia-upgrade` |
