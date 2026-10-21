/**
 * Android 备份格式 ↔ 桌面盯价目标
 * ---------------------------------
 * Android 端已有「设置 → 备份与恢复」：payload 为
 *   { app:"PriceLens", schema:1, exportedAt, favorites[], targets[] }
 * 其中 targets[] 的字段是 productId（`jd:123...` / `taobao:123...`）、title、
 * platform、targetPrice、active、createdAt、updatedAt。
 *
 * 桌面端只同步盯价目标（桌面没有收藏概念），并只接收自己真能查价的平台
 * （京东/淘宝）。手机上的拼多多/当当/识货/值得买目标会如实计入 skipped，
 * 不伪造一个桌面盯不了的目标。
 */
'use strict';

const APP = 'PriceLens';
const SCHEMA = 1;

function jdUrl(id) {
  return `https://item.jd.com/${encodeURIComponent(id)}.html`;
}
function taobaoUrl(id) {
  return `https://item.taobao.com/item.htm?id=${encodeURIComponent(id)}`;
}

/** 从桌面商品 URL 解析 Android 目标所需的 platform/productId；不支持返回 null */
function desktopUrlToAndroidTarget(url) {
  const raw = String(url || '');
  let m = raw.match(/item(?:\.m)?\.jd\.com\/(?:product\/)?(\d{6,})/i);
  if (m) return { platform: 'jd', productId: `jd:${m[1]}` };
  m = raw.match(/(?:item\.taobao\.com|detail\.tmall\.com)\/item[^#]*?[?&]id=(\d{4,})/i);
  if (m) return { platform: 'taobao', productId: `taobao:${m[1]}` };
  return null;
}

/** Android productId → 桌面 URL；桌面暂不支持 pdd/当当/识货/值得买盯价 */
function androidTargetToUrl(target) {
  const productId = String(target?.productId || '');
  const colon = productId.indexOf(':');
  if (colon <= 0) return null;
  const platform = productId.slice(0, colon);
  const id = productId.slice(colon + 1).trim();
  if (!id) return null;
  if (platform === 'jd' && /^\d{6,}$/.test(id)) return jdUrl(id);
  if (platform === 'taobao' && /^[A-Za-z0-9_.@-]{4,64}$/.test(id)) return taobaoUrl(id);
  return null;
}

/**
 * 解析手机备份，转成桌面 watches 配置（还未合并）。
 * @param {object|string} payload
 * @param {number} [now]
 * @returns {{watches:Array<object>, skipped:number, favorites:number}}
 */
function androidBackupToWatches(payload, now = Date.now()) {
  let data = payload;
  if (typeof data === 'string') {
    try {
      data = JSON.parse(data);
    } catch {
      throw new Error('手机备份不是有效的 JSON');
    }
  }
  if (!data || typeof data !== 'object') throw new Error('手机备份结构不正确');
  if (data.app !== APP) throw new Error(`不是 PriceLens 的备份（app="${data.app || '空'}"）`);
  if (data.schema !== SCHEMA) throw new Error(`不支持的手机备份 schema（${data.schema ?? '未知'}）`);

  const targets = Array.isArray(data.targets) ? data.targets : [];
  const watches = [];
  let skipped = 0;
  const seen = new Set();
  for (const target of targets) {
    const url = androidTargetToUrl(target);
    const targetPrice = Number(target?.targetPrice);
    const title = String(target?.title || '').trim().slice(0, 100);
    if (!url || !(targetPrice > 0) || !title || seen.has(url)) {
      skipped += 1;
      continue;
    }
    seen.add(url);
    watches.push({
      keyword: title,
      url,
      target: targetPrice,
      paused: target.active === false,
      since: Number(target.createdAt) > 0 ? Number(target.createdAt) : now,
    });
  }
  return {
    watches,
    skipped,
    favorites: Array.isArray(data.favorites) ? data.favorites.length : 0,
  };
}

/**
 * 桌面 watches → 手机可恢复的备份 payload。
 * 只输出京东/淘宝目标；不支持的目标不计入 targets。
 * @param {Array<object>} watches
 * @param {number} [now]
 * @returns {{payload:object, skipped:number}}
 */
function watchesToAndroidBackup(watches, now = Date.now()) {
  const targets = [];
  let skipped = 0;
  for (const watch of Array.isArray(watches) ? watches : []) {
    const mapped = desktopUrlToAndroidTarget(watch?.url);
    const targetPrice = Number(watch?.target);
    if (!mapped || !(targetPrice > 0)) {
      skipped += 1;
      continue;
    }
    targets.push({
      productId: mapped.productId,
      title: String(watch.keyword || '未命名商品').slice(0, 100),
      platform: mapped.platform,
      targetPrice,
      active: !watch.paused,
      createdAt: Number(watch.since) > 0 ? Number(watch.since) : now,
      // 让手机的「新者胜」判定接受桌面这份；Android 用 updatedAt 与本机 createdAt 比
      updatedAt: now,
    });
  }
  return {
    payload: {
      app: APP,
      schema: SCHEMA,
      exportedAt: now,
      favorites: [],
      targets,
    },
    skipped,
  };
}

module.exports = {
  APP,
  SCHEMA,
  desktopUrlToAndroidTarget,
  androidTargetToUrl,
  androidBackupToWatches,
  watchesToAndroidBackup,
};
