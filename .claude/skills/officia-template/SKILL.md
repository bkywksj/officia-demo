---
name: officia-template
description: |
  用 docx 模板 + 数据批量生成文档（对标 Aspose.Words 邮件合并 / poi-tl）：完整占位符语法
  （文本 / 嵌套路径 / 格式器 / 表达式 / 图片 / 富文本 / 条件块 / 列表循环 / 表格行循环 / 内容控件），
  单份与批量（每条一份 / 合并一份）、Map 与 JSON 两种数据源、一步直出 PDF。

  触发场景：
  - 按数据批量生成合同 / 通知书 / 报表 / 证书 / 工资条
  - 模板占位符怎么写、支持哪些语法、金额大写怎么出
  - 表格要按列表循环出多行
  - 某段落要按条件显示 / 隐藏
  - 一个模板出 N 份，或合并成一份长文档
  - 填充结果要直接是 PDF

  触发词：模板、填充、占位符、邮件合并、mail merge、批量生成、合同、报表、通知书、工资条、循环、条件、格式器、大写金额、{{}}、fillTemplate
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# docx 模板填充 / 邮件合并（`OfficiaWords.fillTemplate*`）

## 概述

给一份含占位符的 `.docx` 模板 + 一份数据，产出填好的 docx 或直接产出 PDF。

**关键特性**：直接在 OOXML DOM 层（`w:p` / `w:r` / `w:t` / `w:tbl`）做替换手术，**保留原模板全部样式**——不走排版 IR（IR 是为排版归一化的有损模型，回写会丢样式）。覆盖**主文档与页眉页脚**。

> ⚠️ **注意**：`../officia/officia-words/.../template/package-info.java` 写着"骨架预留 / 计划实现"，**这是过时注释**。该目录下 `WordTemplateFiller`、`WmlBlockLoopRenderer`、`WmlConditionRenderer`、`WmlTableLoopRenderer`、`WmlSdtFiller`、`WmlBatchMerge`、`TemplateExpression`、`TemplateFormats` 等均已实现，门面有 16 个 `fillTemplate*` 重载。以**门面签名 + 实现类**为准。

## 最短用法

```java
import plus.ruoyi.officia.words.OfficiaWords;
import java.util.Map;

Map<String, Object> data = Map.of(
    "name", "张三",
    "amount", 12345.6
);
byte[] docx = OfficiaWords.fillTemplate(templateBytes, data);       // 出 docx
byte[] pdf  = OfficiaWords.fillTemplateToPdf(templateBytes, data);  // 一步出 PDF
```

模板 `.docx` 里写：`尊敬的 {{name}}，应付金额 {{amount|money:￥}}（{{amount|rmb}}）。`

## 🔴 占位符语法全表（核实自各 Wml*/Template* 实现类）

### 1. 文本与嵌套路径

```
{{name}}            普通字段
{{user.name}}       对象属性 / Map 嵌套
{{a.b.c}}           任意深度路径
```

数据值可为标量、对象、`Map`、数组、`List`、`TemplateImage`。

### 2. 格式器 `{{key|formatter}}` / `{{key|formatter:arg}}`

内置格式器（全部仅用 JDK 实现）：

| 格式器 | 写法 | 效果 |
|---|---|---|
| `money` | `{{amount\|money}}` / `{{amount\|money:￥}}` | 千分位 + 2 位小数，可选货币符号前缀 |
| `number` | `{{v\|number:#,##0.0}}` | `DecimalFormat` 模式 |
| `percent` | `{{r\|percent}}` / `{{r\|percent:1}}` | 百分比（value×100），可选小数位 |
| `date` | `{{d\|date:yyyy-MM-dd}}` | 支持 Date / Calendar / java.time / epoch 毫秒 |
| `upper` / `lower` | `{{code\|upper}}` | 大小写 |
| `rmb` / `capital` | `{{amount\|rmb}}` | 人民币大写：1234.56 → 壹仟贰佰叁拾肆元伍角陆分 |
| `chinese` / `cnnum` | `{{n\|chinese}}` | 中文小写数字：1234 → 一千二百三十四 |
| `cndate` | `{{d\|cndate}}` | 中文日期：2026-07-13 → 二〇二六年七月十三日 |
| `mask` | `{{phone\|mask}}` / `{{id\|mask:6,4}}` | 脱敏，默认手机号式（前 3 后 4，中间 `*`） |
| `default` | `{{remark\|default:无}}` | 值为空时取默认 |

### 3. 算术表达式 `{{=表达式}}`

```
{{=price*qty}}
{{=subtotal*(1+rate)}}
{{=(a+b)/2}}
```

支持 `+ - * / %`、圆括号、一元正负号、数字字面量、**字段路径**（`a.b.c`，从数据取值转数值）。
用 `BigDecimal` 精确计算，除法保留 10 位小数（`HALF_UP`）再去尾零。

