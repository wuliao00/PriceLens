/**
 * PriceLens 自定义脚本管理器（移植自 Android 端 ScriptStore + ShizukuHelper）
 * ------------------------------------------------------------------
 * Android 端经 Shizuku（ADB/shell 权限）执行；桌面端主进程本就拥有
 * 本机权限，直接以 PowerShell 执行（与 Android 的 shell 语义对齐）。
 *
 * 持久化：%APPDATA%/pricelens/scripts.json（原子写）
 * 安全边界：
 *   - 仅主进程执行，渲染进程只能经 IPC 白名单传字符串
 *   - 单条脚本内容 ≤ 64KB，输出截断 200KB，超时 120s 强制终止
 *   - 预置脚本只读（不可删除/覆盖）
 */
'use strict';

const path = require('node:path');
const { app } = require('electron');
const { execFile } = require('node:child_process');
const storage = require('../cache/storage');

const MAX_CONTENT = 64 * 1024;      // 脚本内容上限 64KB
const MAX_OUTPUT  = 200 * 1024;     // 输出截断 200KB
const TIMEOUT_MS  = 120 * 1000;     // 执行超时 120s

/** 安全预置脚本（只读，与 Android 端对应） */
const BUILTINS = [
  {
    id: 'builtin_sysinfo',
    name: '查看系统信息',
    content: [
      'Write-Output "== 系统 =="',
      '[System.Environment]::OSVersion.VersionString',
      'Write-Output "== CPU =="',
      '(Get-CimInstance Win32_Processor).Name',
      'Write-Output "== 内存 =="',
      '"{0:N1} GB" -f ((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)',
    ].join('\r\n'),
    builtin: true,
  },
  {
    id: 'builtin_disk',
    name: '查看磁盘剩余空间',
    content: [
      'Get-PSDrive -PSProvider FileSystem | Where-Object { $_.Used -ne $null } |',
      '  ForEach-Object { "{0}: 剩余 {1:N1} GB / 共 {2:N1} GB" -f $_.Name, ($_.Free / 1GB), (($_.Used + $_.Free) / 1GB) }',
    ].join('\r\n'),
    builtin: true,
  },
  {
    id: 'builtin_ip',
    name: '查看本机 IP 地址',
    content: [
      'Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -ne "127.0.0.1" } |',
      '  ForEach-Object { "$($_.InterfaceAlias): $($_.IPAddress)" }',
    ].join('\r\n'),
    builtin: true,
  },
];

let customScripts = null; // 懒加载缓存

function scriptsFile() {
  return path.join(app.getPath('userData'), 'scripts.json');
}

/** 加载自定义脚本（内存缓存 + 文件回退） */
async function loadCustom() {
  if (customScripts) return customScripts;
  const data = await storage.readJson(scriptsFile(), []);
  customScripts = Array.isArray(data)
    ? data.filter((s) => s && typeof s.id === 'string' && typeof s.content === 'string')
    : [];
  return customScripts;
}

async function persist() {
  await storage.writeJson(scriptsFile(), customScripts || []);
}

/** 全量脚本 = 预置 + 自定义（含 builtin 标记） */
async function listAll() {
  const custom = await loadCustom();
  return [...BUILTINS, ...custom.map((s) => ({ ...s, builtin: false }))];
}

/** 新建或更新（id 为空 → 新建）；返回 { ok, scripts } 或 { ok:false, error } */
async function saveScript({ id, name, content }) {
  const custom = await loadCustom();
  const trimmedName = String(name || '').trim().slice(0, 60) || '未命名脚本';
  const body = String(content || '');
  if (!body.trim()) return { ok: false, error: '脚本内容不能为空' };
  if (body.length > MAX_CONTENT) return { ok: false, error: '脚本内容过长（上限 64KB）' };

  if (id) {
    const idx = custom.findIndex((s) => s.id === id);
    if (idx < 0) return { ok: false, error: '脚本不存在或为预置脚本' };
    custom[idx] = { ...custom[idx], name: trimmedName, content: body };
  } else {
    custom.push({ id: `s_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`, name: trimmedName, content: body });
  }
  await persist();
  return { ok: true, scripts: await listAll() };
}

/** 删除（预置脚本拒绝）；返回 { ok, scripts } */
async function removeScript(id) {
  const custom = await loadCustom();
  const idx = custom.findIndex((s) => s.id === id);
  if (idx < 0) return { ok: false, error: '预置脚本不可删除' };
  custom.splice(idx, 1);
  await persist();
  return { ok: true, scripts: await listAll() };
}

/** 按 id 查找脚本（含预置） */
async function findById(id) {
  const all = await listAll();
  return all.find((s) => s.id === id) || null;
}

/** 截断输出，超长时标注 */
function truncate(text) {
  const s = String(text || '');
  return s.length > MAX_OUTPUT ? `${s.slice(0, MAX_OUTPUT)}\n…(输出过长，已截断)` : s;
}

/**
 * 执行脚本：以 PowerShell 运行脚本内容（对齐 Android shell 语义）。
 * @param {string} id 脚本 id
 * @returns {Promise<{ok: boolean, exitCode?: number, stdout?: string, stderr?: string, error?: string}>}
 */
async function runScript(id) {
  const script = await findById(id);
  if (!script) return { ok: false, error: '脚本不存在' };

  return new Promise((resolve) => {
    const args = ['-NoProfile', '-NonInteractive', '-Command', script.content];
    execFile('powershell.exe', args, {
      timeout: TIMEOUT_MS,
      maxBuffer: 4 * MAX_OUTPUT,
      windowsHide: true,
    }, (err, stdout, stderr) => {
      if (err && err.killed) {
        resolve({ ok: false, error: '执行超时（120 秒），已强制终止' });
        return;
      }
      const exitCode = err && typeof err.code === 'number' ? err.code : (err ? 1 : 0);
      resolve({
        ok: !err,
        exitCode,
        stdout: truncate(stdout),
        stderr: truncate(stderr || (err && !stdout ? err.message : '')),
      });
    });
  });
}

module.exports = { listAll, saveScript, removeScript, runScript };
