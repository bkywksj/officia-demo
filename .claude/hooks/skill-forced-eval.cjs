#!/usr/bin/env node
/**
 * UserPromptSubmit Hook - 强制技能评估（officia-demo / 使用者版）
 *
 * 功能：把"使用 officia"场景下的技能激活率从约 25% 提升到 90% 以上。
 * 定位：本项目面向 **使用 officia 的开发者**（接入、调 API、排错、集成），
 *       不是开发 officia 库本身（那在 ../officia/，另有一套 26 技能）。
 */

const fs = require('fs');

// 从 stdin 读取用户输入
let inputData = '';
try {
  inputData = fs.readFileSync(0, 'utf8');
} catch {
  process.exit(0);
}

let input;
try {
  input = JSON.parse(inputData);
} catch {
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
  process.exit(0);
}

// 斜杠命令 / 其展开形式跳过（命令意图已明确）
const isSlashCommand = /^\/[^\/\s]+$/.test(prompt.split(/\s/)[0]);
const isExpandedCommand = /<command-name>/.test(prompt);
if (isSlashCommand || isExpandedCommand) {
  process.exit(0);
}

const instructions = `## 强制技能激活流程（必须执行）

### 步骤 1 - 评估（必须在响应中明确展示）

针对用户问题，列出匹配的技能：\`技能名: 理由\`，无匹配则写"无匹配技能"。

可用技能（面向"使用 officia"的开发者）：

【入口与接入】
- officia-capability-map: 不知道用哪个能力、能不能做X、支持不支持、能力选型、方法怎么选
- officia-setup: 引依赖、maven、gradle、坐标、版本、JDK、本地仓、install、离线、打包、jar
- officia-license: 授权、license、lic、水印、评估态、去水印、门控、enforcement、限页、激活

【能力使用】
- officia-words: Word、docx、doc、转PDF、word转换、文档转换、流式转换、页数、耗时
- officia-template: 模板、填充、占位符、邮件合并、批量生成、合同、报表、{{}}、循环、条件、格式器
- officia-cells: Excel、xlsx、CSV、表格、公式、求值、重算、导出CSV
- officia-slides: PPT、pptx、幻灯片、演示文稿、版式保真
- officia-pdf: PDF、合并、拆分、抽页、删页、旋转、水印、页码、抽文字、抽图片、加密、口令、元数据
- officia-barcode: 条码、条形码、二维码、QR、Code128、EAN、UPC、ITF、纠错
- officia-imaging: 图片、图像、缩放、裁剪、滤镜、灰度、模糊、锐化、水印、格式转换、图片转PDF
- officia-email: 邮件、EML、邮件解析、邮件归档、附件、收发件人

【排坑与工程化】
- officia-chinese-font: 中文、字体、方块、乱码、豆腐块、TTF、字体目录、CID、嵌入字体
- officia-troubleshooting: 报错、异常、OfficiaException、失败、打不开、空白、结果不对、排查、调试
- officia-performance: 性能、慢、内存、OOM、大文件、大文档、并发、超时、流式
- officia-spring-integration: Spring、SpringBoot、Web、接口、上传、下载、MultipartFile、Controller、集成、微服务
- officia-upgrade: 升级、版本、更新officia、破坏性变更、回归、兼容

【Demo 自身】
- officia-testbench: 测试台、demo、跑起来、启动、可视化、浏览器、面板、批量回归、实测
- officia-demo-test: 写测试、单测、JUnit、AssertJ、断言、验证

【技能体系维护】
- officia-skill-maintain: 加技能、新增技能、改技能、删技能、更新技能、技能维护、同步技能、镜像、一致性、SKILL.md

### 步骤 2 - 激活
对每个匹配的技能逐个激活：一次一个，等返回后再下一个（不要批量并行激活）。无匹配则跳过。

### 步骤 3 - 实现
所有匹配技能激活完成后，再动手实现。

铁律恒常生效：① API 事实以 ../officia/ 源码为准，禁凭印象或按 Aspose/POI 类推 ② .lic 与授权令牌绝不入库 ③ UTF-8 无 BOM。`;

console.log(instructions);
process.exit(0);
