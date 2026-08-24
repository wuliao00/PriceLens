/**
 * 手写 SVG 价格曲线（规范 §6.2，禁止 Chart.js/ECharts）
 * ------------------------------------------------------
 *   - 面积渐变 + 折线 + 当前点脉冲动画 + 历史最低虚线
 *   - 悬停 tooltip（日期 + 价格）
 *   - 右键菜单：导出 PNG / 复制当前价格
 *   - 全部用 createElementNS 构建（红线 #4：无 innerHTML 外部数据）
 */
import { svgEl, el, icon, copyText } from '../utils/dom.js';
import { formatPrice, formatPricePlain } from '../utils/format.js';
import { showContextMenu } from './context-menu.js';
import { showToast } from './toast.js';

/**
 * 渲染价格视图：统计行 + 图表 + 盯价设置行。
 * @param {HTMLElement} container
 * @param {{history: {current:number, lowest:number, highest:number,
 *   points:Array<{date:string, price:number}>} | null,
 *   product: object, watch: object|null,
 *   onSetWatch: (target:number)=>void, onClearWatch: ()=>void}} ctx
 */
export function renderPriceView(container, ctx) {
  const { history, product, watch, onSetWatch, onClearWatch } = ctx;

  if (!history || !history.points || history.points.length < 2) {
    container.appendChild(renderEmpty());
    return;
  }

  const prices = history.points.map((p) => p.price);
  const avg7d = prices.slice(-7).reduce((s, p) => s + p, 0) / Math.min(7, prices.length);

  container.appendChild(
    el('div', { class: 'card' },
      el('h3', { class: 'card-title', text: '历史价格趋势' }),
      el('div', { class: 'stats-row' },
        statItem('当前价', formatPrice(history.current)),
        statItem('历史最低', formatPrice(history.lowest), 'var(--success)'),
        statItem('历史最高', formatPrice(history.highest), 'var(--danger)'),
        statItem('7 日均价', formatPrice(Math.round(avg7d)))),
      el('div', {
        class: 'chart-wrap selectable',
        on: {
          contextmenu: (e) => {
            e.preventDefault(); // 显式传参（红线 #5）
            showContextMenu(e.clientX, e.clientY, [
              {
                label: '导出 PNG',
                icon: 'external',
                action: () => exportChartPng(container, product.title),
              },
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
      }, renderPriceChart(history.points)),
      renderWatchRow(watch, onSetWatch, onClearWatch),
    ),
  );
}

/** 统计项 */
function statItem(label, value, color) {
  return el('div', { class: 'stat-item' },
    el('div', { class: 's-label', text: label }),
    el('div', { class: 's-value', text: value, style: color ? { color } : {} }));
}

/** 空状态 */
function renderEmpty() {
  return el('div', { class: 'card empty-state' },
    icon('chart', 48),
    el('div', { class: 'e-title', text: '暂无历史价格数据' }),
    el('div', {
      class: 'e-desc',
      text: '慢慢买可能未收录该商品，或数据源暂时限流。可尝试粘贴京东/淘宝商品链接重新搜索。',
    }));
}

/** 盯价设置行 */
function renderWatchRow(watch, onSetWatch, onClearWatch) {
  const input = el('input', {
    class: 'field-input', type: 'number', min: '0', step: '0.01',
    placeholder: '目标价 ¥',
    value: watch && watch.target ? String(watch.target) : '',
  });
  const row = el('div', { class: 'watch-row' },
    icon('bell', 16),
    el('span', { class: 'fs-caption text-secondary', text: '盯价提醒：低于目标价时系统通知' }),
    input,
    el('button', {
      class: 'btn btn--primary',
      on: {
        click: () => {
          const target = Number(input.value);
          if (target > 0) onSetWatch(target);
        },
      },
    }, '开启提醒'),
    watch ? el('button', { class: 'btn', on: { click: onClearWatch } }, '取消提醒') : null,
  );
  return row;
}

/* ══════════ 图表本体 ══════════ */

/**
 * 构建价格折线图（SVG，createElementNS）。
 * @param {Array<{date:string, price:number}>} dataPoints
 * @returns {SVGElement}
 */
export function renderPriceChart(dataPoints) {
  const W = 720;
  const H = 200;
  const PAD = { top: 20, right: 16, bottom: 30, left: 56 };

  const prices = dataPoints.map((d) => d.price);
  const min = Math.min(...prices);
  const max = Math.max(...prices);
  const range = max - min || 1;

  const x = (i) => PAD.left + (i / (dataPoints.length - 1)) * (W - PAD.left - PAD.right);
  const y = (p) => PAD.top + (1 - (p - min) / range) * (H - PAD.top - PAD.bottom);

  // 解析设计令牌（SVG 属性里 CSS 变量部分浏览器不可靠，取计算值）
  const css = getComputedStyle(document.documentElement);
  const accent = css.getPropertyValue('--accent').trim() || '#0071e3';
  const success = css.getPropertyValue('--success').trim() || '#34c759';
  const textColor = css.getPropertyValue('--text-tertiary').trim() || '#aeaeb2';

  const svg = svgEl('svg', { width: '100%', viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: 'none', class: 'price-chart-svg' });
  svg.dataset.chart = 'price';

  /* 渐变定义 */
  const defs = svgEl('defs', {});
  const grad = svgEl('linearGradient', { id: 'areaGrad', x1: '0', y1: '0', x2: '0', y2: '1' });
  grad.appendChild(svgEl('stop', { offset: '0%', 'stop-color': accent, 'stop-opacity': '0.15' }));
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

  svg.appendChild(svgEl('path', { d: areaD, fill: 'url(#areaGrad)' }));
  svg.appendChild(svgEl('path', {
    d: pathD, fill: 'none', stroke: accent,
    'stroke-width': '2', 'stroke-linecap': 'round', 'stroke-linejoin': 'round',
  }));

  /* 当前价格点：脉冲动画 */
  const lastX = x(dataPoints.length - 1);
  const lastY = y(prices[prices.length - 1]);
  const dot = svgEl('circle', { cx: lastX, cy: lastY, r: '4', fill: accent });
  const animR = svgEl('animate', { attributeName: 'r', values: '4;7;4', dur: '2s', repeatCount: 'indefinite' });
  const animO = svgEl('animate', { attributeName: 'opacity', values: '1;0.4;1', dur: '2s', repeatCount: 'indefinite' });
  dot.appendChild(animR);
  dot.appendChild(animO);
  svg.appendChild(dot);

  /* 历史最低虚线 + 标注 */
  svg.appendChild(svgEl('line', {
    x1: PAD.left, x2: W - PAD.right, y1: y(min), y2: y(min),
    stroke: success, 'stroke-width': '1', 'stroke-dasharray': '4,4',
  }));
  svg.appendChild(Object.assign(svgEl('text', {
    x: W - PAD.right, y: y(min) - 4, 'text-anchor': 'end',
    'font-size': '10', fill: success,
  }), { textContent: `历史最低 ¥${min}` }));

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
  const tooltip = el('div', { class: 'chart-tooltip' });
  wrap.appendChild(tooltip);

  svg.addEventListener('mousemove', (e) => {
    const rect = svg.getBoundingClientRect();
    const ratio = (e.clientX - rect.left) / rect.width;
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

    tooltip.textContent = ''; // 重建：外部数据 → DOM API
    tooltip.appendChild(el('div', { class: 't-date', text: dataPoints[nearest].date }));
    tooltip.appendChild(el('div', { class: 't-price', text: formatPrice(dataPoints[nearest].price) }));
    tooltip.classList.add('visible');
    tooltip.style.left = `${Math.min(px + 10, rect.width - 90)}px`;
    tooltip.style.top = `${Math.max(0, py - 44)}px`;
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
