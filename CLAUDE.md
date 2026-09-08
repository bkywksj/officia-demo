# CLAUDE.md — officia-demo（Officia 使用者工作台）

## 语言设置

**必须使用中文**与用户对话。

---

## 项目定位

本目录是 **Officia 的消费方示例项目**，同时也是使用者的**学习台 + 验证台**。

- **Officia** = 纯自研、**运行时零第三方依赖**的 Java 办公文档套件（对标 Aspose），17 个模块 / 10 条产品线。
- **officia-demo** = 模拟真实客户，通过 Maven 坐标引入**已发布的** officia 产物（`plus.ruoyi:officia-all` + `officia-license`），演示各能力用法、授权门控效果，并作升级回归护栏。
- 独立 groupId `plus.ruoyi.demo`，**不属于** officia reactor（同级独立目录）。

> 🎯 **本技能体系面向"使用 officia 的开发者"**——你要解决的是「怎么接入、怎么调 API、输出不对怎么排查、怎么集成进我自己的系统」。
>
> ⚠️ 它**不是**开发 officia 库本身的技能体系。改库本身（新增格式解析器、改排版引擎、改 IR 模型…）请去同级 `../officia/`，那里有面向库开发者的 30 个技能（洁净室、零依赖、字体子集、排版引擎…）。

### 同级目录分工

| 目录 | 是什么 | 你什么时候进去 |
|---|---|---|
| `../officia/` | Officia 库**源码**（17 模块 reactor） | 查 API 真实签名/行为；改库本身 |
| `../officia-docs/` | 文档站（VitePress） | 查面向用户的说明文档 |
| `officia-demo/`（本目录） | 消费方示例 + 可视化测试台 | 学用法、实测能力、写验证、回归 |

---

## 🔴 三条铁律（本项目恒常生效）

### 铁律 1：API 事实以 `../officia/` 源码为准

任何 API 签名、参数、返回值、行为描述，**必须**核对 `../officia/officia-*/src/main/java/**` 的真实代码，**禁止**凭印象或从其他 Office 库（Aspose/POI/iText）的用法类推。

**真相源优先级**（冲突时上位覆盖下位）：

```
1. ../officia/ 源码（门面类 OfficiaXxx.java 的方法签名与 Javadoc）   ← 最高
2. ../officia/ 的绿测（src/test/**，证明"真能跑通"）
3. ../officia-docs/docs/ 文档站
4. 各 README.md / 历史注释                                         ← 最低，可能过时
```

> ⚠️ **已知的过时注释**：`officia-words/.../template/package-info.java` 写着模板填充"骨架预留 / 计划实现"，**这是过时的**——该目录下 `WordTemplateFiller` / `WmlBlockLoopRenderer` / `WmlConditionRenderer` / `WmlBatchMerge` 等均已实现，`OfficiaWords.fillTemplate*` 系列有 16 个重载可用。凡遇"预留/计划"措辞，一律回源码目录核实。

### 铁律 2：绝不把 License 令牌写进仓库

`.lic` 文件、License 令牌文本、`OFFICIA_LICENSE` 的真实值，**任何形态都不入库**（`.gitignore` 已拦 `*.lic`）。示例代码里只写路径/占位，不贴真实令牌。

### 铁律 3：UTF-8 无 BOM

所有新建/修改的 `.java`、`.md`、`.xml`、`.json` 一律 **UTF-8 无 BOM**。`pom.xml` 已设 `project.build.sourceEncoding=UTF-8`。

---

## Officia 能力速览（17 模块 / 10 门面）

引 `officia-all` 一次得全部能力，**运行时零第三方依赖**（只依赖 JDK 17+）。

