---
name: officia-skill-maintain
description: |
  维护本项目的技能体系：新增 / 修改 / 删除技能，跟随 officia 版本更新技能内容，
  以及双系统（.claude + .codex）六处同步与一键自检。改任何技能前先读这个。

  触发场景：
  - 要给这个项目加一个新技能
  - 要改某个技能的内容 / 触发词
  - officia 升级了，技能里写的 API 过时了
  - 改完技能不知道还要同步哪些地方
  - 想确认技能体系有没有不一致

  触发词：加技能、新增技能、改技能、删技能、更新技能、技能维护、同步技能、镜像、一致性、skill、SKILL.md、技能体系
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep", "Glob"]
---

# 维护技能体系

## 概述

本项目是**双系统**技能体系：`.claude/`（Claude Code）+ `.codex/`（Codex CLI）。
改一处，往往要同步多处——**漏一处不会报错，但会静默失效**（最典型：技能文件存在，AI 却从不激活它，因为 hook 清单里没有它）。

所以有一条铁规：**任何技能改动后，必须跑一次同步脚本**。

```bash
node scripts/sync-codex.cjs          # 同步 + 自检
node scripts/sync-codex.cjs --check  # 只自检不写（提交前 / CI 用）
```

## 🔴 六处同步点

| # | 位置 | 作用 | 漏了会怎样 |
|---|---|---|---|
| 1 | `.claude/skills/{name}/SKILL.md` | 技能正文 | — |
| 2 | `.claude/hooks/skill-forced-eval.cjs` 的技能清单 | 让 AI 知道有这个技能 | **技能永不被激活**（最隐蔽） |
| 3 | `CLAUDE.md` 的技能索引表 | Claude 端入口文档 | 用户/AI 看不到 |
| 4 | `AGENTS.md` 的技能清单表 | Codex 端入口文档 | Codex 侧看不到 |
| 5 | `.codex/skills/{name}/SKILL.md` | Codex 镜像 | Codex 用不了该技能 |
| 6 | `status.json` 的计数与分类 | 状态台账 | 台账失真 |

**第 2、5、6 项由 `sync-codex.cjs` 自动处理或强制校验**（第 5 项自动重建，第 2、6 项检出不一致会报错并退出 1）。
**第 3、4 项脚本只校验覆盖、不代写**——需要你手动加到索引表的正确分组里。

## 场景一：新增技能

### 1. 建目录与 SKILL.md

```bash
mkdir -p .claude/skills/officia-新技能名
```

`SKILL.md` 模板（frontmatter 四要素，UTF-8 无 BOM）：

```markdown
---
name: officia-新技能名
description: |
  一句话说清这个技能解决什么问题。

  触发场景：
  - 用户会在什么情况下需要它（写 3-5 条，具体到症状）
  - ...

  触发词：逗号分隔的关键词，覆盖用户可能的说法（含口语说法）
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# 标题

## 概述
<这个技能是干什么的，边界在哪>

## <正文：真实 API + 可运行代码 + 表格化决策>

## 排查表
| 现象 | 原因 | 处置 |

## 相关技能
| 接下来 | 用 |
```

> **命名约定**：本项目技能一律 `officia-` 前缀（hook 清单靠 `^- (officia-[a-z0-9-]+):` 正则提取，不加前缀会被漏掉）。

### 2. 补 hook 清单

在 `.claude/hooks/skill-forced-eval.cjs` 的对应分组里加一行，格式**必须**是 `- 技能名: 触发词`：

```javascript
- officia-新技能名: 触发词1、触发词2、触发词3
```

### 3. 补两个入口文档的索引表

`CLAUDE.md` 与 `AGENTS.md` 各有分组表格（入口与接入 / 能力使用 / 排坑与工程化 / Demo 自身 / 体系维护），加到语义最贴的那组。

### 4. 更新 status.json

`skills.total`、`skills.completed`、对应 `categories` 的 `count` 与 `list`、`codex_mirror.skills`。

### 5. 同步 + 校验

```bash
node scripts/sync-codex.cjs
```

全绿才算完成。

## 场景二：修改现有技能

改 `.claude/skills/{name}/SKILL.md` 后：

```bash
node scripts/sync-codex.cjs     # 重新镜像到 .codex（否则两端内容会漂移）
```

> ⚠️ **不要直接改 `.codex/skills/officia-*/SKILL.md`**——它是镜像，下次同步会被覆盖。
> 只有 `.codex/skills/{命令名}/` 是生成物（来自 `.claude/commands/` + `scripts/command-skill-meta.json`），同样不要手改。

改了**触发词**时，记得同步改 hook 清单里那一行——两处的触发词应保持一致。

## 场景三：删除技能

```bash
rm -rf .claude/skills/officia-要删的
```

然后删掉 hook 清单那一行、两个 md 索引表里的行、更新 status.json，最后跑同步（脚本会全量重建 `.codex/skills`，自动清掉残留）。

## 场景四：新增命令

```bash
# 1) 写命令正文（纯内容，无 frontmatter）
.claude/commands/新命令.md

# 2) 在 scripts/command-skill-meta.json 补一条元数据（desc / scenes / words）
#    不补的话同步会报「缺命令元数据」

# 3) 补 CLAUDE.md 与 AGENTS.md 的快捷命令表
# 4) 更新 status.json 的 commands.count / list
node scripts/sync-codex.cjs
```

