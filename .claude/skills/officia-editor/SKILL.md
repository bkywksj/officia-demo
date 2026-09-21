---
name: officia-editor
description: |
  用 OfficiaEditor 在自己的网页里做在线编辑：Java 侧 open(docx/doc) → officia-doc/1 JSON、
  save(JSON) → docx/pdf，工作簿侧 openWorkbook（xlsx/xls）/ recalc / saveWorkbook；
  前端产物 officia-editor.js 随 jar 分发，挂到页面上就是一个能打字的 Word / Excel。
  **不需要 Document Server、不需要新进程新端口**——只多引一个 jar。

  触发场景：
  - 要在浏览器里编辑 Word / Excel（在线编辑、网页编辑器、类 ONLYOFFICE / WPS 在线）
  - 已经能转 PDF 了，现在要让用户在页面上改内容再存回 docx / xlsx
  - 问「officia 能不能在线编辑」「要不要另外部署一个文档服务」
  - 前端不知道怎么引 officia-editor.js、怎么挂功能区 / 属性面板 / 网格
  - 编辑器里的公式重算、页数与服务端排版对不对得上

  触发词：在线编辑、网页编辑、编辑器、editor、OfficiaEditor、可编辑、编辑Word、编辑Excel、
  officia-editor.js、mountEditable、mountRibbon、mountWorkbook、功能区、工具栏、属性面板、
  officia-doc、officia-workbook、往返、roundtrip、重算、recalc、ONLYOFFICE、Document Server
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# 在线编辑（`OfficiaEditor` + `officia-editor.js`）

## 概述

Officia.Editor 是**进程内**的在线编辑底座，两半合起来才是完整能力：

| 半边 | 是什么 | 客户怎么拿到 |
|---|---|---|
| **Java 门面** `OfficiaEditor` | 文档 ⇄ JSON 契约的编解码 + 写回 docx/xlsx/pdf | 引 `officia-all` 即有 |
| **前端产物** `officia-editor.js` | 浏览器里的 Canvas 编辑器（排版 / 光标 / IME / 撤销栈 / 功能区） | **随同一个 jar 分发**，按 `/officia-editor/officia-editor.js` 引用 |

> 🔴 **两半都是对外契约**。只讲 Java 那半，客户拿到 JSON 也不知道该怎么把它变成一个能打字的页面。

> 🔴 **版本前提**：`officia-editor` 模块在 **1.1.3 发版之后**才建，`officia-all:1.1.3` 里**没有它**。
> 客户报「import 不到 `OfficiaEditor`」或「`/officia-editor/officia-editor.js` 404」时，
> **先问版本**再排查别的。核实：`unzip -l ~/.m2/repository/plus/ruoyi/officia-all/<版本>/officia-all-<版本>.jar | grep officia-editor`。

**它不是什么**：不是 ONLYOFFICE 那种要单独部署的 Document Server。别人方案里
「转换 / 保存回调」需要的独立服务，在这里就是两次普通方法调用——没有新进程、没有新端口。

---

## 一、Java 侧：六组门面方法

```java
import plus.ruoyi.officia.editor.OfficiaEditor;
import plus.ruoyi.officia.editor.EditorDocument;
import plus.ruoyi.officia.editor.EditorFormat;
```

### 1. 打开文档 → 交给前端的 JSON

```java
EditorDocument doc = OfficiaEditor.open(docxBytes);   // docx / doc 按内容嗅探，不看扩展名
String json = doc.getJson();                          // officia-doc/1 契约，直接回给前端

doc.getSourceFormat();   // "docx" 或 "doc"（按内容嗅探的真实格式）
doc.getBlockCount();     // 块数，给个规模感
doc.getWarnings();       // 解析告警；hasWarnings() 先判一下
```

重载：`open(byte[])`、`open(byte[], ConvertOptions)`（指定字体目录等）、
`open(InputStream)`（**读满但不关闭**，谁开谁关）、`open(File)`。

### 2. 保存：前端交回 JSON → 字节

```java
byte[] docx = OfficiaEditor.save(json, EditorFormat.DOCX);   // 语义 docx，可被 Word 再打开继续编辑
byte[] pdf  = OfficiaEditor.save(json, EditorFormat.PDF);    // 与 OfficiaWords.toPdf 同一条排版渲染链
```

重载：`save(String, EditorFormat)`、`save(String, EditorFormat, ConvertOptions)`（仅 PDF 用得上）、
`save(String, EditorFormat, OutputStream)`（**不关闭**你传进来的流；返回 `void`，不是页数）、
`save(String, EditorFormat, File)`。

