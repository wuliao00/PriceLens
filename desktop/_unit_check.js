/**
 * 桌面端解析器单元自检（node 直接运行，不参与构建 / CI）
 * -------------------------------------------------------
 * 用法（在 desktop/ 目录）：
 *   node _unit_check.js
 *
 * 与 Android 侧 `app/src/test/java/.../QueryRelevanceTest`、`.../CandidateRankingTest`
 * 使用**同一批夹具**（`../app/src/test/resources/fixtures/`）+ 同名同用例，
 * 保证两端规则不漂移。覆盖：
 *   - utils/relevance.js 相关性规则（分段+顺序+间隔、配件/图书/其它品牌/其它机型）
 *   - utils/ranking.js 跨源候选打分（实况池子的 argmax 必须与 Android 一致）
 *   - crawlers/gwdang.js 只提取显式券、绝不按价差编造
 *   - crawlers/jd.js 移动页 _itemInfo 解析 + 风控页拒绝
 */
'use strict';

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const { isRelevant, querySegments, matchesSegments } = require('./src/main/utils/relevance');
const ranking = require('./src/main/utils/ranking');
const { extractCoupon } = require('./src/main/crawlers/gwdang');
const { parseItemPage } = require('./src/main/crawlers/jd');
const { productFromCandidate } = require('./src/main/crawlers/index');
const linkstars = require('./src/main/crawlers/linkstars');

const FIXTURES = path.join(__dirname, '..', 'app', 'src', 'test', 'resources', 'fixtures');
const fixture = (name) => fs.readFileSync(path.join(FIXTURES, name), 'utf8');

let passed = 0;
let failed = 0;
function check(label, fn) {
  try {
    fn();
    passed += 1;
    console.log(`  ok  ${label}`);
  } catch (e) {
    failed += 1;
    console.error(`  FAIL ${label}\n       ${e.message}`);
    process.exitCode = 1;
  }
}

console.log('== relevance（与 Android QueryRelevanceTest 同用例） ==');
check('手机壳被剔除', () => {
  assert.strictEqual(isRelevant('iPhone 15', '【 iPhone15 Pro 百变磁吸背盖手机壳 】 新品哈利波特正版苹果手机壳'), false);
});
check('镜头膜被剔除', () => {
  assert.strictEqual(isRelevant('iPhone 15', '闪魔苹果16镜头膜17镜头膜适用iphone15promax镜头膜14镜头膜13圈plus手机膜'), false);
});
check('英文说明书被剔除', () => {
  assert.strictEqual(isRelevant('iPhone 15', 'iPhone 15 Pro Max User Guide: Unlocking the Power and Ease'), false);
  assert.strictEqual(isRelevant('iPhone 15', "iPhone 15 Pro Mastery: A Comprehensive Beginner's Guide"), false);
});
check('其它品牌被剔除', () => {
  assert.strictEqual(isRelevant('iPhone 15', 'PLUS会员 国家补贴 小米 15 5G 手机 黑色 16GB+512GB'), false);
});
check('其它机型被剔除', () => {
  assert.strictEqual(isRelevant('iPhone 15', 'Apple iPhone 17 5G手机 8GB+256GB 黑色'), false);
  assert.strictEqual(isRelevant('iPhone 15', '国家补贴 Apple iPhone Air 5G手机'), false);
});
check('热榜无关商品被剔除', () => {
  assert.strictEqual(isRelevant('iPhone 15', 'adidas Originals Superstar 板鞋 轻便贝壳头薄底舒适百搭耐磨复古 白色/金色/纯黑色'), false);
});
check('匹配商品保留', () => {
  assert.strictEqual(isRelevant('iPhone 15', 'Apple iPhone 15 5G手机 512GB 黑色'), true);
});
check('配件关键词查询保留配件', () => {
  assert.strictEqual(isRelevant('iPhone 15 手机壳', '适用 iPhone 15 手机壳 磁吸透明保护套 防摔'), true);
});
check('全角/大小写归一化', () => {
  assert.strictEqual(isRelevant('ＩＰＨＯＮＥ １５', 'Apple iPhone15 5G 手机'), true);
});

