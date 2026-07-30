---
name: check
description: |
  /check - 接入自检（JDK / 依赖 / 本地仓 / 编译 / 冒烟 / 授权 / 字体 / 编码 八项）

  触发场景：
  - 刚接入想确认环境是否正常
  - 上线前做一次全面检查
  - 换机器/换环境后排查

  触发词：/check、自检、检查、环境检查、体检、能不能用、配置对不对
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---
# /check - 接入自检

一次性检查 officia 能不能正常用：JDK、依赖、本地仓、授权、字体、编码。

## 执行步骤

逐项跑，把结果汇总成表。

### 1. JDK 版本（要求 17+）

```bash
java -version
mvn -v
```

### 2. 本地仓是否有 officia

```bash
ls ~/.m2/repository/plus/ruoyi/
```

### 3. 依赖解析与实际版本

```bash
mvn -o dependency:tree -Dincludes=plus.ruoyi
```

确认：解析到的版本 = pom 里写的版本；依赖树里**没有陌生的第三方包**（officia 运行时零第三方依赖）。

### 4. 编译与测试

```bash
mvn -o clean test
```

### 5. 冒烟验证（不需要素材文件）

```java
byte[] png = plus.ruoyi.officia.barcode.OfficiaBarCode.qrPng("smoke");
System.out.println("ok=" + (png.length > 0 && (png[0] & 0xFF) == 0x89));
```

### 6. 授权与门控状态

```java
import plus.ruoyi.officia.license.OfficiaLicense;
System.out.println("enforced=" + OfficiaLicense.isEnforced()
               + " licensed=" + OfficiaLicense.isLicensed()
               + " modules="  + OfficiaLicense.getModules());
```

### 7. 中文字体可用性

启动测试台看日志里的字体探测结果，或直接检查：

```bash
ls C:/Windows/Fonts/simhei.ttf 2>/dev/null || ls /usr/share/fonts 2>/dev/null | head
```

### 8. 文件编码（铁律 3）

确认新增/修改的文件是 **UTF-8 无 BOM**：

```bash
git diff --cached --name-only | while read f; do
  head -c 3 "$f" | od -An -tx1 | grep -q "ef bb bf" && echo "BOM: $f"
done
```

## 输出格式

```
| 检查项 | 结果 | 说明 |
|---|---|---|
| JDK 17+ | ✅/❌ | 实际版本 |
| 本地仓有 officia | ✅/❌ | 版本列表 |
| 依赖解析 | ✅/❌ | 实际解析版本 |
| 编译测试 | ✅/❌ | 失败数 |
| 冒烟 | ✅/❌ | |
| 授权门控 | 评估态/已授权 | enforced / licensed |
| 中文字体 | ✅/❌ | 找到的路径 |
| 编码无 BOM | ✅/❌ | |
```

对每个 ❌ 给出**具体修复动作**并指向对应技能（`officia-setup` / `officia-license` / `officia-chinese-font`）。