`EditorFormat` 只有两个常量：**`DOCX`、`PDF`**。工作簿不走这里，见下面第 4–6 条。

### 3. 逐页出图（可选：给「最终效果」预览用）

```java
List<byte[]> pngs = OfficiaEditor.toImages(json);                 // 每页一张 PNG，按页序
List<byte[]> p2   = OfficiaEditor.toImages(json, options);
```

### 4–6. 工作簿：`openWorkbook` / `recalc` / `saveWorkbook`

```java
String wbJson = OfficiaEditor.openWorkbook(excelBytes);  // xlsx 或 xls；officia-workbook/1 契约的 JSON 文本
String next   = OfficiaEditor.recalc(wbJson);            // 重算全部公式格，返回同契约 JSON
byte[] xlsx   = OfficiaEditor.saveWorkbook(next);        // 写回 xlsx
```

三点要跟客户讲清：

- **返回的是 JSON 文本，不是 `EditorDocument`**。工作簿这一族是 `JSON in → JSON out` 的闭环，
  中间再包一层只带块数的 DTO 没有信息增量。
- **`recalc` 用的就是 Officia.Cells 那 35 个函数的同一个引擎**，所以编辑器里看到的数字与
  `OfficiaCells.toPdf(xlsx)` 算出来的必然一致——浏览器端**没有**另写一套 JS 求值器。
- **`saveWorkbook` 不自动重算**。什么时候算是调用方的节奏问题；要落盘前兜底就自己串：
  `saveWorkbook(recalc(json))`。

算不出来的格写成 `#VALUE!`，**不让整张表失败**；失败原因附在返回 JSON 的根级可选字段
`recalcErrors`（`[{sheet, ref, message}]`，无失败时不出现），界面据此提示「哪张表哪个格为什么算不出来」。
循环引用（`A1=B1, B1=A1`）由求值栈检出并落成错误格，**不会栈溢出**。

---

## 二、前端：`officia-editor.js` 怎么用

### 1. 拿到这个文件（不用你拷贝）

产物打在 `officia-editor.jar` 的 `META-INF/resources/officia-editor/officia-editor.js`——
这是 Servlet 3.0 规范约定的静态资源目录，**Spring Boot / 任何 Servlet 3.0+ 容器会自动把它暴露出来**：

```html
<script src="/officia-editor/officia-editor.js"></script>
```

脚本是 IIFE，加载后挂一个全局 `OfficiaEditor`（`OfficiaEditor.version` 可读版本号）。

> 不是 Servlet 容器（比如自己用 `com.sun.net.httpserver`）就自己映射一条：
> 请求路径 `/officia-editor/*` → classpath 资源 `/META-INF/resources/officia-editor/*`。
> demo 就是这么做的，见 `src/main/java/plus/ruoyi/officia/demo/web/DemoServer.java` 的 `serveStatic`。

### 2. 挂一个能打字的文档

```js
// json 就是 Java 侧 OfficiaEditor.open(...).getJson() 回来的那份
const view = OfficiaEditor.mountEditable(host, json, {
  zoom: 1,                       // 缩放倍率，1 = 100%
  onChange: (state) => refresh(state),   // 每次内容或选区变化回调，供宿主刷新工具栏
});

view.pageCount;      // 前端排版页数
view.fontMisses;     // 字体度量缺口；>0 说明服务端下发的字体表不全
view.renderedCount;  // 已实际绘制的页数（虚拟滚动是否生效看这个）
view.json();         // 当前文档的 officia-doc/1 JSON —— 存盘时把它 POST 回 Java 侧
view.focus(); view.undo(); view.redo(); view.destroy();
```

`OfficiaEditor.mount(...)` 是**只读**版本，`mountEditable` 才可编辑——别挂错。

`onChange` 拿到的 `EditorState` 里有 `pageCount` / `canUndo` / `canRedo` / `editable` /
`canEditStructure` / `selectedText` / `style` / `caret`，工具栏的点亮与置灰全从它来。

### 3. 挂功能区（真工具栏，六标签 145 控件）

```js
const ribbon = OfficiaEditor.mountRibbon(host, {
  surface: 'words',                       // 'words' 或 'cells'
  onCommand: (e) => OfficiaEditor.runWordsCommand(view, e),   // Cells 侧用 runCellsCommand(editor, e)
  onBlocked: (e) => {},                   // 琥珀 / 置灰态被点；不传则只在控件旁提示一次
  onTab: (tab) => {},
  backstage: { title: '合同.docx', sections: ['信息', '导出'], info: [...], exports: [...] },
});

ribbon.setActive('加粗', true);   // 点亮 / 熄灭开关态
ribbon.setTab('插入');
ribbon.reflow();                  // 宿主自己改了容器宽度时重算折叠（窗口 resize 已自动处理）
ribbon.destroy();
```

