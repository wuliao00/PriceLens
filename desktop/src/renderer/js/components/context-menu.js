/**
 * 右键菜单组件（规范 §10.2）
 * 从点击位置展开（200ms scale），自动钳制不超出屏幕；
 * 点击外部 / Esc / 滚动 / 窗口失焦时关闭。
 * 显式传参（clientX/clientY），禁止隐式 event 全局变量（红线 #5）。
 */
import { el, icon } from '../utils/dom.js';

let menuEl = null;

/**
 * 显示右键菜单。
 * @param {number} x 点击位置 clientX
 * @param {number} y 点击位置 clientY
 * @param {Array<{label:string, icon?:string, danger?:boolean, action?:()=>void} | 'separator'>} items
 */
export function showContextMenu(x, y, items) {
  hideContextMenu();
  const root = document.getElementById('context-menu-root');

  menuEl = el('div', { class: 'context-menu' });
  for (const item of items) {
    if (item === 'separator') {
      menuEl.appendChild(el('div', { class: 'cm-sep' }));
      continue;
    }
    const menuItem = el('div', {
      class: `cm-item${item.danger ? ' cm-item--danger' : ''}`,
      on: {
        click: () => {
          hideContextMenu();
          if (typeof item.action === 'function') item.action();
        },
      },
    });
    if (item.icon) menuItem.appendChild(icon(item.icon, 14));
    menuItem.appendChild(el('span', { text: item.label }));
    menuEl.appendChild(menuItem);
  }
  root.appendChild(menuEl);

  // 钳制：超出屏幕时向内偏移
  const rect = menuEl.getBoundingClientRect();
  const left = Math.min(x, window.innerWidth - rect.width - 8);
  const top = Math.min(y, window.innerHeight - rect.height - 8);
  menuEl.style.left = `${Math.max(8, left)}px`;
  menuEl.style.top = `${Math.max(8, top)}px`;

  // 延迟挂监听，避免本次 contextmenu/click 事件立即关闭菜单
  setTimeout(() => {
    document.addEventListener('mousedown', onDocMouseDown, true);
    document.addEventListener('keydown', onDocKeyDown, true);
    window.addEventListener('resize', hideContextMenu);
    window.addEventListener('blur', hideContextMenu);
  }, 0);
}

function onDocMouseDown(e) {
  if (menuEl && !menuEl.contains(e.target)) hideContextMenu();
}

function onDocKeyDown(e) {
  if (e.key === 'Escape') hideContextMenu();
}

/** 关闭右键菜单 */
export function hideContextMenu() {
  if (menuEl) {
    menuEl.remove();
    menuEl = null;
  }
  document.removeEventListener('mousedown', onDocMouseDown, true);
  document.removeEventListener('keydown', onDocKeyDown, true);
  window.removeEventListener('resize', hideContextMenu);
  window.removeEventListener('blur', hideContextMenu);
}
