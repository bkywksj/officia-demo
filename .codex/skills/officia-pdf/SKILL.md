---
name: officia-pdf
description: |
  PDF 工具箱（OfficiaPdf）：合并·拆分·抽页·删页·旋转·水印·页码·抽文字·抽图片·加密·数字签名·元数据·页面尺寸，
  以及多步操作的链式编辑器 PdfEditor（一次解析、少一轮序列化）。

  触发场景：
  - 合并 / 拆分 PDF、抽取或删除某几页、旋转页面
  - 给 PDF 加水印、加页码
  - 从 PDF 里提取文字或图片
  - 给 PDF 加密码、读加密 PDF
  - 给 PDF 加数字签名、防篡改、验证文档有没有被改过、审批签字留痕
  - 读 PDF 页数 / 版本 / 标题作者等元数据
  - 要连续做好几步 PDF 操作

  触发词：PDF、合并、拆分、抽页、删页、旋转、水印、页码、抽文字、提取文字、抽图片、加密、解密、口令、密码、AES、元数据、页数、PdfEditor、数字签名、签名、防篡改、篡改、验签、证书、p12、pfx、PKCS12、签字、审批留痕、溯源、PDF转图片、toImages、扫描件转图片、一页一张、在线预览
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# PDF 工具箱（`OfficiaPdf`）

## 概述

`OfficiaPdf` 是纯 PDF 操作入口（不负责"别的格式转 PDF"，那是 Words/Cells/Slides/Imaging 的活）。
两种用法：**单次静态方法**，或**链式 `PdfEditor`**（多步操作首选）。

另外它还提供**反向转换 `toWord()`**：把电子版 PDF 转成可编辑的 Word（见第七节），
以及**数字签名**：给 PDF 加上阅读器打开即可见的防篡改标记（见第五节）。

## 一、读取 / 信息

```java
import plus.ruoyi.officia.pdf.OfficiaPdf;

int    pages = OfficiaPdf.pageCount(pdf);
int    pages = OfficiaPdf.pageCount(pdf, "口令");        // 加密 PDF
String ver   = OfficiaPdf.version(pdf);                  // 如 "1.7"
boolean enc  = OfficiaPdf.isEncrypted(pdf);
List<float[]> sizes = OfficiaPdf.pageSizes(pdf);         // 每页尺寸
```

### 元数据

```java
PdfMetadata md = OfficiaPdf.metadata(pdf);
PdfMetadata md = OfficiaPdf.metadata(pdf, "口令");
md.getTitle(); md.getAuthor(); md.getSubject(); md.getKeywords();
md.getCreator(); md.getProducer(); md.getCreationDate(); md.getModDate();
md.get("自定义键");  md.all();   // Map<String,String>

byte[] out = OfficiaPdf.setMetadata(pdf, Map.of("Title", "月度报表", "Author", "财务部"));
```

### 提取内容

```java
String text = OfficiaPdf.extractText(pdf);
String text = OfficiaPdf.extractText(pdf, "口令");           // 加密 PDF
String text = OfficiaPdf.extractText(inputStream);
String text = OfficiaPdf.extractText(new File("in.pdf"));
List<String> byPage = OfficiaPdf.extractTextByPage(pdf);     // 逐页文本
List<byte[]> images = OfficiaPdf.extractImages(pdf);         // 内嵌图片
```

> ℹ️ **老 PDF 也读得了**：流过滤器覆盖 `FlateDecode` / `LZWDecode` / `ASCII85Decode` /
> `ASCIIHexDecode` / `RunLengthDecode`。**LZW 对 1990 年代前后的 PDF 很关键**——
> `FlateDecode` 到 PDF 1.2 才引入，更早的生成器只有 LZW 可用。
> 实测一份 1988 年的 ITU-T 规范（PDF 1.2，正文全是 LZW）可正常抽文字与转图片。

## 二、页面操作

```java
byte[] merged = OfficiaPdf.merge(List.of(pdfA, pdfB, pdfC));       // 合并
OfficiaPdf.merge(List.of(pdfA, pdfB), outputStream);               // 合并直写流
OfficiaPdf.mergeToFile(List.of(pdfA, pdfB), new File("out.pdf"));  // 合并直写文件

List<byte[]> pages = OfficiaPdf.split(pdf);                        // 拆成单页

byte[] some = OfficiaPdf.extractPages(pdf, 0, 2, 4);               // 抽页（0-based）
byte[] rest = OfficiaPdf.removePages(pdf, 1, 3);                   // 删页
byte[] rot  = OfficiaPdf.rotate(pdf, 90);                          // 旋转（度）
```

> 页码索引是 **0-based**（第 1 页 = `0`）。

## 三、水印与页码

```java
byte[] wm = OfficiaPdf.watermark(pdf, "内部资料");
byte[] wm = OfficiaPdf.watermark(pdf, "内部资料", fontTtfBytes);   // 中文必须传 TTF

byte[] pn = OfficiaPdf.addPageNumbers(pdf);
byte[] pn = OfficiaPdf.addPageNumbers(pdf, "第 %d 页");
byte[] pn = OfficiaPdf.addPageNumbers(pdf, "第 %d 页 / 共 %d 页", fontTtfBytes);
```

> 🔴 **中文水印 / 中文页码必须传 `fontTtf`**——PDF 内置字体没有中文字形。见 `officia-chinese-font`（含 demo 里自动探测系统字体的做法）。

## 四、加密

