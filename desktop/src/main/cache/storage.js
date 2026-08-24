/**
 * PriceLens 存储层封装
 * --------------------
 * 目录结构（规范 §8.1）：
 *   %APPDATA%/pricelens/
 *   ├── cache/…          （由 CacheManager 管理）
 *   ├── settings.json    （主题 / 盯价配置）
 *   └── logs/crawl.log
 *
 * 红线 #14：主进程禁止同步 fs —— 全部 fs.promises 异步 IO；
 * JSON 写入采用 tmp + rename 原子写，避免断电产生半截文件。
 */
'use strict';

const fs = require('node:fs/promises');
const path = require('node:path');
const { app } = require('electron');

/** 应用默认设置 */
const DEFAULT_SETTINGS = {
  theme: 'system',        // 'light' | 'dark' | 'system'
  watch: null,            // { keyword, url, target, notified, current, since }
};

let settings = { ...DEFAULT_SETTINGS };

/**
 * 初始化：确保目录存在、异步加载 settings.json。
 * 在 app.whenReady 后 await 调用一次。
 */
async function initStorage() {
  const userDataDir = app.getPath('userData'); // %APPDATA%/pricelens
  await fs.mkdir(path.join(userDataDir, 'cache'), { recursive: true });
  await fs.mkdir(path.join(userDataDir, 'logs'), { recursive: true });
  try {
    const raw = await fs.readFile(path.join(userDataDir, 'settings.json'), 'utf-8');
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === 'object') settings = { ...DEFAULT_SETTINGS, ...parsed };
  } catch {
    settings = { ...DEFAULT_SETTINGS }; // 不存在或损坏 → 默认值
  }
}

/**
 * 读取当前设置（只读引用，勿直接修改内部字段）。
 * @returns {typeof DEFAULT_SETTINGS}
 */
function getSettings() {
  return settings;
}

/**
 * 合并更新设置并异步持久化。
 * @param {Partial<typeof DEFAULT_SETTINGS>} patch
 */
function updateSettings(patch) {
  settings = { ...settings, ...patch };
  const file = path.join(app.getPath('userData'), 'settings.json');
  fs.writeFile(file, JSON.stringify(settings, null, 2), 'utf-8').catch(() => {});
  return settings;
}

/* ── 通用文件工具（CacheManager 复用） ─────────────── */

/**
 * 确保目录存在。
 * @param {string} dir
 */
async function ensureDir(dir) {
  await fs.mkdir(dir, { recursive: true });
}

/**
 * 读取 JSON 文件，失败返回 fallback。
 * @param {string} file
 * @param {any} [fallback=null]
 */
async function readJson(file, fallback = null) {
  try {
    return JSON.parse(await fs.readFile(file, 'utf-8'));
  } catch {
    return fallback;
  }
}

/**
 * 原子写 JSON：先写 .tmp 再 rename。
 * @param {string} file
 * @param {any} data
 */
async function writeJson(file, data) {
  const tmp = `${file}.${process.pid}.tmp`;
  await fs.writeFile(tmp, JSON.stringify(data), 'utf-8');
  await fs.rename(tmp, file);
}

/** 递归统计目录字节数（不存在返回 0） */
async function dirSizeBytes(dir) {
  let total = 0;
  let entries;
  try {
    entries = await fs.readdir(dir, { withFileTypes: true });
  } catch {
    return 0;
  }
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      total += await dirSizeBytes(full);
    } else {
      try {
        total += (await fs.stat(full)).size;
      } catch { /* 文件可能已被并发删除 */ }
    }
  }
  return total;
}

/** 递归删除目录（不存在则忽略） */
async function removeDir(dir) {
  await fs.rm(dir, { recursive: true, force: true });
}

module.exports = {
  DEFAULT_SETTINGS,
  initStorage,
  getSettings,
  updateSettings,
  ensureDir,
  readJson,
  writeJson,
  dirSizeBytes,
  removeDir,
};
