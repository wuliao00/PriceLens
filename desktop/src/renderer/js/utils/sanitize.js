/**
 * 消毒工具（渲染层，规范 §3.3）
 * 所有外部数据渲染前必须经过此处；配合 dom.el() 的 textContent 使用。
 */

/**
 * HTML 转义：把外部文本变为可安全插入的字符串。
 * 注意：本项目渲染一律使用 textContent，本函数仅在极少数拼模板场景使用。
 * @param {string} str
 * @returns {string}
 */
export function escapeHTML(str) {
  const div = document.createElement('div');
  div.appendChild(document.createTextNode(String(str ?? '')));
  return div.innerHTML;
}

/**
 * 校验外链：仅放行 http/https，其余返回 null（防 javascript: 注入）。
 * @param {string} url
 * @returns {string|null}
 */
export function safeUrl(url) {
  try {
    const parsed = new URL(String(url || '').trim());
    if (parsed.protocol === 'http:' || parsed.protocol === 'https:') return parsed.href;
    return null;
  } catch {
    return null;
  }
}

/**
 * 安全打开外部链接（经主进程 shell.openExternal）。
 * @param {string} url
 */
export async function openExternalSafe(url) {
  const safe = safeUrl(url);
  if (!safe) return false;
  const res = await window.priceLens.openExternal(safe);
  return Boolean(res && res.ok);
}
