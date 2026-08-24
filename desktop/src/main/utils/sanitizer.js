/**
 * PriceLens 消毒工具（主进程侧）
 * ------------------------------
 * 供爬虫使用：剥离 HTML 标签、压缩空白、hash 文件名。
 * （渲染进程侧的 escapeHTML 在 src/renderer/js/utils/sanitize.js）
 */
'use strict';

const crypto = require('node:crypto');

/**
 * 剥离全部 HTML 标签并压缩空白（用于 <em class="keyword"> 之类的富文本标题）。
 * @param {string} html
 * @returns {string}
 */
function stripTags(html) {
  return String(html ?? '')
    .replace(/<[^>]*>/g, '')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * 生成缓存文件名安全的 SHA-1 十六进制摘要。
 * @param {string} key
 * @returns {string} 40 位 hex
 */
function sha1Hex(key) {
  return crypto.createHash('sha1').update(String(key), 'utf-8').digest('hex');
}

module.exports = { stripTags, sha1Hex };
