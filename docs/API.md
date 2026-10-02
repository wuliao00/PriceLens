# PriceLens API 文档

**版本**：v1.2  
**适用版本**：Android v2.5.0+ / Desktop v2.0.0+  
**更新日期**：2026-09-25

---

## 📋 目录

1. [架构概览](#架构概览)
2. [爬虫接口](#爬虫接口)
3. [缓存层 API](#缓存层-api)
4. [IPC 通信](#ipc-通信)
5. [数据模型](#数据模型)
6. [错误码](#错误码)

---

## 🏗️ 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                        UI 层                                 │
│  Android: Compose UI          Desktop: HTML/CSS/JS         │
└─────────────────────────┬───────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────┐
│                      业务逻辑层                               │
│  Android: ViewModel + Repository    Desktop: Renderer JS   │
└─────────────────────────┬───────────────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────────────┐
│                      数据层                                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ 远程数据源   │  │ 本地数据源   │  │  缓存层     │         │
│  │ (爬虫)      │  │ (Room/SQLite)│  │ (TLRU/LRU)  │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
└─────────────────────────────────────────────────────────────┘
```

---

## 🕷️ 爬虫接口

### Android 端：解析器 + 统一管线 + 仓储编排（无统一 Crawler 接口）

> ⚠️ 早期版本文档描述过统一的 `Crawler` 接口（`data/remote/Crawler.kt`），
> **该接口在实际代码中不存在**。Android 端真实架构为三层：

```kotlin
// ① data/remote/*Api.kt —— 平台解析器（无状态，只解析、不抛错）
JdApi         // 京东：item.m.jd.com 页内 _itemInfo → 标题/主图；p.3.cn 尽力查价（不可用时如实置空）
ManmanbuyApi  // 慢慢买：价格历史曲线（最低/最高/大促）
BiliApi       // 哔哩哔哩：视频搜索（WBI 签名）
GwdangApi     // 找券：什么值得买「优惠券频道」搜索（只提取显式券文案）
SmzdmApi      // 什么值得买：社区帖子搜索
DangdangApi   // 当当：商品搜索（SSR 主数据源）
ShihuoApi     // 识货：m 站搜索接口 `m.shihuo.cn/search?type=goods`（兜底源，含国补标记）

// ② data/remote/ApiClient.kt —— 统一 HTTP 管线：
//    CrawlerResult 四态结果 + 限流/熔断 + singleflight 去重 + 重试，
//    并保留 getHtml()/getJson() 旧签名兼容桥（asNullable）

// ③ data/repository/PriceRepository.kt —— 声明式三级缓存编排：
//    CachedSource(key/TTL/编解码器/源名/取数钩子) →
//    L1 内存 TLRU → L2 Room → L3 网络 → 写回 + 失败降级旧快照（SourceHealth）

// ④ util/QueryRelevance.kt —— 搜索类数据源统一相关性过滤（2026-09 新增，v2.6.0 修正分词）：
//    关键词按非字母数字汉字**切段**后要求逐段命中 + **保持段序** + 相邻段间隔 ≤12 字符
//    （`oppo x8s` 因此能命中 `OPPO Find X8s`；逆序巧合串仍被挡）/
//    配件排除（含"适用 xxx"前缀）/ 图书说明书排除 /
//    品牌一致性 / 纯中文关键词词面命中；桌面端同规则见 desktop/src/main/utils/relevance.js
```

```javascript
// Desktop: src/main/crawlers/index.js（桌面端保留基类风格）
class Crawler {
    async search(keyword) { /* ... */ }
    async getDetail(productId) { /* ... */ }
    async getPriceHistory(productId) { /* ... */ }
    async getCoupons(productId) { /* ... */ }
    async getCommunityInfo(productId) { /* ... */ }
}
```

### 支持平台与实现（Android 实际能力矩阵）

| 平台 | 解析器 | 查价 | 详情/标题 | 价格历史 | 优惠券 | 社区/搜索 |
|------|--------|------|-----------|----------|--------|-----------|
| 京东 | `JdApi` | ⚠️（p.3.cn 通道不可用时如实置空） | ✅（m 站 SSR） | ❌ | ❌ | ❌ |
| 慢慢买 | `ManmanbuyApi` | ❌ | ❌ | ✅ | ❌ | ❌ |
| 哔哩哔哩 | `BiliApi` | ❌ | ❌ | ❌ | ❌ | ✅ 视频搜索 |
| 找券（值得买券频道） | `GwdangApi` | ❌ | ❌ | ❌ | ✅（仅显式券文案） | ❌ |
| 什么值得买 | `SmzdmApi` | ❌ | ❌ | ❌ | ❌ | ✅ 帖子搜索 |
| 当当 | `DangdangApi` | ❌ | ✅（SSR 搜索） | ❌ | ❌ | ✅ |
| 识货 | `ShihuoApi` | ❌ | ✅（m 站接口） | ❌ | ❌ | ✅ 兜底源 |
| 淘宝/拼多多/咕咚/Keep | 无障碍读价 | ✅（本机账号实时读价，无爬虫） | — | — | — | — |
| 盯价（后台） | `worker/PriceCheckWorker` | ✅ 京东（其他平台接入中） | — | — | — | — |

### 数据源实况与降级约定（2026-09-25 实测；2026-09-29 F4 增补"不可达 ≠ 无结果"契约）

上游站点近年频繁改版/收紧，解析器按"**宁可如实降级，不给不准确内容**"的原则实现：

| 数据源 | 当前实况 | 本项目处理 |
|--------|----------|------------|
| 当当 搜索 | 列表价格节点已迁移到 `span.search_now_price` | 三级取价（新→旧→文本），解析条数记日志 |
| 识货 搜索 | PC 搜索地址废弃（302 首页，数据是热榜） | 改走 m 站 `m.shihuo.cn/search?type=goods`；结构不符→空；请求域名是 `m.shihuo.cn`（状态行的诊断键必须与之一致） |
| 京东 商品页 | `item.jd.com` 对脚本请求返回风控页（标题"京东验证"） | 改走 `item.m.jd.com` 的 `_itemInfo`；风控页标题不使用 |
| 京东 查价 | `p.3.cn` 公网 DNS 不再返回可达地址（DoH 双证） | 尽力尝试；失败时价格置空 + UI 明示"请在京东 App 查看" |
| 慢慢买 公开接口 | 已下线（404） | 用自填 Cookie 的 SSR 通道 / 自建曲线 合并；星罗 apikey 命中榜单时**只有 in-sale 价（goods_list_money）够格补今日点**，`real_money` 是券后历史低价、只作参考展示（不写曲线、不触发降价通知，见 `domain/PriceSampling`） |
| 值得买/券频道 | 瑞数 WAF，浏览器 UA 拿 202 挑战页 | Googlebot UA 放行；挑战页识别为 Blocked |
| B站 搜索 | wbi 签名 + 未登录态常被 `code=-412` 拒 | **读业务码**：`code != 0` 视为反爬失败（与桌面端 `crawlers/bilibili.js:114-116` 同规则），不再与"零结果"混为一谈 |
| 全部搜索源 | 结果常混入配件/图书/其它品牌/热榜 | `QueryRelevance` 统一过滤（两端同规则） |

#### 浮窗为什么常常只能到 TITLE_ONLY（2026-09-29/30 真机取证，含三条否证）

浮窗的确定性商品身份来自 `extractItemId` —— 它在节点文本里找 `item.jd.com/<sku>` 或
`?sku=<digits>`。**真机京东商详页的树里没有这种文本**，所以 `itemId=null`，浮窗走降级态
（UI 明示「仅识别到标题 · 不显示历史价/多平台比价」）。这是**如实降级**，不是漏显示。

同一棵真机树（服务自 dump，取证后已删除）：385 个节点、最大深度 30、127 个带 `resource-id`，
其中**语义命名的 id 为 0 个**（全是 `dme`/`c_s`/`by2` 这类混淆短名）。所以：

- `PriceNodeMatcher.isKnownPriceId` / `JD_PRICE_ID_NAMES` 在现版京东恒不命中 →
  `PriceHit.viaKnownId` 只是加分项（曾当必要条件，直接导致浮窗不弹，见 v1.3）；
- `extractTitle` 的"一级：已知标题 resource-id（高置信）"同样恒不命中 →
  标题实际全部来自三级"最长文本"启发式；`TitleHit.viaKnownId` 无任何消费方，仅冗余字段。

曾考虑用可达的公开源把"标题 → 确定性 SKU"补上，**三条路全部实测否证**：

| 候选路径 | 实测结果 | 结论 |
|----------|----------|------|
| 京东 m 站搜索 `so.m.jd.com/ware/search.action?keyword=…` | 302 到 `cfe.m.jd.com/privatedomain/risk_handler/`，响应 2,704 字节风控页 | 服务端按标题搜 SKU 不可得 |
| 什么值得买搜索页 HTML 找京东链接 | 242,362 字节、172 个站内链接，`item.jd.com` / `go.smp.smzdm.com` / `res_url=` **各 0 命中** | 列表页不承载目标链接 |
| 什么值得买文章内页 → `go.smzdm.com/<hash>` 购买跳转 | 抽 3 篇（`/p/182840345/`、`/p/182244115/`、`post/p/a82xgx4q/`，425K/418K/159K 字节）：**直接京东 URL 命中 0 / 0 / 0**，`go.smzdm.com` 链接 1 / 2 / 0 条，三篇都挂 `probev3.js` 反爬探针；跟随后返回 4,355 字节的**混淆 JS 页**（Dean Edwards packer），跳转目标由 cookie 在客户端拼装 | 需真实浏览器执行 JS 才拿得到，且每次识别要 3 跳 + 反爬风险 |

⇒ **确定性 SKU 只能由用户提供**：粘贴商品链接（`ProductCandidateResolver.extractJdSku`），
或在 App 内搜索后由用户选定同款。爬虫猜身份会把别的商品的历史价当成本商品的，
正是本项目要消灭的那类错误（见"宁可如实降级"原则与防错配硬规则）。

#### 盯价轮次能不能拿到"现价"（2026-09-30 实测，决定自建曲线的攒点率）

v2.6.5 起，盯价每轮的 live 现价会写成"今日的曲线点"（见 `data/local/DayCurve.kt`）。
于是**曲线能不能自己长出来，等价于"免凭证时能不能拿到现价"**。四条候选路径的实测：

| 候选价格源 | 实测结果 | 结论 |
|-----------|----------|------|
| `p.3.cn/prices/mgets`（App 现用查价接口） | 权威 DNS 返回 RFC1918 私网 IP（AliDNS / 腾讯 DoH 双证），手机 WiFi 亦不通 | 公网不可达；拿不到时 `price = 0`，UI 明示"价格请在京东 App 查看" |
| `item.m.jd.com/product/<sku>.html` 页内 `priceFloor` | 4 个 SKU 实测：`"jdPrice":"1??9"` / `"5?"` / `"1??0"` / `"2??"` —— **除首末位外每一位都是字面 0x3F 问号**，同页还带 `priceLoginText:"登录查看价格"` | 不是编码/字体障眼法，是服务端就把数字抹掉了；未登录拿不到可读价格 |
| 同页 `realPriceExt.ORIGINAL.jdprice_amount` | 4 个 SKU 里只有 1 个有值（`1041.0`，且该商品为"需预约购买"），字段含义无从确认 | **语义不明的数字不得写进曲线**（F1 那类错误的源头） |
| 慢慢买 Cookie / 星罗好货 apikey | 均需用户凭证；Cookie 走 m 站 SSR（见 `ManmanbuyHistoryPage`） | 有凭证才有外源历史点 |

⇒ 免凭证的一轮盯价**默认拿不到现价**，这不是回写逻辑的 bug，而是价格通道的事实。
所以脚注在零采样日时直说"还没有任何采样日：盯价每 30 分钟一轮，从某一轮真拿到现价的那天起，
曲线才会开始出现点"，只有一天时不画 200dp 空框（见 `watch_curve_one_point`），
取不到价的目标也**不写 0、不沿用上一轮的旧价**（`DayCurveTest` 钉住）。
真机实测的攒点率见 `docs/REGRESSION_BASELINE.md` 的 2026-09-30 小节。


#### 免凭证的出路：浮窗身份确认（v2.7.0 上线，产品决策已闭环）

上一节「免凭证时曲线不会有点」的结论被这条通道改写 —— 用户自己在前台打开商详页时，本机无障碍读到的就是真实现价。v2.7.0 起：

- 浮窗（TITLE_ONLY 展开面板）新增「**就是这个商品**」按钮：用户对着标题确认一次身份后，之后每次无障碍读到的页面价都写成该商品的**今日点**（收盘语义 + 当日至低，出处记本机盯价自采）。
- 身份存新表 `watch_identity`，键为 `ovl:<base36(平台|规范化标题 摘要)>`：**键一经生成永不变**，标题后续变化走 `titlesLikelySameProduct` 相似度认领（与盯价入库防撞同一套判定）。
- **口径闸门**：只有页面价（PAGE）够格写；券后价 / 到手价一律挡住（防"历史最低"假摔，F1 同形错误）。未确认商品的读价整体丢弃 —— 没有键就没法记，写进哪个 productId 都是猜。
- `ovl:` 曲线经 `PriceRepository.identityCurve(productId)` 窄接口读取（`buildHistory` 在 sku==null 时会把自采点置空，读侧不打通 = 功能看着没生效）。
- 边界（phase 2，未实现）：同一商品将来拿到真 SKU 时，`jd:<sku>` 与 `ovl:<hash>` 是两条独立历史；合并需处理同日冲突，本期不做。
- 曲线跟随**当日最后一次浏览**（收盘语义），不是当日最低；「历史最低」用的是 `dayLow`。文案不得误导。

#### 关键词搜索通道的三态契约（F4，2026-09-29）

**"没够着数据源"与"够着了但确实没有"是两件事，全链路不得压成同一个值。**

| 结局 | 产生条件 | 网络层（`CrawlerResult`） | 解析类 | 仓储（`CachedSource`） | 徽标 | 空态文案 |
|------|----------|--------------------------|--------|------------------------|------|----------|
| 有数据 | 2xx + 解析出条目 | `Success` | 返回 N 条 | `Success(N 条)`，`recordSuccess` | 「正常」 | — |
| 无结果 | 2xx 但过滤后 0 条 / 空响应体 | `Success` / `Empty` | 返回 `emptyList()` | `Success(空表)`，**不计失败** | 「无结果」 | 「未匹配到与「关键词」直接相关的商品…」 |
| 取不到 | 超时/DNS/连接拒绝/非 2xx；403/412/JS 挑战页；源在失败冷却期 | `Network` / `Blocked` | **抛** `SourceUnreachableException` / `CrawlerBlockedException` | 有旧快照 → 回吐旧数据 + `staleKeys` 标记 + `recordFailure`；无快照 → 原样抛出 | 「失败」/「反爬」 | 「暂时取不到数据：网络不可达或数据源被拦截，与关键词写法无关」 |

要点：

- 判定入口 `data/remote/CrawlerResult.toSourceFailure()`（唯一映射点）；展示侧两处纯函数
  `ui/components/sourceChipStateOf()`、`ui/common/EmptyStateCauseOf.of()`（均可 JVM 单测，无 Compose 依赖）。
- `recordFailure` 只由"取不到"触发 ⇒ `SourceHealth` 的**连续 3 次失败 → 2 分钟冷却**对反爬风暴/断网才真的生效
  （F4 前解析类从不抛，这条降级路径是死代码）。
- 冷却期内且无旧快照时**同样算取不到**——本轮压根没去访问该源，不能宣称"这个关键词没结果"。
- `AsyncValue.Error.cause` 携带成因，`fallback` 携带旧数据：各页"顶部提示 + 照常展示旧列表"的分支由此可达。
- 例外（本轮未收口，仍是可空桥）：`JdApi`（京东商品页）、`ManmanbuyApi`（历史价）、`LinkstarsApi`（星罗）
  仍用 `getHtml`/`getJson` 的 `String?` 桥，其 `null` 一律按"合法无数据"处理。
  影响面与后续计划见交付报告「没做」栏（F5/F17 同族）。
- 桌面端爬虫层本来就是这个契约（`smzdm.js:57/93`、`gwdang.js:70-73`、`bilibili.js:115`、`manmanbuy.js:90` 全部 `throw`），
  Android 这一轮是向桌面端拉平，不是新增规则。

> 回归方式：Android 侧夹具化单测（`app/src/test/resources/fixtures/`，取自上述实况页面）
> + F4 用例 `SourceUnreachableTest`、`SourceUnreachableCacheTest`、`CrawlerOutcomeMappingTest`、
> `SourceChipStateTest`、`EmptyStateCauseTest`（真 `ApiClient` + 熔断域名造"不可达"，全程不发网络请求）；
> 桌面侧运行 `node desktop/_crawler_check.js "<关键词>"` 做发布前巡检。

### 更新通道（v2.6.0 起，更新源 Gitee）

| 项 | 约定 |
|----|------|
| 清单地址 | `https://gitee.com/wuliao11541/PriceLens/raw/main/update.json?v=<versionCode>&t=<epoch/600s>`（`?v/?t` 用于破 Gitee CDN 的 60s 服务端缓存） |
| 安装包位置 | 孤儿分支 `dist` 上的 `PriceLens-<version>.apk`，raw 直链 `https://gitee.com/wuliao11541/PriceLens/raw/dist/PriceLens-X.Y.Z.apk`。选它而不是发行版附件：Gitee 附件上传要登录态（SSH/CI 都推不上去），而 2.2 MB 的 release 包在 raw 免登录上限（10 MB）以内；二进制只进 `dist`，`main` 历史保持干净。次选 `github.com/.../releases/download/...` |
| 请求方式 | 复用 `ApiClient.getJsonResult`（内部 `FORCE_NETWORK`），**禁止**走允许缓存的重载 |
| 判定入口 | `update/UpdateEvaluator`（纯函数，`currentVersionCode` 由外部传入便于单测） |
| 阈值语义 | 仅 `current < minSupportedVersionCode` 阻断；`< forceBelow` 强提示可跳过；`< latest.versionCode` 可选提示（受 `rolloutPercent` 灰度） |
| fail-open | 清单拉取失败 / JSON 解析失败 / `schemaVersion` 未知 / `sha256` 非 64 位十六进制（含发布前占位符）/ `generatedAt` 超 30 天或超前 24h 以上 → **一律静默**，绝不把人锁在门外 |
| 安装链路 | Range 断点续传 → `.part` → sha256 校验 → rename → FileProvider `content://` → 系统安装器；缺"未知来源"授权时跳系统设置页；连续 3 次失败降级为普通提示 |
| 本地静音 | 同一 `generatedAt` 只提示一次；逃生口 24h；"以后再说" = `cooldownHours`。设置页「检查更新」可立即穿透 |
| 回滚 | revert `update.json` 并推 Gitee 镜像即可，不改代码、不重打包 |

> 字段完整语义与发布步骤见 [DEVELOPMENT.md](DEVELOPMENT.md) 的「强制更新开关」。
> 注意：清单只被 **2.6.0 及以后**的客户端读取，更早版本没有这套代码。


### 请求参数规范

```typescript
// 搜索请求
interface SearchRequest {
    keyword: string;           // 搜索关键词
    page?: number;             // 页码，默认 1
    pageSize?: number;         // 每页数量，默认 20
    sort?: 'price_asc' | 'price_desc' | 'sales' | 'default';
    filters?: Record<string, string>; // 平台特定筛选
}

// 详情请求
interface DetailRequest {
    productId: string;         // 平台商品 ID
    platform: Platform;        // 来源平台枚举
}
```

### 响应数据模型

```typescript
// 商品摘要（搜索结果列表项）
interface ProductSummary {
    productId: string;         // 平台唯一标识
    platform: Platform;        // 来源平台
    title: string;             // 商品标题
    price: number;             // 当前价格（分）
    originalPrice?: number;    // 原价（分）
    imageUrl: string;          // 主图 URL
    shopName: string;          // 店铺名称
    salesVolume?: number;      // 销量
    rating?: number;           // 评分
    url: string;               // 商品页链接
    tags: string[];            // 标签（自营、包邮、秒杀等）
    updatedAt: number;         // 数据更新时间戳
}

// 商品详情
interface ProductDetail extends ProductSummary {
    description: string;       // 商品描述
    specs: Record<string, string>; // 规格参数
    images: string[];          // 所有图片
    skus: Sku[];               // SKU 列表
    priceHistory: PricePoint[]; // 价格历史
    coupons: Coupon[];         // 可用优惠券
    community: CommunityInfo;  // 社区评价
}

// 价格历史点
interface PricePoint {
    timestamp: number;         // 时间戳
    price: number;             // 价格（分）
    type: 'normal' | 'promotion' | 'coupon' | 'flash_sale'; // 价格类型
    promotionName?: string;    // 促销名称
}

// 优惠券
interface Coupon {
    id: string;
    name: string;              // 券名称
    value: number;             // 面值（分）
    threshold: number;         // 使用门槛（分）
    startTime: number;
    endTime: number;
    remainder: number;         // 剩余张数
    conditions: string[];      // 使用条件
}

// 社区信息
interface CommunityInfo {
    platform: 'bilibili' | 'smzdm' | 'other';
    videos: VideoInfo[];       // 相关视频（B站）
    articles: ArticleInfo[];   // 相关文章（值得买）
    tags: string[];            // 关键词标签
    sentiment: 'positive' | 'negative' | 'neutral'; // 整体倾向
}
```

---

## 💾 缓存层 API

### 三级缓存架构

```
L1: 内存缓存 (TLRU / LRU)     → 热数据，毫秒级读取
    ├── Android: TlruCache<T> (8MB 预算)
    └── Desktop: MemoryCache (Map + TTL)

L2: 持久化缓存 (Room / SQLite) → 温数据，重启保留
    ├── Android: Room DAO (10MB 预算)
    └── Desktop: SQLite (better-sqlite3)

L3: 网络层缓存 (OkHttp / HTTP)  → 网络层面缓存
    ├── Android: OkHttp Cache (5MB)
    └── Desktop: 自定义 HTTP 缓存
```

### 缓存键设计

```kotlin
// 统一缓存键格式
// 搜索: "search:{platform}:{keyword}:{page}:{filtersHash}"
// 详情: "detail:{platform}:{productId}"
// 价格历史: "history:{platform}:{productId}"
// 优惠券: "coupons:{platform}:{productId}"
// 社区: "community:{platform}:{productId}"
```

### 缓存策略

| 数据类型 | TTL | 最大条目 | 淘汰策略 |
|---------|-----|----------|----------|
| 搜索结果 | 10 分钟 | 500 | LRU + TTL |
| 商品详情 | 30 分钟 | 1000 | LRU + TTL |
| 价格历史 | 2 小时 | 2000 | LRU + TTL |
| 优惠券 | 1 小时 | 500 | LRU + TTL |
| 社区信息 | 4 小时 | 300 | LRU + TTL |
| 图片资源 | 7 天 | 200MB | Coil/HTTP 缓存 |

### 缓存操作接口

```kotlin
// Android: data/cache/CacheManager.kt
interface CacheManager {
    suspend fun <T> get(key: String): T?
    suspend fun <T> put(key: String, value: T, ttl: Long = DEFAULT_TTL)
    suspend fun invalidate(key: String)
    suspend fun invalidateByPrefix(prefix: String)
    suspend fun clear()
    fun stats(): CacheStats
}
```

```javascript
// Desktop: src/main/cache/manager.js
class CacheManager {
    async get(key) { /* ... */ }
    async put(key, value, ttl) { /* ... */ }
    async invalidate(key) { /* ... */ }
    async invalidateByPrefix(prefix) { /* ... */ }
    async clear() { /* ... */ }
    stats() { /* ... */ }
}
```

---

## 🔌 IPC 通信

仅适用于 Desktop 端（Electron 主进程 ↔ 渲染进程）

### 通道定义

```typescript
// preload.ts 暴露给渲染进程的 API
interface ElectronAPI {
    // 爬虫相关
    search: (platform: string, keyword: string, options?: SearchOptions) => Promise<ApiResponse<ProductSummary[]>>;
    getDetail: (platform: string, productId: string) => Promise<ApiResponse<ProductDetail>>;
    getPriceHistory: (platform: string, productId: string) => Promise<ApiResponse<PricePoint[]>>;
    getCoupons: (platform: string, productId: string) => Promise<ApiResponse<Coupon[]>>;
    getCommunityInfo: (platform: string, productId: string) => Promise<ApiResponse<CommunityInfo>>;
    
    // 缓存管理
    cache: {
        getStats: () => Promise<CacheStats>;
        clear: () => Promise<void>;
        invalidate: (key: string) => Promise<void>;
    };
    
    // 设置
    settings: {
        get: () => Promise<AppSettings>;
        set: (settings: Partial<AppSettings>) => Promise<void>;
    };
    
    // 盯价任务 (规划中)
    watchTasks: {
        list: () => Promise<WatchTask[]>;
        create: (task: WatchTask) => Promise<WatchTask>;
        update: (id: string, task: Partial<WatchTask>) => Promise<void>;
        delete: (id: string) => Promise<void>;
    };
    
    // 系统
    openExternal: (url: string) => Promise<void>;
    showItemInFolder: (path: string) => Promise<void>;
    getAppVersion: () => Promise<string>;
    getPlatform: () => Promise<NodeJS.Platform>;
}
```

### IPC 处理器

```javascript
// src/main/ipc-handlers.js
const handlers = {
    'search': async (event, { platform, keyword, options }) => {
        const crawler = getCrawler(platform);
        return await crawler.search(keyword, options);
    },
    
    'get-detail': async (event, { platform, productId }) => {
        const crawler = getCrawler(platform);
        return await crawler.getDetail(productId);
    },
    
    // ... 其他处理器
};
```

---

## 📦 数据模型

### 实体关系图

```
Product (商品)
├── ProductSummary (搜索摘要)
├── ProductDetail (详情)
│   ├── Sku[] (SKU 列表)
│   ├── PricePoint[] (价格历史)
│   ├── Coupon[] (优惠券)
│   └── CommunityInfo (社区)
└── WatchTask (盯价任务) ← 仅 Android
    ├── productId
    ├── targetPrice
    ├── interval
    └── enabled
```

### Room 实体 (Android)

```kotlin
// data/local/entity/ProductEntity.kt
@Entity(tableName = "products", indices = [
    Index(value = ["platform", "productId"], unique = true),
    Index("updatedAt")
])
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "platform") val platform: String,
    @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "price") val price: Long, // 分
    @ColumnInfo(name = "original_price") val originalPrice: Long?,
    @ColumnInfo(name = "image_url") val imageUrl: String,
    @ColumnInfo(name = "shop_name") val shopName: String,
    @ColumnInfo(name = "sales_volume") val salesVolume: Long?,
    @ColumnInfo(name = "rating") val rating: Double?,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "tags") val tags: String, // JSON
    @ColumnInfo(name = "detail_json") val detailJson: String, // 完整详情 JSON
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
```

### SQLite 表结构

```sql
-- products 表
CREATE TABLE products (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    platform TEXT NOT NULL,
    product_id TEXT NOT NULL,
    title TEXT NOT NULL,
    price INTEGER NOT NULL,           -- 分
    original_price INTEGER,
    image_url TEXT NOT NULL,
    shop_name TEXT NOT NULL,
    sales_volume INTEGER,
    rating REAL,
    url TEXT NOT NULL,
    tags TEXT,                        -- JSON 数组
    detail_json TEXT,                 -- 完整详情 JSON
    updated_at INTEGER NOT NULL,
    UNIQUE(platform, product_id)
);

CREATE INDEX idx_products_updated_at ON products(updated_at);

-- price_history 表
CREATE TABLE price_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    timestamp INTEGER NOT NULL,
    price INTEGER NOT NULL,           -- 分
    type TEXT NOT NULL,               -- normal/promotion/coupon/flash_sale
    promotion_name TEXT
);

CREATE INDEX idx_price_history_product ON price_history(product_id);

-- watch_tasks 表 (仅 Android)
CREATE TABLE watch_tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    target_price INTEGER NOT NULL,    -- 分
    interval_minutes INTEGER NOT NULL DEFAULT 30,
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    last_check_at INTEGER
);
```

---

## ❌ 错误码

### Android：`CrawlerResult` 四态结果模型（v2.5.0 起）

旧模型中 `ApiClient` 吞掉所有异常、一律返回 `null`，上层无法区分“真的没有数据”与“被反爬拦截/网络故障”。
重构后显式建模为四种结局（`data/remote/CrawlerResult.kt`）：

```kotlin
sealed interface CrawlerResult<out T> {
    /** 成功：拿到有效响应体（HTML/JSON 原文） */
    data class Success<T>(val data: T) : CrawlerResult<T>

    /** 请求成功但内容为空/无效（非反爬） */
    data object Empty : CrawlerResult<Nothing>

    /** 反爬拦截：403/412、JS challenge 页、风控异常 */
    data class Blocked(val reason: String) : CrawlerResult<Nothing>

    /** 网络层失败：超时 / DNS / 连接重置 / 非 2xx 状态码 */
    data class Network(val cause: Throwable) : CrawlerResult<Nothing>
}
```

| 结果类型 | 语义 | 可重试 | 处理策略 |
|----------|------|--------|----------|
| `Success` | 拿到有效响应 | — | 写回 L1 内存 / L2 Room 缓存 |
| `Empty` | 服务端成功但无内容 | ❌ | UI 空态展示，不触发熔断 |
| `Blocked` | 反爬拦截（403/412/风控） | ❌ | `RateLimiter` 熔断 5min + `SourceHealth` 降级 |
| `Network` | 网络层失败 | ✅ | 指数退避重试，`SourceHealth` 连续失败计数 |

配套能力：
- **兼容桥**：`asNullable()` 将 Success → data、其余 → null，旧的 `String?` / `JSONObject?` 签名方法内部委托新管线，8 个 `*Api` 解析类零改动。
- **UI 映射**：ViewModel 层将 `Blocked` 映射为 `AsyncValue.Error`（携带 `CrawlerBlockedException`），`SourceStatusRow` 组件可视化各数据源健康度。
- **源健康降级**：`data/repository/SourceHealth.kt` 记录连续失败，超阈值时暂时跳过该源、直接回退旧快照（`PriceRepository.staleKeys` 可观察）。

### Desktop：统一错误格式

```typescript
interface ApiError {
    code: string;        // 错误码
    message: string;     // 用户友好提示
    detail?: string;     // 技术细节（调试用）
    retryable: boolean;  // 是否可重试
}
```

### 常见错误码（Desktop / 通用概念表）

| 错误码 | HTTP 状态 | 说明 | 可重试 | 处理建议 |
|--------|-----------|------|--------|----------|
| `NETWORK_ERROR` | - | 网络不可用/超时 | ✅ | 检查网络，指数退避重试 |
| `RATE_LIMITED` | 429 | 触发反爬限流 | ✅ | 等待 `Retry-After` 秒后重试 |
| `BLOCKED` | 403 | IP/UA 被封禁 | ❌ | 更换 IP/UA，触发熔断 |
| `PARSE_FAILED` | 200 | 页面结构变化解析失败 | ❌ | 更新爬虫选择器，上报 Issue |
| `NOT_FOUND` | 404 | 商品不存在/已下架 | ❌ | 提示用户商品失效 |
| `PLATFORM_UNSUPPORTED` | - | 平台暂不支持 | ❌ | 降级提示 |
| `CACHE_MISS` | - | 缓存未命中 | ✅ | 回源网络请求 |
| `STORAGE_FULL` | - | 本地存储空间不足 | ❌ | 清理缓存提示用户 |
| `PERMISSION_DENIED` | - | 权限不足（无障碍/悬浮窗） | ❌ | 引导用户授权 |

### 熔断与限流机制

```kotlin
// 实现位置：util/RateLimiter.kt（Android） / utils/rate-limiter.js（Desktop）
//  - 同域名 ≤ 1 req/3s，并发域名 ≤ 3，10s 超时 + 失败重试 1 次，UA 轮换 ×5 池
class RateLimiter {
    // 熔断：403/429 连续触发 → 该域名暂停请求 5 分钟（状态持久化，重启不丢）
    // 恢复：熔断到期后半开探测，成功则恢复正常调度
}
```

---

## 🔄 版本兼容性

| API 版本 | Android 最低版本 | Desktop 最低版本 | 变更说明 |
|---------|------------------|------------------|----------|
| v1 | 2.3.0 | 2.0.0 | 初始版本 |
| v1.1 | 2.5.0 | 2.0.0 | 错误模型对齐 `CrawlerResult` 四态；爬虫接口章节修正为解析器 + ApiClient 管线 + PriceRepository 编排（删除不存在的统一 `Crawler` 接口描述） |
| v1.2 | 2.5.1 | 2.1.0 | 数据源准确性专项：京东改 m 站 `_itemInfo` 解析、查价不可用时如实置空；识货改 m 站 `type=goods` 接口；找券只认显式券文案；新增 `QueryRelevance` 相关性过滤（两端同规则）与数据源实况表 |
| v1.3 | 2.6.4 | 2.1.0（修复在 main，未随包发布） | 浮窗门控与读数：商详门控不再依赖 `viaKnownId`（现版京东 view id 全为混淆短名，该条件恒假 → 真机商详页浮窗不弹）；词表收「立即预约」（预约型商品底栏无「立即购买」）；主价「¥1838」+「.9」分体渲染时拼回小数位；节点文本清洗零宽/双向控制字符（京东用 U+200B 填充标题，会污染 CTA 搜索词与缓存 key）。**两端差异记录**：桌面端无当当/识货爬虫；`product.originalPrice` 桌面端旧实现写成等于现价，已改为 0=未知（与 Android `ProductCandidate.originalPrice = null` 同口径，见 `desktop/_unit_check.js` 的 `productFromCandidate` 两条用例） |

> 遵循语义化版本：Breaking Change 升主版本号，新增功能升次版本号，Bug 修复升修订号。

---

## 📝 更新日志

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| v1.0 | 2026-08-24 | 初始版本发布 |
| v1.1 | 2026-08-26 | 错误码对齐 `CrawlerResult`；修正爬虫接口架构描述 |
| v1.2 | 2026-09-25 | 数据源准确性专项修复记录：新增「数据源实况与降级约定」表；京东/识货/找券接口与解析口径更新；新增 `QueryRelevance` 段落 |

---

## 🤝 贡献

欢迎完善 API 文档！请参考 [贡献指南](README.md#-贡献指南) 提交 PR。
