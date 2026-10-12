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

**单元格字体照原样用**（xlsx 与 xls 都是）：宋体、微软雅黑的格连数字英文也用自己的字形；Arial、Calibri 这类西文字体里的汉字自动换成中文字体；
富文本各段按自己的字体名。服务器上没装的字体会换成相近字体——版式和本机不同时开字体替换诊断，查哪款被换掉了：

```java
ConvertResult r = OfficiaCells.convert(excelBytes, new ConvertOptions().setReportFontSubstitutions(true));
r.getFontSubstitutions().forEach(s -> System.out.println(s.describe()));   // 不开时是空列表
```

**上标、下标照文件出**（「0.35M³」「H₂O」）：整格设的与富文本某一段设的都算，xls 也读得出；画法与 Word 转 PDF 相同
（字号 ×0.65、基线抬高 / 压低，后面的字仍按原字号宽度排，右边留白略宽）。1.1.4 及之前的版本会把上标画成正文（「0.35M3」），1.1.4 之后的版本已修复（尚未发布到 Maven Central）。

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

公式原文、样式表、合并区、行列尺寸、定义名称、表格都在，适合要自己处理数据而不只是转格式的场景。

### ⚠️ 已废弃的旧名字

| 旧方法 | 改用 | 说明 |
|---|---|---|
| `evaluateXlsxCell(byte[], String)` | `evaluateCell(byte[], String)` | 名字带 `Xlsx` 但现已同样吃 xls |
| `recalculateXlsx(byte[])` | `recalculate(byte[])` | 同上 |

旧方法标了 `@Deprecated(since = "1.1.4")` 但**行为完全一致**（内部只是委托），
既有代码不会被打断，不急着改。

## 支持的 Excel 函数（85 个，核实自 `FormulaEngine`）

| 分类 | 函数 |
|---|---|
| 数学（16） | `SUM` `SUMIF` `SUMIFS` `SUMPRODUCT` `SUBTOTAL` `PRODUCT` `ABS` `ROUND` `ROUNDUP` `ROUNDDOWN` `CEILING` `FLOOR` `INT` `MOD` `POWER` `SQRT` |
| 统计（13） | `AVERAGE` `AVERAGEIF` `AVERAGEIFS` `MAX` `MIN` `MEDIAN` `COUNT` `COUNTA` `COUNTIF` `COUNTIFS` `SMALL` `LARGE` `RANK` |
| 逻辑（7） | `IF` `IFERROR` `AND` `OR` `NOT` `TRUE` `FALSE` |
| 信息（9） | `ISERROR` `ISERR` `ISNA` `ISNUMBER` `ISTEXT` `ISLOGICAL` `ISBLANK` `N` `NA` |
| 文本（13） | `CONCATENATE` `LEFT` `RIGHT` `MID` `LEN` `TRIM` `UPPER` `LOWER` `TEXT` `FIND` `SEARCH` `SUBSTITUTE` `VALUE` |
| 查找与引用（14） | `VLOOKUP` `HLOOKUP` `LOOKUP` `INDEX` `MATCH` `CHOOSE` `OFFSET` `INDIRECT` `TRANSPOSE` `HYPERLINK` `ROW` `COLUMN` `ROWS` `COLUMNS` |
| 日期时间（13） | `DATE` `YEAR` `MONTH` `DAY` `TODAY` `NOW` `HOUR` `MINUTE` `SECOND` `WEEKDAY` `WEEKNUM` `EOMONTH` `DATEDIF` |