| 门面类 | 模块 | 主要能力 | 对应技能 |
|---|---|---|---|
| `OfficiaWords` | officia-words | docx/doc → PDF；docx 模板填充 / 邮件合并 | `officia-words`、`officia-template` |
| `OfficiaCells` | officia-cells | xlsx → PDF/CSV；CSV → PDF；公式求值与重算 | `officia-cells` |
| `OfficiaSlides` | officia-slides | pptx → PDF（内容提取式 / 版式保真式） | `officia-slides` |
| `OfficiaPdf` | officia-pdf | 合并·拆分·抽页·删页·旋转·水印·页码·抽文字·抽图·加密·元数据 | `officia-pdf` |
| `OfficiaBarCode` | officia-barcode | Code128/39/93、EAN-13/8、UPC-A、ITF-14、QR | `officia-barcode` |
| `OfficiaImaging` | officia-imaging | 15 种滤镜/变换、格式互转、图片 → PDF | `officia-imaging` |
| `OfficiaEmail` | officia-email | EML 解析、邮件归档 → PDF、写出 EML | `officia-email` |
| `OfficiaOcr` | officia-ocr | 图片/扫描件识别成文字（中英两档权重内置） | 见 `officia-capability-map` |
| `OfficiaEditor` | officia-editor | **在线编辑**：docx/xlsx ⇄ JSON 契约，前端 `officia-editor.js` 随 jar 分发 | `officia-editor` |
| `OfficiaLicense` | officia-license | 授权加载、门控查询、评估态判定 | `officia-license` |

底层模块（`officia-common` / `officia-ooxml` / `officia-cfb` / `officia-engine` / `officia-render-pdf` / `officia-render-image` / `officia-all`）**不需要直接调用**——门面已屏蔽。

---

## 快速上手

```bash
# 1) 确保本地仓有 officia（在 ../officia 执行 mvn install）
# 2) 可视化测试台（推荐先跑这个，能直接上传文件实测）
mvn -o package
java -jar target/officia-demo-1.0.0.jar          # 启动后自动开浏览器
java -jar target/officia-demo-1.0.0.jar 9090 --no-open

# 3) 跑示例测试
mvn -o test
```

> 测试台服务用 JDK 内置 `com.sun.net.httpserver.HttpServer`，**demo 自身同样零第三方依赖**；上传文件只存内存、重启即清，不落盘不入库。

---

## 🔴 开发技能索引与强制评估

用户提问时，**先评估匹配技能 → 逐个激活 → 再动手**。技能文件在 `.claude/skills/{技能名}/SKILL.md`。

### 入口与接入（3）

| 技能 | 什么时候用 |
|---|---|
| `officia-capability-map` | **不知道该用哪个能力/方法**、要做能力选型、想知道"officia 支持不支持 X" |
| `officia-setup` | 引依赖、Maven/Gradle 坐标、JDK 版本、本地仓安装、离线部署、打包 |
| `officia-license` | 加载 `.lic`、去水印、评估态判定、模块门控、`enableEnforcement` |

### 能力使用（9）

| 技能 | 什么时候用 |
|---|---|
| `officia-words` | Word → PDF（docx/doc）、流式转换、页数耗时、ConvertOptions |
| `officia-template` | docx 模板填充、邮件合并、占位符语法、格式器、条件/循环、批量出 PDF |
| `officia-cells` | Excel → PDF/CSV、CSV → PDF、公式求值、整表重算 |
| `officia-slides` | PPTX → PDF、内容提取式 vs 版式保真式 |
| `officia-pdf` | PDF 合并拆分抽页删页旋转水印页码抽文字抽图加密、`PdfEditor` 链式 |
| `officia-barcode` | 条码/二维码生成、码制选型、纠错级别、尺寸参数 |
| `officia-imaging` | 图片滤镜变换、格式互转、图片 → PDF、水印 |
| `officia-email` | EML 解析、邮件归档 PDF、构造 EML |
| `officia-editor` | 在线编辑：`OfficiaEditor` 六组门面 + 前端 `officia-editor.js`（功能区/属性面板/网格）、工作簿重算、能力边界 |

### 排坑与工程化（6）

| 技能 | 什么时候用 |
|---|---|
| `officia-chinese-font` | 中文变方块/乱码、字体嵌入、`fontDirectory`、水印中文、CID 子集 |
| `officia-troubleshooting` | 抛 `OfficiaException`、转换失败、输出为空/异常、加密文档打不开 |
| `officia-performance` | 大文档、内存峰值、流式 API 选型、并发调用、超时 |
| `officia-spring-integration` | 集成进 Spring Boot / Web：上传→转换→下载、线程安全、临时文件 |
| `officia-deploy` | 容器化与部署：Dockerfile / compose 编排、监听地址、内存联动、字体、License 注入、远程部署 |
| `officia-upgrade` | 升级 officia 版本、API 破坏性变更检查、回归验证 |

