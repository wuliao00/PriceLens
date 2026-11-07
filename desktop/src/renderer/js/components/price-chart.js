/**
 * 手写 SVG 价格曲线（规范 §6.2，禁止 Chart.js/ECharts）
 * ------------------------------------------------------
 *   - 面积渐变 + 折线 + 当前点脉冲动画 + 历史最低虚线 + 目标价参考线
 *   - 区间切换（近 30 / 90 天 / 全部）+ 区间涨跌标注
 *   - 悬停 tooltip（日期 + 价格）
 *   - 右键菜单：导出 PNG / 复制当前价格
 *   - 盯价列表：状态筛选 + 目标进度条 + 右键管理
 *   - 全部用 createElementNS 构建（红线 #4：无 innerHTML 外部数据）
 */
import { svgEl, el, icon, copyText } from '../utils/dom.js';
import { formatPrice, formatPricePlain, timeAgo } from '../utils/format.js';
import { showContextMenu } from './context-menu.js';
import { showToast } from './toast.js';

/** 渐变 id 自增序号：同一文档多图表共存时避免 id 冲突 */
let gradSeq = 0;

/**
 * 渲染价格视图：购买建议 + 统计 + 图表 + 当前商品盯价控制 + 全部盯价目标。
 * @param {HTMLElement} container
 * @param {{history: {current:number, lowest:number, highest:number,
 *   points:Array<{date:string, price:number}>, advice?:object} | null,
 *   product: object, watch: object|null, watches?: Array<object>, error?: string,
 *   onSetWatch: (target:number)=>void, onRemoveWatch: (id:string)=>void,
 *   onToggleWatch: (id:string, paused:boolean)=>void, onCheckNow?: ()=>void,
 *   onOpenWatch?: (watch:object)=>void,
 *   onRetrySearch?: ()=>void, onRetry?: ()=>void, onOpenProduct?: (()=>void)|null}} ctx
 */
export function renderPriceView(container, ctx) {
  const {
    history, product, watch, watches = [], error, onSetWatch, onRemoveWatch,
    onToggleWatch, onCheckNow, onOpenWatch, onRetrySearch, onRetry, onOpenProduct,
  } = ctx;

  container.appendChild(el('div', { class: 'view-head' },
    el('h2', { class: 'vh-title' }, icon('chart', 20), '盯价'),
    el('div', { class: 'vh-sub', text: '历史价格曲线 · 目标价提醒 · 最多同时盯 20 件商品' })));

  const hasHistory = Boolean(history && Array.isArray(history.points) && history.points.length >= 2);
  if (!hasHistory) {
    container.appendChild(renderEmpty({ product, error, onRetrySearch, onRetry, onOpenProduct }));
  } else {
    container.appendChild(renderHistoryCard({ history, product, watch, onSetWatch, onRemoveWatch, onToggleWatch }));
  }

  // 盯价列表始终展示：即使当前商品没有曲线，也要能管理已有关注目标
  container.appendChild(renderWatchList({ watches, onOpenWatch, onToggleWatch, onRemoveWatch, onCheckNow }));
}

/** 统计项 */
function statItem(label, value, color) {
  return el('div', { class: 'stat-item' },
    el('div', { class: 's-label', text: label }),
    el('div', { class: 's-value', text: value, style: color ? { color } : {} }));
}

/**
 * 空状态：区分「未搜索 / 数据源报错 / 源未收录」，均给出原因与可操作引导。
 * @param {{product: object, error?: string, onRetrySearch?: ()=>void,
 *   onRetry?: ()=>void, onOpenProduct?: (()=>void)|null}} ctx
 */
