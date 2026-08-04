---
name: officia-capability-map
description: |
  Officia 能力全景与选型入口：按"我有什么输入、要什么输出"直接定位到门面方法，
  并明确说清哪些场景 officia **不支持**（避免走上死路）。不确定用哪个能力时先读这个。

  触发场景：
  - 不知道该用哪个门面 / 哪个方法
  - 问"officia 能不能做 X"、"支持不支持 Y 格式"
  - 要做能力选型、评估 officia 是否满足需求
  - 同一件事有多个方法（如 PDF 单次 vs 链式、Words 直出字节 vs 直写流），不知道选哪个

  触发词：能力、支持、能不能、选型、用哪个、怎么选、全景、清单、方法选择、能做什么、限制
disable-model-invocation: false
allowed-tools: ["Read", "Grep", "Glob", "Bash"]
---

# Officia 能力全景与选型

## 概述

Officia 对外只暴露 **8 个门面类**（`OfficiaWords` / `OfficiaCells` / `OfficiaSlides` / `OfficiaPdf` / `OfficiaBarCode` / `OfficiaImaging` / `OfficiaEmail` / `OfficiaLicense`），全部是 `public final class` + **全静态方法** + **`byte[]` 进 `byte[]` 出**。底层 13 个模块（ooxml/cfb/engine/render-pdf 等）不需要你直接碰。

本技能是**入口**：先在这里定位到"该调哪个方法"，再去对应的专项技能拿完整用法。

> 🔴 **回答"支不支持"之前，务必核对 `../officia/` 源码**。本文列的事实取自真实门面签名与 `../officia/status.json` 的实测记录，但版本会演进——有疑问时用
> `grep -n "public static" ../officia/officia-<模块>/src/main/java/plus/ruoyi/officia/<模块>/Officia*.java` 直接看。

## 按输入格式选（最常用的入口）

| 我手上有 | 我想要 | 调用 | 专项技能 |
|---|---|---|---|
| `.docx` | PDF | `OfficiaWords.toPdf(byte[])` | `officia-words` |
| `.doc`（CFB 二进制） | PDF | `OfficiaWords.toPdf(byte[])`（同一入口，**自动识别**格式） | `officia-words` |
| `.docx` 模板 + 数据 | 填好的 docx / PDF | `OfficiaWords.fillTemplate(...)` / `fillTemplateToPdf(...)` | `officia-template` |
| `.xlsx` | PDF | `OfficiaCells.toPdf(byte[])` | `officia-cells` |
| `.xlsx` | CSV 文本 | `OfficiaCells.toCsv(byte[])` | `officia-cells` |
| CSV 文本 | PDF | `OfficiaCells.csvToPdf(String)` | `officia-cells` |
| CSV 文本 | 行列网格 | `OfficiaCells.parseCsv(String)` | `officia-cells` |
| `.pptx` | PDF | `OfficiaSlides.toPdf(byte[])` | `officia-slides` |
| 多个 PDF | 一个 PDF | `OfficiaPdf.merge(List<byte[]>)` | `officia-pdf` |
| 一个 PDF | 多个单页 PDF | `OfficiaPdf.split(byte[])` | `officia-pdf` |
| PDF | 纯文本 | `OfficiaPdf.extractText(byte[])` | `officia-pdf` |
| PDF | 内嵌图片 | `OfficiaPdf.extractImages(byte[])` | `officia-pdf` |
| 一段文本/网址 | 条码/二维码 PNG | `OfficiaBarCode.qrPng(String)` 等 | `officia-barcode` |
| 图片字节 | 处理后图片 / PDF | `OfficiaImaging.*` | `officia-imaging` |
| `.eml` | 结构化对象 / PDF | `OfficiaEmail.parseEml(...)` / `toPdf(...)` | `officia-email` |

## 按目标反查（"我要出 PDF"）

所有能出 PDF 的入口，一张表看全：

```java
OfficiaWords.toPdf(docx)                    // Word（docx / doc 自动识别）
OfficiaWords.fillTemplateToPdf(tpl, data)   // 模板填充后直出 PDF（一步）
OfficiaCells.toPdf(xlsx)                    // Excel
OfficiaCells.csvToPdf(csvText)              // CSV
OfficiaSlides.toPdf(pptx)                   // PPT（版式保真，每张幻灯片一页）
OfficiaImaging.toPdf(imageBytes)            // 单张图片 → PDF
OfficiaImaging.toPdf(List<byte[]> images)   // 多张图片 → 一份 PDF
OfficiaEmail.toPdf(eml)                     // 邮件归档 → PDF
```

