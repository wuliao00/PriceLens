/**
 * 骨架屏组件（规范 §9：shimmer 循环，禁止 spinner）。
 * 每个视图对应一组占位块。
 */
import { el } from '../utils/dom.js';

/**
 * 生成视图骨架。
 * @param {'overview'|'price'|'bilibili'|'coupons'|'community'} view
 * @returns {HTMLElement}
 */
export function renderSkeleton(view) {
  const wrap = el('div', { class: 'view' });

  switch (view) {
    case 'overview':
    case 'price':
      wrap.appendChild(
        el('div', { class: 'sk-header' },
          el('div', { class: 'skeleton', style: { width: '120px', height: '120px', borderRadius: '10px' } }),
          el('div', { style: { flex: '1', display: 'flex', flexDirection: 'column', gap: '10px' } },
            el('div', { class: 'skeleton sk-line', style: { width: '70%' } }),
            el('div', { class: 'skeleton sk-line', style: { width: '40%', height: '28px' } }),
            el('div', { class: 'skeleton sk-line', style: { width: '50%' } }))),
      );
      wrap.appendChild(el('div', { class: 'skeleton sk-chart', style: { marginTop: '16px' } }));
      break;

    case 'bilibili':
      wrap.appendChild(
        el('div', { class: 'sk-video-grid' },
          Array.from({ length: 6 }, () =>
            el('div', null,
              el('div', { class: 'skeleton sk-video' }),
              el('div', { class: 'skeleton sk-line', style: { marginTop: '8px' } }),
              el('div', { class: 'skeleton sk-line', style: { width: '50%', marginTop: '6px' } })))),
      );
      break;

    case 'coupons':
      wrap.appendChild(
        el('div', { class: 'skeleton', style: { height: '140px', borderRadius: '14px', marginBottom: '16px' } }),
        ...Array.from({ length: 3 }, () =>
          el('div', { class: 'skeleton', style: { height: '84px', borderRadius: '10px', marginBottom: '12px' } })),
      );
      break;

    case 'community':
      wrap.appendChild(
        el('div', { class: 'skeleton', style: { height: '48px', borderRadius: '10px', marginBottom: '16px' } }),
        ...Array.from({ length: 5 }, () =>
          el('div', { style: { display: 'flex', gap: '12px', marginBottom: '14px' } },
            el('div', { class: 'skeleton', style: { width: '48px', height: '48px', borderRadius: '10px', flexShrink: '0' } }),
            el('div', { style: { flex: '1' } },
              el('div', { class: 'skeleton sk-line', style: { width: '80%' } }),
              el('div', { class: 'skeleton sk-line', style: { width: '40%', marginTop: '8px' } })))),
      );
      break;

    default:
      wrap.appendChild(el('div', { class: 'skeleton sk-chart' }));
  }
  return wrap;
}