// ===== 2026-09-28：分段 + 顺序 + 间隔上限 G=12（Android 同规则，修 "oppo x8s" 被误杀） =====
check('spaced model query matches title with words between segments', () => {
  const title = 'OPPO Find X8s 12GB+256GB 月光白';
  assert.strictEqual(isRelevant('oppo x8s', title), true);
  assert.strictEqual(isRelevant('OPPO  X8S', title), true);
  assert.deepStrictEqual(querySegments('oppo x8s'), ['oppo', 'x8s']);
  assert.deepStrictEqual(querySegments('Mate80'), ['mate80']);
  assert.strictEqual(matchesSegments('x8s plus', 'OPPO Find X8s Plus 5G手机'), true);
});
check('model aliases written with or without space are interchangeable', () => {
  assert.strictEqual(isRelevant('mate 80', '华为 Mate80 麒麟9020 鸿蒙智能手机'), true);
  assert.strictEqual(isRelevant('Mate80', '华为 Mate 80 手机 12GB+512GB 雪域白'), true);
  assert.strictEqual(isRelevant('MATE 80', '华为 Mate 80 手机 12GB+512GB 云杉绿'), true);
  assert.strictEqual(isRelevant('iphonese2', 'Apple iPhone SE2 64GB'), true);
});
check('segment order must match title order', () => {
  // 实测巧合串：EG80MATE33S 含 mate + 80 但顺序颠倒（海尔洗衣机）
  assert.strictEqual(isRelevant('mate 80', '海尔(Haier)滚筒洗衣机 超薄平嵌EG80MATE33S 全自动'), false);
  assert.strictEqual(isRelevant('mate 80', '80 元券 MATE 30 手机'), false);
  assert.strictEqual(isRelevant('mate 80', 'MATE 系列 80 周年纪念版'), true);
});
check('gap beyond the limit is rejected', () => {
  assert.strictEqual(matchesSegments('oppo x8s', 'OPPO Find X8s 12GB+256GB'), true);
  assert.strictEqual(matchesSegments('oppo x8s', 'OPPO Find N6 折叠屏 官方旗舰正品店 X8s'), false);
  assert.strictEqual(matchesSegments('oppo x8s', 'OPPOX8S 5G手机', 0), true); // G=0 = 旧的整串连续行为
  assert.strictEqual(matchesSegments('oppo x8s', 'OPPO Find X8S 5G手机', 0), false);
});
check('segment rule still blocks other models', () => {
  assert.strictEqual(isRelevant('小米 15', '小米 14 手机 12GB+256GB'), false);
  assert.strictEqual(isRelevant('oppo x8s', 'OPPO Find X8 Pro 5G手机 16GB+512GB'), false);
  assert.strictEqual(isRelevant('watch s9', '三星 Galaxy Watch 7 智能手表'), false);
});
check('extended accessory words are rejected', () => {
  assert.strictEqual(isRelevant('华为 Mate 80', '怎么挑适用华为mate50荣耀80Pro GT无线蓝牙骨传导耳机不入耳运动'), false);
  assert.strictEqual(isRelevant('mate 80', '华为 Mate 80 手机 充电宝 10000mAh'), false);
  assert.strictEqual(isRelevant('mate 80', '华为 Mate 80 手机 移动电源 20000mAh'), false);
  assert.strictEqual(isRelevant('mate 80', '华为 Mate 80 手机 电池 3000mAh 原装'), false);
  assert.strictEqual(isRelevant('mate 80', '华为 Mate 80 手机 保护贴 3片装'), false);
  assert.strictEqual(isRelevant('mate 80', '华为 Mate 80 手机膜 高清防窥'), false);
});
check('accessory query itself keeps those accessories', () => {
  assert.strictEqual(isRelevant('耳机', '索尼 WF-1000XM5 无线蓝牙降噪耳机'), true);
  assert.strictEqual(isRelevant('充电宝', '安克 充电宝 移动电源 12000mAh'), true);
  assert.strictEqual(isRelevant('电池', '耐杰 电池 适用华为 P30'), true);
  assert.strictEqual(isRelevant('手机膜', '闪魔 手机膜 高清防窥 适用苹果'), true);
});
check('series suffix is never filtered out', () => {
  // Pro/Max 只降权不过滤
  assert.strictEqual(isRelevant('mate 80', '华为 Mate 80 Pro Max 手机 16GB+1TB 极昼金'), true);
  assert.strictEqual(isRelevant('x8s', 'OPPO Find X8s+ 5G手机 12GB+512GB'), true);
  assert.strictEqual(isRelevant('iphone 15', 'Apple iPhone 15 Pro 5G手机 256GB'), true);
});

