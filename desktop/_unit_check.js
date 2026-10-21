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
 *   - crawlers/shihuo.js 识货 m 站解析（null/错误结构/国补/相关性）
 *   - utils/content-risk.js 商单/夸大词库、联合投稿、否定语境豁免
 *   - crawlers/bilibili.js 搜索响应解析（业务码 + risk 注入）
 *   - cache/storage.js v2.2 单条 watch → v2.3 watches[] 迁移
 *   - utils/price-advice.js 历史分位购买建议（样本门槛/低位/中位/高位）
 *   - utils/watch-state.js 盯价状态机（到价一次、回升 2% 再武装、失败隔离、提醒历史）
 *   - utils/platform-compare.js 多平台聚合（平台归一/最低价/价差/空池）
 *   - utils/backup.js 盯价目标备份（不含凭证/运行态）、校验、按 URL 合并与上限
 *   - utils/deep-link.js pricelens:// 深链只接受 http(s)
 *   - utils/android-backup.js Android 备份 ↔ 桌面盯价（平台支持/往返/非法 schema）
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
const { parsePriceText } = require('./src/main/crawlers/smzdm');
const { parseSearchPage: parseShihuoPage } = require('./src/main/crawlers/shihuo');
const { parseSearchResponse: parseBiliSearch } = require('./src/main/crawlers/bilibili');
const { assess: assessContentRisk } = require('./src/main/utils/content-risk');
const { advise } = require('./src/main/utils/price-advice');
const { normalizeWatches, newWatchId } = require('./src/main/cache/storage');
const { nextWatchState, failedWatchState } = require('./src/main/utils/watch-state');
const { comparePlatforms, mallOf } = require('./src/main/utils/platform-compare');
const { buildBackup, parseBackup, mergeBackup } = require('./src/main/utils/backup');
const { findDeepLink, parseDeepLink } = require('./src/main/utils/deep-link');
const {
  androidBackupToWatches, watchesToAndroidBackup, desktopUrlToAndroidTarget, androidTargetToUrl,
} = require('./src/main/utils/android-backup');

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

// ===== 识货 m 站搜索解析（与 Android ShihuoParserTest 同夹具同用例） =====
console.log('== 识货搜索解析（与 Android ShihuoParserTest 同用例） ==');
check('null body yields empty list instead of junk', () => {
  assert.deepStrictEqual(parseShihuoPage(fixture('shihuo_null.json'), 'iPhone 15'), []);
});
check('error status yields empty list', () => {
  assert.deepStrictEqual(parseShihuoPage(fixture('shihuo_error.json'), 'iPhone 15'), []);
});
check('goods list is parsed with subsidy flag and search page url', () => {
  const items = parseShihuoPage(fixture('shihuo_search.json'), 'iPhone 15');
  assert.strictEqual(items.length, 4);
  const iphone17 = items[0];
  assert.strictEqual(iphone17.goodsId, 7929848);
  assert.strictEqual(iphone17.price, 7499);
  assert.strictEqual(iphone17.hasSubsidy, true);
  assert.strictEqual(iphone17.brand, 'Apple/苹果');
  assert.ok(iphone17.url.includes('shihuo.cn/search/goods'));
  assert.ok(iphone17.url.includes('iPhone'));
});
check('relevance filter drops unrelated hot goods and accessories', () => {
  const items = parseShihuoPage(fixture('shihuo_search.json'), 'iPhone 15');
  const relevant = items.filter((it) => isRelevant('iPhone 15', it.title));
  // 夹具含：iPhone17 Pro（机型不符剔除）、iPhone 15 手机（保留）、
  // Nike 板鞋（热榜，剔除）、"适用 iPhone 15 手机壳"（配件前缀，剔除）
  assert.strictEqual(relevant.length, 1);
  assert.ok(relevant[0].title.includes('iPhone 15'));
  assert.ok(!relevant.some((it) => it.title.includes('Nike') || it.title.includes('手机壳')));
});
check('device query with no matching model yields empty rather than other models', () => {
  const items = parseShihuoPage(fixture('shihuo_search.json'), 'iPhone 15');
  assert.ok(!items.some((it) => isRelevant('iPhone 15', it.title) && it.title.includes('iPhone17')));
});