> 清单外的函数（如 `RANDBETWEEN`、`GETPIVOTDATA`）会求值失败——重算工作簿时这类格保留文件里 Excel 算好的结果。另有几处如实的边界：
> - 跨表引用（`Sheet2!A1`、`'表 名'!A1:B2`，表名不分大小写）与整列 / 整行引用（`客户信息!A:B`、`$1:$1`）支持；
> - **数组公式**（Excel 里按 Ctrl+Shift+Enter 输入的 `{=MAX(IF(C:C=A2,B:B))}`，xlsx 里带 `t="array"`）按数组逐元素计算；`SUMPRODUCT`、`LOOKUP` 的参数本身就按数组算，不必按数组公式输入。同样的写法作为**普通公式**时走隐式交集（ECMA-376 §18.17.2）：把多格区域交给只收单个值的地方，取与公式同一行（区域是一列时）或同一列（区域是一行时）的那一格，没有交点是 `#VALUE!`；
> - **定义名称**（`=SUM(销量)`）照常计算：同名时本表的局部名称优先于整本的；名称里的计算式按数组公式算；未定义的名称是 `#NAME?`，`IFERROR` 兜得住。读 xlsx 时名称一并读进 `Workbook.getNames()`、写回时原样保留；`.xls` 的名称公式暂未读出，用到它的格保留缓存值；
> - `OFFSET`、`INDIRECT`（含 R1C1 写法 `INDIRECT("R[-1]C",FALSE)`）返回的是**引用**，可以放在 `SUM`、`COUNTIF`、`ROWS`、`INDEX` 等要区域的参数里；在普通公式里直接取一个多格区域的值时同样走隐式交集；
> - `SUBTOTAL` 的 1–11 含隐藏行、101–111 跳过隐藏行，区域里本身是 `SUBTOTAL` 的格不重复计入。筛选掉的行与手动隐藏的行在文件里分不出来，1–11 会把筛选掉的行也算进去；
> - 公式里的数组常量（`{1,2;3,4}`，如个税速算 `MAX(A1*{0.03,0.1}-{0,210},0)`）与直接写的错误值（`#REF!`）都能算；`VLOOKUP` / `HLOOKUP` / `MATCH` 查数组常量暂不支持；
> - **表格与结构化引用**照常计算（[MS-XLSX] §2.2.2）：`表1[金额]`（数据行）、`表1[[#This Row],[单价]]`（本行）、`表1[[#Totals],[金额]]`、`表1[[单价]:[数量]]`、`#All` / `#Headers` 等；写在表格里的公式可以省略表名。读 xlsx 时表格一并读进 `Sheet.getTables()`、写回时原样保留（差异格式编号、筛选条件、排序状态不保留）；
> - 不支持三维引用（`Sheet1:Sheet3!A1`）与外部工作簿引用（`[1]Sheet1!A1`）；`INDEX` 取整行 / 整列（行号或列号为 0）只在数组公式里可用；
> - 日期函数按 1900 日期系统计算（ECMA-376 §18.17.4.1）；`YEAR` / `MONTH` / `DAY` 对序列值 0–60 沿用 Excel 的读法（空格是 1900 年 1 月 0 日，`MONTH(空格)` 为 1）；`date1904` 的工作簿暂不换算；
> - 条件函数（`COUNTIF`、`SUMIFS` 等）里空格只配「空」条件、不当 0 比大小；条件引用的是空格时按 0；文本 "1001" 与数字条件 1001 相等；
> - 求值出错时写真实的错误码（`#N/A`、`#DIV/0!`、`#REF!`、`#NUM!`、`#NAME?`、`#VALUE!`）。
> 核实当前版本：
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
| 公式算不出 / 抛异常 | 函数不在 85 个支持清单内、循环引用、网格非法 | 核对函数名；检查是否互相引用 |
| 文件里显示是 100，算出来不一样 | officia **实算**，不信缓存值——Excel 里的缓存可能过期 | 以实算为准；确认公式依赖的单元格值 |
| 报「暂不支持加密的 xls」 | 文件设了**打开密码**（不是工作表保护） | 在 Excel/WPS 里去掉打开密码后另存 |
| 报「仅支持 BIFF8」 | 文件是 Excel 5.0/95 的更老格式 | 用 Excel/WPS 打开后另存为 .xls（97-2003）或 .xlsx |
| xls 的公式没被重算 | 该公式用到了尚未支持的结构（模拟运算表、数组常量 `{1,2;3,4}`、跨簿名称、跨表引用）——实测占真实文件公式的 6.8% | 会**回落到文件里的缓存值**而不是报错；需要精确重算就先在 Excel 里转成 .xlsx |
| PDF 里一格的字只显示了一截 | 该格设了「不自动换行」，旁边那格有内容（或是合并区、表格边）——Excel / WPS 里也是这样裁掉的 | 要看全就在 Excel 里给那一格勾「自动换行」，或把列拉宽。1.1.4 及之前这种格是在格内折行、把行撑高 |
| xls 里的图片/图表没出现在 PDF 里 | 尚未支持（见下方能力边界） | 需要的话先在 Excel 里另存为 xlsx 也仍不支持——该能力两种格式都未实现 |
| 转出的 PDF 中文方块 | 字体 | `officia-chinese-font` |
| 服务器上转出的 PDF 字形 / 行高 / 页数和本机不同 | 表格用的字体服务器上没装，换成了相近字体 | 开 `setReportFontSubstitutions(true)` 看 `getFontSubstitutions()`，把缺的字体装上或用 `fontDirectory` 指过去 |
| 转出的 PDF 有水印 | 未授权 + 门控开 | `officia-license` |