## 同类方法怎么选（歧义点集中回答）

| 分岔口 | 选 A | 选 B | 判据 |
|---|---|---|---|
| Words 输出形态 | `toPdf(byte[])` → `byte[]` | `toPdf(byte[], OutputStream)` → 页数 | 大文档 / 直写 HTTP 响应用 B（省一份整份 PDF 的内存），普通用 A |
| Words 要不要页数耗时 | `toPdf(...)` | `convert(...)` → `ConvertResult` | 需要 `getPageCount()` / `getCostMillis()` 用 B |
| PDF 多步操作 | 逐个静态方法 | `OfficiaPdf.edit(pdf).xxx().yyy().toBytes()` | 两步以上用链式 `PdfEditor`，少一轮解析/序列化 |
| Cells 公式求值 | `evaluateFormula(Map, ref)` | `evaluateXlsxCell(xlsx, ref)` | 没有 xlsx、只想算一张散列表用 A；对真实 xlsx 实算用 B |
| 模板批量 | `fillTemplateEach` → N 份 | `fillTemplateMerged` → 1 份长文档 | 每人一份文件用 A；打印/归档成一份用 B |
| 引依赖粒度 | `officia-all` | 单模块（`officia-pdf` 等） | 多数场景用 `officia-all` 最省心；只用一个能力可单引 |

## 🔴 明确**不支持** / 有边界的场景

回答用户"能不能"时，这些必须如实说，不要含糊过去：

| 场景 | 状态 | 说明 |
|---|---|---|
| **加密的 `.doc`**（XOR / RC4 / CryptoAPI 加密） | ❌ 明确不支持 | 属显式拒绝范围。加密 DOC 请先用 Word 另存为解密版本 |
| DOC 内的 **WMF / EMF / PICT / CMYK JPEG** 图片负载 | ❌ 明确不支持 | 显式拒绝的图片格式 |
| DOC 内 **宏 / OLE / 数字签名负载**的内部解析 | ❌ 明确不支持 | 只跳过，不解析其内容 |
| **竖排文本**与 **RTL 文本框** | ⚠️ 已知缺口 | 排版层未覆盖 |
| DOC **内嵌字体** | ⚠️ 已知缺口 | 规范链路已核对，但字体数据为 W3C EOT 格式、该规范未归档，故不实现 |
| `.doc` 二进制输入整体 | 🚧 主链路可用、绿测覆盖，**未宣布生产就绪** | 见 `../officia/status.json` 的 `doc_binary_input`。生产上大批量 `.doc` 前先用测试台实测一批真实样本 |
| **大文档吞吐 / 高并发** | ⚠️ 未系统压测 | `CfbLimits` 默认值是**内存安全边界**，不是产品容量承诺。见 `officia-performance` |
| 图像格式 | PNG / JPEG / BMP / GIF | 基于 `javax.imageio`；WebP / AVIF 等不在其中 |
| PDF 加密强度 | RC4-40/128、AES-256 | 见 `officia-pdf` |

> 核实这些状态的命令：
> ```bash
> # 看当前实测事实（能力状态、拒绝项、已知缺口）
> cat ../officia/status.json
> ```

## 一句话决策树

```
要处理文档
├─ 输入是 Office 文件（docx/doc/xlsx/pptx）
│   ├─ 只是要转成 PDF        → OfficiaWords/Cells/Slides.toPdf     → officia-words / cells / slides
│   └─ 要按数据生成文档       → OfficiaWords.fillTemplate*          → officia-template
├─ 输入/输出是 PDF          → OfficiaPdf.*（多步用 edit() 链式）    → officia-pdf
├─ 输入是图片               → OfficiaImaging.*                     → officia-imaging
├─ 要生成条码/二维码         → OfficiaBarCode.*                     → officia-barcode
├─ 输入是邮件 .eml           → OfficiaEmail.*                       → officia-email
└─ 输出有水印/被限页         → 不是 bug，是评估态                    → officia-license
```

## 先跑测试台，比读文档快

不确定某个能力的实际效果时，**直接上传自己的文件实测**比看文档准：

```bash
mvn -o package
java -jar target/officia-demo-1.0.0.jar
```

浏览器面板与能力的对应关系见 `officia-testbench`。

## 相关技能

| 我接下来要 | 用 |
|---|---|
| 把依赖引进我的项目 | `officia-setup` |
| 去掉输出上的水印 | `officia-license` |
| 中文显示成方块 | `officia-chinese-font` |
| 报错了 | `officia-troubleshooting` |
| 集成进 Spring Boot | `officia-spring-integration` |
