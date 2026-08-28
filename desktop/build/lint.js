/**
 * 轻量语法门禁（npm run lint）
 * ---------------------------
 * 仓库未配置 ESLint，CI 只需保证所有 JS 可被解析：
 *   - CommonJS 文件（src/main/**）：直接 node --check
 *   - 含 import/export 的 ESM 文件（src/renderer/**、vite.config.js）：
 *     先按脚本检查失败后，经 stdin 以 --input-type=module 复核
 * 任一文件两种模式都解析失败 → 非零退出码阻断 CI。
 */
'use strict';

const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

/** 递归收集目录下全部 .js 文件 */
function collectJsFiles(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) collectJsFiles(full, out);
    else if (entry.name.endsWith('.js')) out.push(full);
  }
  return out;
}

/** 返回 null 表示解析通过，否则返回携带 stderr 的 Error */
function tryCheck(args, input) {
  try {
    execFileSync(process.execPath, args, input === undefined ? {} : { input });
    return null;
  } catch (err) {
    return err;
  }
}

const root = path.join(__dirname, '..');
const files = [
  ...collectJsFiles(path.join(root, 'src')),
  path.join(root, 'vite.config.js'),
];

let failed = 0;
for (const file of files) {
  const rel = path.relative(root, file).replace(/\\/g, '/');
  // 先按 CommonJS 脚本检查；失败（如 renderer 的 import/export）再按 ES 模块复核
  const asScript = tryCheck(['--check', file]);
  const asModule = asScript
    ? tryCheck(['--input-type=module', '--check', '-'], fs.readFileSync(file, 'utf-8'))
    : null;
  if (asScript && asModule) {
    failed++;
    const detail = String(asModule.stderr || asModule.message || '').trim();
    console.error(`[lint] 语法检查未通过: ${rel}\n${detail}`);
  }
}

if (failed > 0) {
  console.error(`[lint] ${failed}/${files.length} 个文件未通过语法检查`);
  process.exit(1);
}
console.log(`[lint] ${files.length} 个 JS 文件语法检查通过`);
