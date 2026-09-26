# AGENTS.md — officia-demo（Codex 入口）

> Claude Code 用户请读 `CLAUDE.md`（内容等价）。本文件是 Codex CLI 的项目入口。

## 语言设置

**必须使用中文**与用户对话。

---

## 项目定位

本目录是 **Officia 的消费方示例项目**，同时也是使用者的**学习台 + 验证台**。

- **Officia** = 纯自研、**运行时零第三方依赖**的 Java 办公文档套件（对标 Aspose），17 个模块 / 10 个门面类。
- **officia-demo** = 模拟真实客户，通过 Maven 坐标引入**已发布的** officia 产物（`plus.ruoyi:officia-all` + `officia-license`），演示各能力用法、授权门控效果，并作升级回归护栏。
- 独立 groupId `plus.ruoyi.demo`，**不属于** officia reactor。

> 🎯 本技能体系面向**使用 officia 的开发者**（接入 / 调 API / 排错 / 集成）。
> ⚠️ 它**不是**开发 officia 库本身的技能体系——改库请去同级 `../officia/`（那里有面向库开发者的 30 个技能）。

### 同级目录分工

| 目录 | 是什么 | 何时进去 |
|---|---|---|
| `../officia/` | Officia 库**源码**（17 模块 reactor） | 查 API 真实签名/行为；改库本身 |
| `../officia-docs/` | 文档站（VitePress） | 查面向用户的说明文档 |
| `officia-demo/`（本目录） | 消费方示例 + 可视化测试台 | 学用法、实测能力、写验证、回归 |

---

## 🔴 三条铁律（恒常生效）

### 铁律 1：API 事实以 `../officia/` 源码为准

任何 API 签名、参数、返回值、行为描述，**必须**核对 `../officia/officia-*/src/main/java/**` 的真实代码，**禁止**凭印象或从 Aspose / POI / iText 的用法类推。

**真相源优先级**（冲突时上位覆盖下位）：

```
1. ../officia/ 源码（门面类 OfficiaXxx.java 的方法签名与 Javadoc）   ← 最高
2. ../officia/ 的绿测（src/test/**）
3. ../officia-docs/docs/ 文档站
4. 各 README.md / 历史注释                                         ← 最低，可能过时
```

> ⚠️ **已知过时注释**：`officia-words/.../template/package-info.java` 写着模板填充"骨架预留 / 计划实现"，**这是过时的**——`WordTemplateFiller` / `WmlBlockLoopRenderer` / `WmlConditionRenderer` / `WmlBatchMerge` 等均已实现，门面有 16 个 `fillTemplate*` 重载。凡遇"预留/计划"措辞，回源码核实。

### 铁律 2：绝不把 License 令牌写进仓库

`.lic` 文件、License 令牌文本、`OFFICIA_LICENSE` 的真实值，任何形态都不入库（`.gitignore` 已拦 `*.lic`）。不生成、不硬编码、不给绕过水印的建议。

### 铁律 3：UTF-8 无 BOM

所有新建 / 修改的 `.java`、`.md`、`.xml`、`.json` 一律 UTF-8 无 BOM。

---

## Officia 能力速览（10 个门面）

引 `officia-all` 一次得全部能力，**运行时零第三方依赖**（只依赖 JDK 17+）。

| 门面类 | 模块 | 主要能力 |
|---|---|---|
| `OfficiaWords` | officia-words | docx/doc → PDF；docx 模板填充 / 邮件合并 |
| `OfficiaCells` | officia-cells | xlsx → PDF/CSV；CSV → PDF；公式求值与重算 |
| `OfficiaSlides` | officia-slides | pptx → PDF（内容提取式 / 版式保真式） |
| `OfficiaPdf` | officia-pdf | 合并·拆分·抽页·删页·旋转·水印·页码·抽文字·抽图·加密·元数据 |
| `OfficiaBarCode` | officia-barcode | Code128/39/93、EAN-13/8、UPC-A、ITF-14、QR |
| `OfficiaImaging` | officia-imaging | 滤镜变换、格式互转、图片 → PDF |
| `OfficiaEmail` | officia-email | EML 解析、邮件归档 → PDF、写出 EML |
| `OfficiaOcr` | officia-ocr | 图片/扫描件识别成文字（中英两档权重内置） |
| `OfficiaEditor` | officia-editor | 在线编辑：docx/xlsx ⇄ JSON 契约，前端 `officia-editor.js` 随 jar 分发 |
| `OfficiaLicense` | officia-license | 授权加载、门控查询、评估态判定 |

门面统一形态：`public final class` + 全静态方法 + `byte[]` 进 `byte[]` 出 + 异常统一 `OfficiaException`。

---

## 技能清单（28 个 = 21 能力技能 + 7 命令技能）

Codex 启动时已自动加载全部 SKILL.md 的 frontmatter。按 description 的触发场景匹配后，读取完整正文再动手。

### 入口与接入（3）

