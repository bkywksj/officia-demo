---
name: officia-license
description: |
  Officia 授权（License）加载与门控：把 officia.lic 放对位置去掉评估水印、查询授权状态、
  按模块门控，以及排查"为什么有水印 / 为什么本地没水印但线上有"。

  触发场景：
  - 输出的 PDF 带评估水印、被限制页数
  - 拿到 officia.lic 不知道放哪、怎么加载
  - 要判断当前是评估态还是授权态、授权给谁、哪些模块可用
  - 本地跑没水印，打包上线后有水印（或反过来）
  - 容器 / CI 环境怎么注入授权

  触发词：授权、license、lic、令牌、水印、评估态、去水印、限页、降级、门控、enforcement、激活、过期、模块授权
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# Officia 授权加载与门控

## 概述

Officia 采用**席位签名授权**：不锁机器、不联网，把"授权给谁"签进离线授权文件 `officia.lic`，内嵌公钥离线验签。

**核心语义：库永远不会中断你的程序**。未授权只是**降级**（盖评估水印 + 限制页数），不抛异常、不 `System.exit`。所有 API 在评估态下都可正常调用。

## 一、加载授权（零代码，推荐）

买到 `officia.lic` 后放到下列**任一位置**，officia 首次使用能力时自动查找并验签加载，**无需写任何代码**。

自动加载顺序（核实自 `OfficiaLicense` 源码，命中即止）：

| 优先级 | 位置 | 适用场景 |
|---|---|---|
| 1 | 系统属性 `-Dofficia.license=<路径或令牌>` | 启动参数注入 |
| 2 | 环境变量 `OFFICIA_LICENSE=<路径或令牌>` | 容器 / CI 友好 |
| 3 | classpath `/officia.lic` | 放 `src/main/resources/` |
| 4 | 工作目录 `./officia.lic` | 放 jar 同级 |

均未命中 → 维持评估态。自动加载**对坏文件容错**（不抛异常、不阻断宿主）。

> 自动加载**至多尝试一次**（首次读授权态时懒触发），之后不再扫描。

## 二、显式加载（优先级最高，覆盖自动加载）

```java
import plus.ruoyi.officia.license.OfficiaLicense;

OfficiaLicense.setLicense(new java.io.File("/etc/officia/officia.lic"));  // 文件
OfficiaLicense.setLicense(inputStream);                                    // 输入流
OfficiaLicense.setLicense(tokenBytes);                                     // 字节
OfficiaLicense.setLicense(tokenText);                                      // 令牌文本
```

> ⚠️ **显式加载对坏令牌会抛 `OfficiaException`**（空令牌 / 签名无效属配置性错误，要让你早发现）；
> 而**自动加载**对坏文件静默容错。**过期**在两种方式下都不抛异常，只转为降级。

> 🔴 **铁律：令牌不入库**。不要把令牌文本硬编码进源码或提交 `.lic`（`.gitignore` 已拦 `*.lic`）。

## 三、查询授权状态

```java
OfficiaLicense.isLicensed();       // 是否已授权（签名有效且未过期）
OfficiaLicense.isEvaluation();     // 是否评估态
OfficiaLicense.getLicensee();      // 授权给谁
OfficiaLicense.getEdition();       // 版本（个人版 / 企业版）
OfficiaLicense.getModules();       // Set<String> 已授权模块码
OfficiaLicense.getExpiresEpoch();  // 过期时间戳
OfficiaLicense.hasModule("words"); // 某模块是否获授权（含 total 放行全部）
OfficiaLicense.isEnforced();       // 门控是否开启
OfficiaLicense.reset();            // 复位（主要用于测试）
```

**模块码**：`words` / `cells` / `slides` / `pdf` / `email` / `imaging` / `barcode`，另有 `total` 表示放行全部。

启动自检片段：

```java
if (OfficiaLicense.isLicensed()) {
    System.out.println("已授权：" + OfficiaLicense.getLicensee()
        + " / " + OfficiaLicense.getEdition()
        + " / 模块=" + OfficiaLicense.getModules());
} else {
    System.out.println("评估态运行（输出将带水印、限页）");
}
```

## 🔴 四、最容易踩的坑：本地没水印，线上有水印

这**不是 bug**，根因是门控开关 `BuildFlags.ENFORCED_BY_DEFAULT` 是**构建期常量**（由 Maven 属性 `officia.enforced` 在 generate-sources 阶段生成，javac 内联）：

| 你用的 officia 产物 | `officia.enforced` | 未授权时的行为 |
|---|---|---|
| 自己从源码 `mvn install`（**开发/测试构建**） | `false` | **不降级**——没有水印、不限页，单测常绿 |
| `mvn -Prelease` 构建 / 正式发布的 jar | `true` | **恒降级**——未授权就盖水印、限页，且**不可经公开 API 关闭** |

