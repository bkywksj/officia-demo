# officia-demo AI 技能体系使用指南

这份指南面向**拿到 officia 想尽快用起来**的开发者。技能体系已内置在本项目，用 Claude Code 或 Codex CLI 打开本目录即可生效。

---

## 这套技能是干什么的

它让 AI 助手在回答 officia 相关问题时，**先读项目内置的技能文档、再动手**，而不是凭训练数据猜 API。

好处很实际：

- 给出的方法签名是**真的存在**的（技能内容全部从 `../officia/` 源码提取）
- 会主动提醒你那些**必踩的坑**（中文字体、评估水印、`.doc` 的不支持项）
- 明确告诉你**做不到什么**（加密 DOC、竖排 RTL、未压测的高并发），不给虚假承诺

> ⚠️ 本体系面向**使用 officia**。要开发 officia 库本身，请打开同级 `../officia/`——那里有另一套面向库开发者的 26 个技能。

---

## 30 秒上手

### Claude Code

```bash
cd E:/my/高级模块/officia-demo
claude
```

直接说人话即可，技能会自动匹配：

```
把这个 docx 转成 PDF，代码给我
生成的 PDF 有水印，怎么去掉
中文显示成方块了
帮我做一个上传 Word 返回 PDF 的 Spring Boot 接口
officia 能处理加密的 doc 吗
```

### Codex CLI

```bash
cd E:/my/高级模块/officia-demo
codex
```

首次启动会询问是否信任 `.codex/`，选信任后 hooks 生效（技能自动评估 + 经验加载 + 命令安全检查）。

---

## 六个快捷命令

| 命令 | 用途 | 什么时候用 |
|---|---|---|
| `/convert` | 一句话做文档转换 | 知道要转什么，想直接拿代码 |
| `/license` | 授权检查与加载指引 | 有水印 / 拿到 `.lic` 不知道放哪 |
| `/testbench` | 启动可视化测试台 | 想上传自己的文件实测效果 |
| `/check` | 接入自检（八项） | 刚接入 / 上线前 / 换环境 |
| `/demo` | 跑示例并解读 | 想看 demo 演示了什么 |
| `/next` | 下一步建议 | 不知道接下来该做什么 |

---

## 18 个技能：按你的问题找

### 「我要开始用」

| 你的问题 | 技能 |
|---|---|
| officia 能做什么？我这个需求它支持吗？ | `officia-capability-map` |
| 怎么把它引进我的项目？内网怎么装？ | `officia-setup` |
| 输出有水印，怎么去掉？ | `officia-license` |

### 「我要做具体的事」

| 你要做的事 | 技能 |
|---|---|
| Word 转 PDF | `officia-words` |
| 按数据批量生成合同 / 通知书 / 报表 | `officia-template` |
| Excel 转 PDF / 导 CSV / 算公式 | `officia-cells` |
| PPT 转 PDF | `officia-slides` |
| PDF 合并拆分加水印加密 | `officia-pdf` |
| 生成条码 / 二维码 | `officia-barcode` |
| 图片处理 / 图片转 PDF | `officia-imaging` |
| 解析邮件 / 邮件归档 | `officia-email` |

### 「我遇到问题了」

| 症状 | 技能 |
|---|---|
| 中文是方块 / 空白 | `officia-chinese-font` |
| 抛异常 / 结果不对 | `officia-troubleshooting` |
| 慢 / OOM / 并发安全吗 | `officia-performance` |

### 「我要上生产」

| 需求 | 技能 |
|---|---|
| 集成进 Spring Boot | `officia-spring-integration` |
| 写验证测试 | `officia-demo-test` |
| 手工实测 / 环境自检 | `officia-testbench` |
| 升级 officia 版本 | `officia-upgrade` |

---

## 新手最容易卡住的四件事

技能里都有详解，这里先给结论，省得你踩：

### 1. 中文变方块

PDF 内置字体不含中文。两条**不同**的入口，别搞混：

| 场景 | 怎么传 |
|---|---|
| 转换类（Words/Cells/Slides/Email → PDF） | `ConvertOptions.setFontDirectory("/usr/share/fonts")` |
| PDF 水印 / 页码 | 方法的 `byte[] fontTtf` 入参 |

→ `officia-chinese-font`

### 2. 「本地没水印，线上有水印」

不是 bug。门控开关是**构建期常量**：

- 你从源码 `mvn install` 装的 = 开发构建 → 门控关 → 无水印
- 正式发布的 jar = release 构建 → 门控恒开 → 未授权就降级

→ 正确做法是**加载 `.lic`**，不是想办法关门控。见 `officia-license`

### 3. 「支持 .doc 吗」

同一个 `OfficiaWords.toPdf(bytes)` 自动识别 docx / doc。但：

