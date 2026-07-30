# /sync - 技能体系同步与自检

改完技能后跑这个，把 `.claude/` 的改动镜像到 `.codex/`，并校验六处一致性。

## 执行步骤

### 1. 跑同步脚本

```bash
node scripts/sync-codex.cjs
```

只想检查不想写入（提交前 / CI）：

```bash
node scripts/sync-codex.cjs --check
```

### 2. 脚本做了什么

| 动作 | 说明 |
|---|---|
| 校验技能本身 | frontmatter 完整、`name` == 目录名、无 BOM |
| 校验 hook 清单 | `.claude/hooks/skill-forced-eval.cjs` ↔ 技能目录，**缺一个就报错**（缺了该技能永不激活） |
| 校验入口文档 | `CLAUDE.md` / `AGENTS.md` 是否收录全部技能 |
| 校验命令元数据 | `.claude/commands/*.md` ↔ `scripts/command-skill-meta.json` |
| **重建 `.codex/skills`** | 全量重建：技能字节镜像 + 命令加 YAML 头转技能（自动清理已删技能残留） |
| 校验双端一致 | 目录集合一致 + 镜像文件字节一致 |
| 校验 status.json | `skills.total` / `commands.count` / `codex_mirror.skills` 计数 |

### 3. 报错处置

脚本退出码非 0 时按提示修，最常见三类：

| 报错 | 处置 |
|---|---|
| `skill-forced-eval.cjs 缺以下技能` | 在 hook 清单对应分组加 `- 技能名: 触发词` |
| `CLAUDE.md / AGENTS.md 未收录技能` | 加到对应分组的索引表（脚本只校验不代写） |
| `status.json xxx=N，实际 M 个` | 更新台账计数与 `categories.list` |

### 4. 建议加跑的两项

```bash
# 官方 Agent Skills Spec 校验
python E:/my/AI工作站/projects/skill-factory/scripts/demo_skill_validate.py .claude/skills
python E:/my/AI工作站/projects/skill-factory/scripts/demo_skill_validate.py .codex/skills
```

## 什么时候必须跑

- 新增 / 修改 / 删除任何技能之后
- 新增 / 修改命令之后
- 提交前
- 从别处拉取了技能改动之后

## 提示

- **不要手改 `.codex/skills/`** —— 它是生成物，同步时会被全量重建覆盖
- 详细的维护流程（新增/修改/删除/跟随 officia 升级）见技能 `officia-skill-maintain`