所以：

- 本地从源码装的 officia 跑出来干干净净 → 正常，因为门控没开。
- 换成正式发布 jar 后出现水印 → 正常，说明门控开了而你还没加载授权。**去加载 `.lic`，不要试图关门控**。

`enableEnforcement(boolean)` 的真实语义：

```java
OfficiaLicense.enableEnforcement(true);   // 开发/测试构建：打开门控，用来复现"客户未授权"的效果
OfficiaLicense.enableEnforcement(false);  // 发布版忽略此调用（恒 true，关闭分支已被死代码消除）
```

> 它**只能开、不能在发布版关**。用它在开发期复现降级效果，别指望用它去掉正式版水印。

## 五、排查"为什么还有水印"

按顺序查：

```java
System.out.println("enforced   = " + OfficiaLicense.isEnforced());     // false → 门控没开，水印不该来自授权
System.out.println("licensed   = " + OfficiaLicense.isLicensed());     // false → 没加载成功
System.out.println("evaluation = " + OfficiaLicense.isEvaluation());
System.out.println("licensee   = " + OfficiaLicense.getLicensee());
System.out.println("modules    = " + OfficiaLicense.getModules());
System.out.println("hasWords   = " + OfficiaLicense.hasModule("words"));
```

| 现象 | 原因 | 处置 |
|---|---|---|
| `licensed=false`，四个位置都放了 `.lic` | 文件名/路径不对，或 classpath 未打包进去 | 确认是 `officia.lic`（精确名）；jar 里确认 `/officia.lic` 在根；改用 `-Dofficia.license=` 绝对路径验证 |
| `licensed=false`，用显式加载抛 `OfficiaException` | 令牌为空或签名无效 | 从 flm 后台重新下载，别用文本编辑器改过的副本 |
| `licensed=false`，不抛异常 | 授权**已过期**（过期不抛，转降级） | 看 `getExpiresEpoch()`，续期 |
| `licensed=true` 但某能力仍降级 | 该模块未在授权范围内 | `hasModule("cells")` 查；模块门控防"买 Words 却用 Cells" |
| 浏览器下载的授权文件叫 `officia (1).lic` | 文件名不匹配 | 改名为 `officia.lic`（`.gitignore` 已按 `*.lic` 拦截各种重名形态） |
| 加载了但仍评估态 | 加载发生在**首次使用能力之后** | 显式 `setLicense` 放在应用启动最早期；或用自动加载（懒触发时机由库控制） |

## 六、容器 / CI 环境注入

```bash
# Docker：挂载 + 环境变量，不要打进镜像层
docker run -v /host/officia.lic:/etc/officia/officia.lic:ro \
           -e OFFICIA_LICENSE=/etc/officia/officia.lic  myapp
```

```yaml
# K8s：用 Secret 挂载
volumeMounts:
  - name: officia-license
    mountPath: /etc/officia
    readOnly: true
env:
  - name: OFFICIA_LICENSE
    value: /etc/officia/officia.lic
```

CI 里做转换测试时，若不想配授权，接受评估态输出即可（能力全部可用，只是带水印）——本 demo 的测试就是这么写的，见 `officia-demo-test`。

## 七、授权状态是**进程全局**

`OfficiaLicense` 是有状态门面（对齐 Aspose 语义），授权态全进程共享：

- 在应用启动时加载**一次**即可，不要每次请求都 `setLicense`。
- 多租户 / 多 License 并存**不支持**（进程内只有一份授权态）。
- 测试之间要隔离用 `OfficiaLicense.reset()`。

## 八、授权版本差异（供答疑）

两档授权**代码完全一致**，差异在授权主体与席位：

| 条目 | 个人版 | 企业版 |
|---|---|---|
| 能力模块 | 全部 | 全部 |
| 开发席位 | 1 名开发者 | 多名（按团队规模） |
| 团队内部多人使用 | ❌ | ✅ |
| 交付并部署给最终客户 | ✅ | ✅ |
| OEM / 再分发 | ❌ | 可协商 |

> 价格与条款以 `../officia-docs/docs/guide/licensing.md` 与商业授权合同为准，**不要在代码/文档里复述可能变动的价格**。

## 相关技能

| 接下来 | 用 |
|---|---|
| 在 demo 里看门控开/关的并排对比 | `officia-testbench` |
| 写授权相关的测试 | `officia-demo-test` |
| 水印是中文但显示成方块 | `officia-chinese-font` |
| 报了 `OfficiaException` | `officia-troubleshooting` |