```java
byte[] enc = OfficiaPdf.encrypt(pdf, "userPwd", "ownerPwd");             // 默认 RC4-128
byte[] enc = OfficiaPdf.encrypt(pdf, "userPwd", "ownerPwd", 40);         // RC4-40  (/V1 /R2)
byte[] enc = OfficiaPdf.encrypt(pdf, "userPwd", "ownerPwd", 128);        // RC4-128 (/V2 /R3)
byte[] enc = OfficiaPdf.encrypt(pdf, "userPwd", "ownerPwd", 256);        // AES-256 (/V5 /R6)
byte[] enc = OfficiaPdf.encryptAes256(pdf, "userPwd", "ownerPwd");       // 同上，推荐
```

| `bits` | 算法 | 说明 |
|---|---|---|
| 40 | RC4-40（/V1 /R2） | 兼容老阅读器，**强度弱** |
| 128 | RC4-128（/V2 /R3） | `encrypt(3 参)` 的默认 |
| **256** | **AES-256（AESV3 /V5 /R6）** | **推荐**——现代阅读器通用，强于 RC4 |

> ⚠️ `bits` **只接受 40 / 128 / 256**，其它值抛 `OfficiaException`。别指望传 64 会得到"64 位加密"。

口令语义：

- `userPassword`：**打开所需**的口令。空串 = 无口令可打开，但内容仍加密
- `ownerPassword`：权限口令，**可空**，空则等同 user
- **两个口令都能用于打开文档**（读取与解密时任传其一即可），RC4 与 AES-256 一致

### 解密：加密文档要编辑必须先解密

加密 PDF 只能读（文本/元数据/页数），**不能直接编辑**——水印、页码、合并、删页都要先解密：

```java
byte[] plain = OfficiaPdf.decrypt(enc, "userPwd");        // 导出未加密副本（owner 口令同样可用）
byte[] out   = OfficiaPdf.edit(enc, "userPwd")            // 或直接带口令进入链式编辑
        .keepPages(0, 1)
        .watermark("REVIEWED")
        .encryptAes256("newPwd", "newOwner")              // 需要的话换口令重新加密
        .toBytes();
```

`decrypt` 对未加密文档是安全的恒等操作（口令参数被忽略），所以"不确定来源是否加密"时可以无脑先调它。

## 五、数字签名（防篡改）

给 PDF 加**标准数字签名**（PDF32000 §12.8，`adbe.pkcs7.detached`）。签完的文档在
Adobe Acrobat 等阅读器里**打开即显示防篡改状态**，不需要客户额外装任何工具：

| 文档状态 | 阅读器显示 |
|---|---|
| 未被改动 | ✅ **「自应用本签名以来，"文档"未被修改」** |
| 改动过哪怕一个字节 | ❌ **「自应用"签名"以来，"文档"已被更改或损坏」** |

签署人、签署时间、签署原因显示在**签名面板**，多人依次签字时逐条列出——
这就是"审批日志落到 PDF 上"的标准载体，不用另造私有格式。

```java
import plus.ruoyi.officia.pdf.OfficiaPdf;
import plus.ruoyi.officia.pdf.sign.KeyMaterial;
import plus.ruoyi.officia.pdf.sign.SignOptions;

// ① 拿到签名身份（两条路，见下）
KeyMaterial id = KeyMaterial.fromPkcs12(p12Bytes, "口令");

// ② 签名
byte[] signed = OfficiaPdf.sign(pdf, id);                       // 用默认选项
byte[] signed = OfficiaPdf.sign(pdf, id, SignOptions.defaults()
        .name("张三")
        .reason("部门经理审批通过")
        .location("北京·财务部"));
```

### 签名身份 `KeyMaterial`：两条来源

```java
// A. 调用方已有企业证书（生产首选）
KeyMaterial id = KeyMaterial.fromPkcs12(p12Bytes, "口令");
KeyMaterial id = KeyMaterial.fromPkcs12(p12Bytes, "口令", "别名");   // 容器内多条目时指定
KeyMaterial id = KeyMaterial.fromPkcs12(new File("sign.p12"), "口令");
KeyMaterial id = KeyMaterial.fromPkcs12(inputStream, "口令");

// B. 现场生成自签名身份（内部审计场景 / 快速试用）
KeyMaterial id = KeyMaterial.selfSigned("张三", "某某公司", 3650);   // CN、O、有效期天数

// C. 用自建的私有 CA 签发（内网统一签发，见下方「私有 CA」小节）
KeyMaterial id = ca.issue("张三", "研发部", 365);
```

`fromPkcs12` 不指定别名时取**第一个带私钥的条目**。CN 支持中文。

> 🔴 **`.p12` 口令必须是 ASCII 字符**。JDK 的 PKCS#12 实现不接受非 ASCII 口令，
> 用中文口令会抛 `UnrecoverableKeyException: Password is not ASCII`。
> 这是 JDK 的限制不是 officia 的，但国内用户习惯用中文密码，很容易撞上——
> 向 CA 申请证书、或自己导出 `.p12` 时就该注意。

> ℹ️ **统一社会信用代码**放在证书主体的 `serialNumber` 字段（OID 2.5.4.5），这是国内 CA 的惯例。
> 自建 CA 时用 `createRoot(cn, o, 统一社会信用代码, days)` 与 `issue(cn, o, 统一社会信用代码, days)`
> 这两个重载写入。（欧盟 eIDAS 用的是 organizationIdentifier 2.5.4.97，国内验签方认前者。）

> ℹ️ **证书链会整条嵌入签名**。CA 签发的证书通常是「你的证书 → 中间 CA → 根 CA」，
> officia 取 `.p12` 里的完整链一并写进 PKCS#7——只送签署人证书的话，验证方本地
> 若没有那张中间 CA，就接不到受信任的根，**正版证书也会被判"身份未知"**。
> 所以从 CA 拿证书时，记得要的是**含完整链的 `.p12`**，不是单张证书文件。

