# PriceLens v2.5.0 免费开源版 - Release Notes

> **发布日期**：2026-08-26  
> **作者**：莫  
> **协议**：MIT License  
> **GitHub**：https://github.com/wuliao00/PriceLens

---

## 🎯 版本定位

v2.5.0 是一次**大型架构重构的收尾版本**：不动功能面、不加新特性，把过去几个版本积累的
结构债一次还清——设计体系令牌化、状态与错误解耦、数据源稳定性加固、测试安全网建设。
用户体验不变，但代码的可维护性、可观测性与后续迭代速度大幅提升。

---

## ✨ v2.5.0 核心内容

### 🎨 设计体系（阶段4）

- **设计令牌体系**：`ui/theme/` 新增 Color / Type / Shape / Motion / Badge 五套令牌，
  颜色、字阶、圆角、动效曲线一处定义；六个屏幕全部引用令牌，零硬编码样式。
- **四大设计理念落地**：
  - 清晰 —— 令牌统一信息层级；
  - 顺从 —— 顶栏随滚动自动隐去，状态单向流动；
  - 深度 —— 长按卡片浮起 + 场景转场（全部走 `graphicsLayer`，时长 ≤ 350ms）；
  - 极简 —— AppTopBar / SearchBar / PriceCard / SourceStatusRow / EmptyState /
    ShimmerSkeleton 等通用组件六屏复用。
- **文案资源化**：`strings.xml` 收敛 129 条文案，为国际化铺路。

### 🧩 状态解耦（阶段2）

- **上帝 ViewModel 拆分**：`MainViewModel` 拆为 `SearchViewModel`（搜索编排）/
  `PriceWatchViewModel`（盯价目标）/ `ProfileViewModel`（个人页）+ 领域层
  `ProductCandidateResolver`（多源商品候选解析）。
- **AsyncValue 状态模型**：取代"一个全局 loading + 一个全局 error 字符串"，
  每个数据切片独立承载 加载中/成功/空/拦截/网络错误 五态。
- **Worker 通用化**：`PriceCheckWorker` 由硬编码京东改为按 `platform` 分发——
  京东走 p.3.cn 批量查价既有路径；暂无查价通道的平台记录日志并跳过；
  失败返回 `Result.retry()` + 10 分钟线性退避，30 分钟周期与电量约束保持不变。

### 🚦 错误可视化（阶段2）

- **CrawlerResult 四态结果**：`Success / Empty / Blocked / Network` 显式建模，
  终结"吞异常一律返回 null"的旧模型——真没数据、被反爬、网络挂了，上层终于分得清。
- **反爬拦截可见**：`Blocked` 映射为 `AsyncValue.Error`，UI 给出明确提示而非空白；
  `SourceStatusRow` 组件实时展示各数据源健康度。
- **日志统一**：`util/LogT` 薄封装，统一 TAG、Release 静默零开销。

### 🛡️ 数据源稳定性（阶段3）

- **PriceRepository 声明式重写**：每个缓存点只声明 `CachedSource`（key / TTL /
  编解码器 / 源名 / 取数钩子），「L1 内存 TLRU → L2 Room → L3 网络 → 写回」
  由模板统一执行，失败降级返回旧快照。
- **SourceHealth 源健康降级**：连续失败超阈值暂时跳过该源，直接回退旧快照；
  `staleKeys` 可观察，UI 可提示"旧数据"。
- **ApiClient singleflight**：同 key 并发取数合并为一次网络请求，保护目标站点。
- **RateLimiter 熔断持久化**：403 解封时间戳落库（Room `domain_penalties` 表），
  重启后不会立即再撞反爬。
- **AppDatabase v2**：新增 `cache_entries`（通用 L2）与 `domain_penalties` 表。

### 🧪 测试安全网

- **49 个单元测试全绿**：TLRUCache 淘汰/收藏/过期行为（10）、CachedSource 三级回落（8）、
  SourceHealth 降级（5）、ContentRisk 词库（7）、PriceFormatter（5）、
  PriceJudgment 先涨后降（7）、RateLimiter 限流熔断（7）。