## xls 的能力边界（如实说明）

**已支持**：单元格值（文本/数值/布尔/错误/空格带样式）、公式（Ptg 解码后实算）、
合并区、行高列宽、冻结窗格、字体（名/号/粗/斜/下划线/色）、对齐、换行、旋转、缩进、
四边框、填充、数字格式与日期（含 1904 日期系统）。

**日期按格式代码呈现**：单元格设的是 `yyyy"年"m"月"d"日"`，导出的就是`2024年1月1日`，不会被换成别的形态；内置日期格式（编号 14 等）按中文区域给`2026/3/18`，与中文版 Excel / WPS 一致。
边界：`mmm`/`dddd` 给英文月份与星期名（Jan–Dec / Monday），`[h]:mm` 这类累计时长按普通时刻渲染。

**右端空列自动裁剪**：真实表格右侧常拖着一串「什么都没有」的列（xls 的 `Dimensions`会把它们算进使用区域），照单全收会白占页宽、把内容列挤得处处换行。officia 会裁掉它们，但**只裁确实什么都没有的**——有内容、有公式、有框线底纹、或落在合并区里的列一律保留。

**「不自动换行」的文字格照 Excel 铺与裁**：排成一行，超出列宽时铺到空着的邻格上（常规 / 左对齐往右、右对齐往左、居中往两边），遇到有内容的格、合并区或表格边就停，停处之外裁掉，行高不被撑高。「有内容」看值：只带框线底纹的空格照样铺过去，公式算出空串的格挡得住。
边界：两端 / 分散 / 填充 / 跨列居中对齐与旋转过的文字不铺。1.1.4 及之前，邻格有内容时是在格内折行。

