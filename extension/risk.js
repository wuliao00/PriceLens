/**
 * 内容风险规则（浏览器扩展 service worker / Node 共用）
 * -----------------------------------------------------
 * 与桌面端 utils/content-risk.js 同一词库与否定语境豁免；
 * 只标记不删除，判断权留给用户。
 */
(function (root) {
  'use strict';

  const SPONSOR_WORDS = [
    '商单', '恰饭', '广告', '推广', '赞助', '含广',
    '商务合作', '品牌合作', '金主', '投放', '特邀体验',
  ];
  const HYPE_WORDS = [
    '史上最强', '天花板', '封神', '碾压', '吊打', '断货',
    '性价比之王', '闭眼入', '无脑冲', '必买', 'yyds', '无敌',
    '王炸', '吹爆', '全网最低', '颠覆', '永远的神',
  ];
  const NEGATION_TAIL = /(?:不|没|无|非|未|零|拒绝|杜绝|不含|没有|不是|并非|绝非|从不|绝不)$/;

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

  function assessContentRisk(text, isUnionVideo) {
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

  root.assessContentRisk = assessContentRisk;
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { assessContentRisk, SPONSOR_WORDS, HYPE_WORDS };
  }
})(typeof self !== 'undefined' ? self : this);