// ===== 商单/夸大标记（与 Android ContentRiskTest 同用例） =====
console.log('== 商单/夸大标记（与 Android ContentRiskTest 同用例） ==');
check('clean text is not flagged', () => {
  const risk = assessContentRisk('真实测评：这盏台灯连续使用 30 天的体验');
  assert.strictEqual(risk.flagged, false);
  assert.strictEqual(risk.sponsored, false);
  assert.strictEqual(risk.hype, false);
});
check('sponsor word triggers sponsored with matched word', () => {
  const risk = assessContentRisk('本视频是商单，大家理性观看');
  assert.strictEqual(risk.sponsored, true);
  assert.strictEqual(risk.sponsorWord, '商单');
  assert.strictEqual(risk.flagged, true);
  assert.strictEqual(risk.hype, false);
});
check('hype word returns first hit in word list order', () => {
  const risk = assessContentRisk('这款键盘简直天花板，yyds');
  assert.strictEqual(risk.hype, true);
  assert.strictEqual(risk.hypeWord, '天花板'); // 词库顺序："天花板" 在 "yyds" 之前
  assert.strictEqual(risk.sponsored, false);
});
check('matching is case insensitive', () => {
  const risk = assessContentRisk('YYDS 永远的神');
  assert.strictEqual(risk.hypeWord, 'yyds');
  assert.strictEqual(risk.hype, true);
});
check('union video alone counts as sponsored', () => {
  const risk = assessContentRisk('聊聊最近买的手机', true);
  assert.strictEqual(risk.sponsored, true);
  assert.strictEqual(risk.sponsorWord, '联合投稿');
  assert.strictEqual(risk.flagged, true);
});
check('explicit sponsor word wins over union video label', () => {
  const risk = assessContentRisk('感谢金主爸爸的支持', true);
  assert.strictEqual(risk.sponsorWord, '金主');
});
check('sponsor and hype can be flagged together', () => {
  const risk = assessContentRisk('恰饭产品，全网最低价，赶紧入手');
  assert.strictEqual(risk.sponsored, true);
  assert.strictEqual(risk.hype, true);
  assert.strictEqual(risk.sponsorWord, '恰饭');
  assert.strictEqual(risk.hypeWord, '全网最低');
});
check('negated sponsor/hype words are not flagged (desktop-only improvement)', () => {
  // 实况误伤：B 站大量标题写「不恰饭」「无广告」「不是商单」自证独立
  for (const text of ['不恰饭真测评', '无广告，真实体验', '不是商单，自费购买', '拒绝恰饭']) {
    const risk = assessContentRisk(text);
    assert.strictEqual(risk.sponsored, false, `不应把「${text}」判成商单`);
    assert.strictEqual(risk.flagged, false, `不应把「${text}」整条标记`);
  }
  assert.strictEqual(assessContentRisk('不是天花板，只是好用').hype, false);
  assert.strictEqual(assessContentRisk('没有全网最低，但很值').hype, false);
  // 否定词只豁免紧邻的命中；同句里另一次肯定命中仍要标记
  assert.strictEqual(assessContentRisk('不是没有广告，是恰饭').sponsored, true);
  assert.strictEqual(assessContentRisk('无脑冲').hype, true); // 词本身以「无」开头，不算否定
});