**数字放不下照 Excel 显示**（1.1.4 之后的版本，尚未发布）：常规格式先减小数位、再改科学计数，最多显示 11 位——`3.14159265358979` 随列变窄依次显示成 `3.141593`、`3.1416`、`3.14`，`123456789012` 显示成 `1.23E+11`、`1E+11`；都放不下才整格显示 `#`。日期、`0.00`、百分比这类明确格式放不下直接整格 `#`。数字不折行（勾了自动换行也一样），合并格按合并后的总宽算，字号大的格放得下的位数相应少；文本格式（`@`）里的数按文本铺与裁。只影响转 PDF——`toCsv` 照旧导出完整的值。1.1.4 及之前：右边一路空着就铺过去、否则在格内逐位折行。
**常规对齐按值类型摆**（1.1.4 之后的版本，尚未发布）：没设水平对齐的格，数字、日期靠右，`TRUE` / `FALSE` 与错误值居中，文字与文本格式（`@`）里的数靠左，与 Excel / WPS、在线编辑器一致。1.1.4 及之前转 PDF 一律靠左，整列金额参差不齐。
**行高与基线按表格口径**（1.1.4 之后的版本，尚未发布）：格子按它声明的字体排行，Calibri 格里的汉字只借中文字体的字形、不改行高与基线，同一行中西文对齐；没设行高的行接近 Excel / WPS 默认行高。1.1.4 及之前按文字处理口径，含中文的行几乎高出一倍、页数偏多。
**按每张表的打印设置出页**（1.1.4 之后的版本，尚未发布）：纸张（A4、A3、Letter 等及自定义宽高）、方向、页边距、缩放比例、「调整为 N 页宽 × M 页高」、水平居中都照文件；表比纸宽时像 Excel 打印那样按列分到后面几页（先打完左边一批列的所有行，再打右边一批），手动列分页符照样分页，右边那批列下面整页空白的也照样出页（与 WPS 打印一致）；没设打印设置的表按 A4 纵向 100%。`.xls` 与 `.xlsx` 相同。1.1.4 及之前一律 A4 横向 100%，宽表被压进一页宽、逐字折行——客户拿 1.1.4 说「PDF 和 WPS 打印的页数差很多」时多半是这个。
边界：打印区域、打印标题行、手动行分页符、垂直居中、页眉页脚还不认；跨页的合并格，字只画在左边那一页。宽表现在会按列分页，页数可能比 1.1.4 多很多（以前是硬压进一页宽），右边几批列只有顶上几行有字时还会出一串空白页——这都与 WPS 按同样设置打印的结果一致；要一页宽就在 Excel / WPS 里设「调整为 1 页宽」。
**列宽按工作簿的默认字体换算**（1.1.4 之后的版本，尚未发布）：Excel 的列宽以「默认字体里数字的最大宽度」为单位（ECMA-376 §18.3.1.13），同样写着宽 10，默认字体宋体 12 的表一列 60pt、Calibri 11 的表 52.5pt；没设宽度的列宋体表 54pt、Calibri 表 48pt。宋体、黑体这类中文字体按字体本身的规则算，服务器装没装都一样。1.1.4 及之前一律按 Calibri 11 换算、且每列多算 5px：宋体的宽表每页多放一两列，满页宽设计的模板最后一两列被挤到额外的页上——客户说「按 A4 横向设计的一页宽表格转出来多了一页窄条」时多半是这个。
等线（新版中文 Excel 的默认字体）在装了等线的机器上（Windows 自带）与 Excel 存下的列宽对得上；Linux 服务器一般没有等线，按替换字体量，个别字号可能差一个像素。
边界：与 WPS 仍可能差一点（它在高分屏下按物理像素算，宋体 12 宽 10 的列它是 61.6pt），个别宽表每页比 WPS 多放一列。
**隐藏的行、列不打印**（1.1.4 之后的版本，尚未发布）：转 PDF 跳过隐藏的行和列（含筛选掉的行、折叠的分组），合并区跨过隐藏列时只占可见部分，与 Excel / WPS 打印一致；`toCsv` 照旧导出隐藏行列里的数据。1.1.4 及之前隐藏的行列也画进 PDF——客户说「PDF 里多出一列 / 多出好多页」时先查是不是隐藏行列。
边界：单独一列就比纸还宽时，那一页的表格会被等比缩小，按原始列宽放得下的数字不会在缩小后的格里改成 `#`（WPS 打印不缩列，改成 `#` 反而与它不一致）。

**明确不支持**（识别后拒绝或降级，绝不静默出错）：

| 项 | 处置 |
|---|---|
| 加密 xls | 拒绝，并点名加密方式 |
| BIFF5 / BIFF7 | 拒绝，提示另存 |
| 图表表 / 宏表 / VBA 模块 | 跳过（它们没有单元格表） |
| 公式里的模拟运算表、数组常量、跨簿名称、跨表引用 | 该公式回落缓存值，不产出错误公式 |
| 宏表命令函数（Cetab） | 同上 |

**已知缺口**：图片 / 自选图形 / 文本框 / 图表不解析；条件格式、数据验证、超链接、批注不解析；
富文本分段着色与拼音数据只取纯文本；图案填充退化为纯色。（整格字体的上标 / 下标读得出。）

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
