---
name: officia-cells
description: |
  用 OfficiaCells 处理表格：xlsx → PDF、xlsx → CSV、CSV → PDF、CSV 解析（RFC 4180），
  以及公式求值——散列表直接算、对真实 xlsx 实算单元格、整表重算（不信缓存值）。

  触发场景：
  - Excel 转 PDF、xlsx 转 PDF
  - 导出 CSV / 解析 CSV / CSV 转 PDF
  - 算 Excel 公式、单元格求值、公式重算
  - 拿到的 xlsx 缓存值过期/为空，要算出正确结果
  - 想知道支持哪些 Excel 函数

  触发词：Excel、xlsx、表格、CSV、转PDF、导出CSV、解析CSV、公式、求值、计算、重算、SUM、VLOOKUP、单元格
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# Excel / CSV / 公式（`OfficiaCells`）

## 概述

`OfficiaCells` 覆盖三块能力：**格式转换**（xlsx ↔ PDF / CSV）、**CSV 解析**、**公式求值**。

## 一、转换

```java
import plus.ruoyi.officia.cells.OfficiaCells;

byte[] pdf = OfficiaCells.toPdf(xlsxBytes);                    // Excel → PDF
byte[] pdf = OfficiaCells.toPdf(xlsxBytes, options);           // 自定义选项
byte[] pdf = OfficiaCells.toPdf(inputStream);                  // 流（不主动关闭）
byte[] pdf = OfficiaCells.toPdf(new File("in.xlsx"));          // 文件

ConvertResult r = OfficiaCells.convert(xlsxBytes, options);    // 富结果：页数 + 耗时
```

`ConvertOptions` 用法与 Words 一致（纸张 / 字体目录 / 嵌入字体 / 超时），见 `officia-words`。

## 二、CSV

```java
String csv = OfficiaCells.toCsv(xlsxBytes);          // 导出【首个工作表】为 CSV（RFC 4180）
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

### 2. 对真实 xlsx 实算某个单元格

```java
Object v = OfficiaCells.evaluateXlsxCell(xlsxBytes, "C1");
```

> **关键**：这是**实算**——捕获单元格的 `<f>` 公式并按依赖递归求值，**不信缓存 `<v>`**。
> 所以对"程序生成、没有缓存值"或"缓存过期"的 xlsx 也能算出正确结果。

### 3. 整表重算

```java
Map<String, Object> all = OfficiaCells.recalculateXlsx(xlsxBytes);
// 公式格引用（如 "C1"）→ 计算结果，按出现顺序
```

也作用于首个工作表。

## 支持的 Excel 函数（34 个，核实自 `FormulaEngine`）

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
        byte[] xlsx = Files.readAllBytes(Path.of("report.xlsx"));

        // 1) 实算全部公式，核对数据
        Map<String, Object> calc = OfficiaCells.recalculateXlsx(xlsx);
        calc.forEach((ref, val) -> System.out.println(ref + " = " + val));

        // 2) 出 PDF 归档
        Files.write(Path.of("report.pdf"), OfficiaCells.toPdf(xlsx));

        // 3) 出 CSV 给下游系统
        Files.writeString(Path.of("report.csv"), OfficiaCells.toCsv(xlsx));
    }
}
```

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| `toCsv` 只有一个 sheet 的数据 | 设计如此——只导首个工作表 | 需要多表就分别处理源文件 |
| CSV 某些行字段数少 | 合并单元格，按实际输出不补齐 | 下游解析要容忍不定列数，或先在 Excel 里取消合并 |
| 公式算不出 / 抛异常 | 函数不在 34 个支持清单内、循环引用、网格非法 | 核对函数名；检查是否互相引用 |
| xlsx 里显示是 100，算出来不一样 | officia **实算**，不信缓存值——Excel 里的缓存可能过期 | 以实算为准；确认公式依赖的单元格值 |
| 转出的 PDF 中文方块 | 字体 | `officia-chinese-font` |
| 转出的 PDF 有水印 | 未授权 + 门控开 | `officia-license` |

## 在测试台里实测

面板 **「Cells · XLSX/CSV」**：XLSX→PDF、XLSX→CSV、CSV→PDF、CSV 解析、单元格公式求值、整表重算，全都能直接跑。
端点：`/api/cells/topdf`、`/tocsv`、`/csvtopdf`、`/parsecsv`、`/formula`、`/recalc`。

## 相关技能

| 接下来 | 用 |
|---|---|
| 出的 PDF 要合并 / 加水印 | `officia-pdf` |
| 中文乱码 | `officia-chinese-font` |
| 大表格慢 / 内存 | `officia-performance` |
| 做成接口 | `officia-spring-integration` |
