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
const shihuo = require('./shihuo');
const { best: bestCandidate } = require('../utils/ranking');
const { advise } = require('../utils/price-advice');
const { comparePlatforms } = require('../utils/platform-compare');

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
    return { type: parsed.type, product, deals, url: parsed.url, keyword: product.title, comparison: comparePlatforms(product, deals) };
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
    return { type: parsed.type, product, deals, url: parsed.url, keyword: parsed.keyword, comparison: comparePlatforms(product, deals) };
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
    return { type: parsed.type, product, deals: [], url: '', keyword: view.title, comparison: comparePlatforms(product, []) };
  }

  // 关键词：以什么值得买爆料为商品候选。
  // 2026-09-28：候选从"取第一条带价的"改为跨源打分 argmax（与 Android
  // domain/ProductCandidateResolver.resolvePrimary + CandidateRanking 同规则）；
  // 爆料列表本身维持站点热度顺序不变，只是不再让第一条当商品头。
  let deals = [];
  let smzdmError = null;
  try {
    deals = (await smzdm.searchDeals(parsed.keyword)).deals;
  } catch (err) {
    smzdmError = err;
  }
  const pool = deals.filter((d) => d.price && /^https?:\/\//.test(d.url));
  let candidate = bestCandidate(parsed.keyword, pool);
  let productSource = candidate ? 'smzdm' : '';

  // 识货兜底（与 Android 一致）：值得买没有可用候选时，用识货结构化搜索结果补商品头。
  // 识货没有稳定商品详情页，因此只给 product.url（识货搜索页）供「去购买」，
  // 顶层 url 留空——历史价/盯价需要可追溯的商城链接，不能拿搜索页冒充。
  if (!candidate) {
    try {
      const { items } = await shihuo.searchProducts(parsed.keyword);
      const shihuoPool = items.map((item) => ({
        title: item.title,
        price: item.price,
        image: item.image,
        url: item.url,
        mall: '识货',
        explicitSubsidy: item.hasSubsidy,
      }));
      candidate = bestCandidate(parsed.keyword, shihuoPool);
      if (candidate) productSource = 'shihuo';
    } catch (err) {
      // 两个源都不可用时保留原错误语义；识货单独失败不覆盖值得买已有的爆料
      if (smzdmError && !candidate) throw smzdmError;
    }
  }
  if (!candidate && smzdmError) throw smzdmError;

  const product = productFromCandidate(parsed.keyword, candidate);

  return {
    type: parsed.type,
    product,
    deals,
    url: productSource === 'shihuo' ? '' : (product.url || ''),
    productSource,
    // 2026-09：关键词搜索时返回**用户原始关键词**（而非商品全名），
    // 与 Android 端一致——找券/B站/社区的检索词用原始关键词命中率更高；
    // 链接类输入（jd/taobao/bilibili）没有关键词，退回商品标题。
    keyword: parsed.keyword || product.title || '',
    comparison: comparePlatforms(product, deals),
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
  // 购买建议由主进程统一算，渲染层只展示（避免两端各写一份分位口径）
  merged.advice = advise(merged);
  return merged;
}

/**
 * 社区数据：什么值得买爆料/评论/值不值 + 识货商品兜底。
 * 两个源并行、失败互相隔离；只要还有一个源有结果就不整体报错，
 * 失败原因放进 sourceErrors 由 UI 如实展示（不把「源挂了」说成「没结果」）。
 * @param {string} q
 * @returns {Promise<{deals:Array, comments:Array, ratio:{up:number,down:number},
 *   shihuo:Array, sourceErrors:Record<string,string>}>}
 */
async function getCommunity(q) {
  const [smzdmResult, shihuoResult] = await Promise.allSettled([
    smzdm.getCommunity(q),
    shihuo.searchProducts(q),
  ]);

  const sourceErrors = {};
  let deals = [];
  let comments = [];
  let ratio = { up: 0, down: 0 };
  if (smzdmResult.status === 'fulfilled') {
    deals = smzdmResult.value.deals || [];
    comments = smzdmResult.value.comments || [];
    ratio = smzdmResult.value.ratio || ratio;
  } else {
    sourceErrors.smzdm = smzdmResult.reason?.message || '什么值得买不可用';
  }

  let shihuoItems = [];
  if (shihuoResult.status === 'fulfilled') {
    shihuoItems = shihuoResult.value.items || [];
  } else {
    sourceErrors.shihuo = shihuoResult.reason?.message || '识货不可用';
  }

  if (sourceErrors.smzdm && sourceErrors.shihuo) {
    throw new Error(`社区数据源均不可用：什么值得买（${sourceErrors.smzdm}）；识货（${sourceErrors.shihuo}）`);
  }
  return { deals, comments, ratio, shihuo: shihuoItems, sourceErrors };
}

module.exports = {
  parseInput,
  searchProducts,
  productFromCandidate,
  getBiliVideos: (kw) => bilibili.searchVideos(kw),
  getHistory: (url) => manmanbuy.getHistory(url),
  getHistoryMerged,
  getCoupons: (url, keyword) => gwdang.getCoupons(url, keyword),
  getCommunity,
  getShihuo: (q) => shihuo.searchProducts(q),
  crawlers: { bilibili, smzdm, manmanbuy, gwdang, jd, linkstars, shihuo },
};