样式：`OfficiaEditor.RIBBON_CSS`（配 `RIBBON_STYLE_ID`）。要自己渲染就用 `RIBBONS`、
`ribbonHtml` / `panesHtml` / `tabsHtml` / `backstageHtml`。

`runWordsCommand` / `runCellsCommand` **返回 `false` 表示这次点击没有改动文档**——
不要静默吞掉。返回 false 只有三种情况：光标不在可编辑处、选项值解析不出来、
或者这个控件在 `WORDS_UNWIRED` / `CELLS_UNWIRED` / `WORDS_HOST_CAPS` / `CELLS_HOST_CAPS` 里：

```js
const why = OfficiaEditor.WORDS_UNWIRED.get(e.cap);   // Map<控件名, 为什么没接>，可直接念给用户听
```

`*_HOST_CAPS` 是**按设计交给宿主**的那几个（缩放、页面视图、插图要弹文件框、
超链接要弹框问 URL、书签要弹框问名字、Cells 的立即重算要走服务端），不是失败。

宿主接管的两个要点：

```js
// 插图：库不弹文件框，宿主选完把字节交回去
if (e.cap === '图片') { filePicker().then(bytes =>
  view.apply(r => new OfficiaEditor.InsertImage(r.to, bytes, { w: 200 }))); }

// 超链接：库不弹框问 URL。🔴 收到后调 setHyperlink，**不要**自己写
// SetTextStyle({ link })——画布对 st.link 零个引用，只设 link 用户点完画面纹丝不动；
// setHyperlink 连 Word 观感（蓝 HYPERLINK_COLOR 0563C1 + 下划线）一起套，
// 与读 docx 时解析器给的那套一致。不传 uri 也不传 anchor 即取消链接（观感一并撤掉）
if (e.cap === '超链接') { askUrl().then(url =>
  view.apply(r => OfficiaEditor.setHyperlink(r, url))); }
// 内链（文档内书签）：OfficiaEditor.setHyperlink(r, undefined, '第一章')

// 书签：与超链接是一对——内链指向的正是这里建的名字。空名返回 undefined
if (e.cap === '书签') { askName().then(name =>
  view.apply(r => OfficiaEditor.insertBookmark(r, name))); }
```

⚠️ 书签在画布上**按设计不可见**（Word 默认也不显示）。别拿「画面变没变」当它做没做成的判据——
判据是 IR 里多了 anchor 节点、存回 docx 是 `w:bookmarkStart/End`、内链能指到它。

⚠️ 弹框别用 `window.prompt`：它是模态，会阻塞事件循环，样式也不可控。

⚠️ 超链接要求选区**落在同一段正文内**：`view.apply` 拿不到 IR 区间时返回 false。
全选一份带表格的文档会判成"不可编辑"（跨表格/分页符这类非段落块），这是能力边界。

### 4. 挂外壳（右侧属性面板 + 底部状态栏）

```js
const shell = OfficiaEditor.mountWordsShell(panelHost, statusHost, {
  view,
  probe: { serverPages: 6, fontMisses: view.fontMisses },  // 「另一端」才知道的读数
  zoom: 1, onZoom: (z) => remount(z),
  onChange: (key, value) => { dirty = true; },
});
shell.refresh();     // 编辑器每次播报状态时调一次
```

Cells 侧对应 `OfficiaEditor.mountCellsShell(panelHost, statusHost, { editor, probe })`。
样式用 `OfficiaEditor.SHELL_CSS`（配 `SHELL_STYLE_ID`）。

属性面板的标签：Words 是**段落 / 表格 / 页面 / 检查**，Cells 是**单元格 / 工作表 / 页面 / 检查**。
面板**自己不存任何值**，全部从 IR 现算——所以「光标移到别的段落还留着上一段的值」这种失真在结构上就不可能发生。

### 5. 挂工作簿（Cells 网格）

```js
const editor = OfficiaEditor.Cells.mountWorkbook(host, workbookJson, {
  onCellCommitted: (e) => { if (editor.hasFormula) scheduleRecalc(); },
});
editor.view;                 // WorkbookView：选区、活动格、公式栏文本
editor.paint();
editor.hasFormula;           // 有没有公式格——没有就不必为重算跑一趟往返
editor.staleFormulaCount;    // 有公式但还没算出结果的格数；打开时 > 0 就先算一次
editor.applyRecalc(next);    // 只并回计算结果，选区/滚动/正在编辑的那一格都保留
```

