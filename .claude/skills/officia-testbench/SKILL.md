---
name: officia-testbench
description: |
  使用 officia-demo 的可视化测试台：启动方式与参数、10 个面板分别能实测什么、
  对应的 /api 端点、一键批量回归跑了哪些用例、上传自己的字体与 License 做门控对比。
  这是"验证 officia 能不能做某件事"最快的路径。

  触发场景：
  - 想实测某个能力的真实效果，而不是读文档
  - 要跑 demo / 启动测试台 / 打开浏览器面板
  - 端口被占、启动失败、控制台中文乱码
  - 要做一次全能力自检 / 回归
  - 想看授权门控开关的并排对比

  触发词：测试台、demo、跑起来、启动、可视化、浏览器、面板、批量回归、实测、演示、端到端、试一下、端口
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# 可视化测试台

## 概述

`officia-demo` 内置一个**可视化测试台**：浏览器里直接上传 doc/docx/xlsx/pptx/pdf/图片/eml 实测各项能力，每步显示页数/耗时/体积、内嵌预览、可下载。

**服务端用 JDK 内置 `com.sun.net.httpserver.HttpServer`**——demo 自身同样零第三方依赖。
上传文件**只存内存、重启即清，不落盘不入库**。

## 启动

```bash
mvn -o package                                          # 产出自包含 jar（shade）
java -jar target/officia-demo-1.0.0.jar                 # 启动，自动打开浏览器
java -jar target/officia-demo-1.0.0.jar 9090            # 指定端口
java -jar target/officia-demo-1.0.0.jar 9090 --no-open  # 不自动开浏览器
java -Xmx8g -jar target/officia-demo-1.0.0.jar          # 大文件（设计型 PPTX 常上百 MB）建议调大堆
```

启动细节（核实自 `DemoServer.java`）：

- 绑定 **`127.0.0.1`**——只有本机可访问（不对外网暴露）
- 端口被占时会**自动往后找可用端口**，以控制台打印的实际地址为准
- 控制台会打印访问链接，并显示**是否探测到中文字体**（最快的字体环境自检）
- 控制台还会打印**最大堆与单次上传上限**：上限 = 最大堆的 1/8，夹在 [64 MB, 1 GB]，用 `-Xmx` 调大堆即放宽
- Windows 控制台是 GBK 而 JVM 默认 UTF-8，`DemoServer` 已做编码处理避免中文乱码

> 前置：本地仓要有 officia。没有就先在 `../officia` 跑 `mvn install -DskipTests`，见 `officia-setup`。

## 10 个面板能实测什么

| 面板 | 可实测的能力 | 对应技能 |
|---|---|---|
| **Words · DOC/DOCX** | 上传 `.doc`（CFB）/`.docx`（OOXML）→ PDF，**自动识别格式**；字节 / 流式两种输出 | `officia-words` |
| **模板填充 · 邮件合并** | 模板 + JSON → 单条 / 合并一份 / 每条一份 / 只填 docx | `officia-template` |
| **Cells · XLSX/CSV** | XLSX→PDF、XLSX→CSV、CSV→PDF、CSV 解析、单元格公式求值、整表重算 | `officia-cells` |
| **Slides · PPTX** | PPTX→PDF（版式保真），看页数 / 耗时 / 体积并内嵌预览 | `officia-slides` |
| **PDF 工具箱** | 合并·拆分·抽页·删页·旋转·水印·页码·抽文字·抽图片·RC4-128·AES-256·信息 | `officia-pdf` |
| **Imaging · 图像** | 滤镜/变换全项 + 格式转换 + 图片→PDF，**处理前后并排对比** | `officia-imaging` |
| **BarCode · 条码** | Code128/39/93、EAN-13/8、UPC-A、ITF-14、QR（4 档纠错）+ **全码制一键预览** | `officia-barcode` |
| **Email · EML** | EML 解析（主题/收发件/附件/正文）、邮件归档 → PDF | `officia-email` |
| **授权门控与对比** | 加载 `.lic`、模块门控矩阵、**同一份数据在门控开/关下的页数与水印并排对比** | `officia-license` |
| **批量回归** | 用 officia 自造输入跑全能力并断言 | 见下 |

## API 端点速查（可直接 curl）

