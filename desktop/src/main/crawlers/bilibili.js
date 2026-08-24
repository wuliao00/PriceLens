/**
 * B 站爬虫 —— 评测视频聚合（规范 §6.1）
 * ------------------------------------
 *   - 搜索接口需要 WBI 签名（2023 后强制），本模块实现 wbiSign()
 *   - keys 来源于 nav 接口，内存缓存 12h
 *   - 未登录态注入随机 buvid3 降低 -412 风险
 *
 * 对外：
 *   searchVideos(kw)  → { videos: [...] }
 *   getView(bvid)     → { title, pic }
 */
'use strict';

const crypto = require('node:crypto');
const http = require('../utils/http-client');
const { stripTags } = require('../utils/sanitizer');

/** WBI 混淆密钥重排表（社区公开的固定表） */
const MIXIN_KEY_ENC_TAB = [
  46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
  27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
  37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
  22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
];

const WBI_KEYS_TTL = 12 * 3600 * 1000;

/** @type {{ imgKey: string, subKey: string, ts: number } | null} */
let wbiKeysCache = null;

/**
 * md5 摘要（node:crypto，无第三方依赖）。
 * @param {string} s
 */
function md5(s) {
  return crypto.createHash('md5').update(s, 'utf-8').digest('hex');
}

/** 混淆密钥重排并截取前 32 位 */
function getMixinKey(orig) {
  return MIXIN_KEY_ENC_TAB.map((n) => orig[n]).join('').slice(0, 32);
}

/**
 * WBI 参数签名（规范 §7.3）。
 * @param {Record<string, string|number>} params
 * @param {string} imgKey
 * @param {string} subKey
 * @returns {Record<string, string>} 附加了 wts / w_rid 的参数表
 */
function wbiSign(params, imgKey, subKey) {
  const mixinKey = getMixinKey(imgKey + subKey);
  const curr = Math.floor(Date.now() / 1000);
  const query = { wts: curr, ...params };
  // 官方要求过滤 value 中的 !'()* 字符
  const filtered = {};
  for (const [k, v] of Object.entries(query)) {
    filtered[k] = String(v).replace(/[!'()*]/g, '');
  }
  const qs = Object.keys(filtered).sort()
    .map((k) => `${k}=${encodeURIComponent(filtered[k])}`)
    .join('&');
  return { ...filtered, w_rid: md5(qs + mixinKey) };
}

/**
 * 获取 WBI keys（img_url / sub_url 文件名去掉扩展名）。
 * 内存缓存 12 小时。
 * @returns {Promise<{imgKey: string, subKey: string}>}
 */
async function getWbiKeys() {
  if (wbiKeysCache && Date.now() - wbiKeysCache.ts < WBI_KEYS_TTL) {
    return wbiKeysCache;
  }
  const res = await http.getJSON('https://api.bilibili.com/x/web-interface/nav');
  const wbi = res && res.data && res.data.wbi_img;
  if (!wbi || !wbi.img_url || !wbi.sub_url) {
    throw new Error('B 站 WBI 密钥获取失败');
  }
  const imgKey = String(wbi.img_url).split('/').pop().replace(/\.\w+$/, '');
  const subKey = String(wbi.sub_url).split('/').pop().replace(/\.\w+$/, '');
  wbiKeysCache = { imgKey, subKey, ts: Date.now() };
  return wbiKeysCache;
}

/** 随机 buvid3（未登录设备指纹，降低 412 概率） */
function randomBuvid() {
  return md5(String(Math.random()) + Date.now());
}

/**
 * 搜索评测视频。
 * @param {string} keyword
 * @returns {Promise<{videos: Array<{title:string, author:string, play:number,
 *   duration:string, pic:string, url:string, bvid:string}>}>}
 */
async function searchVideos(keyword) {
  const { imgKey, subKey } = await getWbiKeys();
  const signed = wbiSign({
    search_type: 'video',
    keyword,
    page: 1,
    platform: 'pc',
  }, imgKey, subKey);

  const qs = new URLSearchParams(signed).toString();
  const res = await http.getJSON(`https://api.bilibili.com/x/web-interface/wbi/search/type?${qs}`, {
    headers: {
      Referer: 'https://www.bilibili.com',
      Cookie: `buvid3=${randomBuvid()}; b_nut=${Math.floor(Date.now() / 1000)}`,
    },
  });

  if (res.code !== 0) {
    throw new Error(`B 站搜索接口返回错误 code=${res.code} ${res.message || ''}`.trim());
  }
  const list = Array.isArray(res.data?.result) ? res.data.result : [];
  const videos = list.slice(0, 12).map((item) => ({
    title: stripTags(item.title),
    author: stripTags(item.author || ''),
    play: Number(item.play) || 0,
    duration: String(item.duration || '00:00'),
    pic: String(item.pic || '').replace(/^\/\//, 'https://'),
    url: `https://www.bilibili.com/video/${item.bvid}`,
    bvid: String(item.bvid || ''),
  })).filter((v) => v.title && v.url);

  return { videos };
}

/**
 * 取视频标题/封面（用于粘贴 B 站链接时反查关键词）。
 * @param {string} bvid 如 BV1xx411c7mD
 */
async function getView(bvid) {
  const res = await http.getJSON(
    `https://api.bilibili.com/x/web-interface/view?bvid=${encodeURIComponent(bvid)}`,
    { headers: { Referer: 'https://www.bilibili.com' } },
  );
  if (res.code !== 0 || !res.data) throw new Error('B 站视频信息获取失败');
  return {
    title: stripTags(res.data.title),
    pic: String(res.data.pic || '').replace(/^\/\//, 'https://'),
  };
}

module.exports = { searchVideos, getView, wbiSign, getWbiKeys };
