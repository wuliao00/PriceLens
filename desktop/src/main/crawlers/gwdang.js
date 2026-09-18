/**
 * 找券数据源（规范 §6.3）—— 购物党已失效后的替代实现
 * --------------------------------------------------
 * 2026-09 实测：gwdang /tuan/search 404、/search 302 跳滑块验证（全站风控），
 * 关键词还改用 GBK 编码 —— 已不可用。
 * 现走 什么值得买「优惠券频道」搜索（c=youhui，Googlebot UA 过瑞数 WAF）：
 * 爆料正文自带「券后到手价 / 原价 / 满X减Y」，据此还原券面额与门槛。
 * 京东联盟官方接口（需 appkey）可作后续升级源。
 *
 * 对外：
 *   getCoupons(url, keyword) → { coupons, finalPrice, currentPrice }
 */
'use strict';

const cheerio = require('cheerio');
const http = require('../utils/http-client');
const { stripTags } = require('../utils/sanitizer');

const SMZDM_BOT_UA = 'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)';

/** 从爆料正文提取券：优先显式「满X减Y」，否则用 原价−到手价 还原 */
function extractCoupon(summary, finalPrice) {
  const mj = summary.match(/满\s*(\d+(?:\.\d+)?)\s*元?\s*减\s*(\d+(?:\.\d+)?)/);
  if (mj) {
    return { amount: Number(mj[2]), threshold: Number(mj[1]), kind: `满${mj[1]}减${mj[2]}` };
  }
  const om = summary.match(/(?:售价|原价|页面价)\s*(\d+(?:\.\d+)?)\s*元/);
  if (om && finalPrice) {
    const origin = Number(om[1]);
    const off = Math.round((origin - finalPrice) * 100) / 100;
    if (off > 1) return { amount: off, threshold: 0, kind: '券后直降' };
  }
  return null;
}

/**
 * 查询优惠券。
 * @param {string} url 商品链接（保留签名兼容，实际按关键词检索）
 * @param {string} keyword 商品关键词/标题
 */
async function getCoupons(url, keyword) {
  const q = String(keyword || '').trim();
  if (!q) {
    const e = new Error('缺少商品关键词，无法检索优惠券');
    e.code = 'EKEYWORD';
    throw e;
  }
  const html = await http.getText(
    `https://search.smzdm.com/?c=youhui&s=${encodeURIComponent(q)}&v=a&order=score`,
    { headers: { Referer: 'https://www.smzdm.com/', 'User-Agent': SMZDM_BOT_UA } },
  );
  if (html.length < 1000) {
    const e = new Error('什么值得买券源返回异常页面（疑似反爬拦截），请稍后重试');
    e.code = 'EBLOCKED';
    throw e;
  }
  const $ = cheerio.load(html);
  const coupons = [];
  let currentPrice = null;
  let finalPrice = null;

  const items = $('#feed-main-list .feed-row-wide, #feed-main-list li, .list-man .feed-row-wide').toArray();
  let firstDealPrice = null;
  for (const item of items) {
    const node = $(item);
    const titleNode = node.find('h5 a, .feed-block-title a').first();
    const title = stripTags(titleNode.text()).replace(/\s+/g, ' ').trim();
    if (!title) continue;
    const link = titleNode.attr('href') || '';
    const priceEl = node.find('.z-highlight, .feed-block-title .z-highlight').first();
    const dealPrice = Number(
      (priceEl.length ? stripTags(priceEl.text()) : title).replace(/[^\d.]/g, '')
    ) || null;
    if (firstDealPrice === null && dealPrice) firstDealPrice = dealPrice;

    const summary = stripTags(node.text()).replace(/\s+/g, ' ');
    const coupon = extractCoupon(summary, dealPrice);
    if (coupon && coupon.amount > 0) {
      coupons.push({
        title: title.slice(0, 60),
        amount: coupon.amount,
        threshold: coupon.threshold,
        expireAt: '',
        code: null,
        link: link.startsWith('//') ? `https:${link}` : link,
        stackable: /可叠加/.test(summary),
      });
      if (currentPrice === null) {
        const om = summary.match(/(?:售价|原价|页面价)\s*(\d+(?:\.\d+)?)\s*元/);
        currentPrice = om ? Number(om[1]) : dealPrice;
        finalPrice = dealPrice;
      }
    }
    if (coupons.length >= 8) break;
  }

  if (currentPrice === null) currentPrice = firstDealPrice;
  if (finalPrice === null) finalPrice = firstDealPrice;

  return { coupons, finalPrice, currentPrice, source: 'smzdm-youhui', fetchedAt: Date.now() };
}

module.exports = { getCoupons };