> **容错约定**：语法错误 / 变量缺失 / 非数值 / 除零 → **原样保留占位符**（便于你一眼看出哪里没算对），不抛异常。

### 4. 图片 `{{@key}}`

```java
import plus.ruoyi.officia.words.template.TemplateImage;

data.put("logo", TemplateImage.of(pngBytes, 120, 60));   // 指定像素尺寸
data.put("sign", TemplateImage.of(inputStream));          // 用原始尺寸
data.put("qr",   TemplateImage.ofUrl("https://...").center());  // 从 URL 取 + 居中
```

模板里写 `{{@logo}}`。构造方式：`of(byte[])`、`of(byte[], w, h)`、`of(InputStream)`、`of(InputStream, w, h)`、`ofUrl(String)`、`ofUrl(String, w, h)`，链式 `.center()` 居中。

### 5. 富文本 HTML `{{~key}}`

值是一段 HTML 时用 `{{~content}}`，会解析成带样式的 Word 运行。支持的**子集**（`MiniHtml`，非浏览器级解析）：

- 行内样式：`<b>/<strong>`、`<i>/<em>`、`<u>`、`<span style="color:…;font-weight:bold;font-style:italic;text-decoration:underline">`、`<font color="…">`
- 块级换行：`<br>`、`</p>`、`</div>`
- 列表：`<ul>/<ol>/<li>`（• 或序号 + 逐层缩进）
- 实体：`&amp; &lt; &gt; &quot; &apos; &nbsp;` 与数字实体 `&#NN;` / `&#xNN;`

样式栈式继承，空白折叠为单空格，未知标签忽略但保留文本，容错解析。

### 6. 条件块 `{{?key}}` / `{{^key}}`

标记**各自单独成段**：

```
{{?vip}}
    尊贵的 VIP 客户，您享有专属折扣。
{{/vip}}

{{^vip}}
    升级 VIP 可享专属折扣。
{{/vip}}
```

- `{{?key}}`：key 为"真"时保留区块，否则删除
- `{{^key}}`：取反，key 为"假"时保留
- 支持**嵌套**；作用于正文与表格单元格

### 7. 段落级列表循环 `{{#items}}`（支持嵌套）

```
{{#items}}
· {{.name}}：{{.qty|number}} 件
{{/items}}
```

- `{{.}}` = 当前项本身，`{{.field}}` = 项的字段（可带 `|格式器`）
- **嵌套**用点前缀表示"当前项的子列表"：

```
{{#orders}}
订单 {{.no}}
{{#.lines}}
　{{..no}} 的 {{.name}} × {{.qty}}
{{/.lines}}
{{/orders}}
```

- `{{.x}}` = 当前项，`{{..x}}` = 父项，每多一个点向外一层（`{{...x}}` = 祖父）
- 非点前缀的 `{{key}}` 仍按**根数据**取值
- 空列表 / 非列表 → 整段删除

### 8. 表格行循环 `{{items}}` + `[field]`

在表格里：**标记行的首个单元格**写 `{{items}}`，同一行其它单元格写 `[field]`，渲染时按列表克隆行。

| 商品 | 数量 | 金额 |
|---|---|---|
| `{{items}}` `[name]` | `[qty]` | `[amount]` |

### 9. 内容控件（`w:sdt`）

Word 的**内容控件**按 `w:tag`（优先）或 `w:alias` 作绑定键从数据取值（支持嵌套路径 `a.b.c`），替换控件内容文本并保留控件与首个运行样式。适用行内 / 块级控件；跳过嵌套控件避免误改。

> 适合用 Word「开发工具 → 内容控件」做的表单式模板，无需在文档里写 `{{}}`。

## 全部门面方法（16 个重载）

### 单份

```java
byte[] fillTemplate(byte[] templateDocx, Map<String,Object> data)
byte[] fillTemplate(File templateFile,   Map<String,Object> data)
byte[] fillTemplate(InputStream in,      Map<String,Object> data)

byte[] fillTemplateToPdf(byte[] templateDocx, Map<String,Object> data)
byte[] fillTemplateToPdf(File templateFile,   Map<String,Object> data)
byte[] fillTemplateToPdf(InputStream in,      Map<String,Object> data)
```

### 批量（邮件合并）

```java
List<byte[]> fillTemplateEach(byte[] tpl, List<Map<String,Object>> dataList)        // 每条一份 docx
List<byte[]> fillTemplateEachToPdf(byte[] tpl, List<Map<String,Object>> dataList)   // 每条一份 PDF
byte[]       fillTemplateMerged(byte[] tpl, List<Map<String,Object>> dataList)      // 合并成一份长 docx（份间分页）
byte[]       fillTemplateMergedToPdf(byte[] tpl, List<Map<String,Object>> dataList) // 合并成一份长 PDF
```

