# officia-demo

**独立的 officia 依赖消费方示例**——模拟真实客户，通过 Maven 坐标引入已发布的 officia，演示各能力使用 + 授权门控，并作回归验证。

> 不属于 officia reactor（同级独立目录，独立 groupId `plus.ruoyi.demo`）。它引的是 `plus.ruoyi:officia-all` / `officia-license` 的**发布产物**，因此能证明"发布的 jar 真的能被外部项目引入使用"。

> 🔒 **本示例不携带任何 License 令牌 / `.lic` 证书**（面向公开展示）。授权解锁效果由使用者放入自己的 `officia.lic` 时生效。

## 🌐 线上站点

本测试台已部署对外访问：**<https://demo.officia.ruoyi.plus/>**（评估版：水印 + 限 30 页）。

想看效果不必本地构建，直接开这个地址即可；本地跑法见下文。
部署形态（msi / Docker / 18080 端口 / 挂载字体）见 `.claude/skills/officia-deploy/`。

## 前置：让本地仓有 officia

demo 依赖 `officia-*:1.0.0`。联网环境直接在 officia 目录 `mvn install` 即可；
离线环境用 JDK `jar` 手动布署（见 officia 项目根 `mvn` 受限时的绕过：把各模块 `target/classes` 打成 jar 拷进 `~/.m2/repository/plus/ruoyi/`）。

## 跑起来

### 方式一：可视化测试台（推荐）

```bash
mvn -o package                                  # 产出可执行 jar
java -jar target/officia-demo-1.0.0.jar         # 启动后控制台打印访问链接，并自动打开浏览器
java -jar target/officia-demo-1.0.0.jar 9090 --no-open   # 指定端口 / 不自动开浏览器
```

浏览器里可**直接上传 doc/docx/xlsx/pptx/pdf/图片/eml 实测**：转换、模板填充、PDF 工具箱、图像滤镜、
条码生成、邮件归档、授权门控对比、一键批量回归——每步都显示页数/耗时/体积、内嵌预览、可下载。

> 服务用 JDK 内置 `com.sun.net.httpserver.HttpServer`，**demo 自身同样零第三方依赖**；
> 上传文件只存内存、重启即清，不落盘不入库。

### 方式二：命令行 / 单测

```bash
mvn -o test                 # 示例测试全绿（授权演示在未放 officia.lic 时自动跳过）
mvn -o exec:java -Dexec.mainClass=plus.ruoyi.officia.demo.Demo   # 或直接 run Demo.main
```

## 测试台面板

| 面板 | 可实测的能力 |
|---|---|
| Words · DOC/DOCX | 上传 .doc（CFB）/.docx（OOXML）→ PDF，自动识别格式；字节 / 流式两种输出 |
| 模板填充 · 邮件合并 | 模板 + JSON → 单条 / 合并一份 / 每条一份 / 只填 docx |
| Cells · XLSX/CSV | XLSX→PDF、XLSX→CSV、CSV→PDF、CSV 解析、单元格公式求值、整表重算 |
| Slides · PPTX | PPTX→PDF，标准 / 版式保真双模式 |
| PDF 工具箱 | 合并·拆分·抽页·删页·旋转·水印·页码·抽文字·抽图片·RC4-128·AES-256·信息 |
| Imaging · 图像 | 15 种滤镜/变换 + 格式转换 + 图片→PDF，处理前后并排对比 |
| BarCode · 条码 | Code128/39/93、EAN-13/8、UPC-A、ITF-14、QR（4 档纠错）+ 全码制一键预览 |
| Email · EML | EML 解析（主题/收发件/附件/正文）、邮件归档 → PDF |
| OCR · 图片/扫描件 | 图片 → 文字（逐行文本 + 置信度 + 行框）、扫描件 PDF → Word；中/英自研权重，纯 Java 推理 |
| 授权门控与对比 | 加载 .lic、模块门控矩阵、**同一份数据在门控开/关下的页数与水印并排对比** |
| 批量回归 | 用 officia 自造输入跑全能力并断言（%PDF- 头 / 页数 / PNG 魔数 / 往返一致） |

> 中文 PDF 水印需 TTF：服务端自动探测系统字体，也可在界面上传自己的 .ttf。
>
> OCR 面板慢一个量级：推理纯 Java 跑，实测一张 747×600 的书页（28 行）约 40 秒，
> 故扫描件转 Word 限 `maxPages`（默认 3、上限 20）。语种与版面参数（旋转 / 一页两页）
> 选错**不会报错**，只会满屏怪字——识别器在固定字符集上永远给出某个类别，没有"认不出"这一档。

## 示例清单

| 测试 | 演示 |
|---|---|
| `CellsDemoTest` | CSV→PDF、CSV 解析(RFC4180)、公式求值 `=A1+A2` |
| `BarcodeDemoTest` | Code128 / QR → PNG |
| `PdfDemoTest` | 多 PDF 合并、AES-256 加密 |
| `LicenseDemoTest` | 未授权评估态、无效/空 License 抛异常(不 System.exit) |
| `EnforcementDemoTest` | 授权门控：enforcement 关=不降级 / 开+未授权=降级(水印/限页) / 开+自备 officia.lic=不降级(无 lic 自动跳过) |

## 意义

- ✅ **发布可消费**：`import officia-all` 即得全部能力，零传递第三方依赖(officia 运行时零依赖)。
- ✅ **授权门控可见**：`EnforcementDemoTest` 展示"未买降级、买了不降级"，且库不阻断宿主（不 System.exit）。
- ✅ **回归护栏**：升级 officia 版本后重跑 demo，能第一时间发现 API 破坏性变更。

## 如何加载自己的 License（无需写代码）

真实客户从 flm 后台"授权管理 → 签发"下载自己的 `officia.lic`，**放到约定位置即可，零代码**——officia 首次使用能力时自动查找加载：

- classpath `/officia.lic`（丢进 `src/main/resources/`）
- 工作目录 `./officia.lic`
- 环境变量 `OFFICIA_LICENSE` 或 `-Dofficia.license=<路径|令牌>`

也可显式加载（优先级最高）：`OfficiaLicense.setLicense(new File("officia.lic"))`。