### 🔴 关于"不用 CA"——这不是妥协，是标准做法

数字签名解决两个**互相独立**的问题：

| 问题 | 靠什么 | 需要 CA 吗 |
|---|---|---|
| 文档有没有被改过 | 哈希 + 私钥签名（纯数学） | ❌ **完全不需要** |
| 签字的人是不是他自称的人 | 证书信任链 | ✅ 需要信任锚 |

**CA 只解决第二个，一点也不参与第一个。** 所以自签名证书的防篡改能力**不打折**，
差别只有一处：阅读器会提示"签署人身份未知"（黄标），但下面那行
"文档未被修改"照样是绿的。把根证书导入阅读器的受信任列表即可消除黄标。

> ⚖️ **法律边界（务必如实告知客户）**：我国《电子签名法》的"可靠电子签名"要求
> 第三方认证机构签发的证书。自签名方案在**内部审计、溯源追责**上完全有效；
> 若涉及对外合同、可能上法庭举证，**必须使用有资质 CA 签发的证书**。

**要换成 CA 证书时，代码一行都不用改**——客户去买一张证书（`.p12`/`.pfx`），
把 `KeyMaterial.selfSigned(...)` 换成 `KeyMaterial.fromPkcs12(证书, 口令)` 即可。
CA 证书与自签名证书在 PKCS#7 层面完全一样，区别只在证书自己的签发者是谁。

### 客户问"证书去哪弄"时怎么答

三档方案，**防篡改能力完全相同**，差别只在"身份可信到什么范围"：

| 方案 | 验证方看到 | 适用 | 成本 |
|---|---|---|---|
| 自签名（`selfSigned`） | ⚠️ 身份未知 + ✅ 未被修改 | 试用、内部溯源 | 0 |
| **私有 CA**（自建根 + 内网分发） | ✅ 绿勾（**限装了根证书的机器**） | 企业内网审批 | 一次性搭建 |
| 公共 CA 证书（向持牌机构买） | ✅ 绿勾（**谁打开都是**） | 对外合同、法律举证 | 年费 |

🔴 **证书必须由签署主体自己申请**——CA 要做实名认证（企业验营业执照与对公账户），
证书上的 `CN=某某公司` 由此而来。**谁签字就得谁去申请**，软件供应商代办不了这步，
如同公章必须公司自己去刻。

**购买时两个常见错误**（客户容易踩，提前提醒）：

1. **买错类型**——要的是「**文档签名证书**」/「电子签章证书」，**不是 SSL/HTTPS 证书**
2. **忽略 AATL**——普通 CA 证书 Adobe 仍显示黄标（它不认识这家 CA）；
   只有 **AATL（Adobe Approved Trust List）成员**签发的证书才**直接绿勾、验证方零操作**。
   让客户直接问 CA 销售"贵司在不在 Adobe 的 AATL 名单内"

> 持牌机构以**工信部《电子认证服务许可证》公示名单**为准。
> ⚠️ 不要凭记忆给客户报机构名或价格——资质名单有增有撤、报价随产品变，
> 让客户以官网当期信息为准。

**🔴 别一上来就让客户去买证书。** 内部审批场景（三级会签这类）多数用私有 CA 就够了：
自建根证书 + 域策略推送到内网机器，内部全绿勾且无年费；代价是这份信任不出内网。
推荐路径：**自签名跑通流程 → 内网上线用私有 CA → 确有对外举证需求才买公共 CA 证书**。

### 🔴 签名必须是最后一步

签完之后对字节的**任何**改动都会让签名失效——加水印、加页码、加密、合并、再编辑，全都不行。

```java
// ✅ 正确顺序：转换 → 加工 → 加密 → 签名
byte[] out = OfficiaPdf.edit(pdf)
        .watermark("内部资料", font)
        .encryptAes256("open", "owner")
        .toBytes();
byte[] signed = OfficiaPdf.sign(out, id);      // 签名放最后，独立调用

// ❌ 错误：签完再动
byte[] bad = OfficiaPdf.watermark(OfficiaPdf.sign(pdf, id), "水印");  // 签名当场作废
```

正因如此，**`sign` 刻意不做成 `PdfEditor` 的链式方法**——链式每一步都会重写全文，
签名放进中段必然失效。它只有静态方法形式。

### 多人依次签字（已支持）

给已签名的 PDF 再签，会**自动转走增量更新**（PDF32000 §7.5.6）：原文件字节一个都不动，
新签名只追加到尾部——所以**前面每个人的签名都保持有效**。

```java
byte[] s1 = OfficiaPdf.sign(pdf, zhangSan, SignOptions.defaults()
        .name("张三").reason("部门经理审批").fieldName("Signature1"));
byte[] s2 = OfficiaPdf.sign(s1, liSi, SignOptions.defaults()
        .name("李四").reason("财务复核").fieldName("Signature2"));
byte[] s3 = OfficiaPdf.sign(s2, wangWu, SignOptions.defaults()
        .name("王五").reason("总经理批准").fieldName("Signature3"));
```

Adobe 签名面板会列出三条「修订版 1/2/3」，各自显示签署人、时间、原因与"文档未被修改"。

> 🔴 **每个人的 `fieldName` 必须不同**（默认都是 `Signature1`）。重名会导致表单域冲突，
> 阅读器可能只认出其中一个签名。建议按签署顺序编号，或用业务上的角色名。

