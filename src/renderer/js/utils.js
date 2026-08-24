/**
 * 工具函数
 */
const $ = (sel, parent = document) => parent.querySelector(sel);
const $$ = (sel, parent = document) => [...parent.querySelectorAll(sel)];

/**
 * 格式化价格
 */
function formatPrice(n) {
  if (n == null || isNaN(n)) return '--';
  return '¥' + Number(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/**
 * 格式化数字（播放量等）
 */
function formatNum(n) {
  if (!n) return '0';
  if (n >= 10000) return (n / 10000).toFixed(1) + '万';
  return String(n);
}

/**
 * 相对时间
 */
function timeAgo(ts) {
  if (!ts) return '';
  const diff = Date.now() - new Date(ts).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return '刚刚';
  if (mins < 60) return `${mins}分钟前`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}小时前`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}天前`;
  return new Date(ts).toLocaleDateString('zh-CN');
}

/**
 * 高亮关键词
 */
function highlightKeywords(text) {
  const keywords = ['翻车', '神价', '史低', '品控', '退货', '翻新', '不值', '后悔', '缺点', '避坑', '避雷'];
  let result = text;
  for (const kw of keywords) {
    const regex = new RegExp(`(${kw})`, 'g');
    result = result.replace(regex, '<span class="kw-warn">$1</span>');
  }
  return result;
}

/**
 * Toast
 */
function showToast(msg) {
  const el = document.createElement('div');
  el.className = 'toast';
  el.textContent = msg;
  $('#toastContainer').appendChild(el);
  setTimeout(() => el.remove(), 2300);
}

/**
 * 转义 HTML（防 XSS）
 */
function escHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}