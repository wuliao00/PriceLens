# Changelog

本项目所有显著变更记录于此文件。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [SemVer](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Fixed

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
    概览在京东价格不可得时提示"价格请在京东 App 查看"；桌面端商品头同理（`--` + 说明）。
  - **京东直查**：价格不可得时不再覆盖已有的实时价候选（无症状覆盖真实价格）；
    早于修复时刻写入的 JD 商品 Room 行不再复用（旧版本可能缓存过风控页标题）。
- **新增测试**：`QueryRelevanceTest` 与 5 组夹具化解析测试
  （夹具取自 2026-09-25 上游实况页面，见 `app/src/test/resources/fixtures/`）；
  测试源集引入 `org.json:json` 参考实现（Android 单测下 org.json 为未实现桩）。
- **新增工具**：`desktop/_crawler_check.js`（桌面端爬虫实况自检）、
  `desktop/_unit_check.js`（用同一批夹具校验 JS 解析与相关性规则，防止两端规则漂移）。

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
