/**
 * 搜索栏组件（规范 §10.3）
 *   - 支持关键词 / 京东链接 / 淘宝链接 / B站视频链接
 *   - 搜索历史最近 10 条（localStorage），下拉展示
 *   - 输入防抖 300ms；Esc 关闭下拉
 */
import { el, icon } from '../utils/dom.js';

const HISTORY_KEY = 'pricelens:history';
const HISTORY_MAX = 10;
const DEBOUNCE_MS = 300;

/**
 * 初始化搜索栏。
 * @param {{onSearch: (q: string) => void}} handlers
 * @returns {{focus: () => void, setValue: (v: string) => void}}
 */
export function initSearchBar({ onSearch }) {
  const input = document.getElementById('search-input');
  const dropdown = document.getElementById('search-dropdown');
  let debounceTimer = null;
  let history = loadHistory();

  /** 提交搜索：记录历史 → 回调 → 收起下拉 */
  function commit(value) {
    const q = String(value || '').trim();
    if (!q) return;
    history = [q, ...history.filter((h) => h !== q)].slice(0, HISTORY_MAX);
    localStorage.setItem(HISTORY_KEY, JSON.stringify(history));
    input.value = q;
    hideDropdown();
    onSearch(q);
  }

  /** 渲染下拉（过滤后的历史） */
  function showDropdown(filter) {
    const q = String(filter || '').trim().toLowerCase();
    const items = q ? history.filter((h) => h.toLowerCase().includes(q)) : history;
    dropdown.textContent = '';

    if (items.length === 0) {
      dropdown.appendChild(el('div', { class: 'dd-empty', text: q ? '无匹配历史' : '暂无搜索历史' }));
    } else {
      const header = el('div', { class: 'dd-header' },
        el('span', { text: '最近搜索' }),
        el('span', {
          class: 'dd-clear', text: '清空',
          style: { cursor: 'pointer', color: 'var(--accent)' },
          on: {
            click: (e) => {
              e.stopPropagation();
              history = [];
              localStorage.removeItem(HISTORY_KEY);
              hideDropdown();
            },
          },
        }));
      dropdown.appendChild(header);
      for (const item of items) {
        dropdown.appendChild(el('div', {
          class: 'dd-item',
          on: { click: () => commit(item) },
        }, icon('clock', 14), el('span', { class: 'clamp-1', text: item })));
      }
    }
    dropdown.hidden = false;
  }

  function hideDropdown() {
    dropdown.hidden = true;
  }

  // 输入防抖 300ms → 展示历史过滤
  input.addEventListener('input', () => {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
      if (input.value.trim()) showDropdown(input.value);
      else hideDropdown();
    }, DEBOUNCE_MS);
  });

  input.addEventListener('focus', () => showDropdown(input.value));

  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      commit(input.value);
    } else if (e.key === 'Escape') {
      if (!dropdown.hidden) {
        hideDropdown();
      } else if (input.value) {
        input.value = ''; // Esc 清空搜索
      }
      input.blur();
    }
  });

  // 点击下拉外部收起
  document.addEventListener('mousedown', (e) => {
    const bar = document.getElementById('search-bar');
    if (dropdown && !bar.contains(e.target)) hideDropdown();
  });

  return {
    focus: () => input.focus(),
    setValue: (v) => { input.value = v; },
  };
}

/** 读取本地搜索历史 */
function loadHistory() {
  try {
    const arr = JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]');
    return Array.isArray(arr) ? arr.filter((x) => typeof x === 'string') : [];
  } catch {
    return [];
  }
}
