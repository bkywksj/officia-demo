---
name: convert
description: |
  /convert - 一句话做文档转换（自动选门面方法 + 出可运行代码 + 提示该场景的坑）

  触发场景：
  - 用户说"把 X 转成 Y"，需要直接拿到能跑的代码
  - 不确定该调哪个门面方法
  - 要按场景选普通/流式/富结果重载

  触发词：/convert、转换、转成、转PDF、怎么转、给我代码、转换代码
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---
# /convert - 一句话做文档转换

用户描述"把 X 转成 Y"，你负责：**选对门面方法 → 给出可直接运行的代码 → 提示该场景的坑**。

## 执行步骤

1. **识别输入与输出格式**。拿不准就先看 `officia-capability-map` 的输入/输出对照表。

2. **选定门面方法**（照下表，不要凭印象编 API）：

   | 输入 → 输出 | 调用 |
   |---|---|
   | docx/doc → PDF | `OfficiaWords.toPdf(byte[])` |
   | docx 模板 + 数据 → PDF | `OfficiaWords.fillTemplateToPdf(tpl, data)` |
   | xlsx → PDF / CSV | `OfficiaCells.toPdf(...)` / `toCsv(...)` |
   | CSV → PDF | `OfficiaCells.csvToPdf(String)` |
   | pptx → PDF | `OfficiaSlides.toPdf(...)` / `toPdfLayoutAware(...)` |
   | 图片 → PDF | `OfficiaImaging.toPdf(...)` |
   | eml → PDF | `OfficiaEmail.toPdf(...)` |
   | PDF ↔ PDF | `OfficiaPdf.*` / `OfficiaPdf.edit(...)` 链式 |

3. **激活对应技能**读取完整用法（`officia-words` / `officia-cells` / `officia-slides` / `officia-pdf` / `officia-imaging` / `officia-email` / `officia-template`）。

4. **产出可运行代码**，并按场景决定：
   - 输出很大 / 要写文件或 HTTP 响应 → 用**流式**重载
   - 需要页数或耗时 → 用 `convert(...)` 拿 `ConvertResult`
   - **内容含中文** → 必须提示配 `ConvertOptions.setFontDirectory(...)`；若是 PDF 水印/页码则要传 `fontTtf` 字节

5. **主动提示三件事**（用户最常被绊住）：
   - 输出可能带**评估水印**（未授权 + 门控开）→ `officia-license`
   - 中文字体 → `officia-chinese-font`
   - 该格式的**已知不支持项**（如加密 `.doc`）→ `officia-capability-map`

## 输出格式

```
【方案】<用哪个门面方法，为什么>
【代码】<完整可运行片段，含 import>
【注意】<该场景的 1-3 个坑>
【实测】<对应的测试台面板与端点，让用户可自行验证>
```

## 禁止

- ⛔ 凭印象写 API —— 必须核对 `../officia/` 源码的真实签名
- ⛔ 按 Aspose / POI / iText 的用法类推
- ⛔ 断言"支持某特性"而不核实
