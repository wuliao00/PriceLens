/**
 * 购买建议：用历史采样分位给出「可入手 / 中等 / 再等等」的轻量判断。
 * ---------------------------------------------------------------------
 * 不引入预测模型：只回答「当前价在这段历史采样里算不算低」。
 * 样本少于 8 个点不给结论（与 Android 购买建议徽章同一门槛），
 * 避免用两三个点冒充「历史低位」。
 */
'use strict';

const MIN_SAMPLES = 8;
const LOW_PERCENTILE = 0.2;
const HIGH_PERCENTILE = 0.75;

/** 保留两位小数，避免详情文案里出现 4079.1500000000005 */
function round2(n) {
  return Math.round(Number(n) * 100) / 100;
}

/**
 * @param {{current?:number, points?:Array<{date:string, price:number}>}} history
 * @returns {{level:'low'|'mid'|'high'|'unknown', label:string, detail:string,
 *   percentile:number|null, sampleSize:number, lowest:number|null, highest:number|null}}
 */
function advise(history) {
  const points = Array.isArray(history?.points)
    ? history.points.filter((p) => Number(p?.price) > 0)
    : [];
  const sampleSize = points.length;
  const current = Number(history?.current);
  const prices = points.map((p) => Number(p.price));
  const pointLowest = prices.length ? round2(Math.min(...prices)) : null;
  const pointHighest = prices.length ? round2(Math.max(...prices)) : null;
  // 展示口径优先用合并后的历史最低/最高（慢慢买基准可能比曲线采样更低）
  const lowest = Number(history?.lowest) > 0 ? round2(history.lowest) : pointLowest;
  const highest = Number(history?.highest) > 0 ? round2(history.highest) : pointHighest;

  if (!(current > 0) || sampleSize < MIN_SAMPLES) {
    return {
      level: 'unknown',
      label: '样本不足',
      detail: `历史采样只有 ${sampleSize} 个点（少于 ${MIN_SAMPLES} 个），暂不判断购买时机。`,
      percentile: null,
      sampleSize,
      lowest,
      highest,
    };
  }

  // 分位：当前价不高于多少个历史采样点
  const notHigher = prices.filter((p) => p <= current).length;
  const percentile = notHigher / sampleSize;
  const pct = Math.round(percentile * 100);

  let level = 'mid';
  let label = '价格中等';
  let detail = `当前价处于历史采样的 ${pct}% 分位，不算低点，也不是最高。`;
  if (percentile <= LOW_PERCENTILE) {
    level = 'low';
    label = '可以考虑入手';
    detail = current <= lowest
      ? `当前价就是这段历史的样本最低价（${sampleSize} 个采样点）。`
      : `当前价处于历史采样的 ${pct}% 分位，接近历史低位（最低 ¥${lowest}）。`;
  } else if (percentile >= HIGH_PERCENTILE) {
    level = 'high';
    label = '建议再等等';
    detail = current >= highest
      ? `当前价是这段历史的样本最高价（${sampleSize} 个采样点）。`
      : `当前价处于历史采样的 ${pct}% 分位，高于多数历史价格（最低 ¥${lowest}）。`;
  }

  return { level, label, detail, percentile, sampleSize, lowest, highest };
}

module.exports = { advise, MIN_SAMPLES, LOW_PERCENTILE, HIGH_PERCENTILE };
