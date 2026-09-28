/**
 * 跨源商品候选打分（2026-09-28 新增，与 Android 端 domain/CandidateRanking.kt 同规则）
 * ----------------------------------------------------------------------------------
 * 取代旧的候选选择："过滤后取第一条带价的"。实况里第一条往往是错的
 * （2026-09-28 回放，夹具 app/src/test/resources/fixtures/live_candidate_pools.json）：
 *   mate 80   ¥8840.95 渠道价 → ¥4079.15 华为 Mate 80 手机 12GB+512GB
 *   x8s       ¥108.80 当当纸品 → ¥2860.00 国家补贴：OPPO Find X8s+ 5G手机 12GB+512GB
 *   iPhone 15 ¥4248.00 二手混卖 → ¥7561.01 Apple iPhone 15 5G手机 512GB 粉色
 *   oppo x8s  无候选（相关性误杀）→ ¥2860.00（依赖 relevance.js 的分段修复）
 *
 * 打分是纯函数；权重来自同一批复现回放（PriceLens-probe/out/rule_variants2.txt 段 D）：
 *   品类词 +2.0 / 规格完整 +1.0 / 国补·PLUS +0.5 / 二手 −2.5 / 多机型混卖 −1.5 /
 *   价格带偏离 −1.2·|ln(p/中位价)| / query 未含后缀而标题含 pro·max·ultra·plus −0.8 /
 *   query 命中品牌但标题缺该品牌写法 −1.0。
 * 注意：Pro/Max 等后缀**只降权不过滤**（列表里仍要出现，见 relevance.js）。
 */
'use strict';

const { normalize, brandOf, hasAnyAlias } = require('./relevance');

const WEIGHT_CATEGORY = 2.0;
const WEIGHT_SPEC = 1.0;
const WEIGHT_SUBSIDY = 0.5;
const WEIGHT_SECONDHAND = -2.5;
const WEIGHT_MULTI_MODEL = -1.5;
const WEIGHT_PRICE_BAND = -1.2;
const WEIGHT_SERIES_SUFFIX = -0.8;
const WEIGHT_BRAND_MISSING = -1.0;

/** 品类词（归一化后做子串；配件词在相关性阶段已被剔除） */
const CATEGORY_WORDS = ['手机', '平板', '笔记本', '手表', '耳机', '相机', '电视'];
/** 二手 / 官翻信号（实况样例："全网通激活无使用"、"95新"） */
const SECONDHAND = /二手|激活|官换|官翻|翻新|准新|备用机|9[589]新|9\.[589]新/;
/** 多机型混卖：三个及以上型号数字用 / 、 , 串联（"iPhone15/14/13/12"） */
const MULTI_MODEL = /\d{2,4}\s*(?:\/|、|,)\s*\d{2,4}\s*(?:\/|、|,)\s*\d{1,4}/;
/** 国补 / PLUS 渠道信号 */
const SUBSIDY = /国家补贴|政府补贴|国补|政府补助|plus会员/;
/** 内存+存储组合（"12GB+512GB" / "12+256GB" / "16GB+1TB"） */
const MEMORY_STORAGE_COMBO = /\d{1,2}\s*gb\s*\+?\s*\d{1,3}\s*(?:gb|tb)|\d{1,2}\+\d{2,4}\s*(?:gb|tb)/;
/** 容量出现次数（≥2 个容量也算规格完整） */
const CAPACITY = /\d{1,3}\s*(?:gb|tb)/g;
/** 屏幕尺寸；单独出现不足以判"规格完整"，需与容量同时出现 */
const SCREEN_SIZE = /\d+(?:\.\d+)?\s*(?:英寸|寸)/;
const SERIES_SUFFIXES = ['pro', 'max', 'ultra', 'plus'];

/** 规格完整：内存+存储组合 / 两个容量 / 容量 + 屏幕尺寸 */
function isSpecComplete(compactLowerTitle) {
  const caps = compactLowerTitle.match(CAPACITY);
  return MEMORY_STORAGE_COMBO.test(compactLowerTitle) ||
    (caps && caps.length >= 2) ||
    (SCREEN_SIZE.test(compactLowerTitle) && !!caps);
}

/**
 * 单条打分。medianPrice 为**同一批候选池**的中位价（价格带锚点），传 0 表示不计算价格带偏离。
 * @param {string} title
 * @param {number} price
 * @param {number} medianPrice
 * @param {string} keyword
 * @param {boolean} [explicitSubsidy] 结构化国补标记（如识货 labels=PUBLIC_SUBSIDIES）
 * @returns {number}
 */
function score(title, price, medianPrice, keyword, explicitSubsidy = false) {
  const lower = String(title || '').toLowerCase();
  const normalizedTitle = normalize(title);
  const normalizedQuery = normalize(keyword);
  const compact = lower.replace(/\s+/g, '');

  let total = 0;
  if (CATEGORY_WORDS.some((w) => normalizedTitle.includes(w))) total += WEIGHT_CATEGORY;
  if (SECONDHAND.test(lower)) total += WEIGHT_SECONDHAND;
  if (MULTI_MODEL.test(lower)) total += WEIGHT_MULTI_MODEL;
  if (isSpecComplete(compact)) total += WEIGHT_SPEC;
  if (explicitSubsidy || SUBSIDY.test(lower)) total += WEIGHT_SUBSIDY;
  if (medianPrice > 0 && price > 0) {
    total += WEIGHT_PRICE_BAND * Math.abs(Math.log(Math.max(price, 1) / Math.max(medianPrice, 1)));
  }
  if (SERIES_SUFFIXES.some((suf) => !normalizedQuery.includes(suf) && normalizedTitle.includes(suf))) {
    total += WEIGHT_SERIES_SUFFIX;
  }
  const brand = brandOf(normalizedQuery);
  if (brand && !hasAnyAlias(brand, normalizedTitle)) total += WEIGHT_BRAND_MISSING;
  return total;
}

/** 中位价锚点：排序后取 n/2 下标（偶数条取靠上的那个，与 Android / 调参回放口径一致） */
function medianPrice(prices) {
  const list = prices.filter((p) => p > 0).sort((a, b) => a - b);
  if (list.length === 0) return 0;
  return list[Math.floor(list.length / 2)];
}

/**
 * 候选池打分并降序排序（Array.prototype.sort 在 V8 里是稳定排序：
 * 同分保持入参顺序，等价 Android 端 sortedByDescending 的稳定语义）。
 * @param {string} keyword
 * @param {Array<{title:string, price:number, explicitSubsidy?:boolean}>} items
 * @returns {Array<{item:object, score:number}>}
 */
function rank(keyword, items) {
  if (!items || items.length === 0) return [];
  const med = medianPrice(items.map((it) => Number(it.price) || 0));
  return items
    .map((item, index) => ({
      item,
      score: score(item.title, Number(item.price) || 0, med, keyword, !!item.explicitSubsidy),
      index,
    }))
    .sort((a, b) => (b.score - a.score) || (a.index - b.index));
}

/** argmax（池子为空返回 null） */
function best(keyword, items) {
  const ranked = rank(keyword, items);
  return ranked.length ? ranked[0].item : null;
}

module.exports = {
  score,
  rank,
  best,
  medianPrice,
  isSpecComplete,
  CATEGORY_WORDS,
  WEIGHT_CATEGORY,
  WEIGHT_SPEC,
  WEIGHT_SUBSIDY,
  WEIGHT_SECONDHAND,
  WEIGHT_MULTI_MODEL,
  WEIGHT_PRICE_BAND,
  WEIGHT_SERIES_SUFFIX,
  WEIGHT_BRAND_MISSING,
};
