/**
 * 京东价格爬虫 — 获取实时价格
 *
 * 接口: p.3.cn/prices/mgets (无需登录，公开接口)
 */

const { request } = require('../utils/rate-limiter');
const { cacheGet, cacheSet } = require('../cache/manager');

const JD_PRICE_API = 'https://p.3.cn/prices/mgets';
const CACHE_TTL = 5 * 60 * 1000; // 5 分钟

function hashCode(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash) + str.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash);
}

/**
 * 获取京东商品实时价格
 * @param {string} skuId - 京东 SKU ID (如 "100012043978")
 * @returns {Promise<Object>}
 */
async function getJDPrice(skuId) {
  const cacheKey = `jd:price:${skuId}`;
  const cached = cacheGet(cacheKey);
  if (cached && (Date.now() - cached.timestamp < CACHE_TTL)) {
    return cached.data;
  }

  try {
    const params = new URLSearchParams({
      skuIds: `J_${skuId}`,
      type: '1'
    });

    const res = await request(`${JD_PRICE_API}?${params}`, {
      headers: {
        'Referer': 'https://item.jd.com/',
        'Accept': 'application/json'
      }
    });

    // p.3.cn 返回格式: [{ id: "J_xxx", p: "7999.00", op: "8999.00", m: "6999.00" }]
    const item = Array.isArray(res) ? res[0] : res;

    const result = {
      skuId,
      price: parseFloat(item?.p) || 0,
      originalPrice: parseFloat(item?.op) || 0,
      lowestPrice: parseFloat(item?.m) || 0,
      url: `https://item.jd.com/${skuId}.html`,
      platform: '京东',
      fetchedAt: Date.now()
    };

    cacheSet(cacheKey, result, CACHE_TTL);
    return result;
  } catch (err) {
    console.error('[JD Price]', err.message);
    if (cached) return cached.data;

    // 降级模拟
    return {
      skuId,
      price: 3999 + Math.abs(hashCode(skuId)) % 6000,
      originalPrice: 5999 + Math.abs(hashCode(skuId)) % 4000,
      platform: '京东',
      fetchedAt: Date.now(),
      isFallback: true
    };
  }
}

module.exports = { getJDPrice };