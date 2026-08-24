/**
 * B站爬虫 — 搜索商品评测/翻车视频
 *
 * API: https://api.bilibili.com/x/web-interface/wbi/search/type
 * wbi 签名: 需要 w_rid + wts 参数
 */

const crypto = require('crypto');
const { request } = require('../utils/rate-limiter');
const { cacheGet, cacheSet } = require('../cache/manager');

const BILI_SEARCH_API = 'https://api.bilibili.com/x/web-interface/wbi/search/type';
const BILI_NAV_API = 'https://api.bilibili.com/x/web-interface/wbi/index/nav';
const CACHE_TTL = 2 * 60 * 60 * 1000; // 2 小时
const WBI_KEY_TTL = 30 * 60 * 1000;   // wbi 密钥缓存 30 分钟

// wbi 密钥缓存
let wbiKeys = null;
let wbiKeysTs = 0;

const MIXIN_KEY_ENC_TAB = [
  46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
  27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
  37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
  22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 52, 44, 34
];

/**
 * 获取 wbi 密钥对 (img_key, sub_key)
 */
async function getWbiKeys() {
  if (wbiKeys && (Date.now() - wbiKeysTs < WBI_KEY_TTL)) {
    return wbiKeys;
  }

  const cacheKey = 'bilibili:wbi_keys';
  const cached = cacheGet(cacheKey);
  if (cached && (Date.now() - cached.timestamp < WBI_KEY_TTL)) {
    wbiKeys = cached.data;
    wbiKeysTs = cached.timestamp;
    return wbiKeys;
  }

  try {
    const res = await request(BILI_NAV_API, {
      headers: { 'Referer': 'https://www.bilibili.com' }
    });

    const imgKey = res?.data?.wbi_img?.img_url?.split('/').pop()?.split('.')[0] || '';
    const subKey = res?.data?.wbi_img?.sub_url?.split('/').pop()?.split('.')[0] || '';

    wbiKeys = { img_key: imgKey, sub_key: subKey };
    wbiKeysTs = Date.now();
    cacheSet(cacheKey, wbiKeys, WBI_KEY_TTL);

    return wbiKeys;
  } catch (err) {
    console.error('[Bilibili] 获取 wbi 密钥失败:', err.message);
    // 降级: 无签名直接请求（旧版 API 可能仍可用）
    return null;
  }
}

/**
 * 计算 mixin key
 */
function getMixinKey(orig) {
  let mixed = '';
  for (const idx of MIXIN_KEY_ENC_TAB) {
    if (idx < orig.length) mixed += orig[idx];
  }
  return mixed.slice(0, 32);
}

/**
 * 签名 wbi 参数
 */
function signWbi(params, imgKey, subKey) {
  const mixinKey = getMixinKey(imgKey + subKey);
  const wts = Math.floor(Date.now() / 1000);

  // 按 key 排序
  const sorted = Object.keys(params)
    .sort()
    .reduce((acc, k) => {
      // 过滤掉特殊字符
      acc.push(`${encodeURIComponent(k)}=${encodeURIComponent(params[k].toString().replace(/[!'()*]/g, ''))}`);
      return acc;
    }, []);

  const queryStr = sorted.join('&');
  const signStr = queryStr + mixinKey;
  const wRid = crypto.createHash('md5').update(signStr).digest('hex');

  return { w_rid: wRid, wts: String(wts) };
}

/**
 * 搜索 B站视频
 * @param {string} keyword - 搜索关键词
 * @returns {Promise<Array>}
 */
async function searchBilibili(keyword) {
  // 1. 先查缓存
  const cacheKey = `bilibili:${keyword}`;
  const cached = cacheGet(cacheKey);
  if (cached && (Date.now() - cached.timestamp < CACHE_TTL)) {
    return cached.data;
  }

  try {
    // 2. 获取 wbi 密钥
    const keys = await getWbiKeys();

    // 3. 构造参数
    const params = {
      search_type: 'video',
      keyword: `${keyword} 评测`,
      order: 'pubdate',
      page: '1',
      page_size: '12'
    };

    // 添加 wbi 签名
    if (keys?.img_key && keys?.sub_key) {
      const signed = signWbi(params, keys.img_key, keys.sub_key);
      Object.assign(params, signed);
    }

    const searchParams = new URLSearchParams(params);

    const res = await request(`${BILI_SEARCH_API}?${searchParams}`, {
      headers: {
        'Referer': 'https://www.bilibili.com',
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
      }
    });

    if (res.code !== 0) {
      // wbi 签名过期 → 重试（清除密钥缓存）
      if (res.code === -799 || res.code === -352) {
        wbiKeys = null;
        wbiKeysTs = 0;
        cacheSet('bilibili:wbi_keys', null, 0);
        console.warn('[Bilibili] wbi 签名失效，重试...');
        throw new Error('wbi expired');
      }
      throw new Error(`B站 API 错误: code=${res.code}, msg=${res.message || ''}`);
    }

    const videos = (res.data?.result || []).map(v => ({
      id: v.aid || v.id,
      bvid: v.bvid,
      title: (v.title || '').replace(/<em class="keyword">|<\/em>/g, ''),
      author: v.author,
      pic: v.pic ? `https:${v.pic}` : '',
      play: formatCount(v.play),
      duration: formatDuration(v.duration),
      url: `https://www.bilibili.com/video/${v.bvid}`,
      isWarning: /翻车|避坑|避雷|缺点|退货|垃圾|千万别买/.test(v.title || '')
    }));

    // 4. 写缓存
    cacheSet(cacheKey, videos, CACHE_TTL);

    return videos;
  } catch (err) {
    console.error('[Bilibili]', err.message);
    // 返回过期缓存作为降级
    if (cached) return cached.data;
    return [];
  }
}

function formatCount(num) {
  if (!num) return '0';
  const n = parseInt(num);
  if (n >= 10000) return (n / 10000).toFixed(1) + '万';
  return n.toString();
}

function formatDuration(sec) {
  if (!sec) return '00:00';
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

module.exports = { searchBilibili };
