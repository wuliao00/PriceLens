/**
 * 搜索结果相关性过滤（2026-09 新增，与 Android 端 util/QueryRelevance.kt 同规则）
 * -----------------------------------------------------------------------------
 * 上游站点（什么值得买 / 当当 / 识货）的关键词搜索会把无关条目混进结果：通用配件（壳/膜/线）、
 * 图书（"iPhone 15 User Guide"）、其它品牌、热榜商品。桌面端旧实现直接取第一条带价条目
 * 当商品候选，会得到与关键词无关的"商品"。
 *
 * 规则（保守优先）：
 *  1. 归一化：大小写、全角字母数字拉平，空白标点丢弃；
 *  2. 拉丁段**按序**覆盖（2026-09-28 重写）：关键词按非"字母数字汉字"切段，每段都必须在
 *     标题中出现，保持原顺序，且相邻段之间最多允许 MAX_SEGMENT_GAP 个字符的间隔；
 *  3. 配件反向排除：关键词没提配件时，标题含配件词的条目剔除（含"适用 xxx"前缀）；
 *  4. 图书反向排除：关键词不是书时，说明书/导购书条目剔除（guide/manual/指南…）；
 *  5. 品牌反向排除：关键词命中品牌时，标题出现其它品牌且不含本品牌写法的剔除；
 *  6. 纯中文关键词且未命中品牌时：至少有一段长度 ≥2 的中文词出现在标题中。
 *
 * 规则 2 为什么重写（真实 bug，与 Android 同步）：旧实现先 normalize 删空白再切 token，
 * "oppo x8s" 归一化成 "oppox8s" → 一个必须整体连续出现的巨型 token；标题写的是
 * "OPPO Find X8s"（归一化 "oppofindx8s"）→ 判不相关 → 无候选 → 历史价 URL 为空。
 * 分段后 ["oppo","x8s"] 命中（间隔 "find" = 4 ≤ 12），而顺序颠倒的巧合串
 * （实测样例 "EG80MATE33S" 含 mate + 80）仍被挡住；Mate80 / Mate 80 / MATE 80 互认。
 *
 * 用例与 Android `QueryRelevanceTest` 同名同内容，见 `desktop/_unit_check.js`。
 */
'use strict';

const ACCESSORY_WORDS = [
  '手机壳', '保护壳', '保护套', '手机套', '壳', '钢化膜', '镜头膜', '贴膜', '保护膜', '软膜', '膜',
  '数据线', '充电线', '充电器', '转接头', '转换头', '支架', '挂绳', '挂饰', '贴纸', '表带',
  '收纳', '防尘塞', '卡托', '替换带', '笔尖', '防摔',
  // 2026-09-28 放宽规则 2 后实况里唯一新增的漏网条目是"骨传导耳机"，同批混入项一并补齐
  '耳机', '充电宝', '移动电源', '电池', '保护贴', '手机膜',
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

/** 相邻两段之间允许的最大间隔（归一化后字符数）；与 Android MAX_SEGMENT_GAP 一致 */
const MAX_SEGMENT_GAP = 12;

/** 切段分隔符：非"字母 / 数字 / 汉字"（空白、标点、全角符号都算分隔） */
const WORD_SPLIT = /[^0-9A-Za-z\u4e00-\u9fff]+/;

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

/**
 * 关键词的拉丁段（规则 2 的匹配单位）：**先按分隔符切原串，再逐段归一化取字母数字**。
 * 顺序反过来（旧实现）会让 "oppo x8s" 变成单段 "oppox8s"，要求标题出现连续字面。
 * "oppo x8s" → ['oppo','x8s']；"Mate80" → ['mate80']；"华为 Mate 80" → ['mate','80']。
 */
function querySegments(keyword) {
  const segs = [];
  for (const part of String(keyword || '').split(WORD_SPLIT)) {
    const found = normalize(part).match(/[a-z0-9]+/g);
    if (found) segs.push(...found);
  }
  return segs;
}

/**
 * 顺序 + 间隔约束的段匹配：每段尝试其在标题里的**所有**出现位置（回溯），
 * 避免"先出现一次的干扰子串"把后面正确的段卡死。
 */
function matchesSegmentsInOrder(segments, text, maxGap, index, prevEnd) {
  if (index === segments.length) return true;
  const segment = segments[index];
  let pos = text.indexOf(segment);
  while (pos >= 0) {
    const gapOk = prevEnd < 0 || (pos - prevEnd >= 0 && pos - prevEnd <= maxGap);
    if (gapOk && matchesSegmentsInOrder(segments, text, maxGap, index + 1, pos + segment.length)) return true;
    pos = text.indexOf(segment, pos + 1);
  }
  return false;
}

/** 标题是否按序包含关键词的每一段（间隔 ≤ maxGap）；纯中文关键词返回 true（交给规则 6） */
function matchesSegments(keyword, title, maxGap = MAX_SEGMENT_GAP) {
  const segments = querySegments(keyword);
  if (segments.length === 0) return true;
  return matchesSegmentsInOrder(segments, normalize(title), maxGap, 0, -1);
}

/** 关键词命中的品牌组（未命中返回 null）；候选打分用它判断"标题缺本品牌写法" */
function brandOf(normalizedQuery) {
  return BRANDS.find((brand) => brand.some((alias) => normalizedQuery.includes(alias))) || null;
}

/** 标题（归一化）里是否出现了该品牌组的任一写法 */
function hasAnyAlias(aliases, normalizedText) {
  return aliases.some((alias) => normalizedText.includes(alias));
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

  // 规则 5：品牌一致性
  const ownBrand = brandOf(query);
  if (ownBrand) {
    const titleHasOwnBrand = hasAnyAlias(ownBrand, text);
    if (!titleHasOwnBrand) {
      const titleHasOtherBrand = BRANDS.some((brand) => brand !== ownBrand && hasAnyAlias(brand, text));
      if (titleHasOtherBrand) return false;
    }
  }

  // 规则 2：拉丁段按序覆盖（空段 = 纯中文关键词，走规则 6）
  const segments = querySegments(keyword);
  if (segments.length) return matchesSegmentsInOrder(segments, text, MAX_SEGMENT_GAP, 0, -1);

  // 规则 6：纯中文关键词（品牌命中时品牌规则已足以判定）
  if (ownBrand) return true;
  const cjkRuns = query.match(/[\u4e00-\u9fff]{2,}/g) || [];
  return cjkRuns.some((run) => text.includes(run));
}

module.exports = {
  isRelevant,
  normalize,
  querySegments,
  matchesSegments,
  brandOf,
  hasAnyAlias,
  ACCESSORY_WORDS,
  BOOK_WORDS,
  BRANDS,
  MAX_SEGMENT_GAP,
};
