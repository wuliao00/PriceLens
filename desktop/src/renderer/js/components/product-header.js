/**
 * 商品头组件（规范 §5 布局）：商品图 120×120 + 标题两行 + 当前价/划线价 +
 * 价格判断标签 + 渠道与更新时间。
 * 同时导出规范 §6.2 的 judgePrice 价格判断逻辑。
 */
import { el, icon, animateNumber } from '../utils/dom.js';
import { formatPrice, timeAgo } from '../utils/format.js';

/**
 * 价格判断（规范 §6.2）。
 * @param {number} current 当前价
 * @param {Array<{price:number}>} history 历史序列
 * @returns {{level:'low'|'suspicious'|'normal', label:string, color:string}}
 */
export function judgePrice(current, history) {
  const prices = history.map((h) => Number(h.price)).filter((p) => p > 0);
  if (!prices.length || !(current > 0)) {
    return { level: 'normal', label: '常规价格', color: 'var(--text-secondary)' };
  }
  const lowest = Math.min(...prices);
  const last7 = prices.slice(-7);
  const avg7d = last7.reduce((s, p) => s + p, 0) / last7.length;

  if (current <= lowest * 1.05) {
    return { level: 'low', label: '≈ 历史低价', color: 'var(--success)' };
  }
  if (current >= avg7d * 1.10) {
    return { level: 'suspicious', label: '⚠ 疑似先涨后降', color: 'var(--danger)' };
  }
  return { level: 'normal', label: '常规价格', color: 'var(--text-secondary)' };
}

/**
 * 渲染商品头卡片。
 * @param {HTMLElement} container
 * @param {{product: object, history?: {current:number, points:Array, fetchedAt?:number} | null,
 *          onOpenUrl?: (url:string)=>void}} ctx
 */
export function renderProductHeader(container, { product, history, onOpenUrl }) {
  const current = history?.current || product.price || 0;
  const original = product.originalPrice > current ? product.originalPrice : 0;
  const judge = judgePrice(current, history?.points || []);
  const updated = history?.fetchedAt ? `更新于 ${timeAgo(history.fetchedAt)}` : '';

  const priceRow = el('div', { class: 'product-price-row' });
  const priceEl = el('span', { class: 'price-current' });
  if (current > 0) {
    animateNumber(priceEl, current, { format: (n) => formatPrice(Math.round(n)) }); // countUp 600ms
  } else {
    priceEl.textContent = '--';
  }
  priceRow.appendChild(priceEl);
  if (original > 0) priceRow.appendChild(el('span', { class: 'price-original', text: formatPrice(original) }));
  if (judge.level !== 'normal') {
    priceRow.appendChild(el('span', {
      class: `tag ${judge.level === 'low' ? 'tag--success' : 'tag--danger'}`,
      text: judge.label,
      style: { alignSelf: 'center' },
    }));
  }

  const header = el('div', { class: 'card product-header' },
    el('img', {
      class: 'product-img',
      src: product.image || '../assets/placeholder.svg',
      alt: '',
      on: { error: (e) => { e.target.src = '../assets/placeholder.svg'; } },
    }),
    el('div', { class: 'product-info' },
      el('h2', { class: 'product-title clamp-2', text: product.title || '未识别到商品' }),
      priceRow,
      el('div', { class: 'product-meta' },
        product.mall ? el('span', { class: 'tag tag--accent', text: product.mall }) : null,
        updated ? el('span', { text: updated }) : null,
      ),
    ),
    el('div', { class: 'product-actions' },
      product.url ? el('button', {
        class: 'btn btn--primary',
        on: { click: () => onOpenUrl && onOpenUrl(product.url) },
      }, icon('external', 14), '去购买') : null,
    ),
  );

  container.appendChild(header);
}
