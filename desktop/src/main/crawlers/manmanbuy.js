/**
 * 慢慢买爬虫 —— 历史价格（规范 §6.2）
 * -----------------------------------
 *   走 App 端公开接口（apapia-history.manmanbuy.com/HistoryLowest.ashx），
 *   POST JSON { methodName: 'getHistoryTrend', p_url }。
 *   响应字段随版本兼容两代结构：
 *     旧版：bjDate[] + bjPrice[]  新版：singlePriceTimeLine.timeline[]
 *
 * 对外：
 *   getHistory(url) → { current, lowest, highest, points, dateFrom, dateTo, source }
 */
'use strict';

const http = require('../utils/http-client');

/**
 * 兼容解析两代响应结构，产出 [{date, price}] 序列。
 * @param {any} data 接口 JSON
 * @returns {{points: Array<{date:string, price:number}>, lowest:number, highest:number}}
 */
function extractTimeline(data) {
  const points = [];

  // 新版：singlePriceTimeLine.timeline = [{pubDate, price}]
  const timeline = data?.singlePriceTimeLine?.timeline;
  if (Array.isArray(timeline) && timeline.length > 0) {
    for (const p of timeline) {
      const price = Number(p.price);
      if (price > 0) {
        points.push({ date: String(p.pubDate || '').slice(0, 10), price });
      }
    }
  }

  // 旧版：bjDate[] + bjPrice[] 平行数组
  if (points.length === 0 && Array.isArray(data?.bjDate) && Array.isArray(data?.bjPrice)) {
    const n = Math.min(data.bjDate.length, data.bjPrice.length);
    for (let i = 0; i < n; i++) {
      const price = Number(data.bjPrice[i]);
      if (price > 0) points.push({ date: String(data.bjDate[i]).slice(0, 10), price });
    }
  }

  // 降采样：点数过多时按天去重取尾部，曲线仍平滑、文件更小
  const deduped = [];
  for (const p of points) {
    if (deduped.length === 0 || deduped[deduped.length - 1].date !== p.date) deduped.push(p);
  }
  const prices = deduped.map((p) => p.price);
  return {
    points: deduped,
    lowest: prices.length ? Math.min(...prices) : 0,
    highest: prices.length ? Math.max(...prices) : 0,
  };
}

/**
 * 查询商品历史价格。
 * @param {string} url 京东/淘宝商品链接
 * @returns {Promise<{current:number, lowest:number, highest:number,
 *   points:Array<{date:string,price:number}>, dateFrom:string, dateTo:string, source:string}>}
 */
async function getHistory(url) {
  const res = await http.postJSON(
    'https://apapia-history.manmanbuy.com/HistoryLowest.ashx',
    { methodName: 'getHistoryTrend', p_url: url },
    {
      mobileUA: true,
      headers: {
        'Content-Type': 'application/json;charset=UTF-8',
        Referer: 'https://tool.manmanbuy.com/',
        Origin: 'https://tool.manmanbuy.com',
      },
    },
  );

  if (res.status !== 200) throw new Error(`慢慢买接口返回 ${res.status}`);
  let data;
  try {
    data = JSON.parse(res.body);
  } catch {
    throw new Error('慢慢买接口响应解析失败');
  }

  const { points, lowest, highest } = extractTimeline(data);
  const current = Number(data.currentPrice)
    || Number(data.price)
    || (points.length ? points[points.length - 1].price : 0);

  if (!(current > 0) && points.length === 0) {
    throw new Error('慢慢买未收录该商品的历史价格');
  }

  return {
    current,
    lowest: Number(data.lowerPrice) || lowest,
    highest: Number(data.higherPrice) || highest,
    points,
    dateFrom: points.length ? points[0].date : '',
    dateTo: points.length ? points[points.length - 1].date : '',
    source: 'manmanbuy',
    fetchedAt: Date.now(),
  };
}

module.exports = { getHistory };
