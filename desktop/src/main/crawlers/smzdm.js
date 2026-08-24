/**
 * 什么值得买爬虫 —— 社区验证（规范 §6.4）
 * ---------------------------------------
 *   - 爆料列表：search.smzdm.com SSR 页 → cheerio 解析
 *   - 热评：首条爆料的文章页 SSR → 尽力解析（拿不到时优雅返回空）
 *
 * 对外：
 *   searchDeals(q)      → { deals: [...] }
 *   getCommunity(q)     → { deals, comments, ratio }
 */
'use strict';

const cheerio = require('cheerio');
const http = require('../utils/http-client');
const { stripTags } = require('../utils/sanitizer');

/** 把列表页的相对时间/日期文本解析为时间戳（解析失败返回 0） */
function parseTimeText(text) {
  const s = String(text || '').trim();
  if (!s) return 0;
  const now = Date.now();
  if (/(\d+)\s*分钟前/.test(s)) return now - Number(RegExp.$1) * 60 * 1000;
  if (/(\d+)\s*小时前/.test(s)) return now - Number(RegExp.$1) * 3600 * 1000;
  if (/(\d+)\s*天前/.test(s)) return now - Number(RegExp.$1) * 24 * 3600 * 1000;
  const m = s.match(/(\d{4})-(\d{2})-(\d{2})/);
  if (m) return new Date(`${m[1]}-${m[2]}-${m[3]}T12:00:00+08:00`).getTime();
  const m2 = s.match(/(\d{2})-(\d{2})\s+(\d{2}):(\d{2})/); // MM-DD HH:mm（当年）
  if (m2) return new Date(`${new Date().getFullYear()}-${m2[1]}-${m2[2]}T12:00:00+08:00`).getTime();
  return 0;
}

/** 从爆料标题中提取价格（如 "iPhone 16 128g 5999元" → 5999） */
function extractPrice(text) {
  const m = String(text || '').match(/(?:¥|￥|\s)(\d{2,6}(?:\.\d{1,2})?)(?:元|\b)/);
  return m ? Number(m[1]) : null;
}

/**
 * 搜索最近爆料。
 * @param {string} q 关键词
 * @returns {Promise<{deals: Array<{title:string, price:number|null, url:string,
 *   image:string, mall:string, time:number, ts:number}>}>}
 */
async function searchDeals(q) {
  const html = await http.getText(
    `https://search.smzdm.com/?c=home&s=${encodeURIComponent(q)}&v=b&order=time`,
    { headers: { Referer: 'https://www.smzdm.com/' } },
  );
  const $ = cheerio.load(html);
  const deals = [];

  // 列表结构随版本变动，这里用多组选择器兜底
  const items = $('#feed-main-list .feed-row-wide, #feed-main-list li, .list-man .feed-row-wide').toArray();
  for (const item of items) {
    const node = $(item);
    const linkEl = node.find('h5 a, .feed-block-title a').first();
    const url = linkEl.attr('href') || '';
    const titleNode = node.find('h5 a, .feed-block-title a').first();
    let title = stripTags(titleNode.text());
    if (!title) continue;
    // 标题节点内常含价格高亮 span，剥离后重新提取价格
    const priceEl = node.find('.z-highlight, .feed-block-title .z-highlight').first();
    const price = priceEl.length ? Number(stripTags(priceEl.text()).replace(/[^\d.]/g, '')) || null
      : extractPrice(title);
    const img = node.find('img').first();
    const mall = stripTags(node.find('.feed-block-info a.z-highlight, .feed-block-extras span').first().text());
    const timeText = node.find('.feed-block-info time').first().text()
      || node.find('.feed-block-extras time').first().text();

    deals.push({
      title: title.replace(/\s+/g, ' ').trim(),
      price,
      url: url.startsWith('//') ? `https:${url}` : url,
      image: (img.attr('data-src') || img.attr('src') || '').replace(/^\/\//, 'https://'),
      mall: mall || '未知渠道',
      time: parseTimeText(timeText),
      ts: Date.now(),
    });
  }

  if (deals.length === 0) {
    throw new Error('什么值得买搜索结果解析失败（页面结构可能已变更）');
  }
  return { deals: deals.slice(0, 10) };
}

/**
 * 解析文章页的热评与值/不值投票。
 * @param {string} dealUrl
 */
async function fetchArticleMeta(dealUrl) {
  try {
    const html = await http.getText(dealUrl, { headers: { Referer: 'https://www.smzdm.com/' } });
    const $ = cheerio.load(html);
    const comments = [];
    $('.comment-panel .comment-item, .article-comments .comment-item, li.comment').slice(0, 10).each((_i, elem) => {
      const node = $(elem);
      const user = stripTags(node.find('.userinfo a, .comment-contentInfo a, .avatar-name').first().text());
      const content = stripTags(node.find('.comment-content, .c_txt, p').first().text());
      const time = parseTimeText(node.find('.time, .comment_time').first().text());
      if (content) comments.push({ user: user || '匿名用户', content, time });
    });
    // 值 / 不值 投票
    const upText = stripTags($('.score-btn-left .score, .vote-up .num, .unvoted-zhi').first().text());
    const downText = stripTags($('.score-btn-right .score, .vote-down .num, .unvoted-buzhi').first().text());
    const up = Number(upText.replace(/[^\d]/g, '')) || 0;
    const down = Number(downText.replace(/[^\d]/g, '')) || 0;
    return { comments, ratio: { up, down } };
  } catch {
    return { comments: [], ratio: { up: 0, down: 0 } }; // 文章页失败不影响爆料列表
  }
}

/**
 * 社区验证聚合：爆料 + 热评 + 值/不值。
 * @param {string} q
 */
async function getCommunity(q) {
  const { deals } = await searchDeals(q);
  const first = deals.find((d) => /^https?:\/\//.test(d.url));
  const meta = first ? await fetchArticleMeta(first.url) : { comments: [], ratio: { up: 0, down: 0 } };
  return { deals, comments: meta.comments, ratio: meta.ratio };
}

module.exports = { searchDeals, getCommunity };
