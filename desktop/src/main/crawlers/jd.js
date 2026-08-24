/**
 * 京东爬虫 —— 商品基础信息（规范 §7.2）
 * -------------------------------------
 *   价格：p.3.cn 公开批量接口（无需登录）
 *   标题/图片：item.jd.com SSR 页 → cheerio（尽力解析，失败给占位）
 *
 * 对外：
 *   getProduct(skuId) → { title, price, originalPrice, image, url, mall }
 */
'use strict';

const cheerio = require('cheerio');
const http = require('../utils/http-client');
const { stripTags } = require('../utils/sanitizer');

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
 * 获取京东商品信息。
 * @param {string} skuId 商品 ID（纯数字）
 * @returns {Promise<{title:string, price:number, originalPrice:number,
 *   image:string, url:string, mall:string}>}
 */
async function getProduct(skuId) {
  const url = `https://item.jd.com/${skuId}.html`;

  // 标题与图片（尽力；失败不影响价格）
  let title = `京东商品 ${skuId}`;
  let image = '';
  try {
    const html = await http.getText(url, { headers: { Referer: 'https://www.jd.com/' } });
    const $ = cheerio.load(html);
    const skuName = stripTags($('.sku-name').first().text());
    title = skuName || stripTags($('title').first().text()).replace(/[-_]京东.*$/, '') || title;
    image = ($('#spec-img').first().attr('src')
      || $('.spec-list img').first().attr('data-origin')
      || '').replace(/^\/\//, 'https://');
  } catch { /* 详情页失败 → 保留默认标题 */ }

  // 价格（失败则抛出，由上层降级为无价格商品）
  const [price] = await getPrices([skuId]);
  return {
    title,
    price: price?.p || 0,
    originalPrice: price?.op || 0,
    image,
    url,
    mall: '京东',
  };
}

module.exports = { getProduct, getPrices };
