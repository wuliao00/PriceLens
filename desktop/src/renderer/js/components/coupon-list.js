/**
 * 优惠券视图（规范 §6.3）：到手价大字（countUp 600ms）+ 虚线券卡片 +
 * 复制券码 / 领券链接（右键） + 可叠加标签。
 */
import { el, icon, copyText, animateNumber } from '../utils/dom.js';
import { formatPrice } from '../utils/format.js';
import { openExternalSafe } from '../utils/sanitize.js';
import { showContextMenu } from './context-menu.js';
import { showToast } from './toast.js';

/**
 * 渲染优惠券视图。
 * @param {HTMLElement} container
 * @param {{coupons: Array<{title:string, amount:number, threshold:number,
 *   expireAt:string, code:string|null, link:string, stackable:boolean}> | null,
 *   finalPrice: number|null, currentPrice: number|null,
 *   product: object, error?: string}} ctx
 */
export function renderCouponView(container, ctx) {
  const { coupons, finalPrice, currentPrice, product, error } = ctx;

  if (error && (!coupons || coupons.length === 0)) {
    container.appendChild(el('div', { class: 'card empty-state' },
      icon('ticket', 48),
      el('div', { class: 'e-title', text: '优惠券数据源暂不可用' }),
      el('div', { class: 'e-desc', text: error })));
    return;
  }

  /* 到手价卡片 */
  if (finalPrice && finalPrice > 0) {
    const priceEl = el('div', { class: 'final-price' });
    animateNumber(priceEl, finalPrice, { format: (n) => formatPrice(Math.round(n)) }); // 600ms countUp
    const formulaParts = [];
    if (currentPrice) formulaParts.push(`原价 ${formatPrice(currentPrice)}`);
    if (coupons && coupons.length) formulaParts.push(`优惠 -${formatPrice(currentPrice - finalPrice)}`);

    container.appendChild(el('div', { class: 'card final-price-card' },
      el('div', { class: 'final-price-label', text: '预计到手价' }),
      priceEl,
      formulaParts.length ? el('div', { class: 'final-price-formula', text: formulaParts.join(' · ') }) : null,
    ));
  } else if (!coupons || coupons.length === 0) {
    container.appendChild(el('div', { class: 'card empty-state' },
      icon('ticket', 48),
      el('div', { class: 'e-title', text: '未发现隐藏优惠券' }),
      el('div', {
        class: 'e-desc',
        text: `${product?.title || '该商品'}当前未解析到可用的隐藏券；大促节点（618 / 双11）附近再来看看。`,
      })));
    return;
  }

  /* 券列表 */
  for (const coupon of coupons) {
    container.appendChild(buildCouponCard(coupon));
  }
}

/** 单张券卡片 */
function buildCouponCard(coupon) {
  const card = el('div', { class: 'coupon-card' });

  card.appendChild(el('div', { class: 'coupon-amount' },
    el('span', null,
      el('small', { text: '¥ ' }),
      String(coupon.amount))));

  const meta = el('div', { class: 'coupon-meta' });
  if (coupon.threshold > 0) meta.appendChild(el('span', { text: `满 ${formatPrice(coupon.threshold)} 可用` }));
  else meta.appendChild(el('span', { text: '无门槛' }));
  if (coupon.expireAt) meta.appendChild(el('span', null, icon('clock', 12), ` ${coupon.expireAt}`));
  if (coupon.stackable) meta.appendChild(el('span', { class: 'tag tag--success', text: '可叠加' }));

  const info = el('div', { class: 'coupon-info' },
    el('div', { class: 'coupon-title clamp-1', text: coupon.title || '平台优惠券' }),
    meta);
  card.appendChild(info);

  card.appendChild(el('button', {
    class: 'btn',
    on: {
      click: async () => {
        const text = coupon.code || coupon.link || coupon.title;
        const ok = await copyText(text);
        showToast(ok ? '已复制' : '复制失败');
      },
    },
  }, icon('copy', 14), '复制'));

  /* 右键：复制券码 / 复制领券链接 */
  card.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    const items = [];
    if (coupon.code) {
      items.push({
        label: '复制券码',
        icon: 'copy',
        action: async () => {
          const ok = await copyText(coupon.code);
          showToast(ok ? '已复制券码' : '复制失败');
        },
      });
    }
    if (coupon.link) {
      items.push({
        label: '复制领券链接',
        icon: 'copy',
        action: async () => {
          const ok = await copyText(coupon.link);
          showToast(ok ? '已复制链接' : '复制失败');
        },
      }, {
        label: '在浏览器打开',
        icon: 'external',
        action: () => openExternalSafe(coupon.link),
      });
    }
    if (items.length) showContextMenu(e.clientX, e.clientY, items);
  });

  return card;
}
