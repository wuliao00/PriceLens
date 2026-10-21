/**
 * PriceLens 扩展 service worker
 * -----------------------------
 * 只做两件事：
 *   1. B 站评测搜索（WBI 签名 + 商单/夸大标记，缓存 10 分钟）
 *   2. 给 content script 返回结构化结果 / 可读错误
 *
 * 不读取页面 Cookie，不上传任何数据；页面信息只在本机内存里用于请求。
 */
'use strict';

importScripts('md5.js', 'risk.js');

const MIXIN_KEY_ENC_TAB = [
  46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
  27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
  37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
  22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
];

const WBI_TTL = 12 * 3600 * 1000;
const SEARCH_TTL = 10 * 60 * 1000;
const searchCache = new Map(); // keyword → { ts, videos }

function getMixinKey(orig) {
  return MIXIN_KEY_ENC_TAB.map((n) => orig[n]).join('').slice(0, 32);
}

function wbiSign(params, imgKey, subKey) {
  const mixinKey = getMixinKey(imgKey + subKey);
  const query = { wts: Math.floor(Date.now() / 1000), ...params };
  const filtered = {};
  for (const [key, value] of Object.entries(query)) {
    filtered[key] = String(value).replace(/[!'()*]/g, '');
  }
  const qs = Object.keys(filtered).sort()
    .map((key) => `${key}=${encodeURIComponent(filtered[key])}`)
    .join('&');
  return { ...filtered, w_rid: md5(qs + mixinKey) };
}

async function getWbiKeys() {
  const stored = await chrome.storage.local.get('pricelensWbi');
  const cached = stored.pricelensWbi;
  if (cached && cached.imgKey && cached.subKey && Date.now() - cached.ts < WBI_TTL) {
    return cached;
  }
  const res = await fetch('https://api.bilibili.com/x/web-interface/nav', {
    headers: { Accept: 'application/json' },
  });
  if (!res.ok) throw new Error(`B站 WBI 密钥请求失败（HTTP ${res.status}）`);
  const json = await res.json();
  const wbi = json && json.data && json.data.wbi_img;
  if (!wbi || !wbi.img_url || !wbi.sub_url) throw new Error('B站 WBI 密钥结构异常');
  const imgKey = String(wbi.img_url).split('/').pop().replace(/\.\w+$/, '');
  const subKey = String(wbi.sub_url).split('/').pop().replace(/\.\w+$/, '');
  const keys = { imgKey, subKey, ts: Date.now() };
  await chrome.storage.local.set({ pricelensWbi: keys });
  return keys;
}

function stripTags(text) {
  return String(text || '')
    .replace(/<[^>]*>/g, '')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * 轻量相关性：从商品标题里取「带数字的型号 token」作为硬条件
 * （iPhone 15 → 15；Mate 80 → 80；WH-1000XM5 → 1000xm5），
 * 没有数字型号时退回最长拉丁词（AirPods Pro → airpods）。
 * 目的是挡掉 B 站搜索常见的旧型号/配件/其它机型。
 */
function modelTokens(keyword) {
  const s = String(keyword || '').toLowerCase().replace(/评测/g, ' ');
  const tokens = [];
  const re = /[a-z]+[a-z0-9-]*|[0-9]{2,4}|[一-鿿]{2,}/g;
  let m;
  while ((m = re.exec(s)) !== null) tokens.push(m[0]);
  const digit = tokens.filter((t) => /\d/.test(t));
  if (digit.length) return digit;
  const latin = tokens.filter((t) => /[a-z]/.test(t)).sort((a, b) => b.length - a.length);
  if (latin.length) return [latin[0]];
  return tokens.slice(0, 1);
}

function isBiliRelevant(keyword, title) {
  const text = String(title || '').toLowerCase();
  if (!text) return false;
  const required = modelTokens(keyword);
  if (!required.length) return true;
  // 配件/说明书类视频不当作评测（商品关键词本身找配件时除外）
  if (!/手机壳|钢化膜|数据线|充电器/.test(String(keyword).toLowerCase())
    && /手机壳|钢化膜|数据线|充电器|说明书/.test(text)) return false;
  // 二手转让/求购帖子不是评测
  if (/出两个|收自粉丝|求购|转让|闲鱼/.test(text)) return false;
  return required.every((token) => text.includes(token));
}

function formatPlay(n) {
  const v = Number(n) || 0;
  return v >= 10000 ? `${(v / 10000).toFixed(1)}万` : String(v);
}

async function searchBili(keyword) {
  const q = String(keyword || '').trim().slice(0, 80);
  if (!q) return [];
  const hit = searchCache.get(q);
  if (hit && Date.now() - hit.ts < SEARCH_TTL) return hit.videos;

  const { imgKey, subKey } = await getWbiKeys();
  const signed = wbiSign({
    search_type: 'video',
    keyword: q,
    page: 1,
    platform: 'pc',
  }, imgKey, subKey);
  const qs = new URLSearchParams(signed).toString();
  const res = await fetch(`https://api.bilibili.com/x/web-interface/wbi/search/type?${qs}`, {
    headers: { Accept: 'application/json' },
  });
  if (!res.ok) throw new Error(`B站搜索失败（HTTP ${res.status}）`);
  const json = await res.json();
  if (json.code !== 0) {
    throw new Error(`B站搜索被拒绝（code=${json.code} ${json.message || ''}）`.trim());
  }
  const rows = (json.data && Array.isArray(json.data.result)) ? json.data.result : [];
  const videos = rows.slice(0, 6).map((item) => {
    const title = stripTags(item.title);
    const tags = stripTags(item.tag || '');
    return {
      title,
      author: stripTags(item.author || ''),
      play: Number(item.play) || 0,
      playText: formatPlay(item.play),
      duration: String(item.duration || ''),
      pic: String(item.pic || '').replace(/^\/\//, 'https://'),
      bvid: String(item.bvid || ''),
      risk: assessContentRisk(`${title} ${tags}`, Number(item.is_union_video) === 1),
    };
  }).filter((video) => video.title && video.bvid && isBiliRelevant(q, video.title));
  searchCache.set(q, { ts: Date.now(), videos });
  return videos;
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (!message || typeof message !== 'object') return false;
  if (message.type === 'ping') {
    sendResponse({ ok: true, version: chrome.runtime.getManifest().version });
    return false;
  }
  if (message.type === 'biliSearch') {
    searchBili(message.keyword)
      .then((videos) => sendResponse({ ok: true, videos }))
      .catch((err) => sendResponse({ ok: false, error: err && err.message ? err.message : 'B站评测暂不可用' }));
    return true;
  }
  return false;
});