function renderEmpty({ product, error, onRetrySearch, onRetry, onOpenProduct }) {
  const actions = el('div', { class: 'e-actions' });

  /* 尚未搜索任何商品 */
  if (!product || !product.title) {
    if (onRetrySearch) {
      actions.appendChild(el('button', { class: 'btn btn--primary', on: { click: onRetrySearch } }, '去搜索'));
    }
    return el('div', { class: 'card empty-state' },
      icon('chart', 48),
      el('div', { class: 'e-title', text: '先搜索商品，再看价格趋势' }),
      el('div', {
        class: 'e-desc',
        text: '输入商品名或粘贴京东/淘宝商品链接后，这里会展示历史价格曲线，并可设置盯价提醒。',
      }),
      actions);
  }

  /* 数据源明确报错（限流/网络/接口拒绝） */
  if (error) {
    if (onRetry) actions.appendChild(el('button', { class: 'btn btn--primary', on: { click: onRetry } }, '重试'));
    if (onRetrySearch) actions.appendChild(el('button', { class: 'btn', on: { click: onRetrySearch } }, '重新搜索'));
    return el('div', { class: 'card empty-state' },
      icon('chart', 48),
      el('div', { class: 'e-title', text: '历史价格暂不可用' }),
      el('div', { class: 'e-desc', text: error }),
      actions);
  }

  /* 源正常但未收录该商品（新上架/长尾商品通常无历史记录） */
  if (onOpenProduct) {
    actions.appendChild(el('button', { class: 'btn btn--primary', on: { click: onOpenProduct } }, '打开商品页'));
  }
  if (onRetrySearch) actions.appendChild(el('button', { class: 'btn', on: { click: onRetrySearch } }, '换个商品搜索'));
  return el('div', { class: 'card empty-state' },
    icon('chart', 48),
    el('div', { class: 'e-title', text: '暂无历史价格数据' }),
    el('div', {
      class: 'e-desc',
      text: '慢慢买尚未收录该商品的历史价格记录（新上架商品通常无数据）。可以先去商城打开商品页浏览、积累数据后再来查看，或换一个商品搜索。',
    }),
    actions);
}

/** 历史价格卡：购买建议 + 统计 + 区间工具条 + 曲线 + 当前商品盯价控制 */
function renderHistoryCard({ history, product, watch, onSetWatch, onRemoveWatch, onToggleWatch }) {
  const prices = history.points.map((p) => p.price);
  const avg7d = prices.slice(-7).reduce((s, p) => s + p, 0) / Math.min(7, prices.length);
  const target = watch && watch.target > 0 ? watch.target : null;

  /* 区间状态（卡片本地）：近 30 / 90 天 / 全部 */
  const RANGES = [
    { key: '30', label: '近 30 天', n: 30 },
    { key: '90', label: '近 90 天', n: 90 },
    { key: 'all', label: '全部', n: Infinity },
  ];
  let rangeKey = 'all';
  const slicePoints = () => {
    const r = RANGES.find((x) => x.key === rangeKey);
    return Number.isFinite(r.n) ? history.points.slice(-r.n) : history.points;
  };

  const chartWrap = el('div', {
    class: 'chart-wrap selectable',
    on: {
      contextmenu: (e) => {
        e.preventDefault(); // 显式传参（红线 #5）
        showContextMenu(e.clientX, e.clientY, [
          { label: '导出 PNG', icon: 'download', action: () => exportChartPng(card, product.title) },
          {
            label: `复制当前价格 ${formatPrice(history.current)}`,
            icon: 'copy',
            action: async () => {
              const ok = await copyText(formatPricePlain(history.current));
              showToast(ok ? '已复制' : '复制失败');
            },
          },
        ]);
      },
    },
  });
  const rangeNote = el('span', { class: 'chart-range-note' });
  const chipBar = el('div', { class: 'filter-bar', style: { margin: '0' } });
  const chips = {};
  for (const r of RANGES) {
    const disabled = Number.isFinite(r.n) && history.points.length <= r.n && r.key !== 'all';
    const chip = el('button', {
      class: `filter-chip${r.key === rangeKey ? ' active' : ''}`,
      disabled: disabled || undefined,
      title: disabled ? '数据点不足，与「全部」相同' : r.label,
      on: {
        click: () => {
          if (rangeKey === r.key) return;
          rangeKey = r.key;
          for (const [k, node] of Object.entries(chips)) node.classList.toggle('active', k === r.key);
          redrawChart();
        },
      },
    }, r.label);
    chips[r.key] = chip;
    chipBar.appendChild(chip);
  }

  function redrawChart() {
    const pts = slicePoints();
    chartWrap.textContent = '';
    chartWrap.appendChild(renderPriceChart(pts, { target }));
    /* 区间涨跌：首尾价差（降价绿 / 涨价红） */
    rangeNote.textContent = '';
    if (pts.length >= 2) {
      const first = pts[0];
      const last = pts[pts.length - 1];
      const delta = last.price - first.price;
      const pct = first.price > 0 ? (delta / first.price) * 100 : 0;
      rangeNote.appendChild(el('span', { text: `${first.date} → ${last.date}` }));
      if (delta === 0) {
        rangeNote.appendChild(el('span', { class: 'crn-delta', text: '区间持平' }));
      } else {
        const dirCls = delta < 0 ? 'crn-down' : 'crn-up';
        rangeNote.appendChild(el('span', { class: `crn-delta ${dirCls}` },
          icon(delta < 0 ? 'trend-down' : 'trend-up', 13),
          `${delta < 0 ? '-' : '+'}¥${formatPricePlain(Math.abs(delta))}（${delta < 0 ? '' : '+'}${pct.toFixed(1)}%）`));
      }
    }
  }
  redrawChart();

  const card = el('div', { class: 'card' },
    el('h3', { class: 'card-title', text: '历史价格趋势' }),
    renderAdvice(history.advice),
    el('div', { class: 'stats-row' },
      statItem('当前价', formatPrice(history.current), 'var(--danger)'),
      statItem('历史最低', formatPrice(history.lowest), 'var(--success)'),
      statItem('历史最高', formatPrice(history.highest)),
      statItem('7 日均价', formatPrice(Math.round(avg7d)))),
    el('div', { class: 'chart-toolbar' }, chipBar, rangeNote),
    chartWrap,
    renderWatchControls({ watch, onSetWatch, onRemoveWatch, onToggleWatch }),
  );
  return card;
}