// ===== B 站搜索响应解析：业务码与 risk 注入 =====
console.log('== B 站搜索解析（业务码 + 内容风险注入） ==');
check('parse attaches risk from title/tags/union flag', () => {
  const body = JSON.stringify({
    code: 0,
    data: {
      result: [
        {
          title: '<em class="keyword">iPhone 15</em> 真实评测',
          author: 'UP-A', play: 1234, duration: '08:00',
          pic: '//i0.hdslb.com/bfs/archive/a.jpg', bvid: 'BV1xx411c7mD',
          tag: '数码 商单', is_union_video: 0,
        },
        {
          title: '这款键盘天花板，yyds', author: 'UP-B', play: 1,
          duration: '01:00', pic: '//i0.hdslb.com/bfs/archive/b.jpg',
          bvid: 'BV1yy411c7mE', tag: '', is_union_video: 0,
        },
        {
          title: '随便聊聊最近买的手机', author: 'UP-C', play: 2,
          duration: '02:00', pic: '//i0.hdslb.com/bfs/archive/c.jpg',
          bvid: 'BV1zz411c7mF', tag: '', is_union_video: 1,
        },
      ],
    },
  });
  const videos = parseBiliSearch(body);
  assert.strictEqual(videos.length, 3);
  assert.strictEqual(videos[0].title, 'iPhone 15 真实评测');
  assert.strictEqual(videos[0].pic, 'https://i0.hdslb.com/bfs/archive/a.jpg');
  assert.strictEqual(videos[0].risk.sponsored, true);
  assert.strictEqual(videos[0].risk.sponsorWord, '商单');
  assert.strictEqual(videos[1].risk.hype, true);
  assert.strictEqual(videos[1].risk.hypeWord, '天花板');
  assert.strictEqual(videos[2].risk.sponsorWord, '联合投稿');
  assert.strictEqual(videos[2].risk.flagged, true);
});
check('business code != 0 throws instead of returning empty', () => {
  const body = JSON.stringify({ code: -412, message: '请求被拦截' });
  assert.throws(() => parseBiliSearch(body), /code=-412/);
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

// ---------- 值得买价格高亮：括号说明不许被吞进数字（与安卓 SmzdmParserTest 同用例）----------
console.log('\n== 值得买价格高亮解析（与安卓同用例）==');
check('284元（淘金币可抵37.14元起） → 284', () => assert.strictEqual(parsePriceText('284元（淘金币可抵37.14元起）'), 284));
check('209元（需用券） → 209', () => assert.strictEqual(parsePriceText('209元（需用券）'), 209));
check('226.1元（需用券） → 226.1', () => assert.strictEqual(parsePriceText('226.1元（需用券）'), 226.1));
check('¥1,299.00 → 1299', () => assert.strictEqual(parsePriceText('¥1,299.00'), 1299));
check('认不出 → null', () => {
  assert.strictEqual(parsePriceText('暂无报价'), null);
  assert.strictEqual(parsePriceText(''), null);
});

// ===== 多商品盯价：v2.2 单条 watch 迁移与脏数据清理 =====
console.log('== 盯价列表迁移（v2.2 单条 → v2.3 watches[]） ==');
check('legacy single watch is migrated into watches with an id', () => {
  const list = normalizeWatches([], {
    keyword: 'iPhone 15', url: 'https://item.jd.com/100012043978.html',
    target: 3999, current: 4199, notified: true, since: 1700000000000,
  });
  assert.strictEqual(list.length, 1);
  assert.ok(list[0].id.startsWith('w_'));
  assert.strictEqual(list[0].target, 3999);
  assert.strictEqual(list[0].current, 4199);
  assert.strictEqual(list[0].notified, true);
  assert.strictEqual(list[0].paused, false);
});
check('invalid url / target are dropped; duplicate urls keep the first', () => {
  assert.deepStrictEqual(normalizeWatches([
    { url: 'javascript:alert(1)', target: 10 },
    { url: 'https://item.jd.com/1.html', target: 0 },
  ]), []);
  const dup = normalizeWatches([
    { url: 'https://item.jd.com/1.html', target: 100 },
    { url: 'https://item.jd.com/1.html', target: 200 },
  ], null);
  assert.strictEqual(dup.length, 1);
  assert.strictEqual(dup[0].target, 100);
});
check('new watch ids are unique', () => {
  assert.notStrictEqual(newWatchId(), newWatchId());
});

// ===== 历史分位购买建议 =====
console.log('== 购买建议（历史分位，样本门槛 8） ==');
const advicePoints = Array.from({ length: 10 }, (_, i) => ({
  date: `2026-09-${String(i + 1).padStart(2, '0')}`, price: 100 + i * 10,
}));
check('fewer than 8 samples yields unknown instead of a false conclusion', () => {
  const a = advise({ current: 100, points: advicePoints.slice(0, 7) });
  assert.strictEqual(a.level, 'unknown');
  assert.ok(a.detail.includes('少于 8'));
  assert.strictEqual(a.percentile, null);
});
check('current at sample bottom is low and says so', () => {
  const a = advise({ current: 100, points: advicePoints });
  assert.strictEqual(a.level, 'low');
  assert.strictEqual(a.label, '可以考虑入手');
  assert.ok(a.detail.includes('样本最低价'));
  assert.strictEqual(a.lowest, 100);
  assert.strictEqual(a.highest, 190);
});
check('current around median is mid', () => {
  const a = advise({ current: 145, points: advicePoints });
  assert.strictEqual(a.level, 'mid');
  assert.strictEqual(a.percentile, 0.5);
  assert.strictEqual(a.label, '价格中等');
});
check('current at sample top is high and says so', () => {
  const a = advise({ current: 190, points: advicePoints });
  assert.strictEqual(a.level, 'high');
  assert.strictEqual(a.label, '建议再等等');
  assert.ok(a.detail.includes('样本最高价'));
  assert.strictEqual(a.percentile, 1);
});

// ===== 盯价状态机：到价只提醒一次、回升 2% 再武装 =====
console.log('== 盯价状态机（纯函数） ==');
const watchBase = {
  id: 'w_test', keyword: '测试商品', url: 'https://item.jd.com/1.html',
  target: 100, current: 120, lowest: 90, notified: false, alerted: false,
  paused: false, lastChecked: 0, lastError: null,
};
check('price at target alerts once and latches notified', () => {
  const first = nextWatchState(watchBase, { current: 99, lowest: 95 }, 1000);
  assert.strictEqual(first.alert, true);
  assert.strictEqual(first.updated.notified, true);
  assert.strictEqual(first.updated.alerted, true);
  assert.strictEqual(first.updated.current, 99);
  assert.strictEqual(first.updated.lowest, 95);
  assert.strictEqual(first.updated.alertCount, 1);
  assert.strictEqual(first.updated.lastAlertAt, 1000);
  assert.strictEqual(first.updated.lastAlertPrice, 99);
  const second = nextWatchState(first.updated, { current: 99, lowest: 95 }, 2000);
  assert.strictEqual(second.alert, false, '同价位不应重复通知');
  assert.strictEqual(second.updated.notified, true);
});
check('2 percent rebound rearms; a 1 percent rebound does not', () => {
  const hit = nextWatchState(watchBase, { current: 99, lowest: 95 }, 1000).updated;
  const slight = nextWatchState(hit, { current: 101, lowest: 95 }, 2000).updated;
  assert.strictEqual(slight.notified, true, '101 未超过目标价 102%，不应重新武装');
  const rebound = nextWatchState(slight, { current: 103, lowest: 95 }, 3000);
  assert.strictEqual(rebound.updated.notified, false);
  assert.strictEqual(rebound.alert, false, '回升本身不触发提醒');
  const again = nextWatchState(rebound.updated, { current: 99, lowest: 95 }, 4000);
  assert.strictEqual(again.alert, true, '重新武装后再次到价应再次提醒');
  assert.strictEqual(again.updated.alertCount, 2);
  assert.strictEqual(again.updated.lastAlertAt, 4000);
});
check('invalid current and network failure become per-target lastError', () => {
  const empty = nextWatchState(watchBase, { current: 0 }, 1000);
  assert.strictEqual(empty.alert, false);
  assert.strictEqual(empty.updated.lastError, '当前价暂不可得');
  const failed = failedWatchState(watchBase, '网络错误', 2000);
  assert.strictEqual(failed.lastError, '网络错误');
  assert.strictEqual(failed.lastChecked, 2000);
  assert.strictEqual(failed.notified, false);
});

// ===== 同款多平台对比 =====
console.log('== 同款多平台对比（按平台取最低价） ==');
check('groups by mall, keeps the lowest offer and computes deltas', () => {
  const result = comparePlatforms({ price: 4399 }, [
    { mall: '京东', price: 4199, url: 'https://item.jd.com/1.html', title: '京东低' },
    { mall: '京东', price: 4299, url: 'https://item.jd.com/2.html', title: '京东高' },
    { mall: '天猫', price: 4499, url: 'https://detail.tmall.com/item.htm?id=1', title: '天猫' },
    { price: 3999, url: 'https://mobile.yangkeduo.com/goods.html?goods_id=1', title: '拼多多' },
    { mall: '京东', price: 0, url: 'https://item.jd.com/3.html' },
    { mall: '京东', price: 1, url: 'javascript:alert(1)' },
  ]);
  assert.strictEqual(result.platformCount, 3);
  assert.strictEqual(result.offerCount, 4);
  assert.deepStrictEqual(result.platforms.map((p) => p.mall), ['拼多多', '京东', '淘宝']);
  assert.strictEqual(result.platforms[0].price, 3999);
  assert.strictEqual(result.lowestMall, '拼多多');
  assert.strictEqual(result.spread, 500);
  assert.strictEqual(result.platforms.find((p) => p.mall === '京东').count, 2);
  assert.strictEqual(result.platforms.find((p) => p.mall === '京东').delta, -200);
  assert.strictEqual(result.platforms.find((p) => p.mall === '淘宝').delta, 100);
});
check('mall can be inferred from url and unknown hosts are kept as-is', () => {
  assert.strictEqual(mallOf({ mall: '京东商城', url: '' }), '京东');
  assert.strictEqual(mallOf({ mall: '', url: 'https://item.jd.com/1.html' }), '京东');
  assert.strictEqual(mallOf({ mall: '', url: 'https://mobile.yangkeduo.com/goods.html' }), '拼多多');
  assert.strictEqual(mallOf({ mall: '', url: 'https://shop.example.com/item/1' }), 'shop.example.com');
});
check('unknown current price leaves deltas unknown and empty pool is safe', () => {
  const result = comparePlatforms({ price: 0 }, [
    { mall: '京东', price: 100, url: 'https://item.jd.com/1.html' },
  ]);
  assert.strictEqual(result.current, null);
  assert.strictEqual(result.platforms[0].delta, null);
  const empty = comparePlatforms({ price: 100 }, []);
  assert.strictEqual(empty.platformCount, 0);
  assert.strictEqual(empty.lowest, null);
  assert.strictEqual(empty.spread, null);
});

// ===== 盯价备份/恢复 =====
console.log('== 盯价备份/恢复（只含目标设置，不含凭证与运行态） ==');
check('export keeps only user settings', () => {
  const built = buildBackup([{
    id: 'w1', keyword: '测试商品', url: 'https://item.jd.com/1.html',
    target: 100, paused: true, since: 123,
    current: 90, lowest: 80, notified: true, alerted: true,
    lastChecked: 456, lastError: '网络错误', alertCount: 2, lastAlertAt: 789,
  }], 1700000000000);
  assert.strictEqual(built.app, 'pricelens');
  assert.strictEqual(built.schemaVersion, 1);
  assert.strictEqual(built.watches.length, 1);
  const w = built.watches[0];
  assert.strictEqual(w.target, 100);
  assert.strictEqual(w.paused, true);
  for (const runtime of ['current', 'lowest', 'notified', 'alerted', 'lastChecked', 'lastError', 'alertCount', 'lastAlertAt']) {
    assert.ok(!(runtime in w), `运行态字段 ${runtime} 不应进入备份`);
  }
  assert.ok(!JSON.stringify(built).toLowerCase().includes('cookie'));
  assert.ok(!JSON.stringify(built).toLowerCase().includes('apikey'));
});
check('invalid/old/empty backups are rejected with useful errors', () => {
  assert.throws(() => parseBackup('not json'), /不是有效的 JSON/);
  assert.throws(() => parseBackup(JSON.stringify({ schemaVersion: 99, watches: [] })), /不支持的备份版本/);
  assert.throws(() => parseBackup(JSON.stringify({ schemaVersion: 1, watches: [] })), /没有可导入/);
});
check('merge updates same URL, adds new targets and caps at 20', () => {
  const existing = [
    { id: 'a', keyword: 'A', url: 'https://item.jd.com/1.html', target: 100, current: 120, lowest: 90, notified: true, alerted: true },
    { id: 'b', keyword: 'B', url: 'https://item.jd.com/2.html', target: 200, current: 210, lowest: 190 },
  ];
  const imported = [
    { id: 'old-a', keyword: 'A2', url: 'https://item.jd.com/1.html', target: 88, paused: true },
    { id: 'new-c', keyword: 'C', url: 'https://item.jd.com/3.html', target: 300 },
  ];
  const merged = mergeBackup(existing, imported);
  assert.strictEqual(merged.updated, 1);
  assert.strictEqual(merged.added, 1);
  assert.strictEqual(merged.skipped, 0);
  assert.strictEqual(merged.watches.length, 3);
  const a = merged.watches.find((w) => w.url === 'https://item.jd.com/1.html');
  assert.strictEqual(a.target, 88);
  assert.strictEqual(a.keyword, 'A2');
  assert.strictEqual(a.current, 120, '运行态 current 应保留');
  assert.strictEqual(a.notified, false, '改目标后应重置提醒闩锁');
  const c = merged.watches.find((w) => w.url === 'https://item.jd.com/3.html');
  assert.notStrictEqual(c.id, 'new-c', '导入的新目标应换成本机 id');
  assert.ok(c.id.startsWith('w_'));

  const full = Array.from({ length: 20 }, (_, i) => ({
    url: `https://item.jd.com/${100 + i}.html`, target: 10 + i,
  }));
  const capped = mergeBackup(full, [
    { url: 'https://item.jd.com/999.html', target: 1 },
    { url: 'https://item.jd.com/998.html', target: 2 },
  ]);
  assert.strictEqual(capped.watches.length, 20);
  assert.strictEqual(capped.added, 0);
  assert.strictEqual(capped.skipped, 2);
});

// ===== pricelens:// 深链（浏览器扩展 → 桌面端） =====
console.log('== pricelens 深链解析 ==');
check('accepts http(s) product urls only', () => {
  const jd = 'https://item.jd.com/100012043978.html';
  assert.strictEqual(parseDeepLink(`pricelens://product?url=${encodeURIComponent(jd)}`), jd);
  assert.strictEqual(parseDeepLink(`pricelens://product?q=${encodeURIComponent(jd)}`), jd);
  assert.strictEqual(parseDeepLink('pricelens://product?url=javascript%3Aalert(1)'), null);
  assert.strictEqual(parseDeepLink('pricelens://product?url=file%3A%2F%2F%2FC%3A%2Fsecret'), null);
  assert.strictEqual(parseDeepLink('https://item.jd.com/1.html'), null);
  assert.strictEqual(parseDeepLink('pricelens://product?url=' + 'x'.repeat(2100)), null);
});
check('finds the protocol url in windows argv', () => {
  const raw = 'pricelens://product?url=https%3A%2F%2Fitem.jd.com%2F1.html';
  assert.strictEqual(findDeepLink(['PriceLens.exe', raw]), raw);
  assert.strictEqual(findDeepLink(['PriceLens.exe', '--dev']), null);
});

// ===== 手机 App 联动：Android 备份 ↔ 桌面盯价 =====
console.log('== 手机备份格式映射（Android targets ↔ 桌面 watches） ==');
const androidPayload = {
  app: 'PriceLens', schema: 1, exportedAt: 1790000000000,
  favorites: [{ id: 'x', title: '收藏' }],
  targets: [
    { productId: 'jd:100012043978', title: 'iPhone 15', platform: 'jd', targetPrice: 3999, active: true, createdAt: 1700000000000, updatedAt: 1700000000000 },
    { productId: 'taobao:656745498498', title: '淘宝测试', platform: 'taobao', targetPrice: 199, active: false, createdAt: 1700000001000, updatedAt: 1700000001000 },
    { productId: 'pdd:123456', title: '拼多多目标', platform: 'pdd', targetPrice: 99, active: true, createdAt: 1700000002000, updatedAt: 1700000002000 },
    { productId: 'jd:', title: '坏行', platform: 'jd', targetPrice: 10, active: true },
  ],
};
check('android backup maps jd/taobao and skips unsupported platforms', () => {
  const mapped = androidBackupToWatches(androidPayload);
  assert.strictEqual(mapped.watches.length, 2);
  assert.strictEqual(mapped.skipped, 2);
  assert.strictEqual(mapped.received, 4, '收到总数要含被跳过的两行（pdd + 坏行）');
  assert.strictEqual(mapped.favorites, 1);
  assert.strictEqual(mapped.watches[0].url, 'https://item.jd.com/100012043978.html');
  assert.strictEqual(mapped.watches[0].target, 3999);
  assert.strictEqual(mapped.watches[1].paused, true, 'active=false 应变成桌面 paused');
});
check('desktop watches round-trip to android targets without credentials', () => {
  const result = watchesToAndroidBackup([
    { keyword: 'iPhone 15', url: 'https://item.jd.com/100012043978.html', target: 3999, paused: false, since: 1700000000000 },
    { keyword: '淘宝', url: 'https://item.taobao.com/item.htm?id=656745498498', target: 199, paused: true, since: 1700000001000 },
    { keyword: '不支持', url: 'https://mobile.yangkeduo.com/goods.html?goods_id=1', target: 99, paused: false, since: 1700000002000 },
  ], 1790000000000);
  assert.strictEqual(result.payload.targets.length, 2);
  assert.strictEqual(result.skipped, 1);
  assert.strictEqual(result.payload.targets[0].productId, 'jd:100012043978');
  assert.strictEqual(result.payload.targets[1].platform, 'taobao');
  assert.strictEqual(result.payload.targets[1].active, false);
  assert.strictEqual(result.payload.favorites.length, 0);
  assert.ok(!JSON.stringify(result.payload).toLowerCase().includes('cookie'));
  assert.ok(!JSON.stringify(result.payload).toLowerCase().includes('apikey'));
});
check('invalid app/schema is rejected with a usable error', () => {
  assert.throws(() => androidBackupToWatches({ app: 'Other', schema: 1, targets: [] }), /不是 PriceLens/);
  assert.throws(() => androidBackupToWatches({ app: 'PriceLens', schema: 2, targets: [] }), /schema/);
  assert.throws(() => androidBackupToWatches('not json'), /有效的 JSON/);
});
check('url mapping helpers are symmetric for supported platforms', () => {
  const jd = desktopUrlToAndroidTarget('https://item.m.jd.com/product/100012043978.html');
  assert.deepStrictEqual(jd, { platform: 'jd', productId: 'jd:100012043978' });
  assert.strictEqual(androidTargetToUrl(jd), 'https://item.jd.com/100012043978.html');
  const tb = desktopUrlToAndroidTarget('https://detail.tmall.com/item.htm?id=656745498498');
  assert.deepStrictEqual(tb, { platform: 'taobao', productId: 'taobao:656745498498' });
  assert.strictEqual(androidTargetToUrl(tb), 'https://item.taobao.com/item.htm?id=656745498498');
  assert.strictEqual(androidTargetToUrl({ productId: 'pdd:1' }), null);
});

console.log(`\n通过 ${passed} 项，失败 ${failed} 项${process.exitCode ? '（存在失败项）' : '，全部通过'}`);
