/**
 * 缓存管理器 — 离线优先架构
 *
 * 缓存结构:
 *   userData/cache/
 *   ├── index.json          // 索引: key → { file, timestamp, ttl }
 *   ├── data/               // 数据文件目录
 *   └── logs/               // 爬虫日志
 *
 * 策略:
 *   - Stale-while-revalidate: 过期先返回旧数据，后台刷新
 *   - LRU 淘汰: 总大小 > 200MB 时淘汰最久未访问
 *   - 默认 TTL: 6 小时
 */

const path = require('path');
const fs = require('fs');
const { readJSON, writeJSON, getDirSize, ensureDir } = require('./storage');

const DEFAULT_TTL = 6 * 60 * 60 * 1000; // 6 小时
const MAX_CACHE_SIZE = 200 * 1024 * 1024;  // 200 MB

let cacheDir = '';
let index = {};      // { key: { file, timestamp, ttl, accessCount, lastAccess } }
let indexDirty = false;

/**
 * 初始化缓存系统
 * @param {string} userDataPath
 */
async function initCache(userDataPath) {
  cacheDir = path.join(userDataPath, 'cache');

  // 创建目录结构
  ensureDir(cacheDir);
  ensureDir(path.join(cacheDir, 'data'));
  ensureDir(path.join(cacheDir, 'logs'));

  // 加载索引
  index = readJSON(path.join(cacheDir, 'index.json')) || {};
  indexDirty = false;

  console.log(`[Cache] 初始化完成: ${cacheDir} (${Object.keys(index).length} 条记录)`);
}

/**
 * 读取缓存
 * @param {string} key
 * @returns {object|null} { data, timestamp }
 */
function cacheGet(key) {
  const entry = index[key];
  if (!entry || !entry.file) return null;

  const filePath = path.join(cacheDir, 'data', entry.file);
  if (!fs.existsSync(filePath)) {
    delete index[key];
    indexDirty = true;
    return null;
  }

  try {
    const raw = fs.readFileSync(filePath, 'utf-8');
    const data = JSON.parse(raw);

    // 更新访问统计
    entry.accessCount = (entry.accessCount || 0) + 1;
    entry.lastAccess = Date.now();
    indexDirty = true;

    // 检查过期
    const expired = entry.timestamp + (entry.ttl || DEFAULT_TTL) < Date.now();

    return {
      data: data._payload || data,
      timestamp: entry.timestamp,
      expired
    };
  } catch (err) {
    console.error(`[Cache] 读取失败: ${key}`, err.message);
    return null;
  }
}

/**
 * 写入缓存
 * @param {string} key
 * @param {any} data
 * @param {number} [ttl] - 过期时间(ms)，默认 6h
 */
function cacheSet(key, data, ttl = DEFAULT_TTL) {
  const now = Date.now();

  // 生成文件名
  const safeName = key.replace(/[^a-zA-Z0-9\u4e00-\u9fff_-]/g, '_').slice(0, 80);
  const file = `${safeName}_${now}.json`;

  const filePath = path.join(cacheDir, 'data', file);

  try {
    // 包装 payload
    const wrapper = {
      _payload: data,
      _cachedAt: now
    };

    fs.writeFileSync(filePath, JSON.stringify(wrapper), 'utf-8');

    // 清理旧文件
    if (index[key]?.file) {
      const oldPath = path.join(cacheDir, 'data', index[key].file);
      try { fs.unlinkSync(oldPath); } catch { /* 忽略 */ }
    }

    // 更新索引
    index[key] = {
      file,
      timestamp: now,
      ttl,
      accessCount: 0,
      lastAccess: now
    };
    indexDirty = true;

    // 检查总大小
    checkSizeLimit();
  } catch (err) {
    console.error(`[Cache] 写入失败: ${key}`, err.message);
  }
}

/**
 * 获取缓存总大小
 * @returns {{ count: number, size: number, sizeFormatted: string }}
 */
function cacheSize() {
  const bytes = getDirSize(path.join(cacheDir, 'data'));
  return {
    count: Object.keys(index).length,
    size: bytes,
    sizeFormatted: formatBytes(bytes)
  };
}

/**
 * 清除所有缓存
 */
function cacheClear() {
  const dataDir = path.join(cacheDir, 'data');
  if (fs.existsSync(dataDir)) {
    const files = fs.readdirSync(dataDir);
    for (const f of files) {
      try { fs.unlinkSync(path.join(dataDir, f)); } catch { /* skip */ }
    }
  }
  index = {};
  indexDirty = true;
  flushIndex();
}

/**
 * 写入索引文件
 */
function flushIndex() {
  if (!indexDirty) return;
  writeJSON(path.join(cacheDir, 'index.json'), index);
  indexDirty = false;
}

/**
 * LRU 淘汰 — 当缓存超过 200MB 时触发
 */
function checkSizeLimit() {
  const bytes = getDirSize(path.join(cacheDir, 'data'));
  if (bytes <= MAX_CACHE_SIZE) return;

  // 按 lastAccess 排序，淘汰最久未访问的 20%
  const entries = Object.entries(index)
    .sort((a, b) => (a[1].lastAccess || 0) - (b[1].lastAccess || 0));

  const removeCount = Math.max(1, Math.floor(entries.length * 0.2));

  for (let i = 0; i < removeCount; i++) {
    const [key, entry] = entries[i];
    try {
      fs.unlinkSync(path.join(cacheDir, 'data', entry.file));
    } catch { /* 忽略 */ }
    delete index[key];
  }

  indexDirty = true;
  flushIndex();
  console.log(`[Cache] LRU 淘汰: 清除了 ${removeCount} 条记录`);
}

/**
 * 定时刷新索引（每 30 秒）
 */
setInterval(flushIndex, 30000);

/**
 * 进程退出时刷新
 */
process.on('exit', flushIndex);
process.on('SIGINT', () => { flushIndex(); process.exit(); });

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

module.exports = { initCache, cacheGet, cacheSet, cacheSize, cacheClear, flushIndex };