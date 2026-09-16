---
name: officia-cells
description: |
  用 OfficiaCells 处理表格：Excel（**xlsx 与 xls 都收**）→ PDF / CSV、CSV → PDF、
  CSV 解析（RFC 4180），以及公式求值——散列表直接算、对真实文件实算单元格、
  整表重算（不信缓存值）。

  触发场景：
  - Excel 转 PDF、xlsx 转 PDF、**xls 转 PDF（Excel 97-2003 老格式）**
  - 导出 CSV / 解析 CSV / CSV 转 PDF
  - 算 Excel 公式、单元格求值、公式重算
  - 拿到的文件缓存值过期/为空，要算出正确结果
  - 想知道支持哪些 Excel 函数

  触发词：Excel、xlsx、**xls**、**97-2003**、表格、CSV、转PDF、导出CSV、解析CSV、
  公式、求值、计算、重算、SUM、VLOOKUP、单元格
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# Excel / CSV / 公式（`OfficiaCells`）

## 概述

`OfficiaCells` 覆盖三块能力：**格式转换**（Excel ↔ PDF / CSV）、**CSV 解析**、**公式求值**。

## 🔴 输入格式：xlsx 与 xls 都收，按内容识别

所有吃 Excel 字节的方法都**按文件内容**判断格式，**不看扩展名**：

| 格式 | 支持 | 说明 |
|---|---|---|
| `.xlsx`（OOXML） | ✅ | ECMA-376 §18 |
| `.xls`（Excel 97-2003 二进制） | ✅ | MS-XLS 12.2，**仅未加密的 BIFF8** |
| 加密的 `.xls` | ❌ | 明确拒绝并点名加密方式（XOR 混淆 / RC4） |
| BIFF5 / BIFF7（更老的 xls） | ❌ | 明确拒绝，提示另存为较新格式 |

调用方**不必先判断格式**——同一份内容存成两种格式，`toCsv` 输出逐字相等、
`recalculate` 结果也相等（连值类型都一致）。

## 一、转换

```java
import plus.ruoyi.officia.cells.OfficiaCells;

byte[] pdf = OfficiaCells.toPdf(excelBytes);                   // Excel → PDF（xlsx 或 xls）
byte[] pdf = OfficiaCells.toPdf(excelBytes, options);          // 自定义选项
byte[] pdf = OfficiaCells.toPdf(inputStream);                  // 流（不主动关闭）
byte[] pdf = OfficiaCells.toPdf(new File("in.xls"));           // 文件（扩展名不参与判断）

ConvertResult r = OfficiaCells.convert(excelBytes, options);   // 富结果：页数 + 耗时
```

`ConvertOptions` 用法与 Words 一致（纸张 / 字体目录 / 嵌入字体 / 超时），见 `officia-words`。

## 二、CSV

```java
String csv = OfficiaCells.toCsv(excelBytes);         // 导出【首个工作表】为 CSV（xlsx 或 xls）
List<List<String>> grid = OfficiaCells.parseCsv(csv); // 解析 CSV → 逐行字段列表
byte[] pdf = OfficiaCells.csvToPdf(csvText);          // CSV → PDF（表格化渲染，含中文 CID）
byte[] pdf = OfficiaCells.csvToPdf(csvText, options); // 自定义选项（如字体目录）
byte[] pdf = OfficiaCells.csvToPdf(csvBytesUtf8);     // CSV 字节（UTF-8）→ PDF
```

**`toCsv` 的确切行为**（核实自源码 Javadoc，避免误解）：

- 只导出**首个工作表**（不是整个工作簿）
- 行内以**逗号**连接，行间以 **CRLF**（`\r\n`）分隔
- 字段含逗号 / 双引号 / 换行时，用双引号包裹并把内部双引号**双写**
- **合并单元格**会使某些行的单元格数少于列数——**按实际单元格输出，不补齐列**
- 输入不含表格时抛 `OfficiaException`

`parseCsv` 与 `toCsv` **对称**（同样遵循 RFC 4180），可用于往返校验。

## 三、公式求值

三个入口，按"有没有 xlsx"和"算一个还是全算"选：

### 1. 散列表直接算（没有 xlsx 文件时）

```java
Object v = OfficiaCells.evaluateFormula(
    Map.of("A1", "1", "A2", "2"),
    "=A1+A2"                       // 结果 3.0
);

Object s = OfficiaCells.evaluateFormula(
    Map.of("A1", "10", "A2", "20", "A3", "30"),
    "SUM(A1:A3)"                   // 前导 = 可省
);
```

- 网格键为 A1 引用（大小写 / `$` 会归一），值为字面量或以 `=` 开头的公式
- 第二个参数**二选一分派**：是合法单元格引用（如 `"A1"`）就求该单元格（其原始文本可以是公式，会递归求值）；否则当作公式文本直接求值
- 返回 `Double` / `String` / `Boolean`（空 → `""`）
- 网格非法 / 循环引用 / 求值错误 → 抛 `OfficiaException`

