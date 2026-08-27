/**
 * 购物党爬虫 —— 隐藏优惠券（规范 §6.3）
 * -------------------------------------
 *   优先 undici 抓趋势页 SSR；失败或页面为 SPA 壳时降级 playwright
 *   （playwright 为可选依赖，未安装时给出可操作的降级提示）。
 *
 * 对外：
 *   getCoupons(url) → { coupons, finalPrice, currentPrice }
 */
'use strict';

const cheerio = require('cheerio');
const http = require('../utils/http-client');

/**
 * 尝试惰性加载 playwright（可选依赖）。
 * @returns {Promise<import('playwright').ChromiumBrowser | null>}
 */
async function tryLaunchPlaywright() {
  let pw;
  try {
    pw = require('playwright'); // eslint-disable-line global-require
  } catch {
    return null; // 未安装：由调用方给出降级提示
  }
  try {
    return await pw.chromium.launch({ headless: true });
  } catch {
    return null; // 未执行 playwright install
  }
}

/**
 * 抓取趋势页 HTML：undici 优先，playwright 兜底。
 * @param {string} url 商品链接
 * @returns {Promise<{html: string, via: 'fetch'|'playwright'}>}
 */
async function fetchTrendHtml(url) {
  const trendUrl = `https://www.gwdang.com/trend?url=${encodeURIComponent(url)}`;
  try {
    const html = await http.getText(trendUrl, {
      headers: { Referer: 'https://www.gwdang.com/' },
      timeoutMs: 8000,
    });
    // SPA 壳判定：正文没有价格数据脚本则视为需要 JS 渲染
    if (html.length > 20000 && /allPriceInfo|figureJson|trend/i.test(html)) {
      return { html, via: 'fetch' };
    }
  } catch (err) {
    if (err.code === 'ERATE_LIMIT') throw err; // 限流直接上抛，不再消耗 playwright
    if (/服务端错误 5\d{2}/.test(err.message)) {
      // 实测：www.gwdang.com 持续返回 503 错误页（GBK 短页），服务端拒绝访问，
      // 非 SPA 壳问题；不再降级 playwright（渲染后仍是 503），直接给出准确原因。
      const e = new Error(`购物党（gwdang）接口拒绝访问（${err.message.replace(/^服务端错误\s*/, 'HTTP ')}），疑似服务端反爬限流，请稍后重试`);
      e.code = 'EBLOCKED';
      throw e;
    }
  }

  const browser = await tryLaunchPlaywright();
  if (!browser) {
    const e = new Error('购物党（gwdang）趋势页为动态渲染，当前环境未安装可选浏览器组件（playwright），无法获取券数据');
    e.code = 'EDEGRADE';
    throw e;
  }
  try {
    const page = await browser.newPage();
    await page.goto(trendUrl, { waitUntil: 'domcontentloaded', timeout: 15000 });
    await page.waitForTimeout(1500); // 等待价格脚本执行
    const html = await page.content();
    return { html, via: 'playwright' };
  } finally {
    await browser.close().catch(() => {});
  }
}

/**
 * 从趋势页解析优惠券与当前价。
 * 页面为服务端模板 + 内嵌 JSON，尽力多路解析，拿不到的字段给默认值。
 * @param {string} html
 */
function parseTrend(html) {
  const $ = cheerio.load(html);
  const coupons = [];

  // 1) 结构化券卡片
  $('.coupon-item, .coupon-list li, .quan-item').each((_i, elem) => {
    const node = $(elem);
    const text = node.text().replace(/\s+/g, ' ').trim();
    const amountMatch = text.match(/(?:¥|￥)(\d+(?:\.\d+)?)/);
    const thresholdMatch = text.match(/满\s*(\d+(?:\.\d+)?)\s*(?:元)?\s*(?:可用|减)/);
    if (amountMatch) {
      coupons.push({
        title: node.find('.coupon-title, .title').first().text().trim() || '平台优惠券',
        amount: Number(amountMatch[1]),
        threshold: thresholdMatch ? Number(thresholdMatch[1]) : 0,
        expireAt: stripText(node.find('.coupon-time, .time, .expire').first().text()),
        code: stripText(node.find('.coupon-code, .code').first().text()) || null,
        link: node.find('a').first().attr('href') || '',
        stackable: /可叠加|叠加/.test(text),
      });
    }
  });

  // 2) 内嵌 JSON 兜底：页面脚本里的券信息
  if (coupons.length === 0) {
    const m = html.match(/"coupons?"\s*:\s*(\[[\s\S]{0,4000}?\])/);
    if (m) {
      try {
        const arr = JSON.parse(m[1].replace(/,(\s*[\]}])/g, '$1').replace(/'/g, '"'));
        for (const c of (Array.isArray(arr) ? arr : []).slice(0, 8)) {
          const amount = Number(c.amount || c.price || c.denomination) || null;
          if (amount) {
            coupons.push({
              title: String(c.title || c.name || '优惠券'),
              amount,
              threshold: Number(c.threshold || c.man || c.full) || 0,
              expireAt: c.expire_time ? String(c.expire_time) : '',
              code: c.code || c.password || null,
              link: String(c.url || c.link || ''),
              stackable: Boolean(c.superposition || c.stackable),
            });
          }
        }
      } catch { /* JSON 兜底失败 → 返回空券列表 */ }
    }
  }

  // 3) 当前价
  const priceText = $('.price-alternative .price, .current-price, .trend-price').first().text();
  const currentPrice = Number(priceText.replace(/[^\d.]/g, '')) || null;

  return { coupons, currentPrice };
}

/** cheerio 文本规整 */
function stripText(s) {
  return String(s || '').replace(/\s+/g, ' ').trim();
}

/**
 * 查询隐藏优惠券。
 * @param {string} url 商品链接
 * @returns {Promise<{coupons:Array, finalPrice:number|null, currentPrice:number|null, source:string}>}
 */
async function getCoupons(url) {
  const { html } = await fetchTrendHtml(url);
  const { coupons, currentPrice } = parseTrend(html);

  // 到手价 = 当前价 - 满足门槛的券面额之和（可叠加券全部计入，取最优组合的贪心近似）
  let finalPrice = currentPrice;
  if (currentPrice && coupons.length > 0) {
    const usable = coupons
      .filter((c) => c.amount > 0 && (!c.threshold || currentPrice >= c.threshold))
      .sort((a, b) => b.amount - a.amount);
    const stackable = usable.filter((c) => c.stackable);
    const best = stackable.length > 0 ? stackable : (usable.length > 0 ? [usable[0]] : []);
    const totalOff = best.reduce((s, c) => s + c.amount, 0);
    finalPrice = Math.max(0, currentPrice - totalOff);
  }

  return {
    coupons,
    finalPrice,
    currentPrice,
    source: 'gwdang',
    fetchedAt: Date.now(),
  };
}

module.exports = { getCoupons };
