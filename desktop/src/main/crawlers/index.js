/**
 * 爬虫调度器（规范 §7）
 * --------------------
 *   parseInput(text)  → 识别 关键词 / 京东链接 / 淘宝链接 / B站链接
 *   searchProducts(q) → 四步闭环的入口：产出商品头信息 + 爆料列表
 *   其余模块由 ipc 层按需调度（bilibili / manmanbuy / gwdang / smzdm）
 */
'use strict';

const bilibili = require('./bilibili');
const smzdm = require('./smzdm');
const manmanbuy = require('./manmanbuy');
const gwdang = require('./gwdang');
const jd = require('./jd');

/**
 * 输入类型识别。
 * @param {string} text 用户输入（关键词或链接）
 * @returns {{type:'jd'|'taobao'|'bilibili'|'keyword', url?:string, sku?:string, bvid?:string, keyword?:string}}
 */
function parseInput(text) {
  const s = String(text || '').trim();

  // 京东：item.jd.com/{sku}.html / item.m.jd.com/product/{sku}.html / ?sku= / ?wareId=
  let m = s.match(/(?:item(?:\.m)?\.jd\.com\/(?:product\/)?(\d{6,})\.html)|([?&](?:sku|wareId)=(\d{6,}))/i);
  if (m) {
    const sku = m[1] || m[3];
    return { type: 'jd', sku, url: `https://item.jd.com/${sku}.html` };
  }

  // 淘宝/天猫：detail.tmall.com/item.htm?id= / item.taobao.com/item.htm?id=
  m = s.match(/(?:detail\.tmall\.com|item\.taobao\.com|chaoshi\.detail\.tmall\.com)\/item[^#]*?[?&]id=(\d{6,})/i);
  if (m) {
    const id = m[1];
    return {
      type: 'taobao',
      url: `https://item.taobao.com/item.htm?id=${id}`,
      keyword: `淘宝商品 ${id}`,
    };
  }

  // B 站：BV 号
  m = s.match(/bilibili\.com\/video\/(BV[0-9A-Za-z]{10})/i) || s.match(/^(BV[0-9A-Za-z]{10})$/);
  if (m) return { type: 'bilibili', bvid: m[1] };

  return { type: 'keyword', keyword: s };
}

/**
 * 搜索入口：根据输入类型产出商品头 + 爆料列表。
 * 各子源失败互相隔离：smzdm 失败不影响 jd 商品，反之亦然。
 * @param {string} q
 * @returns {Promise<{type:string, product:object, deals:Array, url:string, keyword:string}>}
 */
async function searchProducts(q) {
  const parsed = parseInput(q);

  if (parsed.type === 'jd') {
    const product = await jd.getProduct(parsed.sku);
    let deals = [];
    try {
      deals = (await smzdm.searchDeals(product.title.slice(0, 40))).deals;
    } catch { /* 爆料可选 */ }
    return { type: parsed.type, product, deals, url: parsed.url, keyword: product.title };
  }

  if (parsed.type === 'taobao') {
    // 淘宝详情页强登录墙，标题从历史价格接口的返回里补（见 getHistory）
    const product = {
      title: parsed.keyword,
      price: 0,
      originalPrice: 0,
      image: '',
      url: parsed.url,
      mall: '淘宝',
    };
    let deals = [];
    try {
      deals = (await smzdm.searchDeals(parsed.keyword)).deals;
    } catch { /* 可选 */ }
    return { type: parsed.type, product, deals, url: parsed.url, keyword: parsed.keyword };
  }

  if (parsed.type === 'bilibili') {
    const view = await bilibili.getView(parsed.bvid);
    const product = {
      title: view.title,
      price: 0,
      originalPrice: 0,
      image: view.pic,
      url: `https://www.bilibili.com/video/${parsed.bvid}`,
      mall: '哔哩哔哩',
    };
    return { type: parsed.type, product, deals: [], url: '', keyword: view.title };
  }

  // 关键词：以什么值得买爆料为商品候选
  const { deals } = await smzdm.searchDeals(parsed.keyword);
  const candidate = deals.find((d) => d.price && /^https?:\/\//.test(d.url)) || null;
  const product = candidate
    ? {
        title: candidate.title,
        price: candidate.price,
        originalPrice: candidate.price,
        image: candidate.image,
        url: candidate.url,
        mall: candidate.mall,
      }
    : { title: parsed.keyword, price: 0, originalPrice: 0, image: '', url: '', mall: '' };

  return {
    type: parsed.type,
    product,
    deals,
    url: product.url || '',
    keyword: product.title || parsed.keyword,
  };
}

module.exports = {
  parseInput,
  searchProducts,
  getBiliVideos: (kw) => bilibili.searchVideos(kw),
  getHistory: (url) => manmanbuy.getHistory(url),
  getCoupons: (url) => gwdang.getCoupons(url),
  getCommunity: (q) => smzdm.getCommunity(q),
  crawlers: { bilibili, smzdm, manmanbuy, gwdang, jd },
};
