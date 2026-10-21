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
const crypto = require('node:crypto');
const { app } = require('electron');

/** 应用默认设置 */
const DEFAULT_SETTINGS = {
  theme: 'system',        // 'light' | 'dark' | 'system'
  watches: [],            // 多商品盯价：{ id, keyword, url, target, current, lowest, paused, notified, alerted, since, lastChecked, lastError }
  watch: null,            // v2.2 及以前单条盯价；initStorage 迁移进 watches 后置空
  linkstars_apikey: '',   // 星罗好货开放平台 apikey（历史低价参考）
  mmb_cookie: '',         // 慢慢买登录 Cookie（完整历史曲线）
};


/** 生成盯价目标 ID（无需引入 uuid 依赖） */
function newWatchId() {
  return `w_${Date.now().toString(36)}_${crypto.randomBytes(3).toString('hex')}`;
}

/**
 * 规整盯价列表：兼容 v2.2 的单条 watch；丢掉无链接/无目标价的脏数据。
 * @param {any} rawWatches
 * @param {any} [legacyWatch]
 * @returns {Array<object>}
 */
function normalizeWatches(rawWatches, legacyWatch) {
  const source = Array.isArray(rawWatches) ? rawWatches.slice() : [];
  if (source.length === 0 && legacyWatch && typeof legacyWatch === 'object') source.push(legacyWatch);

  const out = [];
  const seenUrls = new Set();
  for (const raw of source) {
    if (!raw || typeof raw !== 'object') continue;
    const url = String(raw.url || '').trim();
    const target = Number(raw.target);
    if (!/^https?:\/\//i.test(url) || !(target > 0)) continue;
    if (seenUrls.has(url)) continue; // 同一商品只保留一条
    seenUrls.add(url);
    const current = Number(raw.current);
    const lowest = Number(raw.lowest);
    const lastAlertPrice = Number(raw.lastAlertPrice);
    out.push({
      id: String(raw.id || '').trim() || newWatchId(),
      keyword: String(raw.keyword || '').slice(0, 100),
      url,
      target,
      current: Number.isFinite(current) && current > 0 ? current : null,
      lowest: Number.isFinite(lowest) && lowest > 0 ? lowest : null,
      paused: Boolean(raw.paused),
      notified: Boolean(raw.notified),
      alerted: Boolean(raw.alerted),
      since: Number(raw.since) || Date.now(),
      lastChecked: Number(raw.lastChecked) || 0,
      lastError: raw.lastError ? String(raw.lastError).slice(0, 200) : null,
      lastAlertAt: Number(raw.lastAlertAt) || 0,
      lastAlertPrice: Number.isFinite(lastAlertPrice) && lastAlertPrice > 0 ? lastAlertPrice : null,
      alertCount: Math.max(0, Math.round(Number(raw.alertCount) || 0)),
    });
  }
  return out;
}

let settings = { ...DEFAULT_SETTINGS };

/**
 * 初始化：确保目录存在、异步加载 settings.json。
 * 在 app.whenReady 后 await 调用一次。
 */
async function initStorage() {
  const userDataDir = app.getPath('userData'); // %APPDATA%/pricelens
  await fs.mkdir(path.join(userDataDir, 'cache'), { recursive: true });
  await fs.mkdir(path.join(userDataDir, 'logs'), { recursive: true });
  let migrated = false;
  try {
    const raw = await fs.readFile(path.join(userDataDir, 'settings.json'), 'utf-8');
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === 'object') {
      settings = { ...DEFAULT_SETTINGS, ...parsed };
      migrated = Boolean(parsed.watch);
    }
  } catch {
    settings = { ...DEFAULT_SETTINGS }; // 不存在或损坏 → 默认值
  }
  // v2.2 单条 watch → v2.3 watches[]；迁移后把旧字段置空并落盘
  settings.watches = normalizeWatches(settings.watches, settings.watch);
  settings.watch = null;
  if (migrated) {
    await writeJson(path.join(userDataDir, 'settings.json'), settings).catch(() => {});
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
  // 复用 writeJson 的 tmp + rename 原子写：
  // 直接 writeFile 会违反本模块自身的原子写约定（并发写可互覆盖、断电产生半截文件）
  writeJson(file, settings).catch(() => {});
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
  normalizeWatches,
  newWatchId,
  getSettings,
  updateSettings,
  ensureDir,
  readJson,
  writeJson,
  dirSizeBytes,
  removeDir,
};
