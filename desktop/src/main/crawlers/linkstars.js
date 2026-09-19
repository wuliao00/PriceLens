/**
 * 星罗好货开放平台 —— 历史低价榜（与 Android LinkstarsApi 对齐）
 * GET /api/jd_historyLowPriceRank?apikey=..&v=1.0.0&page=N
 * 榜单为人工审核的历史最低价同款商品（每页 100 条，最多 10 页）。
 * 用途：按京东 SKU 命中时给 在售价/券后价 参考，历史曲线补今日点、盯价兜底。
 */
'use strict';

const http = require('../utils/http-client');

const MAX_PAGES = 10;
const PAGE_SIZE = 100;

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

module.exports = { lookupSku };
