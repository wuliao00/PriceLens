/**
 * 桌面端爬虫实况自检（人工运行，不参与构建 / CI）
 * ------------------------------------------------
 * 用法（在 desktop/ 目录）：
 *   node _crawler_check.js [关键词] [京东SKU]
 * 例：
 *   node _crawler_check.js "iPhone 15"
 *   node _crawler_check.js "小米15" 5089253
 *
 * 逐源调用真实爬虫（与 IPC 层同一批函数），打印：
 *   - 各源返回条数 / 前几条摘要（含价格）
 *   - 失败原因（反爬拦截 / 通道不可用 / 结构变更）
 * 用于发布前的"接口内容是否准确"巡检：搜索关键词不应出现配件/其它品牌/热榜商品。
 */
'use strict';

const smzdm = require('./src/main/crawlers/smzdm');
const gwdang = require('./src/main/crawlers/gwdang');
const jd = require('./src/main/crawlers/jd');
const manmanbuy = require('./src/main/crawlers/manmanbuy');
const { isRelevant } = require('./src/main/utils/relevance');

const KEYWORD = process.argv[2] || 'iPhone 15';
const SKU = process.argv[3] || '5089253';

function line(t) {
  console.log(`\n===== ${t} =====`);
}

async function trySource(name, fn) {
  try {
    const out = await fn();
    return out;
  } catch (e) {
    console.log(`[${name}] 失败: ${e.message}`);
    return null;
  }
}

(async () => {
  console.log(`关键词: ${KEYWORD}`);

  line('值得买 爆料（已过相关性过滤）');
  const sm = await trySource('smzdm', () => smzdm.searchDeals(KEYWORD));
  if (sm) {
    console.log(`条数: ${sm.deals.length}`);
    for (const d of sm.deals.slice(0, 5)) {
      const ok = isRelevant(KEYWORD, d.title);
      console.log(`  ${ok ? 'OK ' : '!! '} ¥${d.price ?? '-'}  ${d.title.slice(0, 56)}`);
    }
    if (sm.deals.some((d) => !isRelevant(KEYWORD, d.title))) {
      console.log('  ⚠ 存在未过滤的不相关条目（不应出现）');
    }
  }

  line('找券（只认显式券文案）');
  const cp = await trySource('gwdang', () => gwdang.getCoupons('', KEYWORD));
  if (cp) {
    console.log(`券条数: ${cp.coupons.length} | currentPrice=${cp.currentPrice} finalPrice=${cp.finalPrice}`);
    for (const c of cp.coupons.slice(0, 5)) {
      console.log(`  ¥${c.amount} 满${c.threshold}可用  ${c.title.slice(0, 50)}`);
    }
  }

  line(`京东商品（SKU ${SKU}）`);
  const prod = await trySource('jd', () => jd.getProduct(SKU));
  if (prod) {
    console.log(`标题: ${prod.title}`);
    console.log(`价格: ${prod.price || '(不可得)'} 原价: ${prod.originalPrice || '-'} 来源: ${prod.priceSource}`);
    console.log(`图片: ${prod.image || '(无)'}`);
    if (prod.title.includes('京东验证')) console.log('  ⚠ 标题疑似风控页（不应出现）');
  }

  line('慢慢买 历史价（公开接口已下线，无 Cookie 时预期为空）');
  const hist = await trySource('manmanbuy', () => manmanbuy.getHistory(`https://item.jd.com/${SKU}.html`));
  if (hist) {
    console.log(`采样点: ${hist.points.length} 当前: ${hist.current} 最低: ${hist.lowest} 最高: ${hist.highest} 来源: ${hist.source}`);
  }

  console.log('\n自检完成。');
})().catch((e) => {
  console.error('自检异常:', e);
  process.exitCode = 1;
});
