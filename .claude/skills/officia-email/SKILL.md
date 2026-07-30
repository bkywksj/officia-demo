---
name: officia-email
description: |
  用 OfficiaEmail 处理邮件：解析 .eml（主题/收发件人/日期/正文/附件/任意头），
  邮件归档转 PDF，以及反向构造并写出 .eml。

  触发场景：
  - 解析 .eml 文件、提取邮件正文和附件
  - 邮件归档成 PDF 存档
  - 要读某个自定义邮件头（Message-ID、X-* 等）
  - 程序化构造一封 .eml

  触发词：邮件、EML、eml、邮件解析、邮件归档、附件、收件人、发件人、主题、正文、邮件转PDF、Message-ID
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# 邮件 EML（`OfficiaEmail`）

## 概述

三件事：**解析** `.eml` → 结构化对象、**归档** → PDF、**写出** → `.eml` 字节。

```java
import plus.ruoyi.officia.email.OfficiaEmail;
import plus.ruoyi.officia.email.EmailMessage;

EmailMessage msg = OfficiaEmail.parseEml(emlBytes);
byte[] pdf       = OfficiaEmail.toPdf(emlBytes);
```

## 一、解析

```java
EmailMessage msg = OfficiaEmail.parseEml(byte[] eml);
EmailMessage msg = OfficiaEmail.parseEml(InputStream in);   // 不主动关闭调用方的流
EmailMessage msg = OfficiaEmail.parseEml(File file);
```

### `EmailMessage` 可读字段

```java
String       subject = msg.getSubject();
String       from    = msg.getFrom();
List<String> to      = msg.getTo();
List<String> cc      = msg.getCc();
String       date    = msg.getDate();
String       text    = msg.getTextBody();     // 纯文本正文
String       html    = msg.getHtmlBody();     // HTML 正文
List<Attachment> att = msg.getAttachments();
Map<String,String> headers = msg.getHeaders();
String messageId = msg.getHeader("Message-ID");   // 按名取任意头
```

### `Attachment`

```java
String filename    = a.getFilename();
String contentType = a.getContentType();
byte[] data        = a.getData();
int    size        = a.getSize();
```

## 二、归档为 PDF

```java
byte[] pdf = OfficiaEmail.toPdf(emlBytes);
byte[] pdf = OfficiaEmail.toPdf(emlBytes, options);   // ConvertOptions：字体目录等
```

> 中文邮件归档务必配 `fontDirectory`，见 `officia-chinese-font`。

## 三、构造并写出 .eml

```java
import plus.ruoyi.officia.email.EmailMessage;
import plus.ruoyi.officia.email.Attachment;

EmailMessage msg = new EmailMessage()
    .setSubject("月度报表")
    .setFrom("noreply@example.com")
    .addTo("boss@example.com")
    .addCc("finance@example.com")
    .setDate("Thu, 30 Jul 2026 10:00:00 +0800")
    .setTextBody("详见附件。")
    .setHtmlBody("<p>详见<b>附件</b>。</p>")
    .addAttachment(new Attachment("report.pdf", "application/pdf", pdfBytes))
    .putHeader("X-Source", "officia-demo");

byte[] eml = OfficiaEmail.writeEml(msg);
```

setter 全部返回 `EmailMessage` 支持链式：`setSubject` / `setFrom` / `addTo` / `addCc` / `setDate` / `setTextBody` / `setHtmlBody` / `addAttachment` / `putHeader`。

> ⚠️ `writeEml` 只**生成 .eml 字节**，**不发送邮件**。officia 不含 SMTP 客户端（发送请用 JDK 的 `jakarta.mail` 或你现有的邮件服务）。

## 完整示例：邮件批量归档 + 附件落盘

```java
import plus.ruoyi.officia.email.*;
import java.nio.file.*;

public class MailArchive {
    public static void main(String[] args) throws Exception {
        for (Path eml : Files.newDirectoryStream(Path.of("inbox"), "*.eml")) {
            byte[] raw = Files.readAllBytes(eml);
            EmailMessage m = OfficiaEmail.parseEml(raw);

            System.out.println(m.getDate() + " | " + m.getFrom() + " | " + m.getSubject()
                             + " | 附件 " + m.getAttachments().size() + " 个");

            // 正文归档成 PDF
            Files.write(Path.of("archive", eml.getFileName() + ".pdf"),
                        OfficiaEmail.toPdf(raw));

            // 附件落盘
            for (Attachment a : m.getAttachments()) {
                Files.write(Path.of("archive", a.getFilename()), a.getData());
            }
        }
    }
}
```

## 结合其它能力

```java
// 附件里的 Word 一并转 PDF，和邮件正文合成一份归档
EmailMessage m = OfficiaEmail.parseEml(raw);
List<byte[]> parts = new ArrayList<>();
parts.add(OfficiaEmail.toPdf(raw));                      // 正文
for (Attachment a : m.getAttachments()) {
    if (a.getFilename().endsWith(".docx")) {
        parts.add(OfficiaWords.toPdf(a.getData()));      // 附件 Word → PDF
    }
}
byte[] full = OfficiaPdf.merge(parts);                   // 合成一份完整归档
```

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| 正文是空的 | 邮件只有 HTML 正文 | 用 `getHtmlBody()`；两者都取，谁非空用谁 |
| 中文主题/正文乱码 | 源邮件编码问题 | 确认 `.eml` 原文的 `Content-Transfer-Encoding` 与 charset 头 |
| 归档 PDF 中文方块 | 字体 | 配 `fontDirectory`，见 `officia-chinese-font` |
| 拿不到某个头 | 大小写 / 名字不对 | `getHeaders()` 打印全部头名核对 |
| 想直接发邮件 | officia 不含 SMTP | `writeEml` 出字节后交给你的邮件服务发送 |
| 归档 PDF 有水印 | 未授权 + 门控开 | `officia-license` |

## 在测试台里实测

面板 **「Email · EML」**：EML 解析（主题/收发件/附件/正文）、邮件归档 → PDF。
端点 `/api/email/parse`、`/api/email/topdf`。测试台的 `/api/samples` 还能现造 EML 样本，无需自备文件。

## 相关技能

| 接下来 | 用 |
|---|---|
| 附件里的 Office 文件转 PDF | `officia-words` / `officia-cells` / `officia-slides` |
| 归档 PDF 合并加密 | `officia-pdf` |
| 中文乱码 | `officia-chinese-font` |