```
GET  /api/health                     健康检查
POST /api/upload                     上传文件（存内存，返回句柄）
GET  /api/samples                    生成内置样本（CSV/PDF/PNG/EML 现造；docx/xlsx/pptx 需自行上传）
POST /api/batch/run                  一键批量回归

GET  /api/license/status             授权状态
POST /api/license/set                加载 .lic
POST /api/license/reset              复位授权
POST /api/license/enforce            开关门控（用于并排对比）

POST /api/words/topdf                Word → PDF
POST /api/words/template             模板填充（mode=single|merged|each|docx）

POST /api/cells/topdf   /tocsv   /csvtopdf   /parsecsv   /formula   /recalc
POST /api/slides/topdf
POST /api/pdf/info  /merge  /split  /pages  /rotate  /watermark  /pagenumbers  /text  /images  /encrypt
POST /api/imaging/op    /api/imaging/topdf
POST /api/barcode
POST /api/email/parse   /api/email/topdf
```

```bash
curl http://127.0.0.1:8080/api/health
curl -X POST http://127.0.0.1:8080/api/batch/run     # 全能力回归，返回 JSON 报告
```

## 批量回归跑了哪些用例

`Batch.run()` 用 officia **自造输入**（不需要你准备素材）跑全能力并断言（核实自 `Batch.java`）：

| 模块 | 用例 | 断言 |
|---|---|---|
| Cells | CSV(150 行) → PDF | `%PDF-` 头 |
| Cells | CSV 解析 `parseCsv` | 行列还原一致 |
| Pdf | 两份 PDF 合并 `merge` | `%PDF-` 头 + 页数 |
| Pdf | 文本抽取 `extractText` | 内容匹配 |
| Pdf | AES-256 加密 `encryptAes256` | `%PDF-` 头 |
| BarCode | Code128 / QR → PNG | PNG 魔数 |
| Imaging | 灰度滤镜 + 图片→PDF | PNG 魔数 + `%PDF-` 头 |
| Email | EML 生成 → 解析**往返一致** | 字段回读一致 |
| Email | EML → PDF 归档 | `%PDF-` 头 |
| License | 门控：评估降级 vs 完整 | 页数/水印差异 |

返回 JSON 含 `total` / `pass` / `fail` / `totalMs` 与逐行结果。

> **用途**：换环境、升 officia 版本、排查"是不是我环境有问题"，先点这个——全绿说明环境与依赖没问题。

## 三个高价值用法

### 1. 判别"是我的代码还是 officia"

同一份文件：

- 测试台**能转成功** → 你的调用代码有问题（参数 / 流已关闭 / 字体没配）
- 测试台**也失败** → 是文档本身或 officia 的边界，带样本反馈

### 2. 看清授权门控的真实效果

「授权门控与对比」面板可以**同一份数据、门控开/关并排跑**，直观看到水印与限页差异。
放入自己的 `officia.lic` 后再跑，就能确认"买了之后是什么样"。

> demo **不携带任何 License 令牌 / `.lic`**（面向公开展示）。授权效果由你放入自己的 `officia.lic` 时生效。

### 3. 上传自己的字体验证中文

中文 PDF 水印需要 TTF：服务端会**自动探测系统字体**，你也可以**在界面上传自己的 `.ttf`**（优先级高于探测）。
用它验证"生产环境该配哪个字体"。见 `officia-chinese-font`。

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| `mvn -o package` 失败：找不到 officia | 本地仓没有 | 去 `../officia` 跑 `mvn install -DskipTests` |
| 启动后浏览器没自动打开 | 无桌面环境 / 用了 `--no-open` | 手动访问控制台打印的地址 |
| 端口被占 | — | 会自动换端口，看控制台实际地址；或显式传端口 |
| 别的机器访问不了 | 绑定的是 `127.0.0.1` | 设计如此（本机工具，不对外暴露） |
| 控制台中文乱码 | Windows GBK vs JVM UTF-8 | `DemoServer` 已处理；若仍乱码，终端设 UTF-8 代码页 |
| 上传时前端弹「超过单次上传上限」 | 单次上传上限 = 最大堆的 1/8，夹在 [64 MB, 1 GB]（启动横幅有打印） | `java -Xmx8g -jar …` 调大堆，上限随之放宽 |
| 上传时前端弹「连接中断」 | 服务已停 / 转换途中 JVM 退出 | 看启动测试台那个控制台窗口的输出 |
| 重启后上传的文件没了 | 设计如此——只存内存、不落盘 | 需要留存请自行保存下载结果 |
| 转换结果有水印 | 未授权 + 门控开 | `officia-license` |

## 命令行方式（不开浏览器）

```bash
mvn -o test                                                          # 跑示例测试
mvn -o exec:java -Dexec.mainClass=plus.ruoyi.officia.demo.Demo       # 或直接 run Demo.main
```

## 相关技能

| 接下来 | 用 |
|---|---|
| 写自己的验证测试 | `officia-demo-test` |
| 某个能力的完整 API | 对应能力技能 |
| 实测失败要排查 | `officia-troubleshooting` |
| 升级后回归 | `officia-upgrade` |
