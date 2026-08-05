---
name: officia-chinese-font
description: |
  解决中文显示问题：PDF 里中文变方块 / 空白 / 乱码，以及「字都在但断行分页与 Windows 不同」。
  讲清两条不同的字体入口（转换类用 ConvertOptions.fontDirectory，PDF 水印页码用 fontTtf 字节），
  .ttc 的限制、Docker/Linux 装字体（须含衬线+黑体两族）、CID 子集嵌入、
  字体替换诊断（setReportFontSubstitutions）。

  触发场景：
  - 转出来的 PDF 中文是方块 / 豆腐块 / 空白
  - PDF 水印或页码写中文没显示
  - Linux / Docker 环境没中文字体
  - 不知道 fontDirectory 和 fontTtf 有什么区别
  - 用了 .ttc 字体不生效
  - 字都在但版式变了：断行位置、分页与 Windows 上不一致
  - 标题与正文字面看起来一样，多个字体塌缩成一个
  - 想知道某个字体实际被换成了什么

  触发词：中文、字体、方块、豆腐块、乱码、显示不出来、缺字、TTF、ttc、fontDirectory、fontTtf、字体目录、嵌入字体、CID、宋体、黑体、微软雅黑、仿宋、楷体、断行不一致、版式漂移、字体替换、字体回退、setReportFontSubstitutions、FontSubstitution、Noto、字体塌缩
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
/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc            文泉驿正黑（glyf ✓）
/usr/share/fonts/truetype/wqy/wqy-microhei.ttc          文泉驿微米黑（glyf ✓）
/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf         （仅拉丁，无中文）
/Library/Fonts/Arial.ttf                                 （macOS，仅拉丁）
```

> 🔴 `/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc`（`fonts-noto-cjk` 装的）
> 与 macOS 的 `PingFang.ttc` 都是 **CFF 轮廓**，officia 会跳过——见下方「不要装
> `fonts-noto-cjk`」一节。

## 可直接复用的字体探测代码

本 demo 的 `Fonts.java` 就是一份现成实现，可以抄进你的项目：

🔴 **只判"文件存不存在"是不够的** —— 线上就是这么翻的车：容器里路径确实有字体，
启动横幅报"中文水印可用"，实际调用要么抛 `字体无 glyf/loca`，要么中文静默画成空白。
**必须验轮廓 + 验字形**：

```java
/** 取一份可用的中文 TTF 字节；无则 null。探测一次后缓存复用。 */
static synchronized byte[] systemCjk() {
    String[] candidates = {
        "C:/Windows/Fonts/simhei.ttf",
        "C:/Windows/Fonts/msyh.ttf",
        "C:/Windows/Fonts/simsun.ttc",
        // Linux：文泉驿是 glyf 轮廓，officia 能用；
        // NotoSansCJK-Regular.ttc / PingFang.ttc 是 CFF，放在后面靠校验拒掉即可
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
        "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
    };
    for (String p : candidates) {
        File f = new File(p);
        if (!f.isFile() || f.length() == 0) {
            continue;
        }
        try {
            byte[] data = Files.readAllBytes(f.toPath());
            if (usableForCjk(data)) {     // ← 关键：别省这一步
                return data;
            }
        } catch (Exception ignore) { }    // 读不了 / 解析不了就试下一个
    }
    return null;   // 调用方退回不带字体的重载（仅 ASCII）
}