/** 历史分位购买建议（主进程算好；样本不足时也如实说） */
function renderAdvice(advice) {
  if (!advice) return null;
  const iconName = advice.level === 'low' ? 'check' : (advice.level === 'high' ? 'warn' : 'chart');
  return el('div', { class: `advice advice--${advice.level}` },
    icon(iconName, 18),
    el('div', { class: 'advice-body' },
      el('div', { class: 'advice-label', text: advice.label }),
      el('div', { class: 'advice-detail', text: advice.detail })));
}

/** 当前商品的盯价设置/管理行 */
function renderWatchControls({ watch, onSetWatch, onRemoveWatch, onToggleWatch }) {
  const input = el('input', {
    class: 'field-input', type: 'number', min: '0', step: '0.01',
    placeholder: '目标价 ¥',
    value: watch && watch.target ? String(watch.target) : '',
  });

  let statusText = '低于目标价时系统通知，可同时盯多个商品';
  if (watch) {
    if (watch.paused) statusText = '该目标已暂停，不会参与轮询';
    else if (watch.lastError) statusText = `最近检查失败：${watch.lastError}`;
    else if (watch.notified) statusText = `已到价 · 当前 ¥${watch.current ?? '--'}`;
    else statusText = '盯价中：低于目标价时系统通知';
  }

  const row = el('div', { class: 'watch-row' },
    icon('bell', 16),
    el('span', { class: 'fs-caption text-secondary', text: statusText }),
    input,
    el('button', {
      class: 'btn btn--primary',
      on: {
        click: () => {
          const target = Number(input.value);
          if (target > 0) onSetWatch(target);
          else showToast('请输入大于 0 的目标价');
        },
      },
    }, watch ? '保存目标' : '加入盯价'),
    watch ? el('button', {
      class: 'btn',
      on: { click: () => onToggleWatch(watch.id, !watch.paused) },
    }, watch.paused ? '恢复' : '暂停') : null,
    watch ? el('button', {
      class: 'btn btn--text btn--danger',
      on: { click: () => onRemoveWatch(watch.id) },
    }, '删除') : null,
  );
  return row;
}

/** 盯价状态分类（筛选与进度条共用） */
function watchBucket(w) {
  if (w.paused) return 'paused';
  if (w.current > 0 && w.target > 0 && w.current <= w.target) return 'hit';
  return 'waiting';
}

