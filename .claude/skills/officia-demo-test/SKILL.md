---
name: officia-demo-test
description: |
  为 officia 的使用写验证测试（JUnit 5 + AssertJ）：字节级断言套路（%PDF- 头、PNG 魔数、
  往返一致）、授权相关测试怎么隔离与跳过、现有示例测试清单与可直接抄的模板。

  触发场景：
  - 要给自己的 officia 调用写单测
  - 不知道转换结果该断言什么
  - 授权相关的测试怎么写才不依赖 .lic
  - 想看 demo 里现成的测试怎么写的
  - 测试之间互相影响

  触发词：写测试、单测、单元测试、JUnit、AssertJ、断言、验证、测试用例、mvn test、跳过测试、assume
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# 写验证测试（JUnit 5 + AssertJ）

## 概述

officia 的 API 是 `byte[]` 进 `byte[]` 出，所以测试的核心是**对字节做结构性断言**——不需要打开文件肉眼看。

本 demo 的测试栈：**JUnit 5（junit-jupiter 5.10.2）+ AssertJ 3.25.3**（`pom.xml` 中 `test` 域）。

```bash
mvn -o test
```

## 🔴 断言套路（可直接抄）

### 1. PDF：文件头 + 页数

```java
import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;

private void assertPdf(byte[] pdf) {
    assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    assertThat(pdf.length).isGreaterThan(0);
}
```

> 用 `ISO_8859_1` 读头部字节——它是字节到字符的一一映射，不会因编码丢信息。

进一步断言页数 / 内容：

```java
assertThat(OfficiaPdf.pageCount(pdf)).isEqualTo(3);
assertThat(OfficiaPdf.extractText(pdf)).contains("合同编号");
```

### 2. PNG：魔数

```java
private void assertPng(byte[] png) {
    assertThat(png.length).isGreaterThan(8);
    assertThat(png[0] & 0xFF).isEqualTo(0x89);
    assertThat(png[1]).isEqualTo((byte) 'P');
    assertThat(png[2]).isEqualTo((byte) 'N');
    assertThat(png[3]).isEqualTo((byte) 'G');
}
```

### 3. 往返一致（最强的正确性断言）

```java
// CSV：导出后再解析，应还原
String csv = OfficiaCells.toCsv(xlsx);
List<List<String>> grid = OfficiaCells.parseCsv(csv);
assertThat(grid.get(0)).containsExactly("姓名", "部门");

// EML：构造 → 写出 → 解析回来
byte[] eml = OfficiaEmail.writeEml(msg);
EmailMessage back = OfficiaEmail.parseEml(eml);
assertThat(back.getSubject()).isEqualTo(msg.getSubject());
```

### 4. 公式：直接比值

```java
assertThat(OfficiaCells.evaluateFormula(Map.of("A1", "1", "A2", "2"), "=A1+A2"))
    .isEqualTo(3.0d);
```

### 5. 异常

```java
import plus.ruoyi.officia.common.exception.OfficiaException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

assertThatThrownBy(() -> OfficiaWords.toPdf(new byte[0]))
    .isInstanceOf(OfficiaException.class)
    .hasMessageContaining("为空");
```

## 🔴 授权相关测试：隔离 + 条件跳过

`OfficiaLicense` 是**进程全局状态**，测试之间必须复位，否则会互相污染。

```java
@BeforeEach
void setup() {
    OfficiaLicense.enableEnforcement(false);
    OfficiaLicense.reset();
}

@AfterEach
void teardown() {
    OfficiaLicense.enableEnforcement(false);
    OfficiaLicense.reset();
}
```

**不要在测试里依赖真实 `.lic`**（demo 面向公开展示，绝不内置可解锁的令牌）。要测"授权后的效果"，用 `assumeTrue` 条件跳过：

```java
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Test
@DisplayName("enforcement 开 + 客户自备 officia.lic → 授权则不降级（无 lic 时跳过）")
void licensedIfProvided() {
    File lic = new File("officia.lic");
    assumeTrue(lic.isFile(), "未放置 officia.lic，跳过授权演示（demo 不内置任何令牌）");
    OfficiaLicense.enableEnforcement(true);
    OfficiaLicense.setLicense(lic);
    assumeTrue(OfficiaLicense.hasModule("cells"), "该 License 未授权 cells 模块，跳过");
    assertPdf(OfficiaCells.csvToPdf(CSV));
}
```

> `assumeTrue` 不满足时测试标记为**跳过**而非失败——CI 里没有 `.lic` 也能全绿。

### 三态门控测试模板

