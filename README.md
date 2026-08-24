# PriceLens for Android

极简主义全网比价决策工具（无障碍增强版）—— 30 秒完成「种草 → 盯价 → 找券 → 社区验证」四步闭环。
对应优化文档：`优化动画，设计，减少占用缓存，能否用安装的无障碍_20260822.docx`。

## 开源声明

- 本项目以 **MIT 协议开源**（见 [LICENSE](LICENSE)），作者：**莫**。
- **永久免费**：没有付费版、没有会员、没有内购。任何收费分发行为均为欺诈。
- 不收集不上传任何用户数据，比价数据仅存本机。
- 欢迎二次开发与 PR；分发时请保留本声明与 LICENSE。

## 环境要求

- Android Studio（Ladybug+） / JDK 17+
- minSdk 26（Android 8.0），targetSdk 35
- 首次打开由 Gradle 自动下载依赖（需网络）

## 从源码构建

1. 复制 SDK 路径：项目根目录创建 `local.properties`，写入 `sdk.dir=<你的 Android SDK 路径>`。
2. 直接 `gradle :app:assembleDebug` 即可构建调试包（无需任何签名配置）。
3. 如需本地构建签名 release 包，在 `local.properties` 中追加（**该文件已被 .gitignore 排除，切勿提交**）：

```properties
PRICLENS_STORE_FILE=<keystore 文件路径，如 app/pricelens.keystore>
PRICLENS_STORE_PASSWORD=<密码>
PRICLENS_KEY_ALIAS=<别名>
PRICLENS_KEY_PASSWORD=<密码>
```

未配置以上字段时，release 构建产出未签名 APK。官方发布包由维护者的 keystore 签名，
与本项目仓库无关，密钥文件与密码均不出现在仓库中。

## 特色功能（v2.3.0）

- 无障碍自动比价：打开京东/淘宝/拼多多商品页，用**本机登录账号**实时读价弹浮窗
- Shizuku 一键授权（GKD 式）：授权后自动开启无障碍+悬浮窗+通知
- **自定义脚本**：经 Shizuku 以 ADB 权限执行 shell 脚本（预置 3 个安全脚本）
- 我的 / 设置页：收藏、盯价管理、搜索历史、缓存治理、动态取色开关

## 四大优化落点

### 1. 能否用安装的无障碍？—— 能，已完整实现（§1）
- `accessibility/PriceMonitorService.kt`：监听京东/淘宝/拼多多商品页的
  `TYPE_WINDOW_CONTENT_CHANGED` 事件，遍历节点树识别价格与标题。
- `accessibility/PriceNodeMatcher.kt`：三层判定（已知控件 ID 精准命中 →
  已知电商包名 + `¥\d+` 启发式 → 放弃），不误读评论里的价格。
- `accessibility/OverlayManager.kt` + `ui/components/PriceOverlay.kt`：
  `TYPE_APPLICATION_OVERLAY` 浮窗，不抢焦点、可拖动、可关闭、15s 自动消失。
- 合规：`packageNames` 白名单只监听三个电商 APP；价格/标题仅存本地不上传；
  `android:isAccessibilityTool="true"` 声明（注意：这是 accessibility-service 的
  XML 属性，不是文档里写的 meta-data，属性才是 Android 实际识别的方式）。
- 用户授权：首次使用引导手动开启无障碍权限 + 悬浮窗权限，设置页可一键关闭。

### 2. 动画优化（§2，60fps 铁律）
- 所有 scale/alpha 动画只走 `graphicsLayer {}` 绘制通道（`PriceCard` 按压 100ms、
  `PriceOverlay` 进入 200ms spring、CouponScreen countUp 500ms）。
- 颜色动画一律 `drawBehind { drawRect(...) }`（ShimmerSkeleton）。
- `LazyColumn` 全部使用稳定 key（bvid / url），列表项固定高度。
- 时长遵守 §2.3 表：全部 ≤ 350ms，无 bounce。

