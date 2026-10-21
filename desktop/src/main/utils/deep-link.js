/**
 * pricelens:// 深链解析（纯函数）
 * ------------------------------
 * 浏览器扩展用 `pricelens://product?url=<encoded http(s) URL>` 打开桌面端。
 * 只接受 http/https 商品链接，拒绝 file:/javascript: 等本地协议。
 */
'use strict';

const PROTOCOL = 'pricelens';

/** 从进程参数里找 pricelens:// 深链（Windows/Linux 协议启动） */
function findDeepLink(argv) {
  return (argv || []).find((arg) => /^pricelens:\/\//i.test(String(arg))) || null;
}

/**
 * @param {string} raw
 * @returns {string|null} 规范化后的 http(s) URL
 */
function parseDeepLink(raw) {
  try {
    const link = new URL(String(raw));
    if (link.protocol !== `${PROTOCOL}:`) return null;
    const target = link.searchParams.get('url') || link.searchParams.get('q');
    if (!target || target.length > 2000) return null;
    const parsed = new URL(target);
    return (parsed.protocol === 'http:' || parsed.protocol === 'https:') ? parsed.href : null;
  } catch {
    return null;
  }
}

module.exports = { PROTOCOL, findDeepLink, parseDeepLink };