> ℹ️ 每次加签都会追加一个完整的增量段（约 17 KB），文件随签字人数线性增长——这是
> 增量更新的固有代价，也是"前签不被破坏"的前提，无法优化掉。

### 私有 CA：内网统一签发（`CertAuthority`）

自建一个根 CA，把根证书装进内网各机器，之后**内网任何人打开都是绿勾、且没有年费**。
这是"消除身份未知提示"里成本最低的一条路。

```java
import plus.ruoyi.officia.pdf.sign.CertAuthority;

// ① 建根 CA（一次性，产物要离线妥善保管）
CertAuthority ca = CertAuthority.createRoot("某某公司内部CA", "某某公司", 3650);
byte[] caP12 = ca.exportPkcs12("强口令");         // 离线保存，日后 CertAuthority.load 恢复

// ② 导出根证书，分发到内网各机器
byte[] rootCer = ca.exportRootCertificate();      // 写成 .cer 文件

// ③ 给每个签署人签发证书（自带完整链，可直接用于签名）
KeyMaterial zhang = ca.issue("张三", "研发部", 365);
byte[] signed = OfficiaPdf.sign(pdf, zhang, SignOptions.defaults().name("张三"));
```

**根证书怎么装进机器**（这一步决定内网是否绿勾）：

- Windows 域：组策略推送到「受信任的根证书颁发机构」
- Adobe 另需导入其**受信任身份列表**（编辑 → 首选项 → 签名 → 身份与可信证书），
  或用 Adobe 企业部署配置统一下发

> ⚠️ **这份信任不出内网**：文件发给外部单位，对方机器没装你的根证书，仍显示"身份未知"。
> 对外合同 / 法律举证仍须公共 CA 证书。

> 🔴 **CA 私钥是内网信任的总钥匙**：泄露则任何人都能签发被内网信任的证书、冒充任意签署人。
> 应离线保存或存入硬件密钥模块，签发在受控机器上进行，绝不放进应用服务器或版本库。

**能力边界**：只做建根与签发终端证书。不做证书吊销（CRL/OCSP）、密钥托管、自动续期、
多级中间 CA；根证书 `pathLen=0`，结构上锁死为两层。

### 可信时间戳（RFC 3161）

不盖时间戳时，签名里的时间取自**签名机本地时钟、可被签署人篡改**（把系统时间改掉再签即可）。
盖了之后由受信任的第三方 TSA 背书。

```java
SignOptions opts = SignOptions.defaults()
        .name("张三")
        .timestampProvider(req -> {
            // officia 已把 TimeStampReq 构造好，你只管发出去
            // POST，Content-Type: application/timestamp-query，请求体就是 req
            return yourHttpClient.post("https://tsa.example.com/tsr", req);
        });
```

> 🔴 **officia 不做这次网络调用** —— 它是离线库，只构造请求、解析并嵌入响应，
> HTTP 交给你（业务系统本就有网络栈、代理、重试）。另一个原因是顺序：
> 时间戳盖的是**签名值**，签名值必须签完才存在，所以不可能"先拿好 token 再签"。

> ⚠️ 回调抛异常时签名会**明确失败**而非静默跳过——拿到一份"自以为有可信时间、
> 实则没有"的签名，比签名失败危险得多。

### 验证签名 `OfficiaPdf.verify`

签名的对侧。让业务系统**程序化**判定一份文档是否仍然可信，不必让人开 Acrobat 肉眼看：

```java
SignatureVerification v = OfficiaPdf.verify(pdf);

if (!v.isSigned()) {
    reject("这份文件没有签名");
} else if (v.isValid()) {
    for (SignatureInfo s : v.getSignatures()) {
        log.info("{} 于 {} 签署，理由：{}", s.getSignerName(), s.getSigningTime(), s.getReason());
    }
} else {
    log.warn("签名校验未通过：{}", v.getSummary());   // 如「3 个签名，全部有效，签名后被追加了 76 字节」
}
```

> 🔴 **判"这份文档可信吗"一律看 `v.isValid()`，不要自己遍历各签名的 `isValid()` 取与。**
> 整体结论额外查了一条：**最末一个签名的覆盖区是否延伸到文件末尾**。签完之后被追加的内容
> 不受任何签名保护，却会照常显示给读者——此时每个签名单独验都是"有效"，
> 漏掉这一条会得到危险的假绿。`v.getUnsignedTailBytes()` 给出被追加的字节数。

**验证 ≠ 信任**。默认只做密码学验证；要一并核验签署人身份，传入受信任的根证书：

```java
X509Certificate root = (X509Certificate) CertificateFactory.getInstance("X.509")
        .generateCertificate(new ByteArrayInputStream(rootCerBytes));   // CA.exportRootCertificate()
SignatureVerification v = OfficiaPdf.verify(pdf, List.of(root));
boolean ok = v.isValid() && v.isTrusted();       // 内容没被改 + 签署人可信
```

`SignatureInfo` 的四项独立结论，各查一段、互不替代：

| 方法 | 回答什么 | false 意味着 |
|---|---|---|
| `isDigestMatch()` | 被签内容还是原样吗 | 签名后有人改了正文 |
| `isSignatureMatch()` | 摘要声明真是持私钥者作出的吗 | 签名值被替换或容器被改造 |
| `isCertificateIntact()` | 证书本身没被动过吗 | **证书被冒名改写**（见下） |
| `isCoveringWholeDocument()` | 覆盖区到文件尾了吗 | 多签时前序签名如此**属正常**，看整体结论 |