// ===== 2026-09-28：跨源候选打分（Android CandidateRankingTest 同权重同期望值） =====
console.log('== ranking 跨源候选打分（与 Android CandidateRankingTest 同用例） ==');
check('category word is the dominant positive signal', () => {
  const plain = ranking.score('某某商品 12GB+512GB', 3000, 3000, 'mate 80');
  const withCategory = ranking.score('华为 Mate 80 手机 12GB+512GB', 3000, 3000, 'mate 80');
  assert.strictEqual(withCategory - plain, ranking.WEIGHT_CATEGORY);
});
check('spec completeness and subsidy add up', () => {
  assert.strictEqual(
    ranking.score('国家补贴：OPPO Find X8s+ 5G手机 12GB+512GB', 2860, 2860, 'oppo x8s'),
    ranking.WEIGHT_CATEGORY + ranking.WEIGHT_SPEC + ranking.WEIGHT_SUBSIDY,
  );
  assert.strictEqual(ranking.isSpecComplete('oppofindx8s5g手机12+256gb'), true);
  assert.strictEqual(ranking.isSpecComplete('华为mate80promax手机16gb+1tb'), true);
  assert.strictEqual(ranking.isSpecComplete('华为mate80学生商务通用手机6.75英寸直屏旗舰'), false);
});
check('secondhand and multi model listings are penalised', () => {
  const row = 'Apple iPhone15/14/13/12/苹果 15/14/13/12 全网通激活无使用 黑色';
  assert.strictEqual(ranking.score(row, 4248, 4248, 'iPhone 15'), -4.0);
  const diff = ranking.score('iPhone 15 手机 95新', 4000, 4000, 'iPhone 15') -
    ranking.score('iPhone 15 手机 12GB+256GB', 4000, 4000, 'iPhone 15');
  assert.strictEqual(diff + ranking.WEIGHT_SPEC, ranking.WEIGHT_SECONDHAND);
});
check('price band pulls outliers away from the median', () => {
  const low = ranking.score('华为 Mate 80 手机 12GB+512GB', 2000, 4000, 'mate 80');
  const onMedian = ranking.score('华为 Mate 80 手机 12GB+512GB', 4000, 4000, 'mate 80');
  const high = ranking.score('华为 Mate 80 手机 12GB+512GB', 8000, 4000, 'mate 80');
  assert.ok(Math.abs((low - onMedian) - ranking.WEIGHT_PRICE_BAND * Math.LN2) < 1e-9);
  assert.ok(Math.abs(low - high) < 1e-9); // 偏离方向对称
});
check('series suffix only downweights, never filters out of the pool', () => {
  const pool = [
    { title: '华为 Mate 80 手机 12GB+512GB 雪域白', price: 4079.15 },
    { title: '华为 Mate 80 Pro 手机 12GB+512GB 晨曦金', price: 4079.15 },
  ];
  const ranked = ranking.rank('mate 80', pool);
  assert.strictEqual(ranked.length, 2);
  assert.strictEqual(ranked[0].item.title, pool[0].title);
  assert.ok(Math.abs((ranked[1].score - ranked[0].score) - ranking.WEIGHT_SERIES_SUFFIX) < 1e-9);
});
check('missing brand in title costs one point', () => {
  assert.strictEqual(ranking.score('全网通 5G 15', 3000, 0, '小米 15'), ranking.WEIGHT_BRAND_MISSING);
});
check('rank is stable on ties so dangdang keeps priority', () => {
  const pool = [
    { title: '当当同款 手机 12GB+256GB', price: 3000 },
    { title: '值得买同款 手机 12GB+256GB', price: 3000 },
  ];
  assert.strictEqual(ranking.rank('手机 12gb', pool)[0].item.title, pool[0].title);
});
check('live pools pick the expected candidate instead of the first priced row', () => {
  // 夹具 = 2026-09-28 当当 + 值得买实况；期望值与 Android CandidateRankingTest 一致
  const expected = {
    'oppo x8s': 2860.0, // 旧口径：相关性被误杀 → 无候选
    'OPPO Find X8s': 2860.0, // 旧口径：¥3399.00
    x8s: 2860.0, // 旧口径：¥108.80 纸品
    'mate 80': 4079.15, // 旧口径：¥8840.95 渠道价
    Mate80: 4572.15, // 旧口径：¥8840.95 渠道价
    '华为 Mate 80': 4279.0, // 旧口径：¥4249.15
    'iPhone 15': 7561.01, // 旧口径：¥4248.00 二手混卖
  };
  const groups = JSON.parse(fixture('live_candidate_pools.json')).groups;
  assert.strictEqual(groups.length, Object.keys(expected).length);
  for (const group of groups) {
    const pool = group.items
      .filter((it) => it.price > 0 && isRelevant(group.keyword, it.title))
      .map((it) => ({ title: it.title, price: it.price }));
    assert.ok(pool.length > 0, `${group.keyword} 过滤后候选池为空`);
    const best = ranking.best(group.keyword, pool);
    assert.ok(Math.abs(best.price - expected[group.keyword]) < 0.01,
      `${group.keyword} 期望 ¥${expected[group.keyword]} 实得 ¥${best.price}（${best.title}）`);
  }
});
check('live mate 80 pool keeps Pro/Max variants but ranks them below', () => {
  const group = JSON.parse(fixture('live_candidate_pools.json')).groups.find((g) => g.keyword === 'mate 80');
  const pool = group.items
    .filter((it) => it.price > 0 && isRelevant('mate 80', it.title))
    .map((it) => ({ title: it.title, price: it.price }));
  const ranked = ranking.rank('mate 80', pool);
  assert.strictEqual(ranked.length, pool.length, '打分不过滤任何条目');
  assert.ok(Math.abs(ranked[0].score - 3.0) < 0.01);
  assert.ok(Math.abs(ranked[0].item.price - 4079.15) < 0.01);
  const proMax = ranked.find((r) => /pro max/i.test(r.item.title));
  assert.ok(proMax && proMax.score < ranked[0].score);
  const channel = ranked.find((r) => Math.abs(r.item.price - 8840.95) < 0.01);
  assert.ok(channel && channel.score < ranked[0].score, '¥8840.95 渠道价应排在候选之后');
});