```java
@Test void notEnforced() {                 // 门控关（开发默认）→ 不降级
    assertPdf(OfficiaCells.csvToPdf(CSV));
    assertThat(OfficiaLicense.isEvaluation()).isTrue();
}

@Test void enforcedUnlicensedDegrades() {  // 门控开 + 未授权 → 仍出 PDF（降级），不抛异常不退出
    OfficiaLicense.enableEnforcement(true);
    assertPdf(OfficiaCells.csvToPdf(CSV));
    assertThat(OfficiaLicense.isEvaluation()).isTrue();
}
```

> 这组测试同时验证了 officia 的关键承诺：**库不阻断宿主**——未授权只降级，不抛异常、不 `System.exit`。

## 不依赖外部素材：让 officia 自造输入

测试最怕依赖 `.docx` 样本文件（体积大、不好进仓库）。**能自造的就自造**：

```java
// CSV → PDF：文本现造
String csv = "Name,Dept\nAlice,R&D\nBob,Product";
byte[] pdf = OfficiaCells.csvToPdf(csv);

// PNG：条码现造
byte[] png = OfficiaBarCode.qrPng("test");

// PDF：由 CSV 转出来，再拿去测 PDF 工具箱
byte[] merged = OfficiaPdf.merge(List.of(pdf, pdf));

// EML：构造后写出
byte[] eml = OfficiaEmail.writeEml(new EmailMessage().setSubject("t").setFrom("a@b.c").addTo("d@e.f"));

// 图片：用 BufferedImage + ImageIO 现造
```

只有 **docx / xlsx / pptx** 需要真实样本——那部分放到测试台手工验证（`officia-testbench`），或在测试里用 `assumeTrue(file.exists())` 跳过。

## demo 现有示例测试清单

| 测试类 | 演示什么 |
|---|---|
| `CellsDemoTest` | CSV→PDF、CSV 解析(RFC 4180)、公式求值 `=A1+A2` |
| `BarcodeDemoTest` | Code128 / QR → PNG |
| `PdfDemoTest` | 多 PDF 合并、AES-256 加密 |
| `LicenseDemoTest` | 未授权评估态、无效/空 License 抛异常（**不 System.exit**） |
| `EnforcementDemoTest` | 授权门控三态：关 / 开+未授权 / 开+自备 lic（无 lic 自动跳过） |
| `web/HttpTest` `web/JsonTest` `web/RouterAndBatchTest` `web/StoreTest` | 测试台自身的单测 |

直接读源码抄套路：

```bash
ls src/test/java/plus/ruoyi/officia/demo/
```

## 完整模板：给自己的业务转换写测试

```java
package com.yourcompany.doc;

import org.junit.jupiter.api.*;
import plus.ruoyi.officia.cells.OfficiaCells;
import plus.ruoyi.officia.pdf.OfficiaPdf;
import plus.ruoyi.officia.license.OfficiaLicense;
import plus.ruoyi.officia.common.exception.OfficiaException;

import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

@DisplayName("报表导出验证")
class ReportExportTest {

    private static final String CSV = "月份,金额\n1月,1000\n2月,2000";

    @BeforeEach
    void reset() {
        OfficiaLicense.enableEnforcement(false);
        OfficiaLicense.reset();
    }

    @Test
    @DisplayName("CSV 报表能导出为合法 PDF")
    void exportPdf() {
        byte[] pdf = OfficiaCells.csvToPdf(CSV);
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
        assertThat(OfficiaPdf.pageCount(pdf)).isGreaterThanOrEqualTo(1);
        assertThat(OfficiaPdf.extractText(pdf)).contains("月份");
    }

    @Test
    @DisplayName("空输入抛 OfficiaException 而非 NPE")
    void emptyInput() {
        assertThatThrownBy(() -> OfficiaCells.csvToPdf(new byte[0]))
            .isInstanceOf(OfficiaException.class);
    }
}
```

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| 测试单独跑绿、一起跑红 | `OfficiaLicense` 全局状态污染 | 加 `@BeforeEach` / `@AfterEach` 复位 |
| CI 上授权测试失败 | 依赖了不存在的 `.lic` | 改用 `assumeTrue` 条件跳过 |
| 断言 PDF 内容取不到文字 | 该 PDF 是图片型 / 未嵌文本 | 改断言页数与文件头 |
| 中文断言失败 | 字体缺失导致内容异常 | 见 `officia-chinese-font` |
| 测试很慢 | 每个用例都转大文档 | 用自造的小输入；大文档留给手工测试台 |

## 相关技能

| 接下来 | 用 |
|---|---|
| 手工实测 | `officia-testbench` |
| 升级后的回归 | `officia-upgrade` |
| 断言失败要排查 | `officia-troubleshooting` |
