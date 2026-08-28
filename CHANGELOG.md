# Changelog

本项目所有显著变更记录于此文件。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [SemVer](https://semver.org/lang/zh-CN/)。

## [Unreleased]

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
