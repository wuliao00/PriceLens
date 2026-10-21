/**
 * 内容风险判定（移植自 Android util/ContentRisk.kt，与 v2.4.4 规则一致）
 * ---------------------------------------------------------------------
 * 参考「诚实豆沙包」恰饭鉴定思路的离线规则版：
 *  - 商单痕迹：标题/标签命中商单词库，或 B 站「联合投稿」标记
 *  - 夸大宣传：命中绝对化/煽动性话术词库
 *
 * 原则：只标记、沉底展示，不删除 —— 判断权留给用户，避免误杀真实评测。
 * 词库顺序即命中优先级，返回列表里第一个命中的词（与 Android 同语义）。
 */
'use strict';

/** 商单痕迹词库：恰饭/商单自曝 + 推广性质表述 */
const SPONSOR_WORDS = [
  '商单', '恰饭', '广告', '推广', '赞助', '含广',
  '商务合作', '品牌合作', '金主', '投放', '特邀体验',
];

/** 夸大宣传词库：绝对化表述与煽动性种草话术 */
const HYPE_WORDS = [
  '史上最强', '天花板', '封神', '碾压', '吊打', '断货',
  '性价比之王', '闭眼入', '无脑冲', '必买', 'yyds', '无敌',
  '王炸', '吹爆', '全网最低', '颠覆', '永远的神',
];

/**
 * 否定语境豁免：`不恰饭` / `无广告` / `不是商单` / `并非天花板` 是 UP 主自证独立，
 * 不能按「命中商单词」标记。命中词前 3 个字符内出现否定词即跳过该次命中。
 * 这是桌面端相对 Android v2.4.4 的改进（Android 仍按裸子串匹配，待同步）。
 */
const NEGATION_TAIL = /(?:不|没|无|非|未|零|拒绝|杜绝|不含|没有|不是|并非|绝非|从不|绝不)$/;

/**
 * 按词库顺序找第一个**未被否定**的命中词（大小写不敏感）。
 * @param {string} text
 * @param {string[]} words
 * @returns {string|null}
 */
function firstHit(text, words) {
  const haystack = String(text || '').toLowerCase();
  for (const word of words) {
    const needle = word.toLowerCase();
    let index = haystack.indexOf(needle);
    while (index >= 0) {
      const before = haystack.slice(Math.max(0, index - 3), index);
      if (!NEGATION_TAIL.test(before)) return word;
      index = haystack.indexOf(needle, index + needle.length);
    }
  }
  return null;
}

/**
 * @param {string} text 标题 + 标签拼接文本
 * @param {boolean} [isUnionVideo] B 站联合投稿标记（商单 campaign 的常见环境证据）
 * @returns {{sponsored:boolean, hype:boolean, sponsorWord:string|null,
 *   hypeWord:string|null, flagged:boolean}}
 */
function assess(text, isUnionVideo = false) {
  const sponsorWord = firstHit(text, SPONSOR_WORDS);
  const hypeWord = firstHit(text, HYPE_WORDS);
  const union = Boolean(isUnionVideo);
  return {
    sponsored: sponsorWord !== null || union,
    hype: hypeWord !== null,
    sponsorWord: sponsorWord || (union ? '联合投稿' : null),
    hypeWord,
    flagged: sponsorWord !== null || hypeWord !== null || union,
  };
}

module.exports = { assess, firstHit, SPONSOR_WORDS, HYPE_WORDS };