/** 全部盯价目标列表（历史价页始终展示）：筛选 chips + 进度条 + 右键管理 */
function renderWatchList({ watches, onOpenWatch, onToggleWatch, onRemoveWatch, onCheckNow }) {
  const list = Array.isArray(watches) ? watches : [];
  const head = el('div', { class: 'watch-list-head' },
    el('h3', { class: 'card-title' },
      '盯价目标',
      list.length ? el('span', { class: 'ct-count', text: `${list.length}/20` }) : null),
    el('button', {
      class: 'btn btn--text',
      on: { click: () => onCheckNow && onCheckNow() },
      title: '立即检查所有盯价目标',
    }, icon('refresh', 13), '立即检查'),
  );
  const card = el('div', { class: 'card watch-list-card', style: { marginTop: '16px' } }, head);

  if (list.length === 0) {
    card.appendChild(el('div', { class: 'watch-empty' },
      icon('bell', 20),
      el('span', { text: '还没有盯价目标。搜索商品后，在上方设置目标价即可加入，最多可同时盯 20 个商品。' })));
    return card;
  }

  /* 状态筛选（卡片本地状态） */
  const FILTERS = [
    { key: 'all', label: '全部' },
    { key: 'hit', label: '已到价' },
    { key: 'waiting', label: '等待降价' },
    { key: 'paused', label: '已暂停' },
  ];
  let filterKey = 'all';
  const body = el('div', {});
  const filterRow = el('div', { class: 'filter-bar watch-filter-row' });
  const chips = {};
  for (const f of FILTERS) {
    const count = f.key === 'all' ? list.length : list.filter((w) => watchBucket(w) === f.key).length;
    const chip = el('button', {
      class: `filter-chip${f.key === filterKey ? ' active' : ''}`,
      on: {
        click: () => {
          if (filterKey === f.key) return;
          filterKey = f.key;
          for (const [k, node] of Object.entries(chips)) node.classList.toggle('active', k === f.key);
          redrawList();
        },
      },
    }, f.label, el('span', { class: 'fc-count', text: String(count) }));
    chips[f.key] = chip;
    filterRow.appendChild(chip);
  }
  card.appendChild(filterRow);
  card.appendChild(body);

  function redrawList() {
    body.textContent = '';
    const shown = filterKey === 'all' ? list : list.filter((w) => watchBucket(w) === filterKey);
    if (shown.length === 0) {
      body.appendChild(el('div', { class: 'watch-empty' },
        icon('bell', 20),
        el('span', { text: '该状态下暂无盯价目标。' })));
      return;
    }
    for (const w of shown) body.appendChild(buildWatchItem(w, { onOpenWatch, onToggleWatch, onRemoveWatch }));
  }
  redrawList();
  return card;
}

/** 单条盯价：标题 + 元信息 + 目标进度条 + 操作 + 右键菜单 */
function buildWatchItem(w, { onOpenWatch, onToggleWatch, onRemoveWatch }) {
  const status = watchStatus(w);
  const bucket = watchBucket(w);
  const item = el('div', {
    class: `watch-item${w.alerted ? ' watch-item--alerted' : ''}${w.paused ? ' watch-item--paused' : ''}`,
    on: {
      contextmenu: (e) => {
        e.preventDefault();
        e.stopPropagation();
        showContextMenu(e.clientX, e.clientY, [
          { label: '查看该商品', icon: 'eye', action: () => onOpenWatch && onOpenWatch(w) },
          {
            label: w.paused ? '恢复盯价' : '暂停盯价',
            icon: w.paused ? 'play' : 'pause',
            action: () => onToggleWatch && onToggleWatch(w.id, !w.paused),
          },
          { label: '删除目标', icon: 'trash', danger: true, action: () => onRemoveWatch && onRemoveWatch(w.id) },
        ]);
      },
    },
  },
    el('div', { class: 'watch-main' },
      el('div', { class: 'watch-title clamp-1', text: w.keyword || w.url }),
      el('div', { class: 'watch-meta' },
        el('span', { text: `当前 ${w.current ? formatPrice(w.current) : '--'}` }),
        el('span', { text: `目标 ${formatPrice(w.target)}` }),
        w.lowest ? el('span', { text: `历史最低 ${formatPrice(w.lowest)}` }) : null,
        w.lastChecked ? el('span', { text: `检查于 ${timeAgo(w.lastChecked)}` }) : null,
        w.lastAlertAt ? el('span', { text: `上次提醒 ${timeAgo(w.lastAlertAt)}${w.lastAlertPrice ? ` @ ${formatPrice(w.lastAlertPrice)}` : ''}` }) : null),
      buildWatchProgress(w, bucket),
      el('div', { class: `watch-status ${status.cls}`, text: status.text })),
    el('div', { class: 'watch-actions' },
      el('button', { class: 'btn btn--text', on: { click: () => onOpenWatch && onOpenWatch(w) } }, '查看'),
      el('button', {
        class: 'btn btn--text',
        on: { click: () => onToggleWatch && onToggleWatch(w.id, !w.paused) },
      }, w.paused ? '恢复' : '暂停'),
      el('button', {
        class: 'btn btn--text btn--danger',
        on: { click: () => onRemoveWatch && onRemoveWatch(w.id) },
      }, '删除')));

  // 点击空白处打开该商品；按钮自己 stopPropagation，避免误触
  item.addEventListener('click', () => onOpenWatch && onOpenWatch(w));
  for (const btn of item.querySelectorAll('button')) {
    btn.addEventListener('click', (e) => e.stopPropagation());
  }
  return item;
}

