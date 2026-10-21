/**
 * 识货（shihuo.cn）搜索 —— 社区/找券无数据时的比价兜底源
 * ---------------------------------------------------------
 * 接口：`GET https://m.shihuo.cn/search?page=1&page_size=30&type=goods&keywords=<kw>`
 * 返回：`{"status":0,"data":{"list":[...]}}`
 *
 * 历史坑（与 Android ShihuoApi 同源）：旧实现请求 `www.shihuo.cn/search?keywords=`
 * 已 302 到首页，`__NEXT_DATA__` 是首页热榜（鞋服/美妆），于是「搜 iPhone 15」
 * 会得到 adidas 板鞋。现在只认 m 站真接口，结构不符一律返回空。
 *
 * 另外该接口对匿名请求常返回字面量 `null`（站点侧限制），解析必须防御：
 * 不把 null/异常结构当搜索结果，也绝不回退到首页热榜。
 */
'use strict';

const http = require('../utils/http-client');
const { stripTags } = require('../utils/sanitizer');
const { isRelevant } = require('../utils/relevance');

const MOBILE_REFERER = 'https://m.shihuo.cn/search/goods';
/**
 * 进程内短缓存（5 分钟）：搜索兜底与社区模块会在同一次搜索里各问一次识货，
 * 而同域限流是 1 req/3s——不缓存的话第二次纯等 3 秒。失败不缓存。
 */
const CACHE_TTL = 5 * 60 * 1000;
const memoryCache = new Map(); // key → { ts, result }

/**
 * 识货没有稳定的商品详情页深链，条目统一跳到识货搜索页（沿用 Android 行为）。
 * @param {string} keyword
 */
function searchPageUrl(keyword) {
  const q = String(keyword || '').trim();
  return q
    ? `${MOBILE_REFERER}?keywords=${encodeURIComponent(q)}`
    : MOBILE_REFERER;
}

/** 价格字段容错：字符串 "7499" / 数字 7499 / "¥7,499.00" → 7499；认不出 → 0 */
function parsePrice(value) {
  if (typeof value === 'number') return Number.isFinite(value) && value > 0 ? value : 0;
  const m = String(value || '').match(/([0-9][0-9,]*(?:\.[0-9]{1,2})?)/);
  if (!m) return 0;
  const n = Number(m[1].replace(/,/g, ''));
  return Number.isFinite(n) && n > 0 ? n : 0;
}

/** 图片地址规整：// → https、http → https（页面在 https 下） */
function normalizeImage(value) {
  return String(value || '')
    .trim()
    .replace(/^\/\//, 'https://')
    .replace(/^http:\/\//i, 'https://');
}

/**
 * 纯解析（无网络），供 `_unit_check.js` 用固定 JSON 夹具验证。
 * @param {string} json
 * @param {string} [keyword] 用于生成跳转链接
 * @returns {Array<{goodsId:number, title:string, price:number, image:string,
 *   salesInfo:string, brand:string, hasSubsidy:boolean, url:string}>}
 */
function parseSearchPage(json, keyword = '') {
  let root;
  try {
    root = JSON.parse(json);
  } catch {
    return [];
  }
  // 实况里匿名请求会拿到字面量 null（4 字节）
  if (!root || root.status !== 0) return [];
  const list = root.data && Array.isArray(root.data.list) ? root.data.list : [];
  if (list.length === 0) return [];

  const items = [];
  const seen = new Set();
  for (const row of list) {
    if (!row || typeof row !== 'object') continue;
    const title = stripTags(row.title || row.name || '').trim();
    const price = parsePrice(row.price);
    if (!title || price <= 0) continue;

    const goodsId = Number(row.goods_id || row.id || 0);
    if (goodsId && seen.has(goodsId)) continue;
    if (goodsId) seen.add(goodsId);

    let hasSubsidy = false;
    const labels = Array.isArray(row.labels) ? row.labels : [];
    for (const label of labels) {
      const type = typeof label === 'string' ? label : label && label.type;
      if (type === 'PUBLIC_SUBSIDIES') {
        hasSubsidy = true;
        break;
      }
    }

    items.push({
      goodsId,
      title: title.slice(0, 60),
      price,
      image: normalizeImage(row.img || row.image),
      salesInfo: String(row.sales_info || row.salesInfo || ''),
      brand: String(row.brand_name || row.brandName || ''),
      hasSubsidy,
      url: searchPageUrl(keyword),
    });
    if (items.length >= 10) break;
  }
  return items;
}

/**
 * 搜索识货商品。结构异常/被限流返回空列表，由上层决定是否降级；
 * 网络错误（断网/超时）照常抛出让源健康面板能区分「够着了但没数据」。
 * @param {string} keyword
 * @returns {Promise<{items:Array<object>, total:number}>}
 */
async function searchProducts(keyword) {
  const q = String(keyword || '').trim();
  if (!q) return { items: [], total: 0 };
  const cacheKey = q.toLowerCase();
  const hit = memoryCache.get(cacheKey);
  if (hit && Date.now() - hit.ts < CACHE_TTL) return hit.result;

  const encoded = encodeURIComponent(q);
  const url = `https://m.shihuo.cn/search?page=1&page_size=30&type=goods&keywords=${encoded}`;
  const body = await http.getText(url, {
    mobileUA: true,
    headers: {
      Accept: 'application/json, text/plain, */*',
      Referer: `${MOBILE_REFERER}?keywords=${encoded}`,
    },
  });
  const all = parseSearchPage(body, q);
  // 与 Android 同规则：热榜/配件/其它机型先在这里剔除，避免「兜底」反而带进噪音
  const items = all.filter((item) => isRelevant(q, item.title));
  const result = { items, total: all.length };
  memoryCache.set(cacheKey, { ts: Date.now(), result });
  return result;
}

module.exports = { searchProducts, parseSearchPage, searchPageUrl, parsePrice };
