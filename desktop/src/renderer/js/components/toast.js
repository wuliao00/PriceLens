/**
 * Toast 组件（规范 §6.3）—— 单例复用，300ms --ease-apple 入场，2s 自动消失。
 */
import { el } from '../utils/dom.js';

let toastEl = null;
let hideTimer = null;

/**
 * 显示 Toast。
 * @param {string} message 提示文本（如 "已复制"）
 * @param {number} [duration=2000] 自动消失毫秒数
 */
export function showToast(message) {
  if (!toastEl) {
    toastEl = el('div', { class: 'toast' });
    document.getElementById('toast-root').appendChild(toastEl);
  }
  toastEl.textContent = message; // 外部数据 → textContent
  toastEl.classList.add('show');

  clearTimeout(hideTimer);
  hideTimer = setTimeout(() => toastEl.classList.remove('show'), 2000);
}