/**
 * 目标进度条：当前价距目标价还差多少。
 * 未到价：fill = 目标/现价（价格越接近目标条越满），标注「还差 x%」；
 * 已到价：满条绿色；现价未知：不显示（不编造进度）。
 */
function buildWatchProgress(w, bucket) {
  if (!(w.current > 0 && w.target > 0) || bucket === 'paused') return null;
  const hit = bucket === 'hit';
  const gapPct = hit ? 0 : Math.max(0, Math.round(((w.current - w.target) / w.current) * 100));
  const fillPct = hit ? 100 : Math.max(2, 100 - gapPct);
  const label = hit
    ? `已低于目标 ${formatPrice(w.target - w.current)}`
    : `还差 ${gapPct}%`;
  return el('div', { class: 'watch-progress', title: `目标 ${formatPrice(w.target)} · 当前 ${formatPrice(w.current)}` },
    el('div', { class: 'wp-track' },
      el('div', { class: `wp-fill${hit ? ' wp-fill--hit' : ''}`, style: { width: `${fillPct}%` } })),
    el('span', { class: `wp-label${hit ? ' wp-label--hit' : ''}`, text: label }));
}

/** 盯价目标状态文案 */
function watchStatus(w) {
  if (w.paused) return { cls: 'watch-status--muted', text: '已暂停' };
  if (w.lastError) return { cls: 'watch-status--danger', text: `检查失败：${w.lastError}` };
  if (w.notified) {
    const count = Number(w.alertCount || 0);
    return {
      cls: 'watch-status--success',
      text: count > 0
        ? `已到价，累计提醒 ${count} 次；价格回升后重新提醒`
        : '已到价，等待价格回升后重新提醒',
    };
  }
  if (w.current) return { cls: 'watch-status--waiting', text: '等待降价' };
  return { cls: 'watch-status--muted', text: '已加入，等待首次检查' };
}

/* ══════════ 图表本体 ══════════ */

/**
 * 构建价格折线图（SVG，createElementNS）。
 * @param {Array<{date:string, price:number}>} dataPoints 至少 2 个点
 * @param {{target?: number|null}} [opts] target>0 时绘制目标价参考线
 * @returns {HTMLElement}
 */
