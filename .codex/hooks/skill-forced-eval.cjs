#!/usr/bin/env node
/**
 * Codex UserPromptSubmit Hook - 强制技能评估（officia-demo / 使用者版）
 *
 * 与 Claude 端 .claude/hooks/skill-forced-eval.cjs 设计哲学不同：
 * Codex 启动时已自动加载所有 SKILL.md 的 frontmatter（name + description），
 * 因此这里**不重复列出技能清单**，只注入"流程指令 + 铁律提醒"。
 *
 * Codex stdin schema：
 *   { session_id, prompt, cwd, hook_event_name, ... }
 *
 * Codex 输出：
 *   - {additionalContext: "..."} → 加入上下文
 *   - {} → 不注入
 */

const fs = require('fs');

let raw = '';
let input = {};
try {
  raw = fs.readFileSync(0, 'utf8');
  if (raw) input = JSON.parse(raw);
} catch {
  process.stdout.write('{}');
  process.exit(0);
}

const prompt = (input.prompt || '').trim();

// 恢复会话跳过（防上下文溢出死循环）
const skipPatterns = [
  'continued from a previous conversation',
  'ran out of context',
  'Conversation compacted',
  'context window',
  'session is being continued'
];
if (skipPatterns.some(p => prompt.toLowerCase().includes(p.toLowerCase()))) {
  process.stdout.write('{}');
  process.exit(0);
}

// 斜杠命令跳过（意图已明确）
if (/^\/[^\/\s]+/.test(prompt)) {
  process.stdout.write('{}');
  process.exit(0);
}

const context = `## 强制技能评估流程（officia-demo）

本项目面向 **使用 officia 的开发者**（接入 / 调 API / 排错 / 集成），
不是开发 officia 库本身——改库请去同级 ../officia/（那里另有一套面向库开发者的技能）。

回答前必须：
1. **评估**：列出匹配的 skill（技能名: 理由）。你已加载全部 SKILL.md 的 frontmatter，按 description 里的触发场景/触发词匹配。无匹配写"无匹配技能"。
2. **激活**：读取匹配技能的完整 SKILL.md 正文，一次一个。
3. **实现**：全部激活完成后再动手。

技能分四组：入口与接入（capability-map / setup / license）、能力使用（words / template / cells / slides / pdf / barcode / imaging / email）、排坑与工程化（chinese-font / troubleshooting / performance / spring-integration / upgrade）、Demo 自身（testbench / demo-test）。

## 三条铁律（恒常生效）

1. **API 事实以 ../officia/ 源码为准** —— 任何方法签名/参数/行为必须核对 \`../officia/officia-*/src/main/java/**/Officia*.java\`，禁凭印象、禁按 Aspose/POI/iText 用法类推。真相源优先级：源码 > 绿测 > 文档站 > README/历史注释（后者可能过时，如 words/template/package-info.java 的"骨架预留"已过时）。
2. **.lic 与授权令牌绝不入库** —— 不生成/不硬编码/不提交，不给绕过水印的建议。
3. **UTF-8 无 BOM** —— 所有新建修改文件。`;

process.stdout.write(JSON.stringify({ additionalContext: context }));
process.exit(0);