console.log('== gwdang 券提取（只认显式券） ==');
check('满减券', () => {
  assert.deepStrictEqual(extractCoupon('此款目前活动售价19.87元，下单领取满15减8元优惠券，实付低至7.35元'), { amount: 8, threshold: 15, kind: '满15减8' });
  assert.deepStrictEqual(extractCoupon('活动售价6999元，下单领取满5000减500元优惠券'), { amount: 500, threshold: 5000, kind: '满5000减500' });
});
check('无门槛券', () => {
  assert.deepStrictEqual(extractCoupon('下单领取5元优惠券'), { amount: 5, threshold: 0, kind: '5元券' });
});
check('价差不再被当作券（旧实现的编造点）', () => {
  assert.strictEqual(extractCoupon('目前活动售价5599元，实付低至5099元'), null);
  assert.strictEqual(extractCoupon('原价 3.9 元，实付 0.9 元'), null);
});

console.log('== jd 商品页解析（夹具 = 2026-09-25 实况） ==');
check('_itemInfo 商品名与主图', () => {
  const page = parseItemPage(fixture('jd_m_item.html'), '5089253');
  assert.ok(page, '应解析出商品');
  assert.ok(page.title.startsWith('Apple/苹果 iPhone X'), `标题异常: ${page.title}`);
  assert.strictEqual(page.image, 'https://m.360buyimg.com/mobilecms/s750x750_jfs/t10675/253/1344769770/66891/92d54ca4/59df2e7fN86c99a27.jpg');
});
check('风控页（京东验证）被拒绝', () => {
  assert.strictEqual(parseItemPage(fixture('jd_risk.html'), '100012043978'), null);
});

// F1（2026-09-29）：星罗是「历史低价榜」，couponPrice 是历史位置、不是现价。
// 与 Android `app/src/test/java/com/pricelens/domain/WatchPriceSourceTest` 同一批数值、同一条规则。
console.log('== 星罗价格来源（F1，与 Android WatchPriceSourceTest 同用例） ==');
check('在售价优先于券后历史低价，且来源标成 LINKSTARS_LIST', () => {
  const s = linkstars.toPriceSample({ listPrice: 5999, couponPrice: 4999 });
  assert.strictEqual(s.price, 5999);
  assert.strictEqual(s.source, 'LINKSTARS_LIST');
  assert.strictEqual(s.live, true);
  assert.ok(linkstars.curveWorthy(s), '在售价才有资格补今日的曲线点');
});
check('只有在售价缺失才退到券后历史低价，且只作参考', () => {
  const s = linkstars.toPriceSample({ listPrice: 0, couponPrice: 4999 });
  assert.strictEqual(s.price, 4999);
  assert.strictEqual(s.source, 'LINKSTARS_HISTORY_LOW');
  assert.strictEqual(s.live, false);
  assert.strictEqual(linkstars.curveWorthy(s), null, '历史低价参考不得写进历史曲线（否则等于自己造历史）');
});
check('榜单两个字段都没有 → 本轮没有价格', () => {
  assert.strictEqual(linkstars.toPriceSample({ listPrice: 0, couponPrice: 0 }), null);
  assert.strictEqual(linkstars.toPriceSample(null), null);
});

console.log('== productFromCandidate（原价语义与 Android ProductCandidateResolver 对齐） ==');
check('爆料候选只带一个价格时，原价必须是"未知"(0)，不得等于现价', () => {
  const p = productFromCandidate('mate 80', {
    title: 'HUAWEI Mate 80 12GB+256GB', price: 4099, image: '', url: 'https://a', mall: '京东',
  });
  assert.strictEqual(p.price, 4099);
  assert.strictEqual(p.originalPrice, 0, '源里没有原价字段 → 造一个等于现价的"原价"就是假数据');
  assert.notStrictEqual(p.originalPrice, p.price);
});
check('没有候选时给出全零占位，不抛异常', () => {
  const p = productFromCandidate('mate 80', null);
  assert.strictEqual(p.title, 'mate 80');
  assert.strictEqual(p.price, 0);
  assert.strictEqual(p.originalPrice, 0);
  assert.strictEqual(p.url, '');
});

console.log(`\n通过 ${passed} 项，失败 ${failed} 项${process.exitCode ? '（存在失败项）' : '，全部通过'}`);
