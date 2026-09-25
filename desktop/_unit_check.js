/**
 * 桌面端解析器单元自检（node 直接运行，不参与构建 / CI）
 * -------------------------------------------------------
 * 用法（在 desktop/ 目录）：
 *   node _unit_check.js
 *
 * 与 Android 侧 `app/src/test/java/.../QueryRelevanceTest` 等使用**同一批夹具**
 * （`../app/src/test/resources/fixtures/`），保证两端规则不漂移。
 * 覆盖：
 *   - utils/relevance.js 相关性规则（配件/图书/其它品牌/其它机型）
 *   - crawlers/gwdang.js 只提取显式券、绝不按价差编造
 *   - crawlers/jd.js 移动页 _itemInfo 解析 + 风控页拒绝
 */
'use strict';

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const { isRelevant } = require('./src/main/utils/relevance');
const { extractCoupon } = require('./src/main/crawlers/gwdang');
const { parseItemPage } = require('./src/main/crawlers/jd');

const FIXTURES = path.join(__dirname, '..', 'app', 'src', 'test', 'resources', 'fixtures');
const fixture = (name) => fs.readFileSync(path.join(FIXTURES, name), 'utf8');

let passed = 0;
function check(label, fn) {
  try {
    fn();
    passed += 1;
    console.log(`  ok  ${label}`);
  } catch (e) {
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

console.log(`\n通过 ${passed} 项${process.exitCode ? '，存在失败项' : '，全部通过'}`);
