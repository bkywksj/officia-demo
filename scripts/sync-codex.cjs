#!/usr/bin/env node
/**
 * officia-demo 技能体系同步与自检
 *
 * 解决的问题：技能体系是双系统（.claude/ + .codex/），改一处要同步多处，
 * 手工做必漏。本脚本把「镜像 + 六项一致性校验」自动化。
 *
 * 用法：
 *   node scripts/sync-codex.cjs           同步 + 校验（会写 .codex/）
 *   node scripts/sync-codex.cjs --check   只校验不写（CI / 提交前用）
 *
 * 零第三方依赖，只用 Node 内置模块（与 officia 的气质一致）。
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const CHECK_ONLY = process.argv.includes('--check');

const P = {
  claudeSkills: path.join(ROOT, '.claude/skills'),
  claudeCommands: path.join(ROOT, '.claude/commands'),
  codexSkills: path.join(ROOT, '.codex/skills'),
  hook: path.join(ROOT, '.claude/hooks/skill-forced-eval.cjs'),
  claudeMd: path.join(ROOT, 'CLAUDE.md'),
  agentsMd: path.join(ROOT, 'AGENTS.md'),
  statusJson: path.join(ROOT, 'status.json'),
  cmdMeta: path.join(ROOT, 'scripts/command-skill-meta.json'),
};

const problems = [];
const blocking = [];   // 必须人工修的问题，阻断重建（重建修不好它们）
const notes = [];
function ok(msg) { notes.push(msg); }
/** 可由重建修复的问题（镜像缺失/漂移、计数不符） */
function fail(msg) { problems.push(msg); }
/** 人工才能修的问题：修不好就不该重建，否则把错误状态固化进 .codex */
function failBlocking(msg) { blocking.push(msg); problems.push(msg); }

function readUtf8(f) { return fs.readFileSync(f, 'utf8'); }
function listDirs(p) {
  if (!fs.existsSync(p)) return [];
  return fs.readdirSync(p, { withFileTypes: true })
    .filter(d => d.isDirectory()).map(d => d.name).sort();
}

/**
 * 解析 SKILL.md 的 frontmatter（只取需要的字段，不引 YAML 库）。
 * 先归一化 CRLF——Windows 上编辑过的技能可能是 \r\n 行尾，不能因此误判"缺 frontmatter"。
 */
function parseFrontmatter(raw) {
  const text = raw.replace(/\r\n/g, '\n');
  if (!text.startsWith('---\n')) return null;
  const end = text.indexOf('\n---', 3);
  if (end < 0) return null;
  const fm = text.slice(4, end);
  const name = (fm.match(/^name:\s*(.+)$/m) || [])[1];
  const hasDesc = /^description:/m.test(fm);
  return { name: name ? name.trim() : null, hasDesc, raw: fm };
}

// ───────────────────────── 1. 校验 Claude 端技能本身 ─────────────────────────

const skills = listDirs(P.claudeSkills);
if (skills.length === 0) failBlocking('.claude/skills 下没有任何技能');

const crlfSkills = [];
for (const s of skills) {
  const file = path.join(P.claudeSkills, s, 'SKILL.md');
  if (!fs.existsSync(file)) { failBlocking(`技能 ${s} 缺 SKILL.md`); continue; }

  const buf = fs.readFileSync(file);
  if (buf[0] === 0xEF && buf[1] === 0xBB && buf[2] === 0xBF) {
    failBlocking(`技能 ${s}/SKILL.md 含 BOM（违反铁律3）`);
  }

  const text = buf.toString('utf8');
  if (text.includes('\r\n')) crlfSkills.push(s);   // 提示级：不阻断，但记下来
  const fm = parseFrontmatter(text);
  if (!fm) { failBlocking(`技能 ${s}/SKILL.md 缺 frontmatter（--- 包裹）`); continue; }
  if (!fm.name) failBlocking(`技能 ${s}/SKILL.md frontmatter 缺 name`);
  else if (fm.name !== s) failBlocking(`技能 ${s}/SKILL.md 的 name="${fm.name}" 与目录名不一致`);
  if (!fm.hasDesc) failBlocking(`技能 ${s}/SKILL.md frontmatter 缺 description`);
}
ok(`技能 frontmatter/BOM/name 校验：${skills.length} 个`);
if (crlfSkills.length) {
  ok(`提示：以下技能是 CRLF 行尾，与其余 LF 不一致（不阻断，建议统一）：${crlfSkills.join(', ')}`);
}

// ───────────────────────── 2. hook 技能清单 ↔ 技能目录 ─────────────────────────

const hookText = readUtf8(P.hook);
const hookSkills = (hookText.match(/^- (officia-[a-z0-9-]+):/gm) || [])
  .map(l => l.replace(/^- /, '').replace(/:$/, '')).sort();

const missingInHook = skills.filter(s => !hookSkills.includes(s));
const staleInHook = hookSkills.filter(s => !skills.includes(s));
if (missingInHook.length) {
  failBlocking(`skill-forced-eval.cjs 缺以下技能（AI 将永远不会激活它们）：${missingInHook.join(', ')}`);
}
if (staleInHook.length) {
  failBlocking(`skill-forced-eval.cjs 列了不存在的技能：${staleInHook.join(', ')}`);
}
if (!missingInHook.length && !staleInHook.length) ok(`hook 技能清单 ↔ 技能目录：${hookSkills.length} 个一致`);