> `Merged` 会做**图片关系重映射**，多份的图片不会串。

### JSON 数据源（免手工构造 Map）

```java
byte[]       fillTemplate(byte[] tpl, String json)              // 顶层须为对象
byte[]       fillTemplateToPdf(byte[] tpl, String json)
List<byte[]> fillTemplateEach(byte[] tpl, String jsonArray)     // 顶层须为数组，每元素一个对象
List<byte[]> fillTemplateEachToPdf(byte[] tpl, String jsonArray)
byte[]       fillTemplateMerged(byte[] tpl, String jsonArray)
byte[]       fillTemplateMergedToPdf(byte[] tpl, String jsonArray)
```

JSON 经内置 `MiniJson` 解析为对象树（对象→Map、数组→List、数字→Long/Double），直接对接占位符 / 嵌套路径 / 循环 / 格式器 / 表达式。

## 选型：四种批量输出怎么选

| 场景 | 用 |
|---|---|
| 每个客户一个文件，要分别发送 | `fillTemplateEach` / `fillTemplateEachToPdf` |
| 打印 / 归档成一份长文档 | `fillTemplateMerged` / `fillTemplateMergedToPdf` |
| 还要二次编辑 | `...Each` / `...Merged`（出 docx） |
| 直接给终端用户看 / 存档 | `...ToPdf`（出 PDF，含中文 CID 字体） |

## 完整示例：批量生成合同 PDF

```java
import plus.ruoyi.officia.words.OfficiaWords;
import java.nio.file.*;
import java.util.*;

public class BatchContract {
    public static void main(String[] args) throws Exception {
        byte[] tpl = Files.readAllBytes(Path.of("contract-template.docx"));

        List<Map<String, Object>> rows = List.of(
            Map.of("party", "张三", "amount", 12345.6, "date", new Date(),
                   "items", List.of(Map.of("name", "服务费", "qty", 1, "amount", 12345.6))),
            Map.of("party", "李四", "amount", 8000.0,  "date", new Date(),
                   "items", List.of(Map.of("name", "咨询费", "qty", 2, "amount", 4000.0)))
        );

        // 每人一份 PDF
        List<byte[]> pdfs = OfficiaWords.fillTemplateEachToPdf(tpl, rows);
        for (int i = 0; i < pdfs.size(); i++) {
            Files.write(Path.of("contract-" + i + ".pdf"), pdfs.get(i));
        }

        // 或：合并成一份长 PDF 供打印
        Files.write(Path.of("contracts-all.pdf"),
                    OfficiaWords.fillTemplateMergedToPdf(tpl, rows));
    }
}
```

模板中对应写法：

```
甲方：{{party}}          签订日期：{{date|cndate}}
合计：{{amount|money:￥}}（{{amount|rmb}}）

| {{items}} [name] | [qty] | [amount|money] |
```

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| 占位符原样出现在结果里 | ①字段名不匹配 ②表达式求值失败（约定就是原样保留） | 核对 key 拼写；表达式检查变量是否存在且为数值 |
| 占位符被 Word 拆散没替换成功 | Word 会把一个 `{{name}}` 拆进多个 `w:r`（拼写检查/格式痕迹） | 在 Word 里把占位符整体重打一遍（选中→删除→连续输入），或用内容控件方式 |
| 表格没循环出多行 | 首格没写 `{{items}}`，或字段用了 `{{}}` 而不是 `[]` | 标记行首格 `{{items}}`，其它格 `[field]` |
| 条件块没生效 | `{{?key}}` / `{{/key}}` 没有各自单独成段 | 让标记独占一个段落 |
| 嵌套循环内层取不到值 | 少了点前缀 | 内层用 `{{#.lines}}`，字段 `{{.name}}`，父项 `{{..no}}` |
| 图片没出来 | 值不是 `TemplateImage` | `data.put("logo", TemplateImage.of(bytes))` |
| 出的 PDF 有水印 | 未授权 + 门控已开 | 见 `officia-license` |
| 中文方块 | 字体找不到 | 见 `officia-chinese-font` |

## 在测试台里实测

面板 **「模板填充 · 邮件合并」**：上传模板 + 填 JSON，四种模式（单条 / 合并一份 / 每条一份 / 只填 docx）都能直接跑。
对应端点 `/api/words/template`（`mode=each` 等）。

## 相关技能

| 接下来 | 用 |
|---|---|
| 纯转换不填充 | `officia-words` |
| 生成的 PDF 还要加水印 / 合并 | `officia-pdf` |
| 做成 Web 接口 | `officia-spring-integration` |
| 大批量性能 | `officia-performance` |