### 2. 对真实文件实算某个单元格

```java
Object v = OfficiaCells.evaluateCell(excelBytes, "C1");   // xlsx 或 xls
```

> **关键**：这是**实算**——捕获单元格的公式并按依赖递归求值，**不信缓存值**。
> 所以对"程序生成、没有缓存值"或"缓存过期"的文件也能算出正确结果。
>
> xls 的公式在文件里是二进制的 RPN token（Ptg），解析时已还原成公式文本，
> 因此走的是与 xlsx **同一套求值口径**——同一份表存成两种格式，结果一致。

### 3. 整表重算

```java
Map<String, Object> all = OfficiaCells.recalculate(excelBytes);
// 公式格引用（如 "C1"）→ 计算结果，按出现顺序
```

也作用于首个工作表。

### 4. 读工作簿模型（比转换保留得多）

```java
Workbook wb = OfficiaCells.readWorkbook(excelBytes);   // xlsx 或 xls
```

公式原文、样式表、合并区、行列尺寸都在，适合要自己处理数据而不只是转格式的场景。

### ⚠️ 已废弃的旧名字

| 旧方法 | 改用 | 说明 |
|---|---|---|
| `evaluateXlsxCell(byte[], String)` | `evaluateCell(byte[], String)` | 名字带 `Xlsx` 但现已同样吃 xls |
| `recalculateXlsx(byte[])` | `recalculate(byte[])` | 同上 |

旧方法标了 `@Deprecated(since = "1.1.4")` 但**行为完全一致**（内部只是委托），
既有代码不会被打断，不急着改。

## 支持的 Excel 函数（35 个，核实自 `FormulaEngine`）

| 分类 | 函数 |
|---|---|
| 数学 | `SUM` `SUMIF` `PRODUCT` `ABS` `ROUND` `ROUNDUP` `ROUNDDOWN` `CEILING` `FLOOR` `INT` `MOD` `POWER` `SQRT` |
| 统计 | `AVERAGE` `MAX` `MIN` `MEDIAN` `COUNT` `COUNTA` `COUNTIF` |
| 逻辑 | `IF` `AND` `OR` `NOT` `TRUE` `FALSE` |
| 文本 | `CONCATENATE` `LEFT` `RIGHT` `MID` `LEN` `TRIM` `UPPER` `LOWER` |
| 查找 | `VLOOKUP` |

> 用到清单外的函数（如 `SUMIFS` / `INDEX` / `MATCH` / 日期函数）会求值失败。核实当前版本：
> ```bash
> grep -oE 'case "[A-Z]+"' ../officia/officia-cells/src/main/java/plus/ruoyi/officia/cells/formula/FormulaEngine.java | sort -u
> ```

## 完整示例：报表 xlsx → 重算 → 出 PDF

```java
import plus.ruoyi.officia.cells.OfficiaCells;
import java.nio.file.*;
import java.util.Map;

public class ReportPipeline {
    public static void main(String[] args) throws Exception {
        // xlsx 或 xls 都行，下面三步一个字不用改
        byte[] excel = Files.readAllBytes(Path.of("report.xls"));

        // 1) 实算全部公式，核对数据
        Map<String, Object> calc = OfficiaCells.recalculate(excel);
        calc.forEach((ref, val) -> System.out.println(ref + " = " + val));

        // 2) 出 PDF 归档
        Files.write(Path.of("report.pdf"), OfficiaCells.toPdf(excel));

        // 3) 出 CSV 给下游系统
        Files.writeString(Path.of("report.csv"), OfficiaCells.toCsv(excel));
    }
}
```

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| `toCsv` 只有一个 sheet 的数据 | 设计如此——只导首个工作表 | 需要多表就分别处理源文件 |
| CSV 某些行字段数少 | 合并单元格，按实际输出不补齐 | 下游解析要容忍不定列数，或先在 Excel 里取消合并 |
| 公式算不出 / 抛异常 | 函数不在 35 个支持清单内、循环引用、网格非法 | 核对函数名；检查是否互相引用 |
| 文件里显示是 100，算出来不一样 | officia **实算**，不信缓存值——Excel 里的缓存可能过期 | 以实算为准；确认公式依赖的单元格值 |
| 报「暂不支持加密的 xls」 | 文件设了**打开密码**（不是工作表保护） | 在 Excel/WPS 里去掉打开密码后另存 |
| 报「仅支持 BIFF8」 | 文件是 Excel 5.0/95 的更老格式 | 用 Excel/WPS 打开后另存为 .xls（97-2003）或 .xlsx |
| xls 的公式没被重算 | 该公式用到了尚未支持的结构（模拟运算表、数组常量 `{1,2;3,4}`、跨簿名称、跨表引用）——实测占真实文件公式的 6.8% | 会**回落到文件里的缓存值**而不是报错；需要精确重算就先在 Excel 里转成 .xlsx |
| 文字比 Excel / WPS 里换行早 | 该格设了「不自动换行」，且**右边那格有内容**——Excel 这时是裁切，officia 退回折行（右边空着的话已经会铺过去） | 已知缺口。把列拉宽，或清空右边那格 |
| xls 里的图片/图表没出现在 PDF 里 | 尚未支持（见下方能力边界） | 需要的话先在 Excel 里另存为 xlsx 也仍不支持——该能力两种格式都未实现 |
| 转出的 PDF 中文方块 | 字体 | `officia-chinese-font` |
| 转出的 PDF 有水印 | 未授权 + 门控开 | `officia-license` |

