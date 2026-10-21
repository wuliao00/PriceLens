/**
 * 盯价目标备份/恢复（纯函数）
 * ----------------------------
 * 只备份用户显式设置的字段：keyword / url / target / paused / since。
 * **不备份** Cookie、星罗 apikey、当前价、历史最低、notified/alerted 等运行态，
 * 避免把凭证写进用户随手分享的文件。
 */
'use strict';

const { normalizeWatches, newWatchId } = require('../cache/storage');

const BACKUP_APP = 'pricelens';
const SCHEMA_VERSION = 1;
const MAX_WATCHES = 20;

/**
 * 生成备份对象。
 * @param {Array<object>} watches
 * @param {number} [now]
 */
function buildBackup(watches, now = Date.now()) {
  const normalized = normalizeWatches(watches, null);
  return {
    app: BACKUP_APP,
    schemaVersion: SCHEMA_VERSION,
    exportedAt: new Date(now).toISOString(),
    watches: normalized.map((w) => ({
      id: w.id,
      keyword: w.keyword,
      url: w.url,
      target: w.target,
      paused: w.paused,
      since: w.since,
    })),
  };
}

/**
 * 解析备份文本并规整为可导入的目标列表。
 * @param {string} text
 * @returns {Array<object>}
 */
function parseBackup(text) {
  let payload;
  try {
    payload = JSON.parse(String(text || ''));
  } catch {
    throw new Error('备份文件不是有效的 JSON');
  }
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    throw new Error('备份文件结构不正确');
  }
  if (payload.schemaVersion !== SCHEMA_VERSION) {
    throw new Error(`不支持的备份版本（${payload.schemaVersion ?? '未知'}）`);
  }
  if (!Array.isArray(payload.watches)) {
    throw new Error('备份文件缺少盯价目标列表');
  }
  const watches = normalizeWatches(payload.watches, null);
  if (watches.length === 0) {
    throw new Error('备份里没有可导入的盯价目标');
  }
  return watches;
}

/**
 * 合并导入目标：同 URL 以备份为准（更新目标价/关键词/暂停状态），
 * 运行态 current/lowest 保留，notified/alerted 重置；新目标追加到上限 20。
 * @param {Array<object>} existing
 * @param {Array<object>} imported
 * @returns {{watches:Array<object>, added:number, updated:number, skipped:number}}
 */
function mergeBackup(existing, imported) {
  const current = normalizeWatches(existing, null);
  const incoming = normalizeWatches(imported, null);
  const byUrl = new Map(current.map((w) => [w.url, w]));
  let added = 0;
  let updated = 0;
  let skipped = 0;

  for (const item of incoming) {
    const old = byUrl.get(item.url);
    if (old) {
      byUrl.set(item.url, {
        ...old,
        keyword: item.keyword || old.keyword,
        target: item.target,
        paused: item.paused,
        notified: false,
        alerted: false,
        lastError: null,
      });
      updated += 1;
      continue;
    }
    if (byUrl.size >= MAX_WATCHES) {
      skipped += 1;
      continue;
    }
    byUrl.set(item.url, {
      ...item,
      id: newWatchId(), // 避免与现有目标 id 冲突；URL 才是去重键
      current: null,
      lowest: null,
      notified: false,
      alerted: false,
      lastChecked: 0,
      lastError: null,
    });
    added += 1;
  }

  return { watches: Array.from(byUrl.values()), added, updated, skipped };
}

module.exports = {
  BACKUP_APP,
  SCHEMA_VERSION,
  MAX_WATCHES,
  buildBackup,
  parseBackup,
  mergeBackup,
};
