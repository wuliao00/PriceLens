/**
 * 盯价目标的纯状态迁移：把「一次查价结果」变成「目标状态 + 是否新触发提醒」。
 * -------------------------------------------------------------------------
 * 抽成纯函数是为了能单测“到价只提醒一次、回升 2% 再武装”这条规则，
 * 不必依赖 Electron 通知或真实网络。IPC 层只负责取数与发通知。
 */
'use strict';

/**
 * @param {object} watch 盯价目标
 * @param {{current?:number, lowest?:number}} history 一次查价结果
 * @param {number} [now]
 * @returns {{updated:object, alert:boolean}}
 */
function nextWatchState(watch, history, now = Date.now()) {
  const current = Number(history?.current);
  if (!(current > 0)) {
    return {
      updated: { ...watch, lastChecked: now, lastError: '当前价暂不可得' },
      alert: false,
    };
  }

  const lowest = Number(history?.lowest);
  const hit = current <= watch.target;
  const alert = hit && !watch.notified;
  // 到价后 notified=true；只有回升到目标价 102% 以上才重新武装。
  const notified = hit ? true : (current > watch.target * 1.02 ? false : watch.notified);

  return {
    updated: {
      ...watch,
      current,
      lowest: Number.isFinite(lowest) && lowest > 0 ? lowest : watch.lowest,
      notified,
      alerted: Boolean(watch.alerted || alert),
      lastChecked: now,
      lastError: null,
      lastAlertAt: alert ? now : watch.lastAlertAt || 0,
      lastAlertPrice: alert ? current : (watch.lastAlertPrice ?? null),
      alertCount: Number(watch.alertCount || 0) + (alert ? 1 : 0),
    },
    alert,
  };
}

/** 单次检查失败：只写该目标的 lastError，不动 notified/alerted */
function failedWatchState(watch, error, now = Date.now()) {
  return {
    ...watch,
    lastChecked: now,
    lastError: String(error || '检查失败').slice(0, 200),
  };
}

module.exports = { nextWatchState, failedWatchState };
