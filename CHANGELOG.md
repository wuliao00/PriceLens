# Changelog

本项目所有显著变更记录于此文件。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [SemVer](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [2.7.0] - 2026-10-01

> 本版主题：**免凭证时，盯价曲线真的能长出来。** 免凭证取京东现价的三条公开路（p.3.cn、m 站商详、标题换 SKU）实测全部否证后，
> 唯一还活着的免凭证来源就是本机无障碍读到的现价 —— 本版把它变成可入库的曲线点。

### 新增
- 浮窗「就是这个商品」身份确认闸门（TITLE_ONLY 展开面板）：点一次确认商品身份，之后无障碍每次读到的页面价都写成该商品的今日点（收盘语义 + 当日至低，出处记为本机盯价自采）。身份存新表 `watch_identity`（Room v3→v4），键走 `ovl:<摘要>` 命名空间与 `jd:<sku>` 分家；券后价/到手价被口径闸门挡下，防止``历史最低假摔'
- 盯价页新增「浮窗确认的本机身份」管理区：每条身份显示已记天数与最近读数，取消确认会连带删除它的日点（不留孤儿点）

### 修复
- 盯价前台服务每次目标表变动都新起一条 30 分钟循环（N 个目标 = N 条并发轮次，重复请求 / 重复通知 / 重复写点）→ 服务内恒定单条循环，控制器只在``空↔非空'跳变时动作
- singleflight 落败方把胜者的失败（被反爬 / 断网）吞成``合法的空白``：徽标显示正常、空态说``没结果``，真实成因被抹掉 → 异常与取消一律原样透传，并补 5 条并发行为单测钉死
- 旋转 / 分屏 / 折叠展开后浮窗可被拖到屏幕外找不回（拖动边界进程内只算一次）→ 配置变化即时重算并拉回边界内；测量改用 WindowMetrics（API 30+）
- 浮窗 ComposeView 曾以无障碍 Service 实例作 Context（主题上下文错 + 长期持有已销毁服务的风险）→ 改用 applicationContext + Theme_PriceLens 包装
- 盯价页是唯一没有滚动容器的目标页：小屏 / 大字号下曲线卡与入口画到屏幕外且滚不动 → 补纵向滚动
- 迁移守卫的死角：非空断言硬编码只看 2→3，v4 加列不会红 → 改为遍历全部已登记迁移，用``只记录不执行的假库``跑 migrate() 逐条校验语句形态

### 变更
- 浮窗关闭按钮触摸目标 22dp → 48dp（minimumInteractiveComponentSize），符合无障碍最小命中区
- **正式包改用 release keystore 签名，终结 debug 签名对外发布**。从 debug 签的 ≤2.6.5 覆盖安装 2.7.0 release 包会因签名不同失败 —— 需卸载重装一次（本机自采数据会丢，发布说明已写明）
- `build-apk.ps1` 不再回写源码版本号（唯一真源 `app/build.gradle.kts`，杜绝脚本停在旧版本导致``一跑就静默降级'）；缺签名密钥直接报错
- CI：release 事件的``下载/重命名 APK``步骤补上与签名门控一致的 `if`（此前无密钥时下载必失败、release job 长期假红）；setup-android `api-level` 34→35 与 compileSdk 对齐

## [2.6.5] - 2026-09-30
- 新增：粘贴慢慢买 Cookie 后「检测 Cookie」给出明确结论（登录失效 / 被验证码拦 / 链接不匹配 / 正常）；盯价自建曲线（今日点 + 当日至低）；曲线出处脚注；``历史最低'改用当日至低
- 修复：Room v2→v3 迁移未登记 Hilt 建库点致冷启动崩（加静态守卫）；``每天 1 点'从未生效（补 (商品,日期) 唯一索引 + 去重）；脚注把自采点写成``来源未记录'
- 优化：单个采样日不再留 200dp 空白
- 说明：免凭证时京东现价取不到（p.3.cn 私网 DNS、m 站打码），曲线不会有点

## [2.6.4] - 2026-09-30
- 修复：真机京东商详页浮窗不弹 —— 门控不再依赖恒假的``已知主价 ID'；无障碍文本清洗零宽 / 双向控制字符
- 测试：真机实采的京东节点树进回归

## [2.6.3] - 2026-09-30
- 修复：浮窗``90 天最低'与脚注时间口径收口（窗口外老数据不再冒充 90 天断言、抓取时刻不可知时如实说未知）

## [2.6.2] - 2026-09-29
- 修复：空态成因分类补强 —— 区分``源不可达'与``真的没有结果'，全源不可达时不再说``关键词不匹配'
- 测试：RateLimiter 熔断用例不再依赖 120ms 调度窗口





## [2.6.1] - 2026-09-29

> 为什么要单独升一个版本号：下面这两项修复是在 2.6.0 的包**已经发出去之后**才修好的。
> 客户端只用整数 `versionCode` 比较版本，如果继续写 14，那么改了 `update.json` 的 sha256
> 也不会给已装 2.6.0 的手机任何提示 —— 修复就只能靠 adb 侧载，对普通用户等于没有。

### Changed

- `versionCode 14 → 15`、`versionName 2.6.0 → 2.6.1`；`update.json.latest` 同步跟上。
  `minSupportedVersionCode` 仍为 14、`forceBelow` 仍为 13 —— 不阻断 2.6.0 用户，
  只让他们收到一条可跳过的更新提示。
- 随本版一并分发的还有 `ea9747c`（社区「值不值」不再恒为 0）与 `1a06182`
  （盯价页不再被"没有曲线"整页挡在门外）：此前它们只存在于 2.6.0 的换包产物里，
  已装 2.6.0 的设备拿不到。

### Fixed

- **星罗「券后历史低价」不再冒充现价（假降价通知 + 曲线自污染，F1）**：星罗好货接口本身是
  「历史低价榜」（`jd_historyLowPriceRank`），旧实现把它的 `real_money`（券后**历史**低价）
  当成"本轮现价"塞进无标记的 `Map<String, Double>`，于是价格没有下降也会发
  「当前 ¥X ≤ 目标 ¥Y」的降价通知；同一个值还被写成"今天的曲线点"落进历史表，使曲线最低点
  恒 ≤ 它 ⇒ 入手建议几乎恒判「≈ 历史低价 / 可入」。而真正该当现价的 `goods_list_money`
  （在售价）两端都解析、却没有任何消费点。现在：
  - 新增带来源的价格样本 `domain/PriceSample`（`JD_P3CN` / `LINKSTARS_LIST` /
    `LINKSTARS_HISTORY_LOW`），字段选择与资格判定收口在纯函数 `domain/PriceSampling`；
  - 兜底优先取在售价，只有它 ≤0 时才允许退到券后历史低价，并如实标成"参考值"；
  - 参考值**不触发降价通知**、**不写进历史曲线**，计入 `skipped.noPrice`（另记
    `WatchSkipCounts.referenceOnly`），盯价页脚注说明真实成因；
  - 通知文案跟着来源走：非京东在售价时标题改为「已达目标价」，正文写
    「星罗好货在售价 ¥X ≤ 目标 ¥Y · 非实时」，不再冒充「当前价」。
  回归测试 `domain/WatchPriceSourceTest`（3 条，各带对照组）先在"旧行为逐字转录"的实现上
  跑红，再改实现转绿。桌面端同一处缺陷同步修正（`linkstars.toPriceSample/curveWorthy`），
  同规则用例并入 `desktop/_unit_check.js`。
- **当当候选主图恒为灰占位图（F5）**：当当列表页懒加载把真图写在 `data-original`、`src` 是
  `images/model/guan/url_none.png`（2026-09-29 实况 60 条里 59 条如此，`data-src` 出现 0 次），
  旧解析只读 `src`/`data-src` ⇒ 除第一条外每条候选都拿到相对路径的占位图，`AppImage` 的
  `takeIf { it.startsWith("http") }` 于是恒显示灰块。取值顺序改为
  「非占位 `src` → `data-original` → `data-src`」；并把 `DangdangParserTest` 从"只断言
  `items[0]`"（唯一一条 `src` 就是真图的，正好把这个缺陷遮住）改为遍历夹具全部 8 条。

## [2.6.0] - 2026-09-28

> v2.6.0 是"接口内容准确性"的第二轮：把上一轮修的数据源问题在**真机链路**上跑通
> （无障碍识别 → 比价浮窗 → 盯价跟踪），并补齐两项长期缺失的能力——**新手引导**与
> **应用内强制更新（更新源 Gitee）**。方案对标成熟开源实现（Mihon / XUpdate / azhon-AppUpdate /
> Magisk 清单形态），零新增第三方依赖。

### Added

- **新手引导教程**（`ui/onboarding/`）：首启四步分步流——价值说明 → 无障碍授权（有 Shizuku
  则置顶"一键开启"）→ 悬浮窗授权 → 可选凭据（慢慢买 Cookie / 星罗 apikey）。形态照 Mihon
  `OnboardingStep`：每步 `isComplete` 但**权限步骤不阻断完成**，可跳过、可回退；设置页新增
  "重新查看新手引导"；未完成且仍缺必要权限时首页顶部给一条可关闭的提示条（含重开入口）。
- **应用内强制更新（更新源 Gitee）**：
  - 清单 `update.json` 放仓库根，经 Gitee 镜像以 raw 直链提供（国内可达，实测 0.5s）；
    字段含 `latest / minSupportedVersionCode / forceBelow / rolloutPercent / apkUrls[] /
    sha256 / sizeBytes / notes[] / cooldownHours`。
  - **三级弹窗语义**：低于 `minSupportedVersionCode` 才阻断；低于 `forceBelow` 强提示可跳过；
    其余为常规可选更新（受灰度控制）。
  - **一律 fail-open**（对标 Mihon `GetApplicationRelease`）：清单拉不到 / 解析失败 /
    `schemaVersion` 未知 / `sha256` 不是合法十六进制摘要 / 清单超过 30 天 / 时钟异常超前
    —— 任一情况都**不阻断**用户，静默并记原因。
  - 下载链路对标 azhon/AppUpdate：Range 断点续传 → 落 `.part` → **sha256 校验** → rename →
    FileProvider `content://` → 系统安装器；未授予"安装未知应用"时跳系统设置页；
    连续 3 次失败自动降级为普通提示。三级下载源（Gitee 发行版 → GitHub Release → 手动下载页），
    并提供"复制下载链接"与"我已升级仍提示我"24h 逃生口。
  - 灰度桶首启生成后永久固定（避免今天提示明天不提示）；同一 `generatedAt` 只提示一次；
    设置页"关于"区新增"检查更新 / 当前版本"手动入口。

### Fixed

- **相关性过滤的分词回归（上一轮自己引入的缺陷）**：`QueryRelevance` 先归一化删除空白再切
  token，导致 `oppo x8s` 变成一个必须整体连续命中的巨型 token → 站点写 `OPPO Find X8s` 时
  **全源 0 条** → 无候选、无历史价、盯价入口消失（用户报的"搜 oppo x8s / mate 80 显示不准确、
  跟踪错误"主因）。现改为**按段匹配 + 强制段序 + 相邻段间隔 ≤12 字符**：`Mate80 / Mate 80 /
  MATE 80` 互认，逆序巧合串（`EG80MATE33S`）仍被挡住；配件词表补
  `耳机 / 充电宝 / 移动电源 / 电池 / 保护贴 / 手机膜`。两端（Kotlin 与 `relevance.js`）同步。
- **候选商品不再"取第一条带价"**：新增 `domain/CandidateRanking.kt` 跨源打分
  （品类词 / 规格完整度 / 国补·PLUS / 二手信号 / 多机型混卖 / 价格带偏离中位数 /
  后缀不匹配 / 品牌缺写），`ProductCandidateResolver` 与桌面端 `crawlers/index.js` 均改为
  argmax。实测：`mate 80` 候选 ¥8840.95→¥4079.15、`x8s` ¥108.8 纸品→¥2860、
  `iPhone 15` 二手 ¥4248→新机 ¥7561.01；Pro/Max 只降权**不过滤**，仍留在列表里。
- **社区「值不值」等于没显示**（用户报"社区的值不值没显示清楚"，两层原因各占一半）：
  - 数据层：`SmzdmApi` 的注释写着"票数需进文章页拉取，列表页先置 0"，这是**未验证的推断**——
    实况列表页 SSR 里就带着计数（`span.J_zhi_like_fav[data-zhi-type] > span.unvoted-wrap > span`，
    夹具 `smzdm_faxian.html` 里 16 处、实测 15/0、4/1、0/1…）。旧实现因此**每条都渲染
    「值 0 / 不值 0」+ 一根空进度条**，看着像数据坏了。现由 `extractZhiVotes` 直接从列表页取，
    不多打一次请求；同一 item 同方向重复按钮取最大值而不是相加。
  - 展示层：票数挤在价格右侧的剩余宽度里、用 `labelSmall`(≈11sp) 的次要色渲染，
    真机量到行高只有 14px——有票也看不清。现改为整行 `bodySmall` 主色文案
    「社区 80% 说值（4 值 · 1 不值）」+ 下方整行比例条；**一条票都没有时如实写
    「社区还没人投票」**，不再摆空条冒充结论。
  - 桌面端本来就会进文章页拉票数（`fetchArticleMeta`），显示正常，故本轮只改 Android 侧；
    两端"列表页有票就用列表页"的口径差异记在这里备查。
- **两处"假因果"空态（2026-09-28 真机走查发现）**：
  - 概览页从未搜索过也显示「未匹配到与「」直接相关的商品」——既报了没发生过的过滤失败，
    又把空关键词渲染成一对空引号。现按 `keyword.isBlank()` 分流为「还没搜索：输入商品名，
    或直接粘贴京东 / 淘宝 / 拼多多商品链接」；B站引导卡的「搜索「」」同样补无关键词版本。
  - 盯价页在"已搜到商品、但候选归属不到京东 SKU"时也报「请先搜索商品」，用户照提示再搜一次
    仍然空。现分三态：取历史失败 / 没搜过 / 搜过但无京东 SKU，第三种如实说明曲线与入手建议
    为何留空、以及怎样才能拿到（粘贴京东·淘宝链接，或配慢慢买 Cookie / 星罗 apikey）。
  - 同页更严重的一处：`history == null` 时**整页提前 return**，把下面的盯价入口、
    「盯价检查状态」卡、不可盯原因一起挡掉了。而用户搜到的机型候选恰恰多数来自当当/值得买
    （没有京东 SKU），于是"盯价"这两个字在真机上根本点不到——这正是"跟踪错误"的另一半。
    现在只有"正在取历史"才整页骨架，没有曲线时曲线区显示上面那条如实空态、入口与状态卡照常出现。
- **无障碍识别准确性**（本轮"点击购物平台浮窗不准"的主战场）：
  - 标题提取改为三级——商详 `resource-id` + **`contentDescription`** 白名单（京东/淘宝/PDD
    各一套）→ 促销/参数/评价/推荐位黑名单 → 才退回"最长文本"启发式（旧实现只读 `text`
    且排除可点击容器，常把促销长句或**推荐位里别的商品名**当标题）。
  - 价格提取不再把分期数 / 券面额 / 存储容量当价格，并要求标题与价格出自**同一商品卡片祖先**；
    同时输出**价格口径**（页面价 / 券后价 / 到手价）。
  - **商详页门控**：旧实现只按包名过滤，首页/搜索列表页/购物车同样 emit → 读到别家价格；
    现在非商详不 emit 并主动收起浮窗；离开商详不再残留旧浮窗最长 15 秒。
  - 搜索关键词不再 `take(30)` 盲截断（会把 `OPPO Find X8s` 截成 `OPPO Find X8` 去搜旧款价），
    改由 `util/SearchQueryCleaner` 清洗（去【】前缀、去 `数字GB/Hz/寸/mAh` 与颜色词、
    必要时按 token 边界截）。
  - **状态生命周期**：新一轮搜索复位候选 / 实时价 / 来源 / 到手价（旧实现 `_livePrice`
    从不复位 → 搜 B 商品却显示 A 商品的"本机账号实时价"）；浮窗与概览消费 `staleKeys`，
    展示"上一次商品数据"而不是静默回显。
  - 历史价 URL 只取**与关键词相关**的爆料里的京东 SKU，不再从任意一条爆料抓链接当本商品曲线。
- **比价浮窗信息组织重写**（`ui/components/PriceOverlay.kt`）：默认折叠成**胶囊条**
  （不遮商品价与购买按钮），点开才展开五行层级——当前价 + 口径标签 / 历史位置一句 /
  多平台同款胶囊 / 券（门槛 0 显示"无门槛"）/ 底部灰字"来源 · 时间 · 非实时"；
  缺数据的行整行不出现。硬口径：**只有拿到确定性商品 ID 才显示历史价与多平台比价**，
  仅凭标题相似命中时降级为"识别到标题 · 点击在 App 内搜索"，绝不把别的 SKU 的历史价
  当成当前商品价。拖动改用 Compose `pointerInput`，修掉与内部 `AndroidComposeView`
  抢 `ACTION_DOWN` 导致"能拖不能点"的问题。
- **盯价跟踪链路**（"跟踪错误"的直接原因）：
  - 入口判定 `product?.skuId != null` 只做非空判断 → 无平台 ID 的候选也能建目标；现由
    新增的纯函数 `domain/WatchTargetPolicy` 判定"能不能盯 / 以什么平台+ID 入库"，
    不可盯时**显示具体原因**而不是给了个坏目标。
  - 删除硬编码 `platform="jd"`：非京东候选不再被冒充成京东目标；`productId` 主键格式
    严格校验（旧实现空 SKU 会生成 `"jd:"`，配合 `@PrimaryKey + REPLACE`
    **第二个目标直接覆盖第一个**，标题与目标价被偷换）。同 ID 不同标题时改为弹确认。
  - 检查轮次不再静默：新增「盯价检查状态」卡片（目标数 / 查到价 / 达标 / 三类跳过原因计数 /
    立即检查），连续 2 轮零进展时发**一条可点开的说明通知**；顺带修掉 API 26–32 上
    `POST_NOTIFICATIONS` 判定用错导致降价提醒**永远发不出**的旧缺陷。
  - 默认目标价只认**同一 SKU** 的历史数据；来源不一致时不预填并说明原因。
- **数据源内容准确性专项修复（Android + Desktop 同步）**：针对"搜索结果与关键词不符"的一批
  上游变更与历史实现缺陷逐项修复，并新增夹具化解析测试锁定行为。
  - **当当（Android 主候选源）**：列表页价格节点已改为 `span.search_now_price` /
    `span.search_pre_price`，旧选择器（`.price_n` / `.price_r`）恒为空导致该源**静默返回 0 条**；
    现改为"新选择器 → 旧选择器 → 价格文本"三级取价（`DangdangApi`）。
  - **识货**：旧地址 `www.shihuo.cn/search?keywords=` 已 302 到首页，页面数据是**首页热榜**
    （adidas 板鞋、洗发水……），被当成搜索结果展示；现改用 m 站真接口
    `m.shihuo.cn/search?type=goods&keywords=`，结构不符时返回空并记录原因（`ShihuoApi`）。
  - **京东**：`item.jd.com` 对脚本请求返回风控页（`<title>京东验证</title>`）被当成商品名；
    现解析 `item.m.jd.com` 页内 `window._itemInfo.product`（标题/主图），并提供 `<title>` 兜底。
    另：`p.3.cn` 公开查价接口目前在公网 DNS 不再返回可达地址（AliDNS / 腾讯 DoH 实测均为私网 IP），
    查价失败不再整体丢弃商品——标题/主图照常展示、价格如实置空并提示"请在京东 App 查看"
    （`JdApi` / `crawlers/jd.js`）。
  - **找券**：删除"原价−到手价 ⇒ 券面额"的编造逻辑（会把国补/PLUS 价算成不存在的无门槛券）；
    现在只认显式券文案（`满X减Y(元)优惠券` / `领取X元优惠券`），拿不到就不展示（`GwdangApi` / `gwdang.js`）。
  - **相关性过滤**：新增 `QueryRelevance`（Android）/ `utils/relevance.js`（Desktop），
    对搜索类数据源统一过滤配件（壳/膜/线/"适用 xxx"前缀）、图书说明书（guide/manual/指南…）、
    其它品牌与其它机型条目；修复"搜 iPhone 15 得到手机壳或小米手机"的候选错配。
  - **空态与提示如实化**：找券无显式券时展示"未发现优惠券（仅展示爆料中明确写出的券）"；
    有券但当前价未达门槛时不再显示误导性的"到手价 ¥0"，改为说明"未达券使用门槛"；
    概览在京东价格不可得时提示"价格请在京东 App 查看"；桌面端商品头同理（`--` + 说明）。
  - **京东直查**：价格不可得时不再覆盖已有的实时价候选（无症状覆盖真实价格）；
    早于修复时刻写入的 JD 商品 Room 行不再复用（旧版本可能缓存过风控页标题）。
- **新增测试**：`QueryRelevanceTest` 与 5 组夹具化解析测试
  （夹具取自 2026-09-25 上游实况页面，见 `app/src/test/resources/fixtures/`）；
  测试源集引入 `org.json:json` 参考实现（Android 单测下 org.json 为未实现桩）。
- **新增工具**：`desktop/_crawler_check.js`（桌面端爬虫实况自检）、
  `desktop/_unit_check.js`（用同一批夹具校验 JS 解析与相关性规则，防止两端规则漂移）。
- **桌面端检索词对齐 Android**：关键词搜索时找券/B站/社区改用**用户原始关键词**
  （原为商品全名，命中过窄会导致找券误判"未发现隐藏券"）；链接类输入仍用商品标题。

## [2.5.1]

### Fixed

- Android 分发包更新至 v2.5.1：v2.5.0 发布后的修正版本（该版本未单独保留发布说明，
  详见 [GitHub Release](https://github.com/wuliao00/PriceLens/releases) 页面）。

## [2.5.0] - 2026-08-26

> v2.5.0 是一次大型架构重构的收尾版本：不动功能面、不加新特性，
> 集中偿还结构债——设计体系令牌化、状态与错误解耦、数据源稳定性加固、测试安全网建设。

### Added

- **设计令牌体系**：`ui/theme/` 新增 Color / Type / Shape / Motion / Badge 五套令牌，
  六个屏幕全部引用令牌、零硬编码样式；落实「清晰 / 顺从 / 深度 / 极简」四大设计理念。
- **文案资源化**：`strings.xml` 收敛 129 条文案，为国际化铺路。
- **AsyncValue 状态模型**：每个数据切片独立承载 加载中 / 成功 / 空 / 拦截 / 网络错误 五态，
  `SourceStatusRow` 组件实时展示各数据源健康度。
- **CrawlerResult 四态结果**：`Success / Empty / Blocked / Network` 显式建模，
  终结「吞异常一律返回 null」的旧模型。
- **SourceHealth 源健康降级**：连续失败超阈值暂时跳过该数据源，直接回退旧快照。
- **ApiClient singleflight**：同 key 并发取数合并为一次网络请求，保护目标站点。
- **RateLimiter 熔断持久化**：403 解封时间戳落库（Room `domain_penalties` 表），重启后不立即再撞反爬。
- **AppDatabase v2**：新增 `cache_entries`（通用 L2）与 `domain_penalties` 表。
- **测试安全网**：49 个单元测试（TLRUCache / CachedSource / SourceHealth / ContentRisk /
  PriceFormatter / PriceJudgment / RateLimiter）。
- **CI 门禁升级**：`test + ktlintCheck + assembleDebug`；接入 ktlint
  （`org.jlleitschuh.gradle.ktlint` 12.1.2），规则基线沉淀在根目录 `.editorconfig`。

### Changed

- **上帝 ViewModel 拆分**：`MainViewModel` 拆为 `SearchViewModel` / `PriceWatchViewModel` /
  `ProfileViewModel` + 领域层 `ProductCandidateResolver`。
- **PriceRepository 声明式重写**：每个缓存点只声明 `CachedSource`（key / TTL / 编解码器 /
  源名 / 取数钩子），「L1 内存 TLRU → L2 Room → L3 网络 → 写回」由模板统一执行，
  失败降级返回旧快照。
- **PriceCheckWorker 通用化**：由硬编码京东改为按 `platform` 分发；查价失败按 10 分钟
  线性退避重试，30 分钟周期与电量约束保持不变。
- **文档同步**：`docs/API.md`、`docs/DEVELOPMENT.md`、`README.md` 对齐新架构。

### 升级说明

- 数据库由 v1 迁移至 v2（Room 自动迁移），盯价目标、收藏、搜索记录无损保留。
- 无需重新授权无障碍 / 悬浮窗权限。

---

更早版本的详细发布说明：[RELEASE_NOTES_v2.3.0.md](RELEASE_NOTES_v2.3.0.md)。

[Unreleased]: https://github.com/wuliao00/PriceLens/compare/v2.5.1...HEAD
[2.5.1]: https://github.com/wuliao00/PriceLens/compare/v2.5.0...v2.5.1
[2.5.0]: https://github.com/wuliao00/PriceLens/compare/v2.3.0...v2.5.0
