/**
 * 格式化工具：价格 / 播放量 / 相对时间 / 时长 / 缓存大小 / 输入类型识别。
 */

/**
 * 格式化价格：8999 → "¥8,999"；无价格返回 "--"。
 * @param {number|null|undefined} price
 * @returns {string}
 */
export function formatPrice(price) {
  if (!(price > 0)) return '--';
  return `¥${Number(price).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`;
}

/** 纯数字价格（无货币符），用于复制 */
export function formatPricePlain(price) {
  if (!(price > 0)) return '';
  return Number(price).toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

/**
 * 播放量 / 点赞数：12345 → "1.2万"。
 * @param {number} n
 */
export function formatCount(n) {
  const v = Number(n) || 0;
  if (v >= 100000000) return `${(v / 100000000).toFixed(1)}亿`;
  if (v >= 10000) return `${(v / 10000).toFixed(1)}万`;
  return String(v);
}

/**
 * 相对时间：时间戳 → "2 分钟前"。
 * @param {number} ts 毫秒时间戳（0 视为未知）
 */
export function timeAgo(ts) {
  if (!ts) return '';
  const diff = Date.now() - ts;
  if (diff < 0) return '';
  const min = Math.floor(diff / 60000);
  if (min < 1) return '刚刚';
  if (min < 60) return `${min} 分钟前`;
  const hour = Math.floor(min / 60);
  if (hour < 24) return `${hour} 小时前`;
  const day = Math.floor(hour / 24);
  if (day < 30) return `${day} 天前`;
  const d = new Date(ts);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/**
 * "mm:ss" → 秒（解析失败返回 0）。
 * @param {string} dur
 */
export function durationToSec(dur) {
  const m = String(dur || '').match(/^(\d+):(\d{1,2})$/);
  if (!m) return 0;
  return Number(m[1]) * 60 + Number(m[2]);
}

/** 字节 → "12.3 MB" */
export function formatBytes(bytes) {
  const mb = (Number(bytes) || 0) / (1024 * 1024);
  if (mb < 1) return `${Math.max(0, Math.round(mb * 1024))} KB`;
  return `${mb.toFixed(1)} MB`;
}

/**
 * 识别用户输入类型（渲染层本地判定，用于即时路由跳转）。
 * @param {string} text
 * @returns {{type:'jd'|'taobao'|'bilibili'|'keyword', keyword:string}}
 */
export function detectInputType(text) {
  const s = String(text || '').trim();
  if (/item(?:\.m)?\.jd\.com|jd\.com\/product|\bwareId=/.test(s)) return { type: 'jd', keyword: s };
  if (/(?:detail\.tmall|item\.taobao)\.com/.test(s)) return { type: 'taobao', keyword: s };
  if (/bilibili\.com\/video\/BV|b23\.tv|^BV[0-9A-Za-z]{10}$/.test(s)) {
    return { type: 'bilibili', keyword: s };
  }
  return { type: 'keyword', keyword: s };
}

/**
 * 从任意文本提取 BV 号。
 * @param {string} text
 */
export function extractBvid(text) {
  const m = String(text || '').match(/(BV[0-9A-Za-z]{10})/);
  return m ? m[1] : '';
}