- **CI 门禁升级**：GitHub Actions 由 `test + assembleDebug` 升级为
  `test + ktlintCheck + assembleDebug`；ktlint（`org.jlleitschuh.gradle.ktlint` 12.1.2）
  首次接入即完成全仓一次性格式化，规则基线沉淀在根目录 `.editorconfig`
  （max_line_length=140、设计令牌命名豁免等）。

### 📚 文档同步

- `docs/API.md`：错误码章节对齐 `CrawlerResult` 四态；修正不存在的统一 `Crawler` 接口
  描述为真实的「解析器 + ApiClient 管线 + PriceRepository 编排」；平台能力矩阵按实际更新。
- `docs/DEVELOPMENT.md`：文件名对齐新架构（`PriceMonitorService`、分片 ViewModel、
  `*Api` 解析器），补充 Windows 测试命令 `.\gradlew.bat test`。
- `README.md`：新增设计章节（四大设计理念 + 落点）与架构章节
  （分片 ViewModel、三级缓存、源健康降级）。

---

## 📦 构建与验证

| 项目 | 结果 |
|------|------|
| 单元测试 | ✅ 49 / 49 通过（`.\gradlew.bat test`） |
| ktlint 门禁 | ✅ `.\gradlew.bat ktlintCheck` 通过 |
| Debug 构建 | ✅ `:app:assembleDebug` |
| Release 构建 | ✅ `:app:assembleRelease`（仓库无签名配置时产出未签名包，属正常） |
| APK 体积 | ✅ 远低于 20MB 预算：Release（未签名）≈ 1.90 MB / Debug ≈ 18.19 MB |

### 源码构建

```bash
# 1. 配置 SDK
echo "sdk.dir=<你的 Android SDK 路径>" > local.properties

# 2. 单测 + 风格门禁 + 调试包
./gradlew test ktlintCheck :app:assembleDebug

# 3. 签名 Release 包（需在 local.properties 追加）
# PRICLENS_STORE_FILE=app/pricelens.keystore
# PRICLENS_STORE_PASSWORD=****
# PRICLENS_KEY_ALIAS=pricelens
# PRICLENS_KEY_PASSWORD=****
./gradlew :app:assembleRelease
```

---

## ⬆️ 升级说明

- 数据库由 v1 迁移至 v2（Room 自动迁移），盯价目标、收藏、搜索记录无损保留。
- 盯价周期仍为 30 分钟（网络可用 + 电量不低），新增失败退避：查价失败后
  按 10 分钟线性退避重试，不再静默丢轮次。
- 无需重新授权无障碍/悬浮窗权限。

---

## 🛡️ 安全与隐私

- ✅ **无埋点、无上报**：所有比价数据仅存本机
- ✅ **敏感文件已排除**：`local.properties`、`*.keystore`、`*.apk`、`node_modules/`、`dist/` 均在 `.gitignore`
- ✅ **签名配置外置**：`app/build.gradle.kts` 从 `local.properties` 读取，仓库中无任何密码
- ✅ **开源透明**：MIT 协议，欢迎审计、PR、二次开发

---

## 🙏 致谢

- [Shizuku](https://github.com/RikkaApps/Shizuku) - 免 Root 无线调试授权
- [Coil](https://coil-kt.github.io/coil/) - 图片加载
- [Room](https://developer.android.com/training/data-storage/room) / [WorkManager](https://developer.android.com/jetpack/androidx/releases/work) - 本地数据与后台调度
- [ktlint](https://pinterest.github.io/ktlint/) - Kotlin 风格门禁
- 所有贡献者与测试者

---

## 📄 许可证

MIT License - 详见 [LICENSE](LICENSE)

> **永久免费承诺**：没有付费版、没有会员、没有内购。分发请保留本声明与 LICENSE。