## xls 的能力边界（如实说明）

**已支持**：单元格值（文本/数值/布尔/错误/空格带样式）、公式（Ptg 解码后实算）、
合并区、行高列宽、冻结窗格、字体（名/号/粗/斜/下划线/色）、对齐、换行、旋转、缩进、
四边框、填充、数字格式与日期（含 1904 日期系统）。

**日期按格式代码呈现**：单元格设的是 `yyyy"年"m"月"d"日"`，导出的就是`2024年1月1日`，不会被换成别的形态；内置日期格式（编号 14 等）按中文区域给`2026/3/18`，与中文版 Excel / WPS 一致。
边界：`mmm`/`dddd` 给英文月份与星期名（Jan–Dec / Monday），`[h]:mm` 这类累计时长按普通时刻渲染。

**右端空列自动裁剪**：真实表格右侧常拖着一串「什么都没有」的列（xls 的 `Dimensions`会把它们算进使用区域），照单全收会白占页宽、把内容列挤得处处换行。officia 会裁掉它们，但**只裁确实什么都没有的**——有内容、有公式、有框线底纹、或落在合并区里的列一律保留。

**「不自动换行」的格会把文字铺到右边**：和 Excel / WPS 一样，设了不自动换行的格，文字超出列宽后横着铺到右边的空格上，不在格内折行。
边界：**右边那格有内容时** Excel 是裁切，officia 退回格内折行——折行与 Excel 不同，但信息是全的，不会出现文字重叠。

**明确不支持**（识别后拒绝或降级，绝不静默出错）：

| 项 | 处置 |
|---|---|
| 加密 xls | 拒绝，并点名加密方式 |
| BIFF5 / BIFF7 | 拒绝，提示另存 |
| 图表表 / 宏表 / VBA 模块 | 跳过（它们没有单元格表） |
| 公式里的模拟运算表、数组常量、跨簿名称、跨表引用 | 该公式回落缓存值，不产出错误公式 |
| 宏表命令函数（Cetab） | 同上 |

**已知缺口**：图片 / 自选图形 / 文本框 / 图表不解析；条件格式、数据验证、超链接、批注不解析；
富文本分段着色与拼音数据只取纯文本；图案填充退化为纯色。

**xls 公式解码的实测覆盖**（上游 2026-09-14 实测，18 份真实 xls / 5951 条公式）：

| 项 | 数字 |
|---|---|
| 解码率 | **93.2%** |
| 解不出时的行为 | 回落文件里的缓存值，**不产出错误公式**、不报错 |
| 剩余 6.8% 的成因 | 模拟运算表 40%、数组常量 30%、跨簿名称 5%、跨表引用 3.5% |

已覆盖的形态包括**共享公式**（往下拖那种，真实文件里最常见）、**数组公式**、
**定义名称**（`=COUNTIF(age,">30")` 这类命名区域，含 `Database`/`Criteria` 等内置名称）、
**自定义函数调用**。

> ⚠️ **诚实声明**：解码率是「解出来了多少条」，不是「解得对不对」。上游抽样人工核对过语法，
> **但没有逐格对照 Excel 打开的结果**。对准确性要求高的场景，仍建议先自行核对一批。

## 在测试台里实测

面板 **「Cells · XLSX/CSV」**：XLSX→PDF、XLSX→CSV、CSV→PDF、CSV 解析、单元格公式求值、整表重算，全都能直接跑。
上传 `.xls` 走的是同一批端点（服务端按内容识别格式），不需要另外的面板。
端点：`/api/cells/topdf`、`/tocsv`、`/csvtopdf`、`/parsecsv`、`/formula`、`/recalc`。

## 相关技能

| 接下来 | 用 |
|---|---|
| 出的 PDF 要合并 / 加水印 | `officia-pdf` |
| 中文乱码 | `officia-chinese-font` |
| 大表格慢 / 内存 | `officia-performance` |
| 做成接口 | `officia-spring-integration` |
