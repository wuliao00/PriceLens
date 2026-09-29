/**
 * 星罗好货开放平台 —— 历史低价榜（与 Android LinkstarsApi 对齐）
 * GET /api/jd_historyLowPriceRank?apikey=..&v=1.0.0&page=N
 * 榜单为人工审核的历史最低价同款商品（每页 100 条，最多 10 页）。
 * 用途：按京东 SKU 命中时给 在售价/券后历史低价 参考，历史曲线补点、盯价兜底。
 *
 * ⚠ F1（2026-09-29，与 Android domain/PriceSample.kt + PriceSampling.kt 同规则）：
 *   listPrice（goods_list_money）= 在售价，才有资格当"现价"、补进今日曲线；
 *   couponPrice（real_money）= 券后**历史**低价，是历史位置，只能作参考展示。
 *   旧实现直接把 couponPrice 当现价用（曲线补点 + 通知比价），于是"价格没降也报降价"、
 *   并且曲线的最低点恒等于这个假点，入手建议几乎恒判「≈历史低价」。
 */
'use strict';

const http = require('../utils/http-client');

const MAX_PAGES = 10;
const PAGE_SIZE = 100;

/** 价格来源标记（与 Android PriceSource 一一对应） */
const PRICE_SOURCE = {
  JD_P3CN: 'JD_P3CN',
  LINKSTARS_LIST: 'LINKSTARS_LIST',
  LINKSTARS_HISTORY_LOW: 'LINKSTARS_HISTORY_LOW',
};

async function fetchPage(apikey, page) {
  const url = `https://openapi.linkstars.com/api/jd_historyLowPriceRank`
    + `?apikey=${encodeURIComponent(apikey)}&v=1.0.0&page=${page}`;
  let json;
  try {
    json = await http.getJSON(url, { referer: 'https://openapi.linkstars.com/' });
  } catch {
    return null;
  }
  if (!json || json.code !== 1) return null;
  const list = (json.data && Array.isArray(json.data.list)) ? json.data.list : [];
  return list
    .filter((o) => o && o.goods_id)
    .map((o) => ({
      goodsId: String(o.goods_id),
      title: String(o.short_extension_title || o.goods_brand || ''),
      listPrice: Number(o.goods_list_money) || 0,
      couponPrice: Number(o.real_money) || 0,
    }));
}

/** 在榜单内查找指定京东 SKU；未命中或接口失败返回 null */
async function lookupSku(skuId, apikey) {
  if (!apikey) return null;
  for (let page = 1; page <= MAX_PAGES; page++) {
    const deals = await fetchPage(apikey, page);
    if (!deals) return null;
    const hit = deals.find((d) => d.goodsId === String(skuId));
    if (hit) return hit;
    if (deals.length < PAGE_SIZE) return null;
  }
  return null;
}

/**
 * 榜单条目 → 带来源的价格样本（Android: PriceSampling.linkstarsSample）。
 * ① 在售价优先；② 只有在售价拿不到才退到券后历史低价，并如实标成参考值（live=false）。
 * @param {{listPrice:number, couponPrice:number}|null} deal
 * @returns {{price:number, source:string, live:boolean}|null}
 */
function toPriceSample(deal) {
  if (!deal) return null;
  if (deal.listPrice > 0) return { price: deal.listPrice, source: PRICE_SOURCE.LINKSTARS_LIST, live: true };
  if (deal.couponPrice > 0) return { price: deal.couponPrice, source: PRICE_SOURCE.LINKSTARS_HISTORY_LOW, live: false };
  return null;
}

/**
 * 有资格作为「今日的曲线采样点」的样本（Android: PriceSampling.curveWorthy）。
 * 参考值（券后历史低价）一律不够格——把它写成今天的点等于自己造历史。
 */
function curveWorthy(sample) {
  return sample && sample.live && sample.price > 0 ? sample : null;
}

module.exports = { lookupSku, toPriceSample, curveWorthy, PRICE_SOURCE };