| 技能 | 用途 |
|---|---|
| `officia-capability-map` | 能力全景与选型入口；**明确不支持项**清单 |
| `officia-setup` | Maven/Gradle 坐标、JDK 基线、本地仓安装、离线部署、打包 |
| `officia-license` | `.lic` 加载、去水印、门控排查、构建期开关差异 |

### 能力使用（9）

| 技能 | 用途 |
|---|---|
| `officia-words` | Word → PDF；四种入参、流式、页数耗时、ConvertOptions |
| `officia-template` | 模板填充 / 邮件合并；完整占位符语法；批量与 JSON 数据源 |
| `officia-cells` | Excel/CSV 转换、CSV 解析、公式求值与重算（80 个函数） |
| `officia-slides` | PPTX → PDF；内容提取式 vs 版式保真式 |
| `officia-pdf` | PDF 工具箱 + `PdfEditor` 链式编辑 |
| `officia-barcode` | 8 种码制、输入约束与校验位、QR 四档纠错 |
| `officia-imaging` | 滤镜变换、格式互转、水印、图片 → PDF |
| `officia-email` | EML 解析、归档 PDF、构造写出 |
| `officia-editor` | 在线编辑：`OfficiaEditor` 六组门面 + `officia-editor.js` 前端（功能区/属性面板/网格）、工作簿重算、能力边界 |

### 排坑与工程化（6）

| 技能 | 用途 |
|---|---|
| `officia-chinese-font` | 中文方块/乱码；`fontDirectory` vs `fontTtf` 两条入口 |
| `officia-troubleshooting` | 异常消息**前缀 = 故障域路标**；结果不对的判别流程 |
| `officia-performance` | 流式省什么、`CfbLimits` 真实默认值、线程安全边界、并发建议 |
| `officia-spring-integration` | Spring Boot 集成全套：上传/下载/授权初始化/异常处理/健康检查 |
| `officia-deploy` | 容器编排与部署：监听地址、内存三级联动、字体三入口、License 注入、Reeve |
| `officia-upgrade` | 升级流程、破坏性变更检查、回归护栏、回退 |

### Demo 自身（2）

| 技能 | 用途 |
|---|---|
| `officia-testbench` | 可视化测试台：启动、10 个面板、API 端点、批量回归 |
| `officia-demo-test` | JUnit5 + AssertJ 断言套路；授权测试隔离与跳过 |

### 技能体系维护（1）

| 技能 | 用途 |
|---|---|
| `officia-skill-maintain` | 加/改/删技能、跟随 officia 升级更新内容、双系统六处同步与自检 |

### 命令技能（7）

| 技能 | 用途 |
|---|---|
| `convert` | 一句话做文档转换 |
| `license` | 授权状态检查与加载指引 |
| `testbench` | 启动可视化测试台 |
| `check` | 接入自检（八项） |
| `demo` | 跑示例并解读结果 |
| `next` | 下一步建议 |
| `sync` | 技能体系同步与自检（改完技能必跑） |

---

## 快速上手

```bash
# 1) 确保本地仓有 officia（在 ../officia 执行）
mvn install -DskipTests

# 2) 可视化测试台（推荐先跑）
mvn -o package
java -jar target/officia-demo-1.0.0.jar          # 自动开浏览器
java -jar target/officia-demo-1.0.0.jar 9090 --no-open

# 3) 示例测试
mvn -o test
```

测试台绑定 `127.0.0.1`，上传文件只存内存、重启即清、不落盘。

---

## Codex Hooks（4 个）

`.codex/config.toml` 里 `[features] hooks = true` 启用，`.codex/hooks.json` 注册：

| 事件 | 脚本 | 作用 |
|---|---|---|
| SessionStart(startup) | `session-start.cjs` | 自动注入 `.claude/docs/experience/` 最近经验摘要 |
| UserPromptSubmit | `skill-forced-eval.cjs` | 注入强制技能评估流程 + 三铁律 |
| PreToolUse | `pre-tool-use.cjs` | 危险命令拦截、`.lic` 写入拦截、BOM 拦截、门面调用提醒 |
| Stop | `stop.cjs` | 清理与提示 |

---

## 会话开始时加载经验

`.claude/docs/experience/` 下若有 `*-exp-summary.md`，SessionStart hook 会自动注入最近摘要。
会话末尾用户说"沉淀经验/复盘"时，把本次问题→解决→教训写入 `.claude/docs/experience/{YYYYMMDD}/{主题}-exp-summary.md`。

---

## 禁止事项

| 禁止 | 原因 |
|---|---|
| 凭印象写 officia API | 会写出不存在的方法，编译不过 |
| 把 `.lic` / 令牌写进代码或提交 | 授权凭据泄露 |
| 用 Aspose/POI/iText 的用法类推 | API 形态不同 |
| 在 demo 里改 `../officia/` 源码 | demo 是消费方 |
| 说"支持 X"而不核实 | 有明确不支持项（加密 DOC、WMF/EMF、竖排 RTL 等） |
