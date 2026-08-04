---
name: officia-chinese-font
description: |
  解决中文显示问题：PDF 里中文变方块 / 空白 / 乱码。讲清两条不同的字体入口
  （转换类用 ConvertOptions.fontDirectory，PDF 水印页码用 fontTtf 字节），
  以及 .ttc 的限制、Docker/Linux 装字体、CID 子集嵌入是怎么回事。

  触发场景：
  - 转出来的 PDF 中文是方块 / 豆腐块 / 空白
  - PDF 水印或页码写中文没显示
  - Linux / Docker 环境没中文字体
  - 不知道 fontDirectory 和 fontTtf 有什么区别
  - 用了 .ttc 字体不生效

  触发词：中文、字体、方块、豆腐块、乱码、显示不出来、缺字、TTF、ttc、fontDirectory、fontTtf、字体目录、嵌入字体、CID、宋体、黑体、微软雅黑
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# 中文字体与 CID 嵌入

## 概述

PDF 的内置 14 款标准字体**不含任何中文字形**。所以中文要正常显示，officia 必须拿到一份含中文的 TrueType 字体，做 **CID 子集嵌入**（只把用到的字形打进 PDF）。

**中文出不来，99% 是"officia 没找到能用的中文字体"。**

## 🔴 两条完全不同的字体入口（最常搞混的点）

| 场景 | 入口 | 传什么 |
|---|---|---|
| **格式转换类**（Words / Cells / Slides / Email → PDF） | `ConvertOptions.setFontDirectory(String)` | 字体**目录路径** |
| **PDF 后处理类**（`OfficiaPdf.watermark` / `addPageNumbers`） | 方法的 `byte[] fontTtf` 入参 | 字体**文件字节** |

搞混了就会出现"转换的正文中文正常，但加的水印是方块"，或反过来。

### 转换类：设字体目录

```java
import plus.ruoyi.officia.engine.api.ConvertOptions;

ConvertOptions opts = ConvertOptions.defaults()
    .setFontDirectory("C:/Windows/Fonts")     // Windows
 // .setFontDirectory("/usr/share/fonts")     // Linux
    .setEmbedFonts(true);                     // 默认就是 true

byte[] pdf = OfficiaWords.toPdf(docx, opts);
byte[] pdf = OfficiaCells.toPdf(xlsx, opts);
byte[] pdf = OfficiaSlides.toPdf(pptx, opts);
byte[] pdf = OfficiaEmail.toPdf(eml, opts);
byte[] pdf = OfficiaCells.csvToPdf(csv, opts);
```

### PDF 后处理类：传字体字节

```java
byte[] font = Files.readAllBytes(Path.of("C:/Windows/Fonts/simhei.ttf"));

byte[] out = OfficiaPdf.watermark(pdf, "内部资料", font);
byte[] out = OfficiaPdf.addPageNumbers(pdf, "第 %d 页 / 共 %d 页", font);

// 链式同理
byte[] out = OfficiaPdf.edit(pdf)
    .watermark("机密", font)
    .pageNumbers("第 %d 页", font)
    .toBytes();
```

> **不传 `fontTtf` 的重载只能正常显示 ASCII**——写中文会是方块或空白。

## ⚠️ `.ttc` 字体集合体的限制

**Windows 上优先用纯 `.ttf`，不要首选 `.ttc`**——`.ttc`（TrueType Collection，一个文件打包多款字体）的解析支持有限。

推荐顺序（这也是本 demo `Fonts.java` 的真实探测顺序）：

```
C:/Windows/Fonts/simhei.ttf      黑体   ← 首选，纯 TTF
C:/Windows/Fonts/msyh.ttf        微软雅黑
C:/Windows/Fonts/simsun.ttc      宋体   （ttc，兜底）
C:/Windows/Fonts/simkai.ttf      楷体
C:/Windows/Fonts/arial.ttf       （仅 ASCII）
```

Linux / macOS 候选：

```
/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf         （仅拉丁，无中文）
/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc
/System/Library/Fonts/PingFang.ttc                       （macOS）
/Library/Fonts/Arial.ttf
```

## 可直接复用的字体探测代码

本 demo 的 `Fonts.java` 就是一份现成实现，可以抄进你的项目：