/** 能不能用来画中文：两个条件缺一不可。 */
static boolean usableForCjk(byte[] data) {
    try {
        TrueTypeFont f = new TrueTypeFont(data);          // plus.ruoyi.officia.engine.font
        return f.getTable("glyf") != null                 // 拦 CFF/OTTO：否则子集器抛异常
            && f.hasGlyph('中') && f.hasGlyph('国');       // 拦纯拉丁：否则中文静默变空白
    } catch (RuntimeException e) {
        return false;
    }
}
```

两个条件各拦一种事故，症状完全不同：

| 漏掉哪个校验 | 后果 |
|---|---|
| 没验 `glyf` | 拿到 CFF 字体（`.otf` / Noto CJK `.ttc`）→ **抛异常**，调用直接失败 |
| 没验字形 | 拿到纯拉丁字体（DejaVu/Arial）→ **不报错**，中文画成空白，更难查 |

> 更稳的做法：**把字体文件随应用一起交付**（放进 `src/main/resources/fonts/`），从 classpath 读取，不依赖运行环境装了什么。
> ⚠️ 两个约束都要满足：**授权允许分发**（Apache 2.0 / SIL OFL 可以；Windows 中文字体不可以），且**轮廓是 TrueType glyf**（思源黑体 / Noto CJK 的官方版是 CFF，授权没问题但 officia 用不了）。文泉驿系列两者都满足。

## Linux / Docker 环境装中文字体

### 先问一句：真的需要装吗？

officia **内置了兜底中文字体**（随 jar 分发），Linux 裸镜像开箱即用：不出豆腐块、
文字可复制可检索，而且**断行与分页与 Windows 一致**——因为宋体/仿宋/楷体/黑体的度量
已由常数规则复现，版式不依赖装了什么字体。实测某 68 页招标文：
无字体环境断行一致率 89.2%、页数 68，与有字体环境**完全相同**。

缺的只有**字形长相**（内置的是无衬线黑体，宋体是衬线体；宋/仿/楷/黑会塌缩成同一种字面）。
所以「装字体」是为了字形更接近原稿，不是为了让转换能跑或版式正确。

### 要装的话，装这些

```dockerfile
FROM eclipse-temurin:17-jre