> 🔴 **`isCertificateIntact()` 为什么不能省**：签名值签的是 signedAttrs，证书只是公钥的容器。
> 篡改者若只改证书里的主体名（把"张三"改成"李四"），公钥、signedAttrs、签名值三者都没动，
> 前两项检查**照样全绿**——只有验证证书自身被其签发者签的那道签名才能发现。
> `isValid()` 已包含这一项，直接用它即可。

其余可读字段：`getFieldName()` / `getSignerName()` / `getSigningTime()` / `getTimestampTime()` /
`getReason()` / `getLocation()` / `getContactInfo()` / `getSignerCertificate()` /
`getCertificateChain()` / `getSubject()` / `getIssuer()` / `getProblem()`（失败原因）。

> ℹ️ `getSigningTime()` 优先取 PKCS#7 signedAttrs 里的时间（在签名覆盖范围内，改不了），
> 缺失时才退回签名字典的 `/M`（**不在**保护内）。两者都来自签名机本地时钟；
> 要不可否认的时间看 `getTimestampTime()`（第三方 TSA 出具）。

> ℹ️ **验签不受授权门控**：它是纯读取操作，且要验的往往是**别人**发来的文档。
> 生产签名才是受控能力。

### 当前限制（不要向客户承诺）

| 做不到 | 说明 |
|---|---|
| 可见签章（页面上的红章 / 手写签名图） | 当前是**不可见签名**（`/Rect [0 0 0 0]`），只在签名面板可见 |
| 验签时的**证书吊销检查**（CRL / OCSP） | `verify` 不查吊销；私有 CA 也不具备吊销设施 |
| 只嵌叶子证书时的**证书完整性**判定 | 容器里没有签发者证书就验不了那一项，此时 `isCertificateIntact()` 返回 true（"没验成"不等于"验失败"），身份完整性改由信任锚兜底 |
| 保留原文档的书签 / XMP 元数据 | **仅首次签名**有此影响（走全量重写，不保留 `/Outlines`、`/Metadata`，与 `watermark` 等一致）；后续加签走增量更新，原文档内容分毫不动 |
| 证书吊销（CRL / OCSP） | 私有 CA 不提供；签发出去的证书在有效期内无法作废 |

> 🔴 **私钥是整套机制的命门**：私钥泄露 = 任何人都能伪造"未被篡改"的签名。
> 签名操作必须在**服务端**完成，私钥不下发客户端；`.p12` 口令进配置中心或密钥管理，
> 不写进代码、不入版本库。

## 六、链式编辑器 `PdfEditor`（多步操作首选）

```java
byte[] out = OfficiaPdf.edit(pdf)           // 加密文档用 edit(pdf, password)
    .keepPages(0, 1, 2)                     // 只留前 3 页
    .rotate(90)
    .watermark("机密", fontTtf)
    .pageNumbers("第 %d 页", fontTtf)
    .metadata(Map.of("Title", "季度报告"))
    .encryptAes256("open", "owner")
    .toBytes();
```

全部链式方法：

```java
PdfEditor watermark(String text)                        PdfEditor watermark(String text, byte[] fontTtf)
PdfEditor pageNumbers()                                 PdfEditor pageNumbers(String format)
PdfEditor pageNumbers(String format, byte[] fontTtf)
PdfEditor rotate(int degrees)
PdfEditor keepPages(int... pageIndices)                 PdfEditor removePages(int... pageIndices)
PdfEditor append(byte[] other)                          // 追加另一份 PDF
PdfEditor metadata(Map<String,String> metadata)
PdfEditor encrypt(String user, String owner, int bits)  PdfEditor encryptAes256(String user, String owner)
```

三种收尾：

```java
byte[] b = editor.toBytes();
editor.writeTo(outputStream);       // 直写流
editor.toFile(new File("out.pdf")); // 直写文件
```

> **什么时候用链式**：两步以上就该用——少一轮解析与序列化，代码也更清楚。单步操作用静态方法即可。

## 六点五、PDF → 图片（`toImages`）

把 PDF 转成每页一张的图片，用于**文档在线预览**——图片在任何浏览器与
移动端 webview 里都能直接 `<img>` 显示，不依赖 PDF 阅读器。

**自动选路**，调用方不用关心文档是什么类型：

| 文档类型 | 走哪条路 | 特点 |
|---|---|---|
| 扫描件（整页是图、无文字层） | 抽图快路 | **又快又无损**——无需重编码时原样输出 |
| 电子版 / 图文混排 | 通用渲染 | 解析内容流后重画 |

```java
List<byte[]> pages = OfficiaPdf.toImages(pdf);                 // 顺序即页码

// 加水印（防止读者直接拿到干净原件）
List<byte[]> pages = OfficiaPdf.toImages(pdf,
        ImageRenderOptions.defaults()
                .watermark(WatermarkOptions.text("内部资料").tile(true)));
```

> ℹ️ **扫描件：默认跟随源图，不做无谓转换**（这条只对走快路的扫描件成立）：
> - **分辨率**保持原始像素（通常 200–300 DPI）。只有显式调过 `dpi(...)` 才重采样——
>   拿默认的 96 去采样会把扫描件毁掉。
> - **格式**跟随源图（源是 JPEG 就出 JPEG）。只有显式调过 `format(...)` 才换——
>   实测一份 29 页扫描书硬转 PNG 是 226 MB，跟随源格式只有 38 MB、且快 237 倍
>   （命中原样输出快路，不解码不重编码）。
>
> 走通用渲染时则按 `dpi`（默认 96）光栅化，格式默认 PNG。

### 字形：内嵌 TrueType 走精确，其余走近似

**这是转图片保真度的第一决定因素，先看它属于哪一档**：

