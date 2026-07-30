#!/usr/bin/env node
/**
 * PreToolUse Hook - 工具使用前触发（officia-demo / 使用者版）
 *
 * 功能：
 *   1. 阻止危险 Bash 命令
 *   2. 【铁律2 守门】拦截把 License 令牌 / .lic 内容写进仓库
 *   3. 【铁律3 守门】拦截 BOM 开头的写入
 *   4. 【铁律1 提醒】写 .java 出现 officia 相关调用时，提醒核对 ../officia/ 真实签名
 *   5. 保护 .claude/ 配置与敏感文件
 *
 * 与 CLAUDE.md 三铁律配对，把"文档约束"变成"机检强制"。
 */

const fs = require('fs');

let inputData = '';
try {
  inputData = fs.readFileSync(0, 'utf8');
} catch {
  console.log(JSON.stringify({ continue: true }));
  process.exit(0);
}

let input;
try {
  input = JSON.parse(inputData);
} catch {
  console.log(JSON.stringify({ continue: true }));
  process.exit(0);
}

const toolName = input.tool_name;
const ti = input.tool_input || {};

function block(reason) {
  console.log(JSON.stringify({ decision: 'block', reason }));
  process.exit(0);
}
function warn(msg) {
  console.log(JSON.stringify({ continue: true, systemMessage: msg }));
  process.exit(0);
}

// ───────────── Bash 危险命令 ─────────────
if (toolName === 'Bash') {
  const command = ti.command || '';

  // Windows: > nul 会创建名为 nul 的文件
  if (/[12]?\s*>\s*nul\b/i.test(command)) {
    block(`**命令被阻止**：检测到 \`> nul\`\n请改用 \`> /dev/null 2>&1\`（跨平台）。\n原命令: \`${command}\``);
  }

  const dangerous = [
    { p: /rm\s+-rf\s+\/(?!\w)/, r: '删除根目录' },
    { p: /rm\s+-rf\s+\*/, r: '删除所有文件' },
    { p: /git\s+push\s+--force\s+(origin\s+)?(main|master)/i, r: '强制推送到主分支' },
    { p: /git\s+reset\s+--hard\s+HEAD~\d+/, r: '硬重置多个提交' },
    { p: /mkfs\./, r: '格式化文件系统' },
    { p: /:\(\)\{ :\|:& \};:/, r: 'Fork 炸弹' },
  ];
  for (const { p, r } of dangerous) {
    if (p.test(command)) {
      block(`**危险操作被阻止**\n命令: \`${command}\`\n原因: ${r}\n如确需执行请手动在终端运行。`);
    }
  }

  // 提醒：git add 时别把 .lic 带进去
  if (/git\s+add\s+(-A|--all|\.)\b/.test(command)) {
    warn('**铁律2 提醒**：`git add` 全量暂存前，确认没有 `.lic` / 授权令牌文件被带入（.gitignore 已拦 `*.lic`，但改名/别处的令牌不会被拦）。');
  }
}

// ───────────── 铁律守门（Write / Edit / MultiEdit） ─────────────
if (toolName === 'Write' || toolName === 'Edit' || toolName === 'MultiEdit') {
  const filePath = (ti.file_path || '').replace(/\\/g, '/');

  // 收集"将写入"的文本
  let content = '';
  if (toolName === 'Write') content = ti.content || '';
  else if (toolName === 'Edit') content = ti.new_string || '';
  else if (toolName === 'MultiEdit') content = (ti.edits || []).map(e => e.new_string || '').join('\n');

  // 铁律3：UTF-8 无 BOM —— 仅 Write 从头写才判首字符 BOM
  if (toolName === 'Write' && content.charCodeAt(0) === 0xFEFF) {
    block('**违反铁律3（UTF-8 无 BOM）**：写入内容以 BOM(\\uFEFF) 开头。请去掉文件头 BOM 再写。');
  }

  // 铁律2：授权令牌不入库 —— 直接写 .lic 文件
  if (/\.lic$/i.test(filePath)) {
    block('**违反铁律2（授权令牌不入库）**：不要用工具生成/写入 `.lic` 文件。\n真实 `.lic` 由用户从 flm 后台下载后自行放到约定位置（classpath `/officia.lic`、工作目录 `./officia.lic`、`OFFICIA_LICENSE` 环境变量或 `-Dofficia.license=`）。');
  }

  // 铁律2：疑似把令牌正文硬编码进源码/配置
  //   令牌形态为长串 Base64/紧凑 JWT 风格文本，配合 license 关键字判定，降低误报
  if (/(officia[._-]?license|setLicense|OFFICIA_LICENSE)/i.test(content)) {
    const suspicious = content.match(/["'][A-Za-z0-9+/=_-]{80,}["']/);
    if (suspicious) {
      block('**违反铁律2（授权令牌不入库）**：检测到 License 上下文中出现长串疑似令牌的字面量。\n请改为从文件/环境变量读取（`OfficiaLicense.setLicense(new File(path))`），不要把令牌硬编码进源码或配置。');
    }
  }

  // 铁律1：写 .java 调用 officia 门面 —— 提醒核对真实签名
  if (/\.java$/.test(filePath) && /\bOfficia(Words|Cells|Slides|Pdf|BarCode|Imaging|Email|License)\b/.test(content)) {
    warn('**铁律1 提醒**：新增/修改的 .java 调用了 officia 门面。请核对 `../officia/officia-*/src/main/java/**/Officia*.java` 的真实方法签名（禁按 Aspose/POI/iText 用法类推）。officia 门面是 `public final class` + 全静态 + `byte[]` 进 `byte[]` 出。');
  }

  // 保护 .claude/ 与敏感文件
  if (toolName === 'Write') {
    if (filePath.includes('.claude/hooks/') || filePath.endsWith('.claude/settings.json')) {
      warn(`**敏感文件**：正在写入 \`${filePath}\`，请确保不破坏 Claude Code 配置。`);
    }
    if (/(\.env\.local|\.env\.production|credentials\.json|secrets\.json)$/.test(filePath)) {
      warn(`**敏感文件**：正在写入 \`${filePath}\`，勿提交敏感信息到 Git。`);
    }
  }
}

// 默认放行
console.log(JSON.stringify({ continue: true }));