export function renderPriceChart(dataPoints, opts = {}) {
  const W = 720;
  const H = 200;
  const PAD = { top: 20, right: 16, bottom: 30, left: 56 };
  const target = opts.target > 0 ? opts.target : null;

  const prices = dataPoints.map((d) => d.price);
  let min = Math.min(...prices);
  let max = Math.max(...prices);
  /* 目标价纳入纵轴 domain（过于离谱的目标不拉伸坐标，避免曲线被压扁） */
  const showTargetLine = target != null && target <= max * 1.05 && target >= min * 0.6;
  if (showTargetLine) {
    min = Math.min(min, target);
    max = Math.max(max, target);
  }
  const range = max - min || 1;

  const x = (i) => PAD.left + (i / (dataPoints.length - 1)) * (W - PAD.left - PAD.right);
  const y = (p) => PAD.top + (1 - (p - min) / range) * (H - PAD.top - PAD.bottom);

  // 解析设计令牌（SVG 属性里 CSS 变量部分浏览器不可靠，取计算值）
  const css = getComputedStyle(document.documentElement);
  const accent = css.getPropertyValue('--accent').trim() || '#0b7a5e';
  const success = css.getPropertyValue('--success').trim() || '#0e9f6e';
  const warning = css.getPropertyValue('--warning').trim() || '#c07f00';
  const textColor = css.getPropertyValue('--text-tertiary').trim() || '#8a948e';

  const svg = svgEl('svg', { width: '100%', viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: 'none', class: 'price-chart-svg' });
  svg.dataset.chart = 'price';

  /* 渐变定义（id 带序号，避免多图表冲突） */
  const gradId = `areaGrad${++gradSeq}`;
  const defs = svgEl('defs', {});
  const grad = svgEl('linearGradient', { id: gradId, x1: '0', y1: '0', x2: '0', y2: '1' });
  grad.appendChild(svgEl('stop', { offset: '0%', 'stop-color': accent, 'stop-opacity': '0.18' }));
  grad.appendChild(svgEl('stop', { offset: '100%', 'stop-color': accent, 'stop-opacity': '0' }));
  defs.appendChild(grad);
  svg.appendChild(defs);

  /* 网格线 + Y 轴刻度 */
  for (let i = 0; i <= 3; i++) {
    const value = max - (range / 3) * i;
    const gy = y(value);
    svg.appendChild(svgEl('line', {
      x1: PAD.left, x2: W - PAD.right, y1: gy, y2: gy,
      stroke: textColor, 'stroke-opacity': '0.15', 'stroke-width': '1',
    }));
    svg.appendChild(Object.assign(svgEl('text', {
      x: PAD.left - 8, y: gy + 3.5, 'text-anchor': 'end',
      'font-size': '10', fill: textColor,
    }), { textContent: Math.round(value) }));
  }

  /* 面积 + 折线路径 */
  const pathD = dataPoints.map((d, i) =>
    `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(d.price).toFixed(1)}`).join(' ');
  const areaD = `${pathD} L${x(dataPoints.length - 1).toFixed(1)},${H - PAD.bottom} L${x(0).toFixed(1)},${H - PAD.bottom} Z`;

  svg.appendChild(svgEl('path', { d: areaD, fill: `url(#${gradId})` }));
  svg.appendChild(svgEl('path', {
    d: pathD, fill: 'none', stroke: accent,
    'stroke-width': '2', 'stroke-linecap': 'round', 'stroke-linejoin': 'round',
  }));

  /* 当前价格点：脉冲动画（CSS transform+opacity，见 animations.css chartPulse） */
  const lastX = x(dataPoints.length - 1);
  const lastY = y(prices[prices.length - 1]);
  const dot = svgEl('circle', { cx: lastX, cy: lastY, r: '4', fill: accent, class: 'chart-dot-pulse' });
  svg.appendChild(dot);

  /* 历史最低虚线 + 标注 */
  const lowest = Math.min(...prices);
  svg.appendChild(svgEl('line', {
    x1: PAD.left, x2: W - PAD.right, y1: y(lowest), y2: y(lowest),
    stroke: success, 'stroke-width': '1', 'stroke-dasharray': '4,4',
  }));
  svg.appendChild(Object.assign(svgEl('text', {
    x: W - PAD.right, y: y(lowest) - 4, 'text-anchor': 'end',
    'font-size': '10', fill: success,
  }), { textContent: `历史最低 ¥${lowest}` }));

  /* 目标价参考线（琥珀色点划线；与最低价过近时标注下移避让） */
  if (showTargetLine) {
    const ty = y(target);
    svg.appendChild(svgEl('line', {
      x1: PAD.left, x2: W - PAD.right, y1: ty, y2: ty,
      stroke: warning, 'stroke-width': '1.2', 'stroke-dasharray': '6,3,2,3',
    }));
    const nearLowest = Math.abs(ty - y(lowest)) < 14;
    svg.appendChild(Object.assign(svgEl('text', {
      x: PAD.left + 4, y: nearLowest ? ty + 12 : ty - 4, 'text-anchor': 'start',
      'font-size': '10', fill: warning, 'font-weight': '600',
    }), { textContent: `目标 ¥${target}` }));
  }

  /* X 轴首/中/尾日期 */
  for (const idx of [0, Math.floor((dataPoints.length - 1) / 2), dataPoints.length - 1]) {
    svg.appendChild(Object.assign(svgEl('text', {
      x: x(idx), y: H - 8, 'text-anchor': idx === 0 ? 'start' : (idx === dataPoints.length - 1 ? 'end' : 'middle'),
      'font-size': '10', fill: textColor,
    }), { textContent: dataPoints[idx].date }));
  }

  /* 悬停交互层：垂直指示线 + 高亮圆点 + tooltip */
  const hoverLine = svgEl('line', {
    y1: PAD.top, y2: H - PAD.bottom,
    stroke: accent, 'stroke-opacity': '0.4', 'stroke-width': '1',
    visibility: 'hidden',
  });
  const hoverDot = svgEl('circle', { r: '4.5', fill: accent, stroke: '#fff', 'stroke-width': '1.5', visibility: 'hidden' });
  svg.appendChild(hoverLine);
  svg.appendChild(hoverDot);

  const wrap = el('div', { class: 'chart-inner', style: { position: 'relative' } }, svg);
  const tipDate = el('div', { class: 't-date' });
  const tipPrice = el('div', { class: 't-price' });
  const tooltip = el('div', { class: 'chart-tooltip' }, tipDate, tipPrice);
  wrap.appendChild(tooltip);

  /* rAF 合帧：高频 mousemove 每帧最多更新一次，复用节点不重建 DOM */
  let pendingEvent = null;
  let rafScheduled = false;
  svg.addEventListener('mousemove', (e) => {
    pendingEvent = e;
    if (rafScheduled) return;
    rafScheduled = true;
    requestAnimationFrame(() => {
      rafScheduled = false;
      const ev = pendingEvent;
      if (!ev || !svg.isConnected) return;
      const rect = svg.getBoundingClientRect();
      const ratio = (ev.clientX - rect.left) / rect.width;
      const svgX = ratio * W;
      let nearest = 0;
      let bestDist = Infinity;
      for (let i = 0; i < dataPoints.length; i++) {
        const d = Math.abs(x(i) - svgX);
        if (d < bestDist) { bestDist = d; nearest = i; }
      }
      const px = (x(nearest) / W) * rect.width;
      const py = (y(dataPoints[nearest].price) / H) * rect.height;
      hoverLine.setAttribute('x1', x(nearest));
      hoverLine.setAttribute('x2', x(nearest));
      hoverLine.setAttribute('visibility', 'visible');
      hoverDot.setAttribute('cx', x(nearest));
      hoverDot.setAttribute('cy', y(dataPoints[nearest].price));
      hoverDot.setAttribute('visibility', 'visible');

      tipDate.textContent = dataPoints[nearest].date; // 外部数据 → textContent
      tipPrice.textContent = formatPrice(dataPoints[nearest].price);
      tooltip.classList.add('visible');
      tooltip.style.left = `${Math.min(px + 10, rect.width - 90)}px`;
      tooltip.style.top = `${Math.max(0, py - 44)}px`;
    });
  });
  svg.addEventListener('mouseleave', () => {
    hoverLine.setAttribute('visibility', 'hidden');
    hoverDot.setAttribute('visibility', 'hidden');
    tooltip.classList.remove('visible');
  });

  return wrap;
}