| 源 PDF 的字体 | 字形 | 占比（21 份真实 PDF、238 个去重字体实测） |
|---|---|---|
| **内嵌 TrueType**（`FontFile2`） | ✅ **精确**——直接光栅化 PDF 里那份字体程序 | 约 50% |
| 内嵌 CFF/Type1（`FontFile3`/`FontFile`） | ⚠️ 近似 | 约 19% |
| **完全没内嵌**（只声明字体名） | ⚠️ 近似（这是唯一可能，Adobe 同样如此） | 约 31% |

近似 = 用 officia 自己的字体栈画字，**笔画细节与原文件不同，但版面位置精确**
（按 PDF 声明宽度横向缩放对齐）。两档在同一份文档里可以混着出现，逐片段自动选。

> 💡 **精确字形还顺带绕开了一类错误**：它走 `码 → GID → 内嵌轮廓`，不经过 `ToUnicode`。
> 而 `ToUnicode` 是可选的、只影响能不能复制文本，生成器常写错——所以
> **有些 PDF 你用 `extractText` 抽出来是乱码，转图片却是对的**。别拿抽取结果推断渲染结果。

### 其余已知失真

| 失真 | 说明 |
|---|---|
| 非内嵌 / CFF 字体的笔画不像原文件 | 见上表，属既定取舍 |
| 文本解码错误（仅影响走近似字形的那部分） | 近似路径要先解成 Unicode，源文件 `ToUnicode` 错就会画错字 |
| 绘制次序按类别而非内容流原序 | 底纹→图案→图像→线→文字。绝大多数文档文字在最上层，这个近似成立；刻意把图盖在字上的会失真 |

**裁剪路径**（`W`/`W*`）已支持：填充色块按裁剪区精确裁切，图像与线条整块剔除；
非矩形裁剪路径按包围盒近似（**欠裁剪**——宁可多画，不裁掉该显示的）。
文本刻意不按裁剪区剔除，因为漏字比多画严重。

**不支持且不打算做**：内联图像（`BI/ID/EI` 只跳过不解码 → 该处不画图）、渐变（`sh`）。
这不是"还没排上"——12 份真实 PDF、9149 个内容流的算子普查里这两个各出现 **0 次**
（同一批样本里裁剪算子出现 76 万次）。**客户样本里真撞上了就反馈**，
换一批样本重跑普查即可重议。

透明组与混合模式同样不支持。

**性能参考**（实测，含解析）：46 页规范 4.0 s；164 页 14 s；341 页中文书 259 s。
精确字形比近似字形慢约 15%（开销在路径填充）。扫描件快路则是另一个量级：29 页 0.2 s。

**若源文件本是 Word/Excel/PPT，别绕道 PDF** —— 直接 `OfficiaWords.toImages(docx)`
保真更好（字形是真的），也无需渲染 PDF。

### 何时该用哪条路

| 你手上的 PDF | 用什么 |
|---|---|
| 扫描件（整页是图） | `OfficiaPdf.toImages` ✅ 快路，又快又无损 |
| 电子版（有文字层） | `OfficiaPdf.toImages` ✅ 通用渲染，注意上面三类失真 |
| 源文件是 Office | `OfficiaWords.toImages` 等，别经 PDF |
| 扫描件且要**文字** | `ScannedPdfConverter`（OCR，见第七节末） |

## 七、PDF → Word（`toWord`）

把**电子版** PDF 转成可编辑的 `.docx`。

```java
byte[] docx = OfficiaPdf.toWord(pdf);
byte[] docx = OfficiaPdf.toWord(pdf, "口令");                 // 加密 PDF
byte[] docx = OfficiaPdf.toWord(new File("in.pdf"));
byte[] docx = OfficiaPdf.toWord(inputStream);
OfficiaPdf.toWord(new File("in.pdf"), new File("out.docx"));  // 直接写盘
```

### 选项 `WordConvertOptions`

```java
import plus.ruoyi.officia.pdf.word.WordConvertOptions;

byte[] docx = OfficiaPdf.toWord(pdf, WordConvertOptions.defaults()
    .setPassword("open")                  // 加密 PDF 口令
    .setExtractImages(true)               // 嵌入图片（默认 true）
    .setExtractVectors(true)              // 表格线 / 底纹（默认 true）
    .setRasterizeVectorPatterns(true));   // 公章 / 艺术字光栅化（默认 true）
```

| 选项 | 默认 | 关掉的效果 |
|---|---|---|
| `extractImages` | `true` | 产物无图片，体积显著变小 |
| `extractVectors` | `true` | 产物无表格线 / 底纹 |
| `rasterizeVectorPatterns` | `true` | 公章、签名、艺术字轮廓**不出现**在产物里 |

### 模式：`TEXTBOX`（当前唯一可用）

`WordConvertOptions.Mode` 有两个值，取舍是**明摆着的**、不会悄悄替你选：

| 模式 | 含义 | 状态 |
|---|---|---|
| `TEXTBOX`（默认） | 每个视觉文本块 = 一个绝对定位的文本框，**版式最像原文**；代价是产物在 Word 里编辑不便 | ✅ 可用 |
| `FLOW` | 推断段落 / 标题 / 表格，还原成可自由编辑的流式文档 | ❌ **尚未实现，传入即抛异常**（不会降级成 TEXTBOX） |

### 能还原什么

文字 · **加粗** · 字体名 · 逐行缩进（含中文首行缩进 2 字符）· 图片（含透明通道）·
表格线 / 单元格底纹 · **表格单元格切分**（靠竖线判断"这两段文字不在同一个格子里"）·
公章 / 艺术字（光栅化成位图）。