### Demo 自身（2）

| 技能 | 什么时候用 |
|---|---|
| `officia-testbench` | 启动/使用可视化测试台、面板对应能力、批量回归、上传字体 |
| `officia-demo-test` | 写自己的验证测试（JUnit 5 + AssertJ）、断言套路、授权测试跳过 |

### 技能体系维护（1）

| 技能 | 什么时候用 |
|---|---|
| `officia-skill-maintain` | 加/改/删技能、跟随 officia 升级更新技能内容、双系统六处同步 |

---

## 快速命令

| 命令 | 用途 |
|---|---|
| `/convert` | 一句话做文档转换（自动选门面 + 出可运行代码） |
| `/license` | 授权状态检查与加载指引 |
| `/testbench` | 启动可视化测试台并说明面板 |
| `/check` | 接入自检（JDK / 依赖 / 本地仓 / 授权 / 编码） |
| `/demo` | 跑示例测试并解读结果 |
| `/next` | 根据当前状态给下一步建议 |
| `/sync` | 技能体系同步与自检（改完技能必跑） |

---

## 会话开始时加载经验

若 `.claude/docs/experience/` 下有内容，会话开始时读取最近的 `*-exp-summary.md`，把历史踩坑带进上下文。
会话末尾用户说"沉淀经验/复盘"时，把本次问题→解决→教训写入 `.claude/docs/experience/{YYYYMMDD}/{主题}-exp-summary.md`。

---

## 目录结构

```
officia-demo/
├── CLAUDE.md                  # 本文件（Claude Code 入口）
├── AGENTS.md                  # Codex 入口（同等内容 + 技能清单表）
├── pom.xml                    # 引 officia-all + officia-license + JUnit5/AssertJ
├── README.md                  # 面向人的说明
├── status.json                # 技能体系状态台账
├── docs/usage-guide.md        # 使用指南
├── deploy/                    # 容器编排（Dockerfile / docker-compose.yml / .env.example）
├── scripts/
│   ├── sync-codex.cjs         # ★ 技能体系同步与六处一致性自检（改完技能必跑）
│   └── command-skill-meta.json# 命令 → Codex 技能的 frontmatter 元数据
├── .claude/                   # Claude Code 配置（skills/hooks/commands）
├── .codex/                    # Codex 配置（skills 镜像 + hooks + config.toml，★ 生成物勿手改）
└── src/
    ├── main/java/plus/ruoyi/officia/demo/
    │   ├── Demo.java                  # 命令行示例入口
    │   └── web/                       # 可视化测试台（HttpServer + 路由 + 批量回归）
    │       ├── DemoServer.java        # main：启动服务、开浏览器
    │       ├── ApiRoutes.java         # 全部 /api/** 端点（各能力实测入口）
    │       ├── Batch.java             # 一键批量回归
    │       ├── Fonts.java             # 系统字体探测（中文水印用）
    │       ├── Router.java / Http.java / Json.java / Store.java
    │   └── resources/web/index.html   # 测试台前端页面
    └── test/java/plus/ruoyi/officia/demo/
        ├── CellsDemoTest / BarcodeDemoTest / PdfDemoTest
        ├── LicenseDemoTest / EnforcementDemoTest   # 授权与门控
        └── web/                                     # 测试台自身单测
```

---

## 禁止事项

| 禁止 | 原因 |
|---|---|
| 凭印象写 officia API（不核对 `../officia/` 源码） | 会写出不存在的方法，编译不过 |
| 把 `.lic` / License 令牌写进代码或提交 | 授权凭据泄露 |
| 用 Aspose/POI/iText 的用法类推 officia | API 形态不同（officia 是 `byte[]` 进 `byte[]` 出的静态门面） |
| 在 demo 里改 `../officia/` 的源码 | demo 是消费方，改库请去库目录 |
| 说"officia 支持 X"而不核实 | 有明确不支持项（加密 DOC、WMF/EMF 图片、竖排 RTL 等），见 `officia-capability-map` |