- **加密的 `.doc` 明确不支持**
- `.doc` 主链路可用且有绿测覆盖，但**未宣布生产就绪**
- 竖排 / RTL、DOC 内嵌字体是已知缺口

→ 上生产前用测试台拿真实样本跑一批。见 `officia-capability-map`

### 4. 大文件 OOM

流式 API 只省**输出侧**内存，输入与排版仍需整篇在内存（这是格式本质，不是能优化的）。
officia **未做系统压测**，并发保护要放在你这侧（有界线程池 + 限文件大小 + 设超时）。

→ `officia-performance`

---

## 可视化测试台：比读文档更快

```bash
mvn -o package
java -jar target/officia-demo-1.0.0.jar
```

浏览器里**上传自己的文件**实测所有能力，每步显示页数/耗时/体积、内嵌预览、可下载。

10 个面板：Words · 模板填充 · Cells · Slides · PDF 工具箱 · Imaging · BarCode · Email · 授权门控对比 · 批量回归。

**最高价值的三个用法**：

1. **判别"是我的代码还是 officia"**——测试台能转成功 = 你的调用有问题
2. **看清授权效果**——同一份数据在门控开/关下并排对比
3. **环境自检**——点「批量回归」，全绿说明依赖/JDK/能力链路都正常

> 上传文件只存内存、重启即清、不落盘；服务绑定 `127.0.0.1` 仅本机可访问。

详见 `officia-testbench`。

---

## 技能体系怎么组织的

```
officia-demo/
├── CLAUDE.md                  # Claude Code 入口（三铁律 + 技能索引）
├── AGENTS.md                  # Codex 入口（等价内容 + 24 技能清单）
├── status.json                # 技能体系状态台账（含真相源核实记录）
├── docs/usage-guide.md        # 本文件
├── .claude/
│   ├── settings.json          # Hook 注册
│   ├── hooks/                 # skill-forced-eval（强制技能评估）+ pre-tool-use（铁律守门）
│   ├── commands/              # 6 个快捷命令
│   ├── skills/                # 18 个技能
│   └── docs/experience/       # 经验沉淀目录
└── .codex/
    ├── config.toml            # features hooks = true
    ├── hooks.json             # 4 个事件处理器
    ├── hooks/                 # session-start / skill-forced-eval / pre-tool-use / stop
    └── skills/                # 24 个（18 技能镜像 + 6 命令转技能）
```

### 两个 hook 在替你做什么

| Hook | 作用 |
|---|---|
| `skill-forced-eval` | 每次提问前注入"先评估技能再动手"的流程，把技能激活率从约 25% 提到 90%+ |
| `pre-tool-use` | 拦危险命令、拦 `.lic` 写入、拦 BOM、调用门面时提醒核对真实签名 |

---

## 技能内容的可信度

所有 API 事实来自 `../officia/` 的**真实源码**，不是训练数据。核实过的关键事实包括：

- 8 个门面的全部公开方法签名与 Javadoc
- `ConvertOptions` 四项配置与默认值、`PageSize` 五个枚举尺寸
- 模板引擎九类占位符语法（从 `Wml*` / `Template*` 实现类提取）
- `FormulaEngine` 支持的 34 个 Excel 函数
- 条码各码制的位数约束与默认尺寸参数
- `CfbLimits.defaults()` 的十项真实上限
- `BuildFlags.ENFORCED_BY_DEFAULT` 的构建期语义
- 测试台 32 个 API 端点与 10 个回归用例

**同时如实记录了不支持项与未验证事实**（见 `status.json` 的 `honest_disclaimers_carried`）——不给虚假承诺是这套技能的底线。

> 若你发现技能内容与源码不符（officia 演进后），以 `../officia/` 源码为准，并更新对应 SKILL.md。

---

## 想给自己的场景加技能

在 `.claude/skills/` 下新建目录 + `SKILL.md`，frontmatter 四要素：

```markdown
---
name: 与目录名完全一致
description: |
  一句话说明。

  触发场景：
  - ...
  触发词：...
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---
```

加完后记得：

1. 把技能名与触发词补进 `.claude/hooks/skill-forced-eval.cjs` 的清单
2. 补进 `CLAUDE.md` 的技能索引表
3. 镜像到 `.codex/skills/`（保持双系统一致）
4. 更新 `status.json` 的计数

---

## 遇到问题找谁

| 类型 | 去哪 |
|---|---|
| 用法不清楚 | 问 AI（技能会自动匹配），或读 `../officia-docs/` 文档站 |
| 确认能力边界 | `cat ../officia/status.json` 看实测事实 |
| 授权购买咨询 | 见 `../officia-docs/docs/guide/licensing.md` |
| 疑似 officia 的问题 | 先用测试台复现，带上文件头 4 字节 + 完整异常消息 + officia 版本反馈 |
