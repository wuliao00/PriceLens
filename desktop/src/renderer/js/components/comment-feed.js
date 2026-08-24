/**
 * 社区验证视图（规范 §6.4）：什么值得买
 *   - 近 7 天更低价横幅（可点击跳转）
 *   - 最近爆料列表 + 值/不值双色比例条
 *   - 热评 Top10，关键词高亮（positive 绿加粗 / negative 红加粗）
 */
import { el, icon, copyText } from '../utils/dom.js';
import { formatPrice, timeAgo } from '../utils/format.js';
import { openExternalSafe } from '../utils/sanitize.js';
import { showContextMenu } from './context-menu.js';
import { showToast } from './toast.js';

/** 高亮关键词映射（规范 §6.4） */
const HIGHLIGHT_MAP = {
  positive: ['神价', '史低', '好价', '必入', '真香'],
  negative: ['翻车', '品控', '退货', '缩水', '丐版'],
};

/**
 * 渲染社区视图。
 * @param {HTMLElement} container
 * @param {{deals: Array|null, comments: Array|null, ratio: {up:number, down:number}|null,
 *   product: object, error?: string, onOpenDeal:(url:string)=>void}} ctx
 */
export function renderCommunityView(container, ctx) {
  const { deals, comments, ratio, product, error } = ctx;

  if (error && (!deals || deals.length === 0)) {
    container.appendChild(el('div', { class: 'card empty-state' },
      icon('chat', 48),
      el('div', { class: 'e-title', text: '什么值得买数据源暂不可用' }),
      el('div', { class: 'e-desc', text: error })));
    return;
  }
  if (!deals || deals.length === 0) {
    container.appendChild(el('div', { class: 'card empty-state' },
      icon('chat', 48),
      el('div', { class: 'e-title', text: '暂无社区爆料' }),
      el('div', { class: 'e-desc', text: '搜索商品后，这里会展示最新爆料、值/不值投票与热评。' })));
    return;
  }

  /* 近 7 天更低价横幅 */
  const lowerDeal = findLowerDeal(deals, product?.price);
  if (lowerDeal) {
    container.appendChild(el('div', {
      class: 'banner',
      on: { click: () => openExternalSafe(lowerDeal.url) },
    },
      el('span', null,
        `⚡ ${timeAgo(lowerDeal.time) || '近日'}出现过更低价 `,
        el('b', { text: formatPrice(lowerDeal.price) })),
      el('span', { class: 'banner-action', text: '查看爆料 →' })));
  }

  /* 爆料 + 值/不值 */
  const listCard = el('div', { class: 'card' },
    el('h3', { class: 'card-title', text: '最近爆料' }));
  for (const deal of deals.slice(0, 5)) {
    listCard.appendChild(buildDealItem(deal));
  }

  if (ratio && (ratio.up > 0 || ratio.down > 0)) {
    const total = ratio.up + ratio.down;
    const upPct = Math.round((ratio.up / total) * 100);
    listCard.appendChild(el('div', { class: 'ratio-bar' },
      el('div', { class: 'r-up', style: { width: `${upPct}%` } }),
      el('div', { class: 'r-down', style: { width: `${100 - upPct}%` } })));
    listCard.appendChild(el('div', { class: 'ratio-legend' },
      el('span', { class: 'up', text: `👍 值 ${ratio.up}` }),
      el('span', { class: 'down', text: `👎 不值 ${ratio.down}` })));
  }
  container.appendChild(listCard);

  /* 热评 Top10 */
  if (comments && comments.length > 0) {
    const commentCard = el('div', { class: 'card' },
      el('h3', { class: 'card-title', text: '热门评论' }));
    for (const comment of comments.slice(0, 10)) {
      commentCard.appendChild(buildCommentItem(comment));
    }
    container.appendChild(commentCard);
  }
}

/**
 * 找近 7 天内低于当前价的爆料。
 * @param {Array} deals
 * @param {number} currentPrice
 */
function findLowerDeal(deals, currentPrice) {
  if (!(currentPrice > 0)) return null;
  const weekAgo = Date.now() - 7 * 24 * 3600 * 1000;
  return deals.find((d) => d.price > 0 && d.price < currentPrice && (!d.time || d.time >= weekAgo)) || null;
}

/** 单条爆料 */
function buildDealItem(deal) {
  return el('div', {
    class: 'deal-item',
    on: { click: () => openExternalSafe(deal.url) },
  },
    el('div', { class: 'd-main' },
      el('div', { class: 'd-title clamp-1', text: deal.title }),
      el('div', { class: 'd-meta' },
        el('span', { text: deal.mall || '' }),
        deal.time ? el('span', { text: timeAgo(deal.time) }) : null)),
    deal.price ? el('div', { class: 'd-price', text: formatPrice(deal.price) }) : null,
  );
}

/** 单条评论（关键词高亮 + 右键复制） */
function buildCommentItem(comment) {
  const body = el('div', { class: 'comment-body selectable' });
  appendHighlighted(body, comment.content);

  const item = el('div', { class: 'comment-item' },
    el('div', { class: 'comment-head' },
      icon('user', 12),
      el('span', { text: comment.user || '匿名用户' }),
      comment.time ? el('span', { text: timeAgo(comment.time) }) : null),
    body);

  item.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    showContextMenu(e.clientX, e.clientY, [
      {
        label: '复制评论文本',
        icon: 'copy',
        action: async () => {
          const ok = await copyText(comment.content);
          showToast(ok ? '已复制' : '复制失败');
        },
      },
    ]);
  });
  return item;
}

/**
 * 把文本按关键词分段渲染：命中 positive → .hl-pos，negative → .hl-neg。
 * 全程 DOM API，无 innerHTML（红线 #4）。
 * @param {HTMLElement} container
 * @param {string} text
 */
export function appendHighlighted(container, text) {
  const rules = [
    ...HIGHLIGHT_MAP.positive.map((w) => ({ word: w, cls: 'hl-pos' })),
    ...HIGHLIGHT_MAP.negative.map((w) => ({ word: w, cls: 'hl-neg' })),
  ];
  let rest = String(text || '');
  let matched = true;
  while (rest && matched) {
    matched = false;
    // 找最早出现的关键词
    let best = null;
    for (const rule of rules) {
      const idx = rest.indexOf(rule.word);
      if (idx >= 0 && (!best || idx < best.idx)) best = { ...rule, idx };
    }
    if (best) {
      matched = true;
      if (best.idx > 0) container.appendChild(document.createTextNode(rest.slice(0, best.idx)));
      container.appendChild(el('span', { class: best.cls, text: best.word }));
      rest = rest.slice(best.idx + best.word.length);
    }
  }
  if (rest) container.appendChild(document.createTextNode(rest));
}
