/**
 * 搜索结果相关性过滤（2026-09 新增，与 Android 端 util/QueryRelevance.kt 同规则）
 * -----------------------------------------------------------------------------
 * 上游站点（什么值得买 / 识货）的关键词搜索会把无关条目混进结果：通用配件（壳/膜/线）、
 * 图书（"iPhone 15 User Guide"）、其它品牌、热榜商品。桌面端旧实现直接取第一条带价条目
 * 当商品候选，会得到与关键词无关的"商品"。
 *
 * 规则（保守优先）：
 *  1. 归一化：大小写、全角字母数字、空白标点拉平后做子串判断；
 *  2. 拉丁 token 全覆盖：关键词里每段字母/数字都必须出现在标题中；
 *  3. 配件反向排除：关键词没提配件时，标题含配件词的条目剔除（含"适用 xxx"前缀）；
 *  4. 图书反向排除：关键词不是书时，说明书/导购书条目剔除（guide/manual/指南…）；
 *  5. 品牌反向排除：关键词命中品牌时，标题出现其它品牌且不含本品牌写法的剔除；
 *  6. 纯中文关键词且未命中品牌时：至少有一段长度 ≥2 的中文词出现在标题中。
 */
'use strict';

const ACCESSORY_WORDS = [
  '手机壳', '保护壳', '保护套', '手机套', '壳', '钢化膜', '镜头膜', '贴膜', '保护膜', '软膜', '膜',
  '数据线', '充电线', '充电器', '转接头', '转换头', '支架', '挂绳', '挂饰', '贴纸', '表带',
  '收纳', '防尘塞', '卡托', '替换带', '笔尖', '防摔',
];
const ACCESSORY_PREFIX = /^[\s【\[（(]*适用/;
const BOOK_WORDS = ['指南', '手册', '教程', '攻略', '宝典', '说明书'];
const BOOK_WORD_ASCII = /(?:^|[^a-z])(guide|guides|guidebook|manual|handbook|mastery|unboxed|essentials)(?:[^a-z]|$)/;
const BRANDS = [
  ['iphone', 'apple', '苹果', 'ipad', 'macbook', 'airpods'],
  ['小米', '红米', 'redmi', 'xiaomi'],
  ['华为', 'huawei'],
  ['荣耀', 'honor'],
  ['三星', 'samsung'],
  ['oppo'],
  ['vivo'],
  ['一加', 'oneplus'],
  ['魅族', 'meizu'],
  ['索尼', 'sony'],
  ['任天堂', 'nintendo', 'switch'],
  ['大疆', 'dji'],
  ['戴森', 'dyson'],
  ['美的', 'midea'],
  ['格力'],
  ['海尔', 'haier'],
  ['罗技', 'logitech'],
  ['漫步者', 'edifier'],
  ['安克', 'anker'],
];

function normalize(raw) {
  let out = '';
  for (const ch of String(raw || '')) {
    const code = ch.codePointAt(0);
    let c = ch;
    if (code >= 0xFF01 && code <= 0xFF5E) c = String.fromCodePoint(code - 0xFEE0); // 全角 → 半角
    if (/[a-zA-Z0-9]/.test(c) || /[\u4e00-\u9fff]/.test(c)) out += c.toLowerCase();
  }
  return out;
}

function hasWord(normalized, words) {
  return words.some((w) => normalized.includes(w));
}

/** 条目标题是否与关键词相关（关键词或标题为空 → 不相关） */
function isRelevant(keyword, title) {
  const query = normalize(keyword);
  const text = normalize(title);
  if (!query || !text) return false;

  // 规则 3：配件（"适用 xxx" 前缀同属强配件信号；关键词本身在找配件时整条规则不启用）
  const queryHasAccessory = hasWord(query, ACCESSORY_WORDS);
  if (!queryHasAccessory) {
    if (hasWord(text, ACCESSORY_WORDS)) return false;
    if (ACCESSORY_PREFIX.test(String(title).trim())) return false;
  }

  // 规则 4：图书/说明书（英文词用原串 + 词边界）
  const queryIsBookish = hasWord(query, BOOK_WORDS) || BOOK_WORD_ASCII.test(String(keyword).toLowerCase());
  if (!queryIsBookish && (hasWord(text, BOOK_WORDS) || BOOK_WORD_ASCII.test(String(title).toLowerCase()))) {
    return false;
  }

  // 规则 2：拉丁 token 全覆盖
  const tokens = query.match(/[a-z0-9]+/g) || [];

  // 规则 5：品牌一致性
  const ownBrand = BRANDS.find((brand) => brand.some((alias) => query.includes(alias)));
  if (ownBrand) {
    const titleHasOwnBrand = ownBrand.some((alias) => text.includes(alias));
    if (!titleHasOwnBrand) {
      const titleHasOtherBrand = BRANDS.some(
        (brand) => brand !== ownBrand && brand.some((alias) => text.includes(alias)),
      );
      if (titleHasOtherBrand) return false;
    }
  }

  if (tokens.length) return tokens.every((t) => text.includes(t));

  // 规则 6：纯中文关键词（品牌命中时品牌规则已足以判定）
  if (ownBrand) return true;
  const cjkRuns = query.match(/[\u4e00-\u9fff]{2,}/g) || [];
  return cjkRuns.some((run) => text.includes(run));
}

module.exports = { isRelevant, normalize, ACCESSORY_WORDS, BOOK_WORDS, BRANDS };