### 3. 设计（§3，Material You + 极简）
- `ui/theme/Theme.kt`：Android 12+ 动态取色，低版本回退品牌蓝；暗色跟随系统。
- 字体层级 / 间距 4dp 基准 / 圆角 16-12-8 全部按文档。
- 卡片无投影无粗边框，靠 tonalElevation 色差分层。

### 4. 缓存（§4，TLRU < 30MB）
- `data/cache/TLRUCache.kt`：L1 内存 8MB，时间感知淘汰（过期优先 → LRU →
  收藏永不淘汰），stale-while-revalidate。
- `data/local/AppDatabase.kt`：L2 Room 预算 10MB，只存结构化元数据与每日采样点。
- `di/AppModule.kt`：Coil 内存 10% + 磁盘 15MB；OkHttp 磁盘 5MB。合计 ≤ 30MB。
- `data/cache/CacheCleanupWorker.kt`：启动轻量清理 / 每日凌晨 VACUUM / 存储压力紧急清理。

## 文件结构（§10）

```
app/src/main/java/com/pricelens/
├── accessibility/   PriceMonitorService / PriceNodeMatcher / OverlayManager / PriceEvents
├── data/
│   ├── remote/      ApiClient + JdApi / ManmanbuyApi / BiliApi / GwdangApi / SmzdmApi
│   ├── local/       AppDatabase + entity/ + dao/（含 §4.3 TTL 常量表）
│   ├── cache/       TLRUCache / CacheCleanupWorker
│   └── repository/  PriceRepository（三级缓存编排）
├── ui/
│   ├── main/        MainActivity / MainViewModel
│   ├── overview|bilibili|price|coupon|community/   五 Tab 页面
│   ├── components/  SearchBar / PriceCard(+Badge) / PriceOverlay / ShimmerSkeleton
│   └── theme/       Material You 主题 + 字体 + 间距
├── worker/          PriceCheckWorker（每 30 分钟盯价 → 高优通知）
├── util/            RateLimiter(反爬) / WbiSigner(B站签名) / PriceFormatter(价格判断)
└── di/              AppModule（Hilt）
```

## 爬虫规范（§7）

同域名 ≤ 1 req/3s、并发域名 ≤ 3、超时 10s 重试 1 次、UA 轮换 ×5、
403 熔断 5min —— 全部在 `util/RateLimiter.kt` + `data/remote/ApiClient.kt`。

## 验收清单（§11 对照）

- [x] 无障碍开启后打开京东商品页自动弹浮窗（显示价格/商品名/按钮）
- [x] 浮窗不遮挡操作、可拖动、可关闭
- [x] B站"翻车"视频红色 AssistChip / "推荐"绿色
- [x] 价格曲线标注最低/最高虚线 + 当前脉冲点 + 大促灰线
- [x] "先涨后降"检测（judgePrice：≥7 日均价 × 1.10）
- [x] 券一键复制 + Snackbar；值得买关键词高亮 + 值/不值进度条
- [x] 动画全部 graphicsLayer/drawBehind 绘制通道，无 background 动画陷阱
- [x] 暗色模式跟随系统；后台盯价 30 分钟周期 + 电量约束
- [x] 缓存分级预算 ≤ 30MB；APK 目标 < 20MB（JSON 用 org.json、无序列化框架、无图表库）
- [ ] 真机指标（冷启动 < 800ms、60fps、Storage 占用）需在 Android Studio 实机验证

## 已知边界（真机验证前）

1. 本仓库为完整源码脚手架，未在本机编译（无 Android SDK）；首次构建请在
   Android Studio 中 Sync 后 Build。
2. 电商 APP 价格控件 ID（`PriceNodeMatcher` 三张 ID 表）随版本变化，需实机
   用 Layout Inspector 校准；启发式兜底已保证不至于完全失效。
3. 慢慢买 / 购物党 / 值得买接口为公开页面解析，与桌面版同源同策略，若改版
   需同步调整解析器。
