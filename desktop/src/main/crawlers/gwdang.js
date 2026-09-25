/**
 * 找券数据源（规范 §6.3）—— 购物党已失效后的替代实现
 * --------------------------------------------------
 * 2026-09 实测：gwdang /tuan/search 404、/search 302 跳滑块验证（全站风控），不可用。
 * 现走 什么值得买「优惠券频道」搜索（c=youhui，Googlebot UA 过瑞数 WAF）。
 *
 * 2026-09 修复（接口内容不准确，与 Android GwdangApi 同步）：
 * 爆料正文里的券信息是自然语言，如「目前活动售价19.87元，下单领取满15减8元优惠券，
 * 实付低至7.35元」。旧实现在找不到「满X减Y」时用 **原价−到手价** 反推券面额
 * （"券后直降"），把国补/PLUS 价/活动折扣算成了不存在的券（如"无门槛券￥500"）。
 * 现在只认显式券文案：「满X减Y(元)优惠券」或「领取X元优惠券」；拿不到就不产出该条。
 *
 * 对外：
 *   getCoupons(url, keyword) → { coupons, finalPrice, currentPrice, source, fetchedAt }
 */
'use strict';

const cheerio = require('cheerio');
const http = require('../utils/http-client');
const { stripTags } = require('../utils/sanitizer');
const { isRelevant } = require('../utils/relevance');

const SMZDM_BOT_UA = 'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)';

/** 显式满减券：「满15减8元优惠券」/「领取满5000减500元优惠券」 */
const MAN_JIAN_COUPON = /满\s*(\d+(?:\.\d+)?)\s*元?\s*减\s*(\d+(?:\.\d+)?)\s*元?\s*(?:优惠券|券)/;
/** 无门槛券：「领取5元优惠券」 */
const FLAT_COUPON = /领取\s*(\d+(?:\.\d+)?)\s*元\s*(?:优惠券|券)/;
/** 活动售价 / 实付低至（只用于价格展示，不作为券） */
const DEAL_PRICE = /(?:活动售价|售价)\s*(\d+(?:\.\d+)?)\s*元/;
const FINAL_PRICE = /实付低至\s*(\d+(?:\.\d+)?)\s*元/;

/**
 * 只提取显式券文案；找不到返回 null（绝不按价差反推）。
 * @param {string} summary 条目纯文本
 * @returns {{amount:number, threshold:number, kind:string}|null}
 */
function extractCoupon(summary) {
  const text = String(summary || '');
  const mj = text.match(MAN_JIAN_COUPON);
  if (mj) {
    const threshold = Number(mj[1]);
    const amount = Number(mj[2]);
    if (amount > 0 && threshold > amount) return { amount, threshold, kind: `满${mj[1]}减${mj[2]}` };
  }
  const flat = text.match(FLAT_COUPON);
  if (flat) {
    const amount = Number(flat[1]);
    if (amount > 0) return { amount, threshold: 0, kind: `${flat[1]}元券` };
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
  const parsed = [];
  let currentPrice = null;
  let finalPrice = null;

  const items = $('#feed-main-list .feed-row-wide, #feed-main-list li, .list-man .feed-row-wide').toArray();
  for (const item of items) {
    const node = $(item);
    const titleNode = node.find('h5 a, .feed-block-title a').first();
    const title = stripTags(titleNode.text()).replace(/\s+/g, ' ').trim();
    if (!title) continue;

    const summary = stripTags(node.text()).replace(/\s+/g, ' ');
    const deal = summary.match(DEAL_PRICE);
    const dealPrice = deal ? Number(deal[1]) : null;
    const finalM = summary.match(FINAL_PRICE);
    const final = finalM ? Number(finalM[1]) : null;
    if (currentPrice === null && dealPrice) currentPrice = dealPrice;
    if (finalPrice === null && final) finalPrice = final;

    const coupon = extractCoupon(summary);
    if (!coupon) continue; // 无显式券文案 → 不产出（旧实现在这里编造"券后直降"）

    const link = titleNode.attr('href') || '';
    parsed.push({
      title: title.slice(0, 60),
      amount: coupon.amount,
      threshold: coupon.threshold,
      kind: coupon.kind,
      expireAt: '',
      code: null,
      link: link.startsWith('//') ? `https:${link}` : link,
      stackable: /可叠加/.test(summary),
    });
  }

  // 2026-09：券条目同样做关键词相关性过滤（避免搜 iPhone 出现充电线券）
  const coupons = parsed.filter((c) => isRelevant(q, c.title)).slice(0, 8);
  if (parsed.length && coupons.length === 0) {
    console.warn(`[gwdang] ${parsed.length} 条券全部与关键词不相关: ${q}`);
  }
  if (finalPrice === null) finalPrice = currentPrice;

  return { coupons, finalPrice, currentPrice, source: 'smzdm-youhui', fetchedAt: Date.now() };
}

module.exports = { getCoupons, extractCoupon };
