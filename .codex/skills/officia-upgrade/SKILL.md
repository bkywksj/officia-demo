---
name: officia-upgrade
description: |
  升级 officia 版本的安全流程：改坐标、验证依赖树、用 demo 作回归护栏、
  检查 API 破坏性变更（对比门面签名）、核对能力状态变化，以及升级失败怎么回退。

  触发场景：
  - 要升级 officia 到新版本
  - 升级后编译不过 / 行为变了
  - 想知道新版改了什么
  - 要建立"升级不出事"的检查流程

  触发词：升级、更新officia、换版本、新版本、破坏性变更、兼容、回归、回退、changelog、版本对比
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# 升级 officia 版本

## 概述

officia-demo 的核心价值之一就是**回归护栏**：升级 officia 后重跑 demo，能第一时间发现 API 破坏性变更与行为变化。

## 标准升级流程（按顺序）

### 1. 升级前：记录当前基线

```bash
# 当前用的是什么版本
mvn dependency:tree -Dincludes=plus.ruoyi

# 当前能力状态与实测事实（旧版）
cat ../officia/status.json > /tmp/officia-status-before.json
```

跑一遍现有测试，确认**升级前是全绿的**（否则升级后分不清是新问题还是旧问题）：

```bash
mvn -o test
```

### 2. 看新版改了什么

```bash
# 文档站变更日志
cat ../officia-docs/docs/changelog.md

# 新版的能力状态 / 拒绝项 / 已知缺口
cat ../officia/status.json
```

重点看 `status.json` 里这几段的变化：

| 段 | 关注点 |
|---|---|
| `capabilities` | 有能力从 `in-progress` 变 `available` 吗 |
| `doc_binary_input` | `.doc` 支持是否推进 / 是否宣布生产就绪 |
| `explicitly_rejected` | 拒绝项有增减吗 |
| `known_gaps` | 已知缺口是否补上 |
| `not_verified` | 未验证项是否变化 |
| `product.tests` | 测试总数与失败数 |

### 3. 改坐标

```xml
<dependency>
  <groupId>plus.ruoyi</groupId>
  <artifactId>officia-all</artifactId>
  <version>1.1.3</version>   <!-- 改这里 -->
</dependency>
```

若从源码构建，先装新版进本地仓：

```bash
# 在 ../officia
mvn install -DskipTests
```

### 4. 验证依赖真的换了

```bash
mvn -o dependency:tree -Dincludes=plus.ruoyi
ls ~/.m2/repository/plus/ruoyi/officia-all/
```

> 常见坑：改了 pom 但本地仓没有新版本，Maven 用了旧的缓存版本。

### 5. 编译 + 测试 + 回归

```bash
mvn -o clean test                        # 编译期破坏性变更会在这里暴露
mvn -o package
java -jar target/officia-demo-1.0.0.jar  # 启动测试台
curl -X POST http://127.0.0.1:8080/api/batch/run   # 全能力批量回归
```

**三层护栏**：

| 层 | 发现什么 |
|---|---|
| `mvn -o clean test` 编译期 | 方法删除 / 签名变更 / 包路径变更 |
| `mvn -o test` 运行期 | 行为变化（页数变了、断言不成立） |
| 批量回归 `/api/batch/run` | 跨模块的端到端能力是否还完整 |

### 6. 用真实文档实测

自动化测试用的是自造小输入，**你的真实业务文档才是关键**。用测试台上传几份典型文件（尤其复杂版式的 docx / xlsx / pptx），对比升级前后的输出。

## 检查 API 破坏性变更

officia 的对外 API 就是 8 个门面类的公开方法。直接 diff 签名最可靠：

```bash
# 列出某个门面当前的全部公开方法
grep -E "^\s*public static" \
  ../officia/officia-words/src/main/java/plus/ruoyi/officia/words/OfficiaWords.java

# 全部门面一次列出
for m in words cells slides pdf barcode imaging email license; do
  echo "===== $m"
  grep -hE "^\s*public static" ../officia/officia-$m/src/main/java/plus/ruoyi/officia/$m/Officia*.java
done
```

把升级前后的输出各存一份再 diff，新增/删除/改签名一目了然。

### 破坏性变更处置

| 变更类型 | 症状 | 处置 |
|---|---|---|
| 方法被删除 | 编译报 `cannot find symbol` | 看 changelog 找替代方法 |
| 参数类型变了 | 编译报类型不匹配 | 按新签名调整 |
| 返回类型变了 | 编译报不兼容 | 调整接收变量 |
| 包路径变了 | `package does not exist` | 改 import |
| **行为变了但签名没变**（最危险） | 编译通过，**结果不对** | 只有回归测试能发现——所以每次升级都要跑 |
| 枚举值增减 | `switch` 不完整 / 找不到常量 | 检查 `PageSize` / `QrEcc` 等枚举 |

## 回退

升级不顺利时，改回旧版本号即可——officia 是普通 Maven 依赖，无迁移脚本、无持久化状态：

```xml
<version>1.0.0</version>   <!-- 改回去 -->
```

```bash
mvn -o clean test    # 确认回退后恢复全绿
```

> 若旧版本已从本地仓被清掉，需要重新从对应源码 tag `mvn install`。**建议升级前保留一份旧版 jar**。

## 升级检查清单

```
□ 升级前 mvn -o test 全绿（有基线）
□ 读了 ../officia-docs/docs/changelog.md
□ 对比了 ../officia/status.json 的 capabilities / explicitly_rejected / known_gaps
□ pom 版本号已改，且本地仓确实有新版
□ mvn -o dependency:tree 确认解析到新版
□ mvn -o clean test 编译 + 测试全绿
□ 批量回归 /api/batch/run 全绿
□ 用真实业务文档在测试台实测过
□ 门面签名 diff 过，无未处理的破坏性变更
□ 授权行为未变（isLicensed / hasModule 正常）
□ 保留了回退路径（旧版本可用）
```

## 特别注意：授权与门控

升级时留意 `officia-license` 的行为：

```java
System.out.println("enforced=" + OfficiaLicense.isEnforced()
               + " licensed=" + OfficiaLicense.isLicensed()
               + " modules=" + OfficiaLicense.getModules());
```

- 从**源码构建版**换到**正式发布版**（或反之），门控默认值会变（`officia.enforced` 构建期常量），**输出是否带水印会跟着变**——这不是升级 bug，见 `officia-license`
- 新版可能校验 License 的 `maxver`（授权允许的最大大版本）——跨大版本升级时确认你的授权覆盖新版本

## 相关技能

| 接下来 | 用 |
|---|---|
| 依赖解析问题 | `officia-setup` |
| 跑回归 | `officia-testbench` |
| 写回归测试 | `officia-demo-test` |
| 升级后报错 | `officia-troubleshooting` |
| 水印行为变了 | `officia-license` |
