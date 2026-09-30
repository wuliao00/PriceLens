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
const linkstars = require('./linkstars');
const { best: bestCandidate } = require('../utils/ranking');

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
/**
 * 把"跨源打分选出的候选"装配成商品头（抽成纯函数便于单测，与 Android 端
 * domain/ProductCandidateResolver 的可测要求一致）。
 *
 * originalPrice 一律 0 = "未知"：爆料源只带一个价格（smzdm deal 结构 =
 * {title, price, url, …}，没有原价字段）。旧实现写 `originalPrice: candidate.price`
 * 等于凭空造出一个和现价相等的"原价"，也与 Android 端口径不一致
 * （那边 ProductCandidate.originalPrice 可空，爆料/当当/识货构造处一律传 null）。
 */
function productFromCandidate(keyword, candidate) {
  if (!candidate) {
    return { title: keyword, price: 0, originalPrice: 0, image: '', url: '', mall: '' };
  }
  return {
    title: candidate.title,
    price: candidate.price,
    originalPrice: 0,
    image: candidate.image,
    url: candidate.url,
    mall: candidate.mall,
  };
}

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

  // 关键词：以什么值得买爆料为商品候选。
  // 2026-09-28：候选从"取第一条带价的"改为跨源打分 argmax（与 Android
  // domain/ProductCandidateResolver.resolvePrimary + CandidateRanking 同规则）；
  // 爆料列表本身维持站点热度顺序不变，只是不再让第一条当商品头。
  const { deals } = await smzdm.searchDeals(parsed.keyword);
  const pool = deals.filter((d) => d.price && /^https?:\/\//.test(d.url));
  const candidate = bestCandidate(parsed.keyword, pool);
  const product = productFromCandidate(parsed.keyword, candidate);

  return {
    type: parsed.type,
    product,
    deals,
    url: product.url || '',
    // 2026-09：关键词搜索时返回**用户原始关键词**（而非商品全名），
    // 与 Android 端一致——找券/B站/社区的检索词用原始关键词命中率更高；
    // 链接类输入（jd/taobao/bilibili）没有关键词，退回商品标题。
    keyword: parsed.keyword || product.title || '',
  };
}

/**
 * 历史价格三源合并（与 Android PriceRepository.buildHistory 对齐）：
 *  1. 慢慢买：公开 JSON + 用户自填 Cookie 的 SSR 通道
 *  2. 自建曲线：本地累积的每日采样点（readLocal/writeLocal 由 ipc 注入）
 *  3. 星罗好货：京东 SKU 命中榜单时补点——只有在售价（goods_list_money）够格，
 *     券后历史低价（real_money）不写进曲线（F1，2026-09-29）
 * @param {string} url 商品链接
 * @param {{apikey?:string, cookie?:string,
 *   readLocal?: () => Promise<Array<{date:string,price:number}>>,
 *   writeLocal?: (points:Array<{date:string,price:number}>) => Promise<void>}} creds
 */
async function getHistoryMerged(url, creds = {}) {
  const skuMatch = String(url || '').match(/item(?:\.m)?\.jd\.com\/(?:product\/)?(\d{6,})/i);
  const sku = skuMatch ? skuMatch[1] : null;

  let base = null;
  let baseErr = null;
  try {
    base = await manmanbuy.getHistory(url, creds.cookie);
  } catch (err) { baseErr = err; }

  let points = base ? base.points.slice() : [];
  if (points.length === 0 && creds.readLocal) {
    points = (await creds.readLocal().catch(() => [])) || [];
  }
  if (sku && creds.apikey) {
    const deal = await linkstars.lookupSku(sku, creds.apikey).catch(() => null);
    // F1（2026-09-29，与 Android PriceRepository.buildHistory 同规则）：
    // 星罗是「历史低价榜」，只有它的在售价（goods_list_money）够格补今日的曲线点；
    // real_money（券后历史低价）是历史位置，写进曲线等于自己造历史。
    const sample = linkstars.curveWorthy(linkstars.toPriceSample(deal));
    if (sample) {
      const today = new Date().toISOString().slice(0, 10);
      if (!points.some((p) => p.date === today)) {
        points = points.concat([{ date: today, price: sample.price }]).sort((a, b) => a.date.localeCompare(b.date));
      }
    }
  }
  if (points.length === 0) {
    throw baseErr || new Error('暂无该商品的历史价格数据');
  }

  const prices = points.map((p) => p.price);
  const merged = {
    current: base ? base.current : prices[prices.length - 1],
    lowest: Math.min(base ? base.lowest : Infinity, ...prices),
    highest: Math.max(base ? base.highest : 0, ...prices),
    points,
    dateFrom: points[0].date,
    dateTo: points[points.length - 1].date,
    source: base ? base.source : 'local',
    fetchedAt: Date.now(),
  };
  if (creds.writeLocal) await creds.writeLocal(points).catch(() => {});
  return merged;
}

module.exports = {
  parseInput,
  searchProducts,
  productFromCandidate,
  getBiliVideos: (kw) => bilibili.searchVideos(kw),
  getHistory: (url) => manmanbuy.getHistory(url),
  getHistoryMerged,
  getCoupons: (url, keyword) => gwdang.getCoupons(url, keyword),
  getCommunity: (q) => smzdm.getCommunity(q),
  crawlers: { bilibili, smzdm, manmanbuy, gwdang, jd, linkstars },
};