## 🔴 场景五：officia 升级导致技能内容过时（最重要）

技能里的 API 事实全部来自 `../officia/` 源码。officia 一变，技能就可能失真。

> 💡 **上游侧也有对应机制**：`../officia/.claude/skills/officia-downstream-sync/SKILL.md` 是从上游视角写的
> **影响矩阵**（改了哪个门面/类型/行为 → 波及本项目哪些技能 + 文档站哪些页）。在 officia 里改 API 的人会被
> 该技能提醒同步下游；你在这边核对时，也可以直接拿那张矩阵反查。两者是同一条链的两端。

### 判断哪些技能受影响

```bash
# 1) 门面签名 diff —— 这是技能内容的主要来源
for m in words cells slides pdf barcode imaging email license; do
  echo "===== $m"
  grep -hE "^\s*public static" ../officia/officia-$m/src/main/java/plus/ruoyi/officia/$m/Officia*.java
done > /tmp/facade-now.txt
# 与上次留存的版本 diff

# 2) 能力状态事实
cat ../officia/status.json     # capabilities / explicitly_rejected / known_gaps / not_verified

# 3) 变更日志
cat ../officia-docs/docs/changelog.md
```

### 受影响技能对照

| officia 变动 | 要改的技能 |
|---|---|
| 某门面新增/删除/改签名 | 对应能力技能（`officia-words` / `officia-cells` / …） |
| `ConvertOptions` / `PageSize` 增减字段 | `officia-words`（配置表）+ 各转换类技能 |
| 模板占位符语法扩展 | `officia-template` |
| `FormulaEngine` 增减函数 | `officia-cells` 的函数表（现为 50 个） |
| 条码码制/参数变化 | `officia-barcode` |
| PDF 加密算法/`PdfEditor` 方法变化 | `officia-pdf` |
| `CfbLimits` 默认值变化 | `officia-performance` |
| 授权/门控语义变化 | `officia-license` |
| `explicitly_rejected` / `known_gaps` 增减 | `officia-capability-map` + 对应能力技能 |
| 异常消息前缀体系变化 | `officia-troubleshooting` |
| demo 测试台端点/面板变化 | `officia-testbench` |

### 更新原则

1. **只改事实，不改结构** —— 表格、排查表的骨架保持稳定，只更新其中的值
2. **删掉不再成立的说法** —— 特别是"不支持 X"变成"支持 X"时，务必删掉旧的否定表述
3. **诚实声明同步更新** —— `status.json` 的 `truth_sources_verified.honest_disclaimers_carried` 要跟着改
4. **改完跑一遍验证** —— 技能里的代码片段要能真的编译通过（可在 demo 里试）

## 内容质量标准（写技能时对照）

| 要求 | 说明 |
|---|---|
| **API 必须核实** | 每个方法签名都要能在 `../officia/` 源码里找到原文，禁止凭印象 |
| **诚实标注边界** | 不支持项、未验证项、已知缺口必须写出来，不给虚假承诺 |
| **代码可运行** | 含 import、完整可粘贴，不写伪代码 |
| **表格化决策** | "什么时候用 A、什么时候用 B" 用表格，别用长段落 |
| **带排查表** | 现象 → 原因 → 处置 三列，覆盖该能力最常见的坑 |
| **指向相关技能** | 结尾给"接下来该看哪个技能" |
| **面向使用者** | 讲怎么用，不讲库内部怎么实现（那是 `../officia/` 的技能） |

## 校验命令速查

```bash
# 本项目一键自检（六处一致性）
node scripts/sync-codex.cjs --check

# 官方 Agent Skills Spec 校验（若有 AI 工作站）
python E:/my/AI工作站/projects/skill-factory/scripts/demo_skill_validate.py .claude/skills
python E:/my/AI工作站/projects/skill-factory/scripts/demo_skill_validate.py .codex/skills

# BOM 扫描（铁律3）
find .claude .codex -name "*.md" -exec sh -c 'head -c3 "$1" | od -An -tx1 | grep -q "ef bb bf" && echo "BOM: $1"' _ {} \;

# hook 脚本语法
node --check .claude/hooks/skill-forced-eval.cjs
```

## 常见错误

| 症状 | 原因 | 处置 |
|---|---|---|
| 新技能写好了，AI 从来不用 | hook 清单没加（第 2 处） | 补 `skill-forced-eval.cjs`，跑同步 |
| Codex 里没有这个技能 | `.codex/skills` 没同步 | `node scripts/sync-codex.cjs` |
| 改了 Codex 端，下次又变回去 | 直接改了镜像文件 | 只改 `.claude/skills`，靠同步生成 |
| 同步脚本报"缺命令元数据" | 新命令没在 `command-skill-meta.json` 登记 | 补一条 |
| 同步脚本报 name 与目录不一致 | frontmatter 的 `name` 写错 | 两者必须完全相同 |
| 校验报 status.json 计数不符 | 加减技能后没更新台账 | 按报错提示改 |
| 技能里的代码编译不过 | 凭印象写了不存在的 API | 回 `../officia/` 源码核实（铁律1） |

## 相关技能

| 接下来 | 用 |
|---|---|
| officia 版本升级流程 | `officia-upgrade` |
| 确认某能力的真实边界 | `officia-capability-map` |
| 验证改动是否可行 | `officia-testbench` / `officia-demo-test` |