### 明确做不到的（不要向客户承诺）

| 做不到 | 说明 |
|---|---|
| **扫描件 / 图片型 PDF 走 `toWord`** | 无文字层，`toWord` 会**明确抛异常**并说明需要 OCR，**不会**返回空白文档。这不是缺陷——扫描件有专门的路径 `ScannedPdfConverter`（见下），别硬塞进 `toWord` |
| 产物是"真表格" | 表格线是逐条画出来的形状，位置精确但**不能插入行列**；真表格需要 `FLOW` 模式 |
| 虚线样式 | 虚线会被画成实线 |

### 扫描件走 `ScannedPdfConverter`（`officia-ocr` 模块）

```java
import plus.ruoyi.officia.ocr.recog.BuiltinModels.Language;
import plus.ruoyi.officia.ocr.scan.ScannedPdfConverter;

// 最简：整份扫描件 → 可编辑 DOCX（语种必填，见下方红字）
byte[] docx = new ScannedPdfConverter()
        .language(Language.CHINESE)
        .toWord(pdfBytes);

// 老书常见版面：扫描时页面横放、两页并排
byte[] docx = new ScannedPdfConverter()
        .language(Language.CHINESE)
        .rotate(ScannedPdfConverter.Rotation.CLOCKWISE_90)
        .splitFacingPages(true)   // 按最大空白带定位中缝，不是对半切（装订线未必居中）
        .minConfidence(0.01)      // 滤掉插图/印章/表格线被误当文本行的噪声
        .toWord(pdfBytes);
```

> 🔴 **`.language(...)` 是必填的，不填直接抛异常**。OCR 不做语种自动判别，
> 而 `OcrOptions.defaults()` 的默认值是**英文**——中文扫描件走英文模型时，
> CTC 在固定字符集上永远给某个类别，于是吐出满篇拉丁字母，**不报错、置信度还不低**。
> 上游踩过：整本 183 页书就这么静默产出 25 万字噪声。所以现在改成响亮失败。

| 能力 | 状态（2026-08-15 实测） |
|---|---|
| 中文识别 | ✅ **已支持**（自训 CRNN-lite，3885 类，随包内置 int8 权重 10.85 MB） |
| 现代扫描件（清晰印刷） | 实测基本逐字准确 |
| 1990 年代铅印扫描书 | 召回 69.6%——**能读懂大意，但不能当作可直接交付的转录** |
| 留出字体 CER | 0.0071（7 款训练未见过的字体） |
| 标点符号 | 书名号《》、引号“”、省略号…、破折号—、间隔号· 均可正确识别（183 页真实书实测，书名号左右各 343 完美配对） |

**能力边界（如实告知客户，不要夸大）**：只做「行 → 段落」的线性还原，
**不**还原表格、多栏、图文混排——扫描件的版面分析属另一层能力，尚未实现。
模型也**没有「认不出」这个输出**：CTC 在固定字符集上永远给某个类别，
超出字符集的输入会硬凑结果，**且置信度反而更高**，所以
`minConfidence` 能滤掉纯噪声区域（实测 0.0001 量级），但滤不掉"认错字"。

```java
// 纯文本层判断仍用 toWord：它抛异常是在告诉你"这份该走 OCR"
try {
    byte[] docx = OfficiaPdf.toWord(pdf);
} catch (OfficiaException e) {
    // 消息里已包含"没有文字层""需要 OCR"——此时改走 ScannedPdfConverter
    byte[] docx2 = new ScannedPdfConverter().language(Language.CHINESE).toWord(pdf);
}
```

## 完整示例：批量合并 + 加水印 + 加密归档