# Debian / Ubuntu —— 文泉驿是 TrueType glyf 轮廓，officia 能用
RUN apt-get update && apt-get install -y --no-install-recommends \
      fonts-wqy-zenhei fonts-wqy-microhei fontconfig \
    && fc-cache -f && rm -rf /var/lib/apt/lists/*

ENV OFFICIA_FONTS_DIR=/usr/share/fonts
```

`OFFICIA_FONTS_DIR` 环境变量（或 `-Dofficia.fonts.dir=...`）零改码生效；
也可以在代码里 `setFontDirectory("/usr/share/fonts")`。

### 不要装 `fonts-noto-cjk` —— 对 officia 零收益

该包装的是 `/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc`，
其 sfnt 版本是 **`OTTO`（CFF/PostScript 轮廓）**；officia 的 PDF 子集器**只支持
TrueType `glyf` 轮廓**，加载时直接跳过 CFF。装了它，引擎一个字体都拿不到。

同理不可用：思源黑体/宋体的 `.otf` 官方版、Noto CJK 的 OTF/OTC 全系。
**判据：装之前确认是 `.ttf`/`.ttc` 且为 glyf 轮廓。**

### 另一个坑：可变字体只取默认实例

officia 不解析 `gvar` 增量，可变字体（含 `fvar` 表）只渲染**默认实例**。
Google Fonts 的 `NotoSansSC[wght].ttf` 等 CJK 可变字体 `wght` 轴 default=100，
默认实例是 **Thin**——挂上去会发现中文笔画极细、整体发虚。
挂载字体请选**静态、常规字重**的版本。

### 字面塌缩怎么看

用 `setReportFontSubstitutions(true)`：多个 `requestedFamily` 指向同一个
`resolvedFamily` 就是塌缩了。想分开字面，就挂载对应的衬线/黑体两族字体。

### ⚠️ 不要把 Windows 中文字体拷进镜像

微软字体许可禁止再分发（*"you may not copy them to other computers or servers"*），
宋体/黑体/仿宋版权属北京中易，仅授权随 Windows 分发。把 `simsun.ttc` 打进 Docker 镜像
有法律风险。合规做法：用文泉驿（Apache 2.0 / GPL+字体例外，且是 glyf 轮廓），或为服务器单独购买字体授权。

> 注意区分两件事：**把文档里的字体嵌进导出的 PDF** 是许可的（officia 会先校验 OS/2 的
> `fsType` 嵌入权限位）；**把字体文件本身拷到服务器**才是禁止的。

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

## 🔴 另一类问题：字都在、但版式不对

上面讲的都是「看得见的缺字」（方块 / 空白）。还有一类更隐蔽：
**页面上每个字都在、都不是方块，但断行位置和 Windows 上不一样**，段落多出一行、表格被挤换行。

原因是**回退后照用了新字体的度量**。宋体/新宋体/仿宋/楷体/黑体是严格的 duospaced
（西文恒 0.5 em、CJK 恒 1.0 em），而 Noto Sans CJK 的西文是**比例宽度**
（W=0.889、i=0.245 em…）。CJK 侧两者都是 1.0 em 对得上，西文侧却每个字符都偏一点、
沿行累积——中文公文里的编号、年月日、括号内英文缩写全部踩中。

**officia 已自动处理这一层**：声明的是中易系字体时，字形用回退字体渲染，
但宽度按原字体的常数规则算，断行与 Windows 逐字符一致。**无需配置**。
微软雅黑不适用（它本就不是 duospaced），属于必须真有字体的情形。

### 用诊断开关查"到底换成了什么"

```java
ConvertResult r = OfficiaWords.convert(docx,
    ConvertOptions.defaults().setReportFontSubstitutions(true));

for (FontSubstitution s : r.getFontSubstitutions()) {
    System.out.println(s.describe());
    // 字体「宋体」字体不存在，实际使用「noto sans sc」
}
```

`FontSubstitution` 的字段：`requestedFamily`（文档要什么）、`bold` / `italic`（哪个字面）、
`resolvedFamily`（实际用了什么）、`reason`。

`reason` 两种，**处置方式不同、别混为一谈**：

| Reason | 含义 | 怎么办 |
|---|---|---|
| `FAMILY_NOT_FOUND` | 整个字体这台机器没有 | 装字体 / 配 `fontDirectory` |
| `GLYPH_NOT_IN_FAMILY` | 字体在，只是不含这个字（如拉丁字体遇到汉字） | 正常的字形级回退，通常无需处理 |

记录已按「族名+字重+倾斜」去重，4 万字文档也只有几条。默认关闭（有开销），排查时才开。

## 排查流程

```
中文显示不对
├─ 是转换出来的正文？
│   └─ 设 ConvertOptions.setFontDirectory(...) → 确认该目录下真有中文 TTF
├─ 是 PDF 水印 / 页码？
│   └─ 用带 fontTtf 的重载，传中文 TTF 字节
├─ 设了目录还是方块？
│   ├─ 目录里只有 .ttc → 换纯 .ttf（simhei.ttf / msyh.ttf）
│   ├─ 目录里只有拉丁字体（DejaVuSans）→ 装文泉驿（glyf；Noto CJK 是 CFF、用不了）
│   └─ 路径写错 / 容器内不存在 → ls 验证
├─ 字都在但断行/分页与 Windows 不同？
│   └─ 开 setReportFontSubstitutions(true) 看实际用了什么字体（见上一节）
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
| 字都在，但断行 / 分页与 Windows 不同 | 回退字体的**度量**与原字体不同 | 中易系（宋体/仿宋/楷体/黑体）已自动按常数度量补偿；其它字体需装真字体。开 `setReportFontSubstitutions(true)` 确认换成了什么 |
| 标题与正文字面一样、看不出区别 | 只有一个字体可用，多个字面塌缩到同一字体 | 挂载衬线 + 黑体两族的 glyf 字体（**不是** `fonts-noto-cjk`，它是 CFF 轮廓、officia 跳过） |

## 在测试台里实测

测试台**服务端自动探测系统字体**（就是上面 `Fonts.java` 那套），也允许你**在界面上传自己的 `.ttf`**（优先级高于探测）。
启动日志会打印是否探测到中文字体——这是最快的环境自检方式。

## 相关技能

| 接下来 | 用 |
|---|---|
| 转换本身报错 | `officia-troubleshooting` |
| PDF 水印用法 | `officia-pdf` |
| 部署到容器 | `officia-setup` |