🔴 **公式结果不在浏览器里算**：同一个 35 函数引擎只有一份（在服务端），
前端另写一套，「编辑器里的数字与 xlsx→PDF 算出的必然一致」这句承诺就作废了。
所以打开时若 `staleFormulaCount > 0` 要先 `recalc` 一次，之后每次 `onCellCommitted`
再按需重算——**不是「提交的是公式才算」**，改一个常量同样会牵动引用它的公式。

⚠️ **xls 进、xlsx 出**：`openWorkbook` 也收 .xls（未加密），JSON 顶层会多一个
`"src": "xls"`；而写侧只有 xlsx，`saveWorkbook` 存回来的一定是 xlsx。
拿这个字段告诉用户一声，别默默替人改了文件格式。

Cells 的全部对外面都在 `OfficiaEditor.Cells.*` 命名空间下（`mountWorkbook`、`WorkbookView`、
`PutCell` / `MergeCells` / `SetFreeze` 等命令、`formatNumber` 等数字格式函数），
**不平摊到顶层**：Words 与 Cells 是两份并列的契约，两边都有「合并单元格」和「设列宽」，
说的却是不同的东西。共用的只有编辑命令的**机制**（`Command` / `CommandStack`），那两个在顶层。

---

## 三、一个最小闭环（Spring Boot Controller + 页面）

```java
@PostMapping("/doc/open")
public Map<String, Object> open(@RequestParam MultipartFile file) throws IOException {
    EditorDocument doc = OfficiaEditor.open(file.getBytes());
    return Map.of("json", doc.getJson(), "blocks", doc.getBlockCount());
}

@PostMapping(value = "/doc/save", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
public byte[] save(@RequestBody String json, @RequestParam(defaultValue = "docx") String format) {
    EditorFormat target = "pdf".equalsIgnoreCase(format) ? EditorFormat.PDF : EditorFormat.DOCX;
    return OfficiaEditor.save(json, target);
}
```

```html
<div id="host"></div>
<script src="/officia-editor/officia-editor.js"></script>
<script>
  const json = await (await fetch('/doc/open', { method: 'POST', body: fd })).json();
  const view = OfficiaEditor.mountEditable(document.getElementById('host'), json.json);
  // 存盘：把前端当前 JSON 交回去
  document.getElementById('save').onclick = () =>
    fetch('/doc/save?format=docx', { method: 'POST', body: view.json() });
</script>
```

Spring 集成的通用坑（上传大小、响应头、下载文件名）见 `officia-spring-integration`。

---

## 四、能力边界（回答"能不能"时照实说）

### 工具栏三态就是能力承诺

功能区共 **145 个控件**（Words **78** / Cells **67**），每个控件都有一个**机检过的**状态：

| 态 | 含义 | 数量 |
|---|---|---|
| 正常（可点） | 模型里有这个东西，且**保存回目标格式时语义仍在** | Words 53 / Cells 47 |
| 琥珀（只读受限） | 只读透传 / 不属于文档内容 / 可编辑但导出时降级 | Words 14 / Cells 0 |
| 置灰（不做） | 模型层就没有这个东西 | Words 11 / Cells 20 |

真相源是 `../officia/docs/design/online-editor/toolbar-capability.tsv`，由 officia 的
`ToolbarCapabilityTest` 逐行机检：反射 IR、反射工作簿模型、真跑一次写出器看产物 XML、扫前端符号。
**标了状态不等于通过——状态必须与当前代码一致，对不上就红。**

### 明确不做（本轮）

| 场景 | 状态 | 说明 |
|---|---|---|
| **实时协同**（多人同时编辑同一份） | ❌ 明确不做 | 必然要服务与长连接。命令层已设计成可序列化操作日志，为将来留门 |
| **修订 / 批注 / 脚注的编辑** | ⚠️ 只读透传 | 打开时保留、存回时不丢，但编辑不了 |
| **浮动对象拖拽、图文环绕的编辑** | ⚠️ 只读 | 同上 |
| **演示文稿（pptx）在线编辑** | ❌ 不做 | 编辑器只覆盖 Words 与 Cells 两条线 |
| 前端引入 UI 框架 | ❌ 不做 | 零依赖同样适用于前端产物 |

### 已知缺口（想接却暂时接不上的控件）

