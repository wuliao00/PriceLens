/**
 * 京东爬虫 —— 商品基础信息（规范 §7.2）
 * -------------------------------------
 *   标题/图片：item.m.jd.com 移动页 → 页内 `window._itemInfo.product` 结构化 JSON
 *   价格：p.3.cn 公开批量接口（尽力而为；失败时如实置 0，由 UI 显示"--"）
 *
 * 2026-09 修复（接口内容不准确 + 通道下线，与 Android JdApi 同步）：
 *   1. 旧实现解析 item.jd.com，脚本请求会拿到风控页（<title>京东验证</title>）并被
 *      当成商品名。改用 item.m.jd.com（实测可用）并锚定 _itemInfo.product 取字段。
 *   2. p.3.cn 目前在公网 DNS 已不返回可达地址（AliDNS/腾讯 DoH 均返回私网 IP，
 *      实测 2026-09-25），查价失败不再整体抛错：标题/主图照常返回，price = 0，
 *      响应里带 priceSource: 'unavailable' 供 UI 提示。
 *
 * 对外：
 *   getProduct(skuId) → { title, price, originalPrice, image, url, mall, priceSource }
 */
'use strict';

const http = require('../utils/http-client');
const { stripTags } = require('../utils/sanitizer');

const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; PLB110) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.0.0 Mobile Safari/537.36';

/** 京东模板后缀："【图片 价格 品牌 评论】-京东" / "-京东" */
const TITLE_SUFFIX = /【[^】]{0,20}】-京东\s*$|-京东\s*$/;
const SKU_NAME = /"skuName"\s*:\s*"((?:[^"\\]|\\.){1,400})"/;
const IMAGE_URL = /"imageurl"\s*:\s*"((?:[^"\\]|\\.){1,400})"/;

/** 把捕获到的 JSON 字符串字面量还原为真实文本 */
function unescapeJsonString(raw) {
  try {
    return JSON.parse(`"${raw}"`);
  } catch (_e) {
    return raw;
  }
}

function buildImageUrl(rawImg) {
  const cleaned = String(rawImg || '').replace(/^\/\//, 'https://');
  if (!cleaned) return '';
  if (/^https?:\/\//.test(cleaned)) return cleaned;
  if (cleaned.startsWith('jfs/')) return `https://m.360buyimg.com/mobilecms/s750x750_${cleaned}`;
  return '';
}

/**
 * 解析移动商品页：优先 _itemInfo 里 product 对象的 skuName/imageurl（页面别处还有
 * 截断版 skuName，必须先锚到 product）；兜底 <title>，风控页直接判失败。
 * @param {string} html
 * @returns {{title:string, image:string}|null}
 */
function parseItemPage(html) {
  const anchor = String(html).indexOf('window._itemInfo');
  if (anchor >= 0) {
    const region = String(html).slice(anchor, anchor + 200000);
    const productIdx = region.indexOf('"product"');
    const scope = productIdx >= 0 ? region.slice(productIdx, productIdx + 3000) : region;
    const nameM = scope.match(SKU_NAME);
    if (nameM) {
      const name = unescapeJsonString(nameM[1]).trim();
      if (name) {
        const imgM = scope.match(IMAGE_URL);
        return { title: name.slice(0, 80), image: imgM ? buildImageUrl(unescapeJsonString(imgM[1])) : '' };
      }
    }
  }
  const titleM = String(html).match(/<title>(.*?)<\/title>/s);
  const rawTitle = titleM ? stripTags(titleM[1]).replace(/\s+/g, ' ').trim() : '';
  const title = rawTitle.replace(TITLE_SUFFIX, '').trim();
  if (!title || title.includes('京东验证')) return null;
  return { title: title.slice(0, 80), image: '' };
}

/**
 * 批量查价（p.3.cn）。
 * @param {string[]} skuIds
 * @returns {Promise<Array<{p:number, op:number, m:number}>>}
 */
async function getPrices(skuIds) {
  const q = skuIds.map((id) => `J_${id}`).join(',');
  const res = await http.getJSON(`https://p.3.cn/prices/mgets?skuIds=${encodeURIComponent(q)}`, {
    headers: { Referer: 'https://item.jd.com/' },
  });
  if (!Array.isArray(res)) throw new Error('京东价格接口返回异常');
  return res.map((item) => ({
    p: Number(item.p) || 0,
    op: Number(item.op) || 0,
    m: Number(item.m) || 0,
  }));
}

/**
 * 获取京东商品信息（标题/主图尽力而为；价格不可得时 price = 0）。
 * @param {string} skuId 商品 ID（纯数字）
 */
async function getProduct(skuId) {
  if (!/^\d{6,}$/.test(String(skuId))) throw new Error('京东 SKU 格式不正确');
  const url = `https://item.m.jd.com/product/${skuId}.html`;

  let title = `京东商品 ${skuId}`;
  let image = '';
  try {
    const html = await http.getText(url, {
      headers: { Referer: 'https://item.m.jd.com/', 'User-Agent': MOBILE_UA },
    });
    const parsed = parseItemPage(html);
    if (parsed) {
      title = parsed.title || title;
      image = parsed.image || '';
    } else {
      console.warn(`[jd] 商品页解析失败（疑似风控页或结构变更）: ${skuId}`);
    }
  } catch (e) {
    console.warn(`[jd] 商品页获取失败: ${skuId} ${e.message}`);
  }

  let price = 0;
  let originalPrice = 0;
  let priceSource = 'unavailable';
  try {
    const prices = await getPrices([skuId]);
    const first = prices[0];
    if (first && first.p > 0) {
      price = first.p;
      originalPrice = first.op > 0 ? first.op : 0;
      priceSource = 'p3';
    } else {
      console.warn(`[jd] 公开查价不可用（p.3.cn 不可达或未收录）: ${skuId}`);
    }
  } catch (e) {
    console.warn(`[jd] 查价失败（p.3.cn 不可达）: ${skuId} ${e.message}`);
  }

  return { title, price, originalPrice, image, url, mall: '京东', priceSource };
}

module.exports = { getProduct, getPrices, parseItemPage };
