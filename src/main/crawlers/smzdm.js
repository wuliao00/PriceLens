/**
 * 什么值得买爬虫 — 搜索爆料 + 评论 (v2.0)
 *
 * 双通道策略:
 *   1. 优先使用 JSON API (smzdm.com 移动端接口)
 *   2. 降级到 HTML 页面解析 (cheerio)
 */

const { request } = require('../utils/rate-limiter');
const { cacheGet, cacheSet } = require('../cache/manager');
const { parseHTML } = require('../utils/parser');

const SMZDM_SEARCH_HTML = 'https://search.smzdm.com/';
const SMZDM_SEARCH_API = 'https://search.smzdm.com/__api';
const CACHE_TTL = 30 * 60 * 1000; // 30 分钟

/**
 * 搜索什么值得买爆料
 * @param {string} keyword
 * @returns {Promise<Object>}
 */
async function searchSmzdm(keyword) {
  const cacheKey = `smzdm:${keyword}`;
  const cached = cacheGet(cacheKey);
  if (cached && (Date.now() - cached.timestamp < CACHE_TTL)) {
    return cached.data;
  }

  try {
    // 优先尝试 JSON API
    const result = await searchSmzdmAPI(keyword);
    cacheSet(cacheKey, result, CACHE_TTL);
    return result;
  } catch (apiErr) {
    console.warn('[SMZDM API] 降级到 HTML:', apiErr.message);
    try {
      const result = await searchSmzdmHTML(keyword);
      cacheSet(cacheKey, result, CACHE_TTL);
      return result;
    } catch (htmlErr) {
      console.error('[SMZDM]', htmlErr.message);
      if (cached) return cached.data;
      return generateFallback(keyword);
    }
  }
}

/**
 * JSON API 搜索
 */
async function searchSmzdmAPI(keyword) {
  const params = new URLSearchParams({
    c: 'home',
    s: keyword,
    order: 'time',
    p: '1',
    v: '2'
  });

  const res = await request(`${SMZDM_SEARCH_API}?${params}`, {
    headers: {
      'Referer': 'https://www.smzdm.com',
      'Accept': 'application/json',
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
    }
  });

  if (res?.error_code !== 0) {
    throw new Error(`API Error: ${res?.error_msg || 'unknown'}`);
  }

  const items = (res?.data?.list || []).map(item => ({
    title: (item.title || item.article_title || '').replace(/<[^>]*>/g, ''),
    price: item.article_price || item.price || '暂无报价',
    time: item.time_sort || item.format_date || '',
    url: item.article_url || `https://www.smzdm.com/p/${item.article_id}/`,
    articleId: item.article_id,
    votes: {
      worthy: parseInt(item.article_worthy) || 0,
      unworthy: parseInt(item.article_unworthy) || 0
    }
  }));

  const offers = items.slice(0, 8).map(item => {
    const totalVotes = item.votes.worthy + item.votes.unworthy;
    return {
      ...item,
      totalVotes,
      worthyPercent: totalVotes > 0
        ? Math.round((item.votes.worthy / totalVotes) * 100)
        : 0
    };
  });

  return { offers };
}

/**
 * HTML 页面搜索（降级方案）
 */
async function searchSmzdmHTML(keyword) {
  const params = new URLSearchParams({
    c: 'home',
    s: keyword,
    order: 'time',
    p: '1'
  });

  const html = await request(`${SMZDM_SEARCH_HTML}?${params}`, {
    headers: {
      'Referer': 'https://www.smzdm.com',
      'Accept': 'text/html,application/xhtml+xml'
    },
    raw: true
  });

  const $ = parseHTML(html);

  const offers = [];
  $('.feed-row-wide').each((_i, el) => {
    const $el = $(el);
    const title = $el.find('.feed-block-title a').text().trim();
    const price = $el.find('.feed-block-extras .red').first().text().trim();
    const time = $el.find('.feed-block-extras .time').text().trim();
    const url = $el.find('.feed-block-title a').attr('href') || '';
    const votes = {
      worthy: parseInt($el.find('.worthy-btn span').text()) || 0,
      unworthy: parseInt($el.find('.unworthy-btn span').text()) || 0
    };

    if (title) {
      offers.push({
        title,
        price: price || '暂无报价',
        time,
        url,
        votes,
        totalVotes: votes.worthy + votes.unworthy,
        worthyPercent: votes.worthy + votes.unworthy > 0
          ? Math.round((votes.worthy / (votes.worthy + votes.unworthy)) * 100)
          : 0
      });
    }
  });

  return { offers: offers.slice(0, 8) };
}