| 控件 | 为什么 |
|---|---|
| 下标 | 前端 `VerticalAlign` 目前只有 `NONE` / `SUPERSCRIPT`，写 `SUBSCRIPT` 会产出非法 JSON |
| 单元格边距 | 前端 `CellPropsPatch` 不含 `marT/marL/marB/marR` |
| 标题行跨页重复 | 行级属性 `TableRow.header` 没有对应命令，`SetCellProps` 只改到格级 |
| Cells 的格式刷 | 要先记住源格、下一次点击才套用，是两步交互，工具栏这一层不持有该状态 |

代码里这几条写在 `WORDS_UNWIRED` / `CELLS_UNWIRED` 里且**能被前端读出来**，所以可以直接把原因念给用户听。

### 一个容易被问到的字节差异

`OfficiaEditor.save(json, PDF)` 与 `OfficiaWords.toPdf(docx)` 走**同一条排版渲染链**，
从同一份 IR 出发必然产出相同字节。但 `toPdf(docx)` 会额外把 **docx 内嵌的字体**叠加进字体表，
而 `save` 只拿得到 JSON、拿不到原始 docx 包，用的是基础字体表——所以对**内嵌了字体的文档**，
两者字节可以不同。**这是信息量差异，不是缺陷**。

---

## 五、先在测试台上看一眼

demo 测试台有完整的编辑器面板与 6 个端点，改代码前先跑一遍最省事：

| 端点 | 干什么 |
|---|---|
| `POST /api/editor/open?id=` | docx/doc → `officia-doc/1` JSON，附服务端排版页数 |
| `POST /api/editor/save?format=docx\|pdf` | JSON → docx / pdf |
| `POST /api/editor/roundtrip?id=` | 一键保真度验收：open → save(docx) → 再 open，比两轮 JSON，给 `lossless` 判定 |
| `POST /api/editor/workbook/open?id=` | xlsx → `officia-workbook/1` JSON |
| `POST /api/editor/workbook/recalc` | 整表重算，`hasErrors` 标出有没有算不出来的格 |
| `POST /api/editor/workbook/save` | JSON → xlsx |

启动方式与面板对应关系见 `officia-testbench`。前端怎么把这些串起来，
直接读 `src/main/resources/web/index.html` 里 `edEnsureLib` / `edRender` /
`edMountRibbon` / `edMountShell` 那几个函数——**那是本技能所有前端示例的出处**。

---

## 常见错误

**错误 1：以为要另外部署一个文档服务**
```
❌ "在线编辑得先起一个 Document Server / 转换服务"
✅ 引 officia-all 就够了：open / save 是两次普通方法调用，没有新进程、没有新端口
```

**错误 2：挂了只读视图还问为什么打不了字**
```js
// ❌ mount 是只读渲染
const view = OfficiaEditor.mount(host, json);
// ✅ 可编辑的是 mountEditable
const view = OfficiaEditor.mountEditable(host, json, { onChange });
```

**错误 3：用 save(json, XLSX) 存工作簿**
```java
// ❌ EditorFormat 只有 DOCX / PDF，没有 XLSX
byte[] x = OfficiaEditor.save(wbJson, EditorFormat.XLSX);
// ✅ 工作簿是另一条闭环
byte[] x = OfficiaEditor.saveWorkbook(wbJson);
```

**错误 4：以为 saveWorkbook 会顺手重算**
```java
// ❌ 直接存，公式格还是打开时的缓存值
byte[] xlsx = OfficiaEditor.saveWorkbook(json);
// ✅ 要兜底就显式串一下（正常路径是前端 debounce 调 recalc，存屏幕上确认过的那一份）
byte[] xlsx = OfficiaEditor.saveWorkbook(OfficiaEditor.recalc(json));
```

**错误 5：把 `runWordsCommand` 返回的 false 当成功**
```js
// ❌ 点了没反应，用户以为改了
OfficiaEditor.runWordsCommand(view, e);
// ✅ false = 没改文档，要么是宿主该接手（HOST_CAPS），要么如实告诉用户为什么
if (!OfficiaEditor.runWordsCommand(view, e)) {
  const why = OfficiaEditor.WORDS_UNWIRED.get(e.cap);
  if (why) toast(e.cap + '：' + why);
}
```

## 相关技能

| 我接下来要 | 用 |
|---|---|
| 引依赖 / 确认坐标 | `officia-setup` |
| 存成 PDF 后中文变方块 | `officia-chinese-font` |
| 接进 Spring Boot 的上传下载 | `officia-spring-integration` |
| 编辑器里的公式与 Excel 对不上 | `officia-cells` |
| 输出带水印 / 被限页 | `officia-license` |
| 在测试台上实测 | `officia-testbench` |
| 报错了 | `officia-troubleshooting` |