// ───────────────────────── 3. CLAUDE.md / AGENTS.md 索引覆盖 ─────────────────────────

for (const [label, file] of [['CLAUDE.md', P.claudeMd], ['AGENTS.md', P.agentsMd]]) {
  const text = readUtf8(file);
  const missing = skills.filter(s => !text.includes(s));
  if (missing.length) failBlocking(`${label} 未收录技能：${missing.join(', ')}`);
  else ok(`${label} 技能索引：${skills.length} 个全覆盖`);
}

// ───────────────────────── 4. 命令 ↔ 元数据 ─────────────────────────

const commands = fs.existsSync(P.claudeCommands)
  ? fs.readdirSync(P.claudeCommands).filter(f => f.endsWith('.md')).map(f => f.replace(/\.md$/, '')).sort()
  : [];
const meta = JSON.parse(readUtf8(P.cmdMeta));
const missingMeta = commands.filter(c => !meta[c]);
if (missingMeta.length) {
  failBlocking(`scripts/command-skill-meta.json 缺命令元数据：${missingMeta.join(', ')}（补上才能生成 Codex 命令技能）`);
} else {
  ok(`命令元数据：${commands.length} 个齐备`);
}

// ───────────────────────── 5. 同步到 .codex/skills ─────────────────────────

function copyDirSync(src, dst) {
  fs.mkdirSync(dst, { recursive: true });
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, e.name), d = path.join(dst, e.name);
    if (e.isDirectory()) copyDirSync(s, d);
    else fs.copyFileSync(s, d);
  }
}

function buildCommandSkill(name) {
  const m = meta[name];
  const body = readUtf8(path.join(P.claudeCommands, name + '.md'));
  const fm = [
    '---',
    'name: ' + name,
    'description: |',
    '  ' + m.desc,
    '',
    '  触发场景：',
    ...m.scenes.map(s => '  - ' + s),
    '',
    '  触发词：' + m.words,
    'disable-model-invocation: false',
    'allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]',
    '---',
    '',
  ].join('\n');
  return fm + body;
}

if (!CHECK_ONLY && blocking.length === 0) {
  // 全量重建 .codex/skills，避免残留已删除的技能
  fs.rmSync(P.codexSkills, { recursive: true, force: true });
  fs.mkdirSync(P.codexSkills, { recursive: true });

  for (const s of skills) {
    copyDirSync(path.join(P.claudeSkills, s), path.join(P.codexSkills, s));
  }
  for (const c of commands) {
    const dir = path.join(P.codexSkills, c);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'SKILL.md'), buildCommandSkill(c), 'utf8');
  }
  ok(`已重建 .codex/skills：${skills.length} 技能镜像 + ${commands.length} 命令技能 = ${skills.length + commands.length}`);
}

// ───────────────────────── 6. 双端一致性校验 ─────────────────────────

const codexSkills = listDirs(P.codexSkills);
const expected = [...skills, ...commands].sort();
const diffA = expected.filter(s => !codexSkills.includes(s));
const diffB = codexSkills.filter(s => !expected.includes(s));
if (diffA.length) fail(`.codex/skills 缺：${diffA.join(', ')}`);
if (diffB.length) fail(`.codex/skills 多出（应删）：${diffB.join(', ')}`);

// 镜像技能必须与 Claude 端字节一致
let byteMismatch = [];
for (const s of skills) {
  const a = path.join(P.claudeSkills, s, 'SKILL.md');
  const b = path.join(P.codexSkills, s, 'SKILL.md');
  if (!fs.existsSync(b)) continue;
  if (!fs.readFileSync(a).equals(fs.readFileSync(b))) byteMismatch.push(s);
}
if (byteMismatch.length) fail(`镜像与 Claude 端不一致（需重新同步）：${byteMismatch.join(', ')}`);
else if (!diffA.length && !diffB.length) ok(`双端一致：${codexSkills.length} 个（${skills.length} 镜像字节一致 + ${commands.length} 命令技能）`);

// ───────────────────────── 7. status.json 计数 ─────────────────────────

try {
  const st = JSON.parse(readUtf8(P.statusJson));
  if (st.skills && st.skills.total !== skills.length) {
    fail(`status.json skills.total=${st.skills.total}，实际 ${skills.length} 个（请更新）`);
  }
  if (st.commands && st.commands.count !== commands.length) {
    fail(`status.json commands.count=${st.commands.count}，实际 ${commands.length} 个（请更新）`);
  }
  if (st.codex_mirror && st.codex_mirror.skills !== codexSkills.length) {
    fail(`status.json codex_mirror.skills=${st.codex_mirror.skills}，实际 ${codexSkills.length} 个（请更新）`);
  }
  if (!problems.some(p => p.includes('status.json'))) ok('status.json 计数一致');
} catch (e) {
  fail('status.json 解析失败：' + e.message);
}

// ───────────────────────── 报告 ─────────────────────────

console.log('\n=== officia-demo 技能体系' + (CHECK_ONLY ? '自检' : '同步 + 自检') + ' ===\n');
for (const n of notes) console.log('  ✅ ' + n);
if (problems.length) {
  console.log('');
  for (const p of problems) console.log('  ❌ ' + p);
  console.log(`\n不通过：${problems.length} 个问题待修复\n`);
  process.exit(1);
}
console.log('\n全部通过。\n');