```java
/** 取一份可用的中文 TTF 字节；无则 null。探测一次后缓存复用。 */
static synchronized byte[] systemCjk() {
    String[] candidates = {
        "C:/Windows/Fonts/simhei.ttf",
        "C:/Windows/Fonts/msyh.ttf",
        "C:/Windows/Fonts/simsun.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/System/Library/Fonts/PingFang.ttc",
    };
    for (String p : candidates) {
        File f = new File(p);
        if (f.isFile() && f.length() > 0) {
            try { return Files.readAllBytes(f.toPath()); } catch (Exception ignore) { }
        }
    }
    return null;   // 调用方退回不带字体的重载（仅 ASCII）
}
```

> 更稳的做法：**把字体文件随应用一起交付**（放进 `src/main/resources/fonts/`），从 classpath 读取，不依赖运行环境装了什么。
> ⚠️ 注意字体的**授权协议**——不是所有字体都允许随应用分发嵌入。思源黑体（Source Han Sans）/ Noto Sans CJK 是 SIL OFL，可安全分发。

## Linux / Docker 环境装中文字体

```dockerfile
FROM eclipse-temurin:17-jre

# Debian / Ubuntu
RUN apt-get update && apt-get install -y \
      fonts-wqy-zenhei fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

# Alpine
# RUN apk add --no-cache font-noto-cjk

ENV OFFICIA_FONT_DIR=/usr/share/fonts
```

装完后在代码里 `setFontDirectory("/usr/share/fonts")`，或用上面的探测逻辑自动找。

验证容器里有没有字体：

```bash
docker run --rm your-image sh -c "ls -R /usr/share/fonts | head -30"
fc-list :lang=zh | head        # 若镜像里有 fontconfig
```

## `embedFonts` 与 CID 子集

```java
opts.setEmbedFonts(true);    // 默认 true，建议保持
```

| 设置 | 效果 |
|---|---|
| `true`（默认） | 把**用到的字形子集**嵌进 PDF。换任何机器/阅读器显示都一致，体积只增加子集大小 |
| `false` | 不嵌入。体积小，但目标机器没这款字体就会替换字体或显示异常 |

> 中文场景**不要关**——收件人机器不一定有你的字体。

## 排查流程

```
中文显示不对
├─ 是转换出来的正文？
│   └─ 设 ConvertOptions.setFontDirectory(...) → 确认该目录下真有中文 TTF
├─ 是 PDF 水印 / 页码？
│   └─ 用带 fontTtf 的重载，传中文 TTF 字节
├─ 设了目录还是方块？
│   ├─ 目录里只有 .ttc → 换纯 .ttf（simhei.ttf / msyh.ttf）
│   ├─ 目录里只有拉丁字体（DejaVuSans）→ 装 Noto CJK / 文泉驿
│   └─ 路径写错 / 容器内不存在 → ls 验证
└─ 换个阅读器还是方块？
    └─ 确认 embedFonts=true（默认），子集是否真的嵌进去了
```

快速自检代码：

```java
File dir = new File("/usr/share/fonts");
System.out.println("目录存在=" + dir.isDirectory());
// 递归列出 ttf/ttc，确认真有中文字体
Files.walk(dir.toPath()).filter(p -> p.toString().matches(".*\\.(ttf|ttc)$"))
     .limit(20).forEach(System.out::println);
```

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| 正文中文全是方块 | 没设 `fontDirectory` 或目录无中文字体 | 设目录 + 确认目录内容 |
| 正文正常，水印是方块 | 水印走的是 `fontTtf` 入参 | 用带 `fontTtf` 的重载 |
| 本机好的，服务器上方块 | 服务器没装中文字体 | Docker 装 Noto CJK / 随应用交付字体 |
| 用了 `simsun.ttc` 不生效 | `.ttc` 解析支持有限 | 换 `simhei.ttf` / `msyh.ttf` |
| 部分生僻字缺失 | 该字体本身没这个字形 | 换字符集更全的字体（Noto Sans CJK） |
| PDF 体积暴涨 | 嵌入了完整字体而非子集 / 多种字体 | 减少使用的字体种类；确认走的是子集嵌入 |
| 中文是乱码而非方块 | 不是字体问题，是**源文件编码** | 见 `officia-troubleshooting` |

## 在测试台里实测

测试台**服务端自动探测系统字体**（就是上面 `Fonts.java` 那套），也允许你**在界面上传自己的 `.ttf`**（优先级高于探测）。
启动日志会打印是否探测到中文字体——这是最快的环境自检方式。

## 相关技能

| 接下来 | 用 |
|---|---|
| 转换本身报错 | `officia-troubleshooting` |
| PDF 水印用法 | `officia-pdf` |
| 部署到容器 | `officia-setup` |
