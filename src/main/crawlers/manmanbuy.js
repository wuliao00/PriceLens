/**
 * 慢慢买爬虫 — 商品历史价格查询
 *
 * 接口: tool.manmanbuy.com/HistoryLowest.aspx
 */

const { request } = require('../utils/rate-limiter');
const { cacheGet, cacheSet } = require('../cache/manager');

const CACHE_TTL = 60 * 60 * 1000; // 1 小时

/**
 * 获取商品90天历史价格
 * @param {string} productId - 京东商品 ID 或 慢慢买商品编码
 * @returns {Promise<Array>}
 */
async function getHistoryPrice(productId) {
  const cacheKey = `history:${productId}`;
  const cached = cacheGet(cacheKey);
  if (cached && (Date.now() - cached.timestamp < CACHE_TTL)) {
    return cached.data;
  }

  try {
    const params = new URLSearchParams({
      procode: productId,
      type: 'jd',
      days: '90'
    });

    const res = await request(`https://tool.manmanbuy.com/HistoryLowest.aspx?${params}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        'Referer': 'https://tool.manmanbuy.com/',
        'X-Requested-With': 'XMLHttpRequest'
      },
      body: params.toString()
    });

    // 慢慢买返回格式: { history: [{ date, price }], lowest, highest, current }
    const history = parseHistoryData(res);

    cacheSet(cacheKey, history, CACHE_TTL);
    return history;
  } catch (err) {
    console.error('[ManmanBuy]', err.message);
    if (cached) return cached.data;

    // 降级: 返回模拟数据供 UI 展示
    return generateFallbackData(productId);
  }
}

function parseHistoryData(raw) {
  if (!raw || !raw.history || !Array.isArray(raw.history)) {
    throw new Error('Invalid response format');
  }

  return {
    points: raw.history.map(p => ({
      date: p.date,
      price: parseFloat(p.price) || 0
    })),
    lowest: parseFloat(raw.lowest) || 0,
    highest: parseFloat(raw.highest) || 0,
    current: parseFloat(raw.current) || 0,
    avg7d: raw.avg7d || 0,
    isHistoricalLow: raw.isLowest || false,
    isPriceHiked: raw.isHiked || false
  };
}

/**
 * 降级模拟数据 — 当爬虫失败时提供合理的兜底
 */
function generateFallbackData(productId) {
  const now = Date.now();
  const basePrice = 3000 + Math.abs(hashCode(productId)) % 5000;
  const points = [];

  for (let i = 89; i >= 0; i--) {
    const date = new Date(now - i * 24 * 60 * 60 * 1000);
    const noise = Math.sin(i * 0.3) * 300 + Math.random() * 200;
    const price = basePrice + noise;
    points.push({
      date: date.toISOString().split('T')[0],
      price: Math.round(price * 100) / 100
    });
  }

  const prices = points.map(p => p.price);
  const lowest = Math.min(...prices);
  const highest = Math.max(...prices);
  const current = points[points.length - 1].price;
  const recent7 = prices.slice(-7);
  const avg7d = recent7.reduce((a, b) => a + b, 0) / 7;

  return {
    points,
    lowest: Math.round(lowest * 100) / 100,
    highest: Math.round(highest * 100) / 100,
    current: Math.round(current * 100) / 100,
    avg7d: Math.round(avg7d * 100) / 100,
    isHistoricalLow: current <= lowest * 1.05,
    isPriceHiked: current >= avg7d * 1.1,
    isFallback: true
  };
}

function hashCode(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash) + str.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash);
}

module.exports = { getHistoryPrice };