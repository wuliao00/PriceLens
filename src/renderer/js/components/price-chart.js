/**
 * Price Chart — SVG 折线图组件
 *
 * Props: { points: [{date, price}], lowest, highest, current }
 * Events: onHover(date, price)
 */

function renderPriceChart(containerId, data) {
  const container = document.getElementById(containerId);
  if (!container) return;

  const { points, lowest, highest, current, isHistoricalLow, isPriceHiked } = data;

  if (!points || points.length === 0) {
    container.innerHTML = '<div style="text-align:center;padding:40px;color:var(--text-tertiary);">暂无历史价格数据</div>';
    return;
  }

  const W = container.clientWidth || 700;
  const H = 220;
  const PAD = { top: 20, right: 20, bottom: 30, left: 50 };
  const plotW = W - PAD.left - PAD.right;
  const plotH = H - PAD.top - PAD.bottom;

  const prices = points.map(p => p.price);
  const minP = Math.min(...prices) * 0.95;
  const maxP = Math.max(...prices) * 1.05;
  const range = maxP - minP || 1;

  // 生成 SVG
  let pathD = '';
  let areaD = '';
  let circlesSvg = '';
  let priceLabels = '';

  points.forEach((pt, i) => {
    const x = PAD.left + (i / (points.length - 1)) * plotW;
    const y = PAD.top + plotH - ((pt.price - minP) / range) * plotH;
    const cmd = i === 0 ? 'M' : 'L';
    pathD += `${cmd} ${x.toFixed(1)},${y.toFixed(1)} `;
    if (i === 0) areaD += `M ${x.toFixed(1)},${H - PAD.bottom} `;
    areaD += `L ${x.toFixed(1)},${y.toFixed(1)} `;
    if (i === points.length - 1) areaD += `L ${x.toFixed(1)},${H - PAD.bottom} Z`;

    // Current point pulse
    if (i === points.length - 1) {
      circlesSvg += `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="5" fill="#0071e3" class="price-current-dot"/>`;
      circlesSvg += `<text x="${x.toFixed(1)}" y="${y - 10}" text-anchor="middle" fill="#1d1d1f" font-size="11" font-weight="600">${formatPrice(pt.price)}</text>`;
    }

    // Lowest price marker
    if (pt.price === lowest) {
      circlesSvg += `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="4" fill="#34c759"/>`;
      priceLabels += `<text x="${x.toFixed(1)}" y="${(y - 6).toFixed(1)}" text-anchor="middle" fill="#34c759" font-size="10">最低 ¥${lowest.toFixed(0)}</text>`;
    }

    // Highest price marker
    if (pt.price === highest) {
      circlesSvg += `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="4" fill="#ff3b30"/>`;
    }
  });

  // Grid lines
  let gridLines = '';
  for (let i = 0; i <= 4; i++) {
    const y = PAD.top + (i / 4) * plotH;
    const val = maxP - (i / 4) * range;
    gridLines += `
      <line x1="${PAD.left}" y1="${y}" x2="${PAD.left + plotW}" y2="${y}" stroke="rgba(0,0,0,0.04)" stroke-width="1"/>
      <text x="${PAD.left - 6}" y="${y + 4}" text-anchor="end" fill="#aeaeb2" font-size="10">¥${val.toFixed(0)}</text>
    `;
  }

  // Date labels
  let dateLabels = '';
  const step = Math.max(1, Math.floor(points.length / 6));
  for (let i = 0; i < points.length; i += step) {
    const x = PAD.left + (i / (points.length - 1)) * plotW;
    const d = points[i].date?.slice(5) || '';
    dateLabels += `<text x="${x.toFixed(1)}" y="${H - 6}" text-anchor="middle" fill="#aeaeb2" font-size="10">${d}</text>`;
  }

  const statusText = isHistoricalLow
    ? '当前价格处于近3个月最低点 ⬇️'
    : isPriceHiked
      ? '⚠️ 当前价格高于近7天均价，疑似先涨后降'
      : '当前价格处于正常区间';

  const statusColor = isHistoricalLow ? 'var(--success)' : isPriceHiked ? 'var(--danger)' : 'var(--text-secondary)';

  container.innerHTML = `
    <svg viewBox="0 0 ${W} ${H}" width="100%" height="${H}" preserveAspectRatio="xMidYMid meet">
      ${gridLines}
      <path d="${areaD}" fill="rgba(0,113,227,0.06)"/>
      <path d="${pathD}" fill="none" stroke="#0071e3" stroke-width="2" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>
      ${circlesSvg}
      ${priceLabels}
      ${dateLabels}
    </svg>
    <div class="chart-legend">
      <span><span class="dot dot-current"></span> 当前价</span>
      <span><span class="dot dot-lowest"></span> 历史最低 ¥${(lowest || 0).toFixed(0)}</span>
      <span><span class="dot dot-highest"></span> 历史最高 ¥${(highest || 0).toFixed(0)}</span>
    </div>
    <div style="text-align:center;margin-top:10px;font-size:12px;color:${statusColor};font-weight:500;">
      ${statusText}
    </div>
  `;
}