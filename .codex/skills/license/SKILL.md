---
name: license
description: |
  /license - 授权状态检查与加载指引（排查水印、指导 .lic 放置、解释门控构建差异）

  触发场景：
  - 输出带评估水印或被限页，要定位原因
  - 拿到 officia.lic 不知道放哪
  - 本地没水印但线上有（构建期门控差异）

  触发词：/license、授权、水印、lic、评估态、去水印、门控、enforcement
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---
# /license - 授权状态检查与加载指引

排查"为什么有水印 / 授权加载不上"，或指导用户把 `officia.lic` 放对位置。

## 执行步骤

1. **激活 `officia-license` 技能**读取完整规则。

2. **先判断问题类型**：

   | 用户说 | 走哪条 |
   |---|---|
   | 输出有水印 / 被限页 | 走"排查"分支 |
   | 拿到 .lic 不知道放哪 | 走"加载"分支 |
   | 本地没水印线上有 | 走"构建差异"分支（最常见） |

3. **排查分支**：给出这段自检代码让用户跑：

```java
import plus.ruoyi.officia.license.OfficiaLicense;

System.out.println("enforced   = " + OfficiaLicense.isEnforced());
System.out.println("licensed   = " + OfficiaLicense.isLicensed());
System.out.println("evaluation = " + OfficiaLicense.isEvaluation());
System.out.println("licensee   = " + OfficiaLicense.getLicensee());
System.out.println("edition    = " + OfficiaLicense.getEdition());
System.out.println("modules    = " + OfficiaLicense.getModules());
System.out.println("expires    = " + OfficiaLicense.getExpiresEpoch());
```

   按结果判读：
   - `enforced=false` → 门控没开，水印**不是**授权造成的（去查是不是自己加的 `watermark`）
   - `licensed=false` → 没加载成功，查文件名/路径/是否过期
   - `licensed=true` 但某能力仍降级 → 该模块不在授权范围（`hasModule("cells")`）

4. **加载分支**：说明四个自动加载位置及优先级
   `-Dofficia.license` → `OFFICIA_LICENSE` 环境变量 → classpath `/officia.lic` → 工作目录 `./officia.lic`；
   显式 `OfficiaLicense.setLicense(File)` 优先级最高。

5. **构建差异分支**：解释 `BuildFlags.ENFORCED_BY_DEFAULT` 是**构建期常量**——
   源码 `mvn install` 构建 = 门控关（无水印）；`mvn -Prelease` / 正式发布 jar = 门控恒开（未授权就降级，且不可经公开 API 关闭）。
   → 正确处置是**加载授权**，不是想办法关门控。

## 铁律

- 🔴 **绝不**生成 / 伪造 / 猜测 License 令牌内容
- 🔴 **绝不**把令牌写进源码或提交（`.gitignore` 已拦 `*.lic`）
- 🔴 **绝不**给"绕过水印 / 破解门控"的建议——正确做法是购买并加载授权
- 价格与条款以 `../officia-docs/docs/guide/licensing.md` 与商业合同为准，不要复述可能变动的数字