```java
import plus.ruoyi.officia.pdf.OfficiaPdf;
import java.nio.file.*;
import java.util.*;

public class ArchivePdf {
    public static void main(String[] args) throws Exception {
        List<byte[]> parts = new ArrayList<>();
        for (String f : List.of("a.pdf", "b.pdf", "c.pdf")) {
            parts.add(Files.readAllBytes(Path.of(f)));
        }
        byte[] font = Files.readAllBytes(Path.of("C:/Windows/Fonts/simhei.ttf")); // 中文水印必需

        byte[] out = OfficiaPdf.edit(OfficiaPdf.merge(parts))
            .watermark("内部资料 请勿外传", font)
            .pageNumbers("第 %d 页 / 共 %d 页", font)
            .metadata(Map.of("Title", "归档合集"))
            .encryptAes256("open2026", "owner2026")
            .toBytes();

        Files.write(Path.of("archive.pdf"), out);
        System.out.println("归档完成，" + OfficiaPdf.pageCount(out, "open2026") + " 页");
    }
}
```

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| `toImages` 出的字笔画不像原文件 | 该字体没内嵌，或内嵌的是 CFF/Type1 | **属预期取舍**（约占字体的一半），见第六点五的字形表。版面位置仍是精确的 |
| `toImages` 出的正文是乱码 | 走近似字形，且这份 PDF 的 `ToUnicode` 表本身错了 | 内嵌 TrueType 的部分不受影响。先 `extractText(pdf)` 验证——抽出来就乱说明是源文件问题 |
| `extractText` 是乱码但转出来的图是对的 | **正常，不是矛盾**：两条路走不同的信息链 | 转图片走 `码 → GID → 内嵌轮廓`，不经过会写错的 `ToUnicode` |
| `toImages` 某页整页空白 | 该页在源文件里就没有可渲染内容 | 属预期。刻意输出空白页而非让整份失败 |
| `toImages` 产物比预期大很多 | 扫描件被显式 `format("png")` 转了，或通用渲染 DPI 调高了 | 扫描件去掉 `format(...)` 跟随源图（实测 226 MB → 38 MB）；通用渲染降 `dpi` |
| `toImages` 出的图糊了 | 显式指定了低 DPI | 扫描件去掉 `dpi(...)` 即保持原分辨率；通用渲染默认 96，预览建议 150 |
| `toImages` 慢 | 通用渲染要解析内容流逐图元重画 | 属预期：实测 86–760 ms/页，随页面复杂度变化。扫描件走快路则是 ms 级 |
| 中文水印 / 页码是方块或空白 | 没传 `fontTtf` | 传中文 TTF 字节，见 `officia-chinese-font` |
| 读加密 PDF 抛异常 | 没传口令 | 用 `extractText(pdf, pwd)` / `pageCount(pdf, pwd)` / `metadata(pdf, pwd)` |
| 抽页抽错了 | 索引以为是 1-based | **0-based**：第 1 页传 `0` |
| 加密后老阅读器打不开 | 用了 AES-256 | 兼容优先降到 128；安全优先保持 256 |
| `extractImages` 返回空 | PDF 里是矢量图形不是位图 | 属预期 |
| 合并后体积很大 | 各源 PDF 的字体/图片资源叠加 | 属预期；需要精简请先在源头压 |
| 输出带评估水印 | 未授权 + 门控开 | `officia-license`（与你自己加的 `watermark` 无关） |
| `toWord` 抛"没有文字层" | 扫描件 / 图片型 PDF | 属**预期行为**，不是 bug；改走 `ScannedPdfConverter`（见第七节），不必再找外部 OCR 工具 |
| `toWord` 抛"FLOW 模式尚未实现" | 传了 `Mode.FLOW` | 一期只有 `TEXTBOX`；刻意抛异常而非静默降级 |
| 转出的 Word 里表格不能插入行列 | Textbox 模式把表格线画成形状 | 属既定取舍，见第七节 |
| 签名后阅读器显示"签署人身份未知" | 用了自签名证书 | **属预期，不是失败**——防篡改那行仍是绿的。消除它需把根证书导入阅读器受信任列表，或改用 CA 证书 |
| 签名显示"文档已被更改" | 签完之后又动过字节 | 签名必须是**最后一步**；检查是否在 `sign` 之后还做了水印/加密/合并 |
| 多人签字后只认出一个签名 | 几个人的 `fieldName` 重名 | 每人给不同的 `.fieldName("SignatureN")`，默认值都是 `Signature1` |
| 多签后文件明显变大 | 每次加签追加一个增量段（约 17 KB） | 属**预期**——原字节不动是"前签不失效"的前提，这部分开销无法优化掉 |
| `KeyMaterial.fromPkcs12` 抛"口令错误或容器损坏" | 口令不对 / 文件不是 PKCS#12 | 核对口令；`.pfx` 与 `.p12` 是同一格式，都可直接传 |
| `verify` 各签名都 `isValid()` 但整体 `isValid()` 为 false | 签完之后有人往文件尾追加了内容 | **不是误报**——看 `getUnsignedTailBytes()`。那段内容不受签名保护却照常显示，整份文档不可信 |
| `verify` 里某个签名 `isCoveringWholeDocument()` 为 false | 多签时的前序签名 | **属正常**，不是缺陷。增量更新下先签者的覆盖区天然不含后追加的段；判文档可信看整体 `isValid()` |
| `verify` 说"签署人证书自身的签名验证不通过" | 证书内容被篡改（典型是冒名改主体） | 该文档不可信。公钥没变所以签名值仍验得过，正是这一项把它挡下来的 |
| `verify` 的 `isTrusted()` 恒为 false | 没传信任锚 | 那表示"没验过"而非"验过不可信"。要判身份用 `verify(pdf, List.of(rootCert))` |

## 在测试台里实测

面板 **「PDF 工具箱」**：合并·拆分·抽页·删页·旋转·水印·页码·抽文字·抽图片·RC4-128·AES-256·信息·**转 Word**，逐项可点。
「转 Word」下方有三个开关（嵌入图片 / 表格线底纹 / 图案光栅化）与加密口令输入框，可现场对比开关效果。
端点：`/api/pdf/info`、`/merge`、`/split`、`/pages`、`/rotate`、`/watermark`、`/pagenumbers`、`/text`、`/images`、`/encrypt`、`/toword`。

**数字签名**面板内有四个按钮：「数字签名」「三人会签」「验证签名」「篡改演示」
（端点 `/api/pdf/sign`、`/multisign`、`/verify`、`/tamper`），可填签署人与签署原因。

推荐演示流程：**签名 → 把产物重新上传 → 验证签名 → 篡改演示**。
「篡改演示」会把签名件正文改掉**一个字节**再验一次，页面上并排显示改动前后的结论
（一正一反），是"防篡改"最直观的证明，且**不需要 Acrobat**。

> ⚠️ **要看阅读器里的效果则必须用 Adobe Acrobat**——浏览器内置的 PDF 查看器多数不验证
> 数字签名，页面上看不出区别。测试台用的是现场生成的自签名证书，
> 会提示"签署人身份未知"（属预期），但"文档未被修改"仍为绿。
>
> 「验证签名」按钮走的是 `OfficiaPdf.verify`，**不依赖阅读器**，直接给结构化结论。

## 相关技能

| 接下来 | 用 |
|---|---|
| 别的格式转 PDF | `officia-capability-map` |
| 中文字体 | `officia-chinese-font` |
| 大 PDF 内存 | `officia-performance` |
| 做成下载接口 | `officia-spring-integration` |