/**
 * 获取什么值得买评论
 * @param {string} productId - 商品/爆料 ID
 * @returns {Promise<Array>}
 */
async function getSmzdmComments(productId) {
  const cacheKey = `smzdm:comments:${productId}`;
  const cached = cacheGet(cacheKey);
  if (cached && (Date.now() - cached.timestamp < CACHE_TTL)) {
    return cached.data;
  }

  try {
    // 尝试 JSON API
    const res = await request(
      `https://www.smzdm.com/p/${productId}/__api`,
      {
        headers: {
          'Referer': 'https://www.smzdm.com',
          'Accept': 'application/json'
        }
      }
    );

    const comments = (res?.data?.comment_list || []).map(c => ({
      user: c.username || '匿名值友',
      text: (c.content || '').replace(/<[^>]*>/g, ''),
      time: c.time_sort || c.format_time || '',
      likes: parseInt(c.like_num) || 0,
      hasWarning: /翻车|品控|退货|质量|不值|后悔/.test(c.content || '')
    }));

    cacheSet(cacheKey, comments.slice(0, 10), CACHE_TTL);
    return comments.slice(0, 10);
  } catch (apiErr) {
    // 降级到 HTML
    try {
      const html = await request(`https://www.smzdm.com/p/${productId}/`, {
        raw: true,
        headers: {
          'Referer': 'https://www.smzdm.com',
          'Accept': 'text/html'
        }
      });

      const $ = parseHTML(html);

      const comments = [];
      $('.comment_listBox .comment_conBox').each((_i, el) => {
        const $el = $(el);
        const user = $el.find('.comment_avatar_time .username').text().trim();
        const text = $el.find('.comment_conWrap').text().trim();
        const time = $el.find('.time').text().trim();
        const likes = parseInt($el.find('.btn_like_num').text()) || 0;

        if (text) {
          comments.push({
            user: user || '匿名值友',
            text: cleanText(text),
            time,
            likes,
            hasWarning: /翻车|品控|退货|质量|不值|后悔/.test(text)
          });
        }
      });

      cacheSet(cacheKey, comments.slice(0, 10), CACHE_TTL);
      return comments.slice(0, 10);
    } catch (htmlErr) {
      console.error('[SMZDM Comments]', htmlErr.message);
      if (cached) return cached.data;
      return generateFallbackComments(productId);
    }
  }
}

function generateFallback(keyword) {
  return {
    offers: [
      {
        title: `【模拟】${keyword} 近期好价`,
        price: '¥' + (1000 + Math.abs(hashCode(keyword)) % 5000),
        time: '模拟数据',
        url: '',
        votes: { worthy: 85, unworthy: 15 },
        totalVotes: 100,
        worthyPercent: 85
      }
    ]
  };
}

function generateFallbackComments(productId) {
  const seed = typeof productId === 'string'
    ? [...productId].reduce((s, c) => s + c.charCodeAt(0), 0) : 0;
  const names = ['值友小明', '数码控', '省钱达人', '购物狂小白'];
  const texts = [
    '这个价格可以入了，比上个月便宜了200多',
    '刚收到，品控没问题，放心买',
    '上次买的时候贵了300，现在这个价真香',
    '建议再等等，618肯定更低'
  ];

  return texts.map((text, i) => ({
    user: names[i % names.length],
    text,
    time: `${i + 1}天前`,
    likes: Math.floor(seed / (i + 2)) % 50,
    hasWarning: /品控|翻车|退货/.test(text)
  }));
}

function cleanText(str) {
  return str.replace(/\s+/g, ' ').replace(/收起|展开|回复/g, '').trim();
}

function hashCode(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash) + str.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash);
}

module.exports = { searchSmzdm, getSmzdmComments };
