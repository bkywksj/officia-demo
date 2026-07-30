---
name: demo
description: |
  /demo - 跑示例测试或命令行入口并解读结果

  触发场景：
  - 想跑一遍 demo 的示例测试
  - 测试结果有 skipped 不知道正不正常
  - 想知道各示例测试演示了什么

  触发词：/demo、跑示例、跑测试、mvn test、示例、样例、跳过
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---
# /demo - 跑示例并解读结果

执行 demo 的示例测试或命令行入口，并把结果讲清楚。

## 执行步骤

### 1. 跑示例测试

```bash
mvn -o test
```

预期全绿。各测试类演示的内容：

| 测试类 | 演示 |
|---|---|
| `CellsDemoTest` | CSV→PDF、CSV 解析(RFC 4180)、公式求值 `=A1+A2` |
| `BarcodeDemoTest` | Code128 / QR → PNG |
| `PdfDemoTest` | 多 PDF 合并、AES-256 加密 |
| `LicenseDemoTest` | 未授权评估态、无效/空 License 抛异常（**不 System.exit**） |
| `EnforcementDemoTest` | 授权门控三态：关 / 开+未授权 / 开+自备 lic |
| `web/*Test` | 测试台自身的单测 |

### 2. 或跑命令行入口

```bash
mvn -o exec:java -Dexec.mainClass=plus.ruoyi.officia.demo.Demo
```

### 3. 解读结果

- **有 skipped**：正常——`EnforcementDemoTest.licensedIfProvided` 在没有 `officia.lic` 时会自动跳过（demo 不内置任何令牌）
- **全绿**：说明依赖、JDK、officia 能力链路都正常
- **失败**：先看异常消息**前缀**定位故障域（`MS-DOC` / `CFB` / `OPC` / `License` …），见 `officia-troubleshooting`

### 4. 想看可视化效果

引导用 `/testbench`（浏览器里上传自己的文件实测）。

## 提示

- 跑测试前确认本地仓有 officia（没有先在 `../officia` 跑 `mvn install -DskipTests`）
- 要给自己的业务写类似的验证测试 → 激活 `officia-demo-test` 技能拿断言模板