/**
 * 导出图表 PNG：SVG → canvas → dataURL → 主进程另存为。
 * @param {HTMLElement} container 视图容器（查找 svg）
 * @param {string} title 商品标题（文件名）
 */
async function exportChartPng(container, title) {
  const svg = container.querySelector('svg[data-chart="price"]');
  if (!svg) return;
  const clone = svg.cloneNode(true);
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
  clone.setAttribute('width', '720');
  clone.setAttribute('height', '200');

  const svgData = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(new XMLSerializer().serializeToString(clone))}`;
  const img = new Image();
  img.onload = async () => {
    const canvas = document.createElement('canvas');
    canvas.width = 720 * 2;
    canvas.height = 200 * 2;
    const ctx2d = canvas.getContext('2d');
    ctx2d.fillStyle = '#ffffff';
    ctx2d.fillRect(0, 0, 720, 200);
    ctx2d.scale(2, 2);
    ctx2d.drawImage(img, 0, 0, 720, 200);
    const name = `pricelens-${(title || 'chart').slice(0, 30)}-${new Date().toISOString().slice(0, 10)}.png`;
    const res = await window.priceLens.exportPng(canvas.toDataURL('image/png'), name);
    if (res && res.ok) showToast('已导出 PNG');
    else if (res && res.cancelled) showToast('已取消导出');
    else showToast('导出失败');
  };
  img.onerror = () => showToast('导出失败');
  img.src = svgData;
}