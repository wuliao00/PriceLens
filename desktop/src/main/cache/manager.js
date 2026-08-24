/**
 * PriceLens 缓存管理器（规范 §8）
 * --------------------------------
 *   - 本地 JSON 文件缓存，离线可用
 *   - TTL 过期 → stale-while-revalidate：先返回旧值，后台异步刷新
 *   - LRU 淘汰：总量超 200MB 时按 ts 删最旧
 *   - 索引：cache/_index.json → { key: { file, ts, ttl, bytes } }
 *
 * key 命名空间 → 子目录映射：
 *   products: → products/   history: → history/   bilibili: → bilibili/
 *   coupons:  → coupons/    smzdm:    → smzdm/     其他 → misc/
 */
'use strict';

const path = require('node:path');
const storage = require('./storage');
const { sha1Hex } = require('../utils/sanitizer');

const MAX_CACHE_BYTES = 200 * 1024 * 1024; // 200MB 上限（规范 §11）

/** key 前缀 → 缓存子目录 */
const NAMESPACE_DIRS = {
  products: 'products',
  history: 'history',
  bilibili: 'bilibili',
  coupons: 'coupons',
  smzdm: 'smzdm',
};

class CacheManager {
  #dir = '';
  #indexFile = '';
  /** @type {Map<string, {file: string, ts: number, ttl: number, bytes: number}>} */
  #index = new Map();
  /** @type {Map<string, Promise>} 进行中的后台刷新（同 key 去重） */
  #inflight = new Map();
  #ready = null;

  /**
   * @param {string} cacheDir 缓存根目录（%APPDATA%/pricelens/cache）
   */
  constructor(cacheDir) {
    this.#dir = cacheDir;
    this.#indexFile = path.join(cacheDir, '_index.json');
  }

  /** 初始化：建目录、载入索引（幂等，返回同一 Promise） */
  init() {
    if (!this.#ready) {
      this.#ready = this.#initInternal().catch(async (err) => {
        // 索引损坏 → 重建空索引继续工作
        console.error(`[pricelens-cache] 索引加载失败，重建: ${err.message}`);
        this.#index = new Map();
        await this.#persistIndex();
      });
    }
    return this.#ready;
  }

  async #initInternal() {
    await storage.ensureDir(this.#dir);
    for (const dir of new Set(Object.values(NAMESPACE_DIRS))) {
      await storage.ensureDir(path.join(this.#dir, dir));
    }
    const loaded = await storage.readJson(this.#indexFile, {});
    if (loaded && typeof loaded === 'object') {
      for (const [key, meta] of Object.entries(loaded)) {
        if (meta && typeof meta.file === 'string') {
          this.#index.set(key, {
            file: meta.file,
            ts: Number(meta.ts) || 0,
            ttl: Number(meta.ttl) || 0,
            bytes: Number(meta.bytes) || 0,
          });
        }
      }
    }
  }

  /** 持久化索引（原子写） */
  async #persistIndex() {
    const obj = {};
    for (const [key, meta] of this.#index) obj[key] = meta;
    await storage.ensureDir(this.#dir);
    await storage.writeJson(this.#indexFile, obj);
  }

  /** key → 子目录 + 文件名 */
  #fileFor(key) {
    const ns = String(key).split(':')[0];
    const dir = NAMESPACE_DIRS[ns] || 'misc';
    return path.join(this.#dir, dir, `${sha1Hex(key)}.json`);
  }

  /**
   * 读取缓存。
   * @param {string} key
   * @returns {Promise<{data: any, fresh: boolean} | null>} 未命中返回 null；
   *          fresh=false 表示已过期（调用方可触发后台刷新）
   */
  async get(key) {
    await this.init();
    const meta = this.#index.get(String(key));
    if (!meta) return null;
    const data = await storage.readJson(meta.file, null);
    if (data === null) {
      // 文件丢失（被用户手动删除等）→ 清理索引条目
      this.#index.delete(String(key));
      await this.#persistIndex();
      return null;
    }
    return { data, fresh: Date.now() - meta.ts <= meta.ttl };
  }

  /**
   * 写入缓存（同时更新索引 + LRU 淘汰检查）。
   * @param {string} key
   * @param {any} data
   * @param {number} [ttl] 毫秒，默认 6 小时
   */
  async set(key, data, ttl = 6 * 3600 * 1000) {
    await this.init();
    const file = this.#fileFor(key);
    await storage.writeJson(file, data);
    this.#index.set(String(key), {
      file,
      ts: Date.now(),
      ttl,
      bytes: JSON.stringify(data).length,
    });
    await this.#persistIndex();
    await this.#evictIfNeeded();
  }

  /**
   * 过期数据的后台刷新（stale-while-revalidate）。
   * 同一 key 的并发刷新自动去重。
   * @param {string} key
   * @param {number} ttl
   * @param {() => Promise<any>} fetcher
   */
  revalidate(key, ttl, fetcher) {
    const k = String(key);
    if (this.#inflight.has(k)) return this.#inflight.get(k);
    const p = (async () => {
      try {
        const data = await fetcher();
        await this.set(k, data, ttl);
      } finally {
        this.#inflight.delete(k);
      }
    })();
    this.#inflight.set(k, p);
    return p;
  }

  /** 当前缓存总字节数 */
  async size() {
    await this.init();
    let total = 0;
    for (const meta of this.#index.values()) total += meta.bytes || 0;
    return total;
  }

  /** 清空全部缓存（保留目录结构与空索引） */
  async clear() {
    await this.init();
    this.#index.clear();
    this.#inflight.clear();
    await storage.removeDir(this.#dir);
    await this.#initInternal(); // 重建目录与空索引
  }

  /**
   * LRU 淘汰：总量超 200MB 时按 ts 从旧到新删除，直到回到上限内。
   */
  async #evictIfNeeded() {
    let total = 0;
    for (const meta of this.#index.values()) total += meta.bytes || 0;
    if (total <= MAX_CACHE_BYTES) return;

    const sorted = [...this.#index.entries()].sort((a, b) => a[1].ts - b[1].ts);
    const fs = require('node:fs/promises');
    for (const [key, meta] of sorted) {
      if (total <= MAX_CACHE_BYTES) break;
      try {
        await fs.unlink(meta.file);
      } catch { /* 文件可能已不存在 */ }
      total -= meta.bytes || 0;
      this.#index.delete(key);
    }
    await this.#persistIndex();
  }
}

module.exports = CacheManager;
