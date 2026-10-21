/**
 * 同款多平台对比：把「同关键词爆料」按平台聚合，取每个平台的最低价。
 * ---------------------------------------------------------------------
 * 数据来自什么值得买爆料（已过 QueryRelevance），因此只能保证「关键词相关」，
 * 不保证同规格/同店铺。UI 必须如实标注这一限制，不能把最低价说成「同款最低」。
 *
 * 纯函数：输入商品头 + 爆料列表，输出平台行，便于单测。
 */
'use strict';

const MALL_RULES = [
  ['京东', /京东|jingdong|jd\.com/i],
  ['淘宝', /淘宝|天猫|tmall|taobao/i],
  ['拼多多', /拼多多|pinduoduo|yangkeduo/i],
  ['唯品会', /唯品会|vip\.com/i],
  ['苏宁', /苏宁|suning/i],
  ['抖音', /抖音|douyin/i],
  ['识货', /识货|shihuo/i],
  ['什么值得买', /值得买|smzdm/i],
  ['亚马逊', /亚马逊|amazon/i],
];

function round2(n) {
  return Math.round(Number(n) * 100) / 100;
}

/**
 * 平台名归一：优先用爆料自带 mall，缺失时从 URL 域名推断。
 * 认不出的域名原样返回（去掉 www.），不强行塞进某个平台。
 * @param {{mall?:string, url?:string}} deal
 * @returns {string}
 */
function mallOf(deal) {
  const raw = String(deal?.mall || '').trim();
  if (raw) {
    for (const [name, re] of MALL_RULES) {
      if (re.test(raw)) return name;
    }
    return raw.slice(0, 20);
  }
  try {
    const host = new URL(String(deal?.url || '')).hostname.replace(/^www\./i, '');
    for (const [name, re] of MALL_RULES) {
      if (re.test(host)) return name;
    }
    return host;
  } catch {
    return '未知平台';
  }
}

/**
 * @param {{price?:number}} product 商品头（当前价）
 * @param {Array<{title?:string, price?:number, url?:string, mall?:string}>} deals 爆料列表
 * @returns {{current:number|null, platforms:Array<object>, platformCount:number,
 *   offerCount:number, lowest:number|null, highest:number|null, spread:number|null,
 *   lowestMall:string|null}}
 */
function comparePlatforms(product, deals) {
  const current = Number(product?.price) > 0 ? round2(product.price) : null;
  const groups = new Map();

  for (const deal of Array.isArray(deals) ? deals : []) {
    const price = Number(deal?.price);
    const url = String(deal?.url || '').trim();
    if (!(price > 0) || !/^https?:\/\//i.test(url)) continue;
    const mall = mallOf(deal);
    const group = groups.get(mall) || { mall, price: Infinity, count: 0, title: '', url: '' };
    group.count += 1;
    if (price < group.price) {
      group.price = price;
      group.title = String(deal.title || '').slice(0, 120);
      group.url = url;
    }
    groups.set(mall, group);
  }

  const platforms = Array.from(groups.values())
    .map((group) => {
      const price = round2(group.price);
      return {
        mall: group.mall,
        price,
        title: group.title,
        url: group.url,
        count: group.count,
        delta: current == null ? null : round2(price - current),
        isLower: current != null && price < current,
      };
    })
    .sort((a, b) => a.price - b.price);

  const prices = platforms.map((p) => p.price);
  return {
    current,
    platforms,
    platformCount: platforms.length,
    offerCount: platforms.reduce((sum, p) => sum + p.count, 0),
    lowest: prices.length ? prices[0] : null,
    highest: prices.length ? prices[prices.length - 1] : null,
    spread: prices.length ? round2(prices[prices.length - 1] - prices[0]) : null,
    lowestMall: platforms.length ? platforms[0].mall : null,
  };
}

module.exports = { comparePlatforms, mallOf, MALL_RULES };
