# officia-demo

**独立的 officia 依赖消费方示例**——模拟真实客户，通过 Maven 坐标引入已发布的 officia，演示各能力使用 + 授权门控，并作回归验证。

> 不属于 officia reactor（同级独立目录，独立 groupId `plus.ruoyi.demo`）。它引的是 `plus.ruoyi:officia-all` / `officia-license` 的**发布产物**，因此能证明"发布的 jar 真的能被外部项目引入使用"。

> 🔒 **本示例不携带任何 License 令牌 / `.lic` 证书**（面向公开展示）。授权解锁效果由使用者放入自己的 `officia.lic` 时生效。

## 前置：让本地仓有 officia

demo 依赖 `officia-*:1.0.0`。联网环境直接在 officia 目录 `mvn install` 即可；
离线环境用 JDK `jar` 手动布署（见 officia 项目根 `mvn` 受限时的绕过：把各模块 `target/classes` 打成 jar 拷进 `~/.m2/repository/plus/ruoyi/`）。

## 跑起来

```bash
mvn -o test                 # 示例测试全绿（授权演示在未放 officia.lic 时自动跳过）
mvn -o exec:java -Dexec.mainClass=plus.ruoyi.officia.demo.Demo   # 或直接 run Demo.main
```

## 示例清单

| 测试 | 演示 |
|---|---|
| `CellsDemoTest` | CSV→PDF、CSV 解析(RFC4180)、公式求值 `=A1+A2` |
| `BarcodeDemoTest` | Code128 / QR → PNG |
| `PdfDemoTest` | 多 PDF 合并、AES-256 加密 |
| `LicenseDemoTest` | 未授权评估态、无效/空 License 抛异常(不 System.exit) |
| `EnforcementDemoTest` | 授权门控：enforcement 关=不降级 / 开+未授权=降级(水印/限页) / 开+自备 officia.lic=不降级(无 lic 自动跳过) |

## 意义

- ✅ **发布可消费**：`import officia-all` 即得全部能力，零传递第三方依赖(officia 运行时零依赖)。
- ✅ **授权门控可见**：`EnforcementDemoTest` 展示"未买降级、买了不降级"，且库不阻断宿主（不 System.exit）。
- ✅ **回归护栏**：升级 officia 版本后重跑 demo，能第一时间发现 API 破坏性变更。

## 如何加载自己的 License（无需写代码）

真实客户从 flm 后台"授权管理 → 签发"下载自己的 `officia.lic`，**放到约定位置即可，零代码**——officia 首次使用能力时自动查找加载：

- classpath `/officia.lic`（丢进 `src/main/resources/`）
- 工作目录 `./officia.lic`
- 环境变量 `OFFICIA_LICENSE` 或 `-Dofficia.license=<路径|令牌>`

也可显式加载（优先级最高）：`OfficiaLicense.setLicense(new File("officia.lic"))`。
