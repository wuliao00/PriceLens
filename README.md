# PriceLens — 极简全网比价助手

> **双端开源** · **永久免费** · **本地优先** · **MIT License**  
> Android 无障碍增强版 + Windows Electron 便携版  
> 作者：**莫** | 版本：Android v2.3.0 / Desktop v2.0.0

---

## 📱 支持平台对比

| 功能 | Android | Windows 桌面端 |
|------|---------|----------------|
| **京东/淘宝/拼多多** 商品页自动读价 | ✅ 无障碍服务 | ✅ 网页解析 |
| **哔哩哔哩** 翻车/推荐视频识别 | ✅ | ✅ |
| **什么值得买/慢慢买/购物党** 爬取 | ✅ | ✅ |
| **咕咚/Keep** 运动装备比价 | ✅ | ✅ |
| 价格历史曲线（最低/最高/大促标注） | ✅ | ✅ |
| 优惠券一键复制 | ✅ | ✅ |
| 盯价后台任务（30min 周期） | ✅ WorkManager | ⏳ 计划中 |
| 自定义脚本 | ✅ Shizuku ADB | ⏳ 计划中 |
| 数据存储 | 本机 Room + TLRU | 本机 SQLite + IndexedDB |
| 分发方式 | APK（需签名） | ZIP 便携版（免安装） |

---

## 🚀 快速开始

### Android 版（手机端）

**下载**：[Release](https://github.com/wuliao00/PriceLens/releases) → `PriceLens比价助手_v2.3_免费开源_作者莫.apk`

**源码构建**：
```bash
# 1. 克隆仓库
git clone https://github.com/wuliao00/PriceLens.git
cd PriceLens

# 2. 配置 SDK
echo "sdk.dir=<你的 Android SDK 路径>" > local.properties

# 3. 调试包（无需签名）
./gradlew :app:assembleDebug

# 4. 签名 Release 包（需在 local.properties 追加签名信息）
# PRICLENS_STORE_FILE=app/pricelens.keystore
# PRICLENS_STORE_PASSWORD=****
# PRICLENS_KEY_ALIAS=pricelens
# PRICLENS_KEY_PASSWORD=****
./gradlew :app:assembleRelease
```

> 🔐 **安全**：`local.properties`、`*.keystore` 已在 `.gitignore`，仓库中**无任何密码**。

---

### Windows 版（桌面端）

**下载**：[Release](https://github.com/wuliao00/PriceLens/releases) → `PriceLens-2.0.0-win.zip`（116 MB，解压即用）

**源码构建**：
```bash
cd desktop
npm install          # 首次安装依赖（需 Node 18+）
npm run build        # 产出 dist/PriceLens-<version>-win.zip
```

> 💡 `electron-builder.yml` 已配置仅打包 ZIP 便携版（避免 NSIS 兼容性问题）。  
> 💡 桌面端爬虫与 Android 端**同源同策略**，解析器复用 `desktop/src/main/crawlers/`。

---

## ✨ 核心特性（v2.3.0 / v2.0.0）

### 🔍 智能比价
- **全平台覆盖**：京东、淘宝、拼多多、哔哩哔哩、什么值得买、慢慢买、购物党、咕咚、Keep
- **实时读价**：Android 端无障碍服务监听商品页变化；桌面端后台爬虫定时抓取
- **价格曲线**：最低/最高虚线标注、当前价脉冲点、大促灰色区间、先涨后降检测（≥7日均价×1.10）

### 🎯 精准决策
- **B站社区验证**：翻车视频红色标记、推荐视频绿色标记、关键词高亮
- **值得买值/不值进度条**：直观判断商品口碑
- **优惠券一键复制**：自动识别可用券，Snackbar 提示复制成功

### ⚡ 极致性能
- **60fps 动画铁律**：`graphicsLayer` + `drawBehind`，时长 ≤ 350ms，无 bounce
- **分级缓存 ≤ 30MB**：L1 内存 TLRU + L2 Room + Coil + OkHttp 精细预算控制
- **反爬规范**：同域名限流、UA 轮换、熔断机制，保护目标站点

### 🎨 Material You 设计
- Android 12+ 动态取色，低版本回退品牌蓝
- 暗色模式跟随系统，圆角 16-12-8、间距 4dp 基准
- 卡片无投影无粗边框，靠 tonalElevation 色差分层

### 🛡️ 隐私优先
- **零埋点、零上报**：所有数据仅存本机
- **权限最小化**：Android 仅需无障碍 + 悬浮窗 + 通知（均可一键关闭）
- **开源透明**：MIT 协议，欢迎审计

---

## 📁 项目结构

```
PriceLens/
├── app/                              # Android 模块
│   └── src/main/java/com/pricelens/
│       ├── accessibility/            # 无障碍服务：监听/匹配/浮窗
│       ├── data/
│       │   ├── remote/               # 爬虫：JD/淘宝/PDD/Bili/值得买/慢慢买
│       │   ├── local/                # Room 数据库 + 实体 + DAO
│       │   ├── cache/                # TLRU 缓存 + 清理 Worker
│       │   └── repository/           # 三级缓存编排
│       ├── ui/
│       │   ├── main/                 # MainActivity / ViewModel
│       │   ├── overview|bilibili|price|coupon|community/
│       │   ├── components/           # SearchBar / PriceCard / PriceOverlay / Shimmer
│       │   └── theme/                # Material You 主题
│       ├── worker/                   # 盯价 Worker
│       ├── util/                     # RateLimiter / WbiSigner / PriceFormatter
│       └── di/                       # Hilt 依赖注入
│
├── desktop/                          # Electron 桌面端
│   ├── src/main/
│   │   ├── crawlers/                 # 同源爬虫：jd/gwdang/bili/smzdm/manmanbuy
│   │   ├── cache/                    # SQLite + LRU/TTL 缓存
│   │   ├── ipc-handlers.js           # 主渲染进程通信
│   │   └── preload.js                # 安全预加载脚本
│   ├── src/renderer/
│   │   ├── js/components/            # UI 组件：价格图表/优惠券/搜索/骨架屏
│   │   ├── css/                      # CSS 变量设计系统
│   │   └── index.html
│   ├── electron-builder.yml          # 仅 zip 便携版
│   └── package.json
│
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── LICENSE
├── README.md                         # 本文件
└── RELEASE_NOTES_v2.3.0.md           # 详细发布说明
```

---

## 🔧 爬虫规范（双端共用）

```
同域名 ≤ 1 req/3s
并发域名 ≤ 3
超时 10s 重试 1 次
UA 轮换 ×5 池
403 熔断 5min
```
> 实现位置：Android → `util/RateLimiter.kt` + `data/remote/ApiClient.kt`  
> Desktop → `desktop/src/main/utils/rate-limiter.js` + `http-client.js`

---

## 🧪 验收清单（对照优化文档 §11）

- [x] 无障碍开启后打开京东商品页自动弹浮窗（价格/商品名/按钮）
- [x] 浮窗不遮挡操作、可拖动、可关闭、15s 自动消失
- [x] B站"翻车"红色 Chip / "推荐"绿色 Chip
- [x] 价格曲线：最低/最高虚线 + 当前脉冲点 + 大促灰线
- [x] "先涨后降"检测（≥7日均价 × 1.10）
- [x] 券一键复制 + Snackbar；值得买关键词高亮 + 值/不值进度条
- [x] 动画全部 `graphicsLayer` / `drawBehind` 绘制通道
- [x] 暗色模式跟随系统；后台盯价 30min 周期 + 电量约束
- [x] 缓存分级预算 ≤ 30MB；APK < 20MB（无序列化框架、无图表库）
- [ ] 真机指标（冷启动 < 800ms、60fps、Storage 占用）需实机验证

---

## ⚠️ 已知边界

1. **首次构建需 Android SDK**：本仓库为完整源码，未预编译；请在 Android Studio 中 Sync 后 Build
2. **电商控件 ID 随版本变化**：`PriceNodeMatcher` 三张 ID 表需实机用 Layout Inspector 校准；启发式兜底已保证不至于完全失效
3. **页面解析器随目标站改版需同步调整**：桌面端与 Android 端爬虫同源，改版需双端同步
4. **桌面端盯价/脚本功能计划中**：当前桌面端为查询型工具，后台盯价与自定义脚本仅 Android 端支持

---

## 🤝 贡献指南

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feat/your-feature`
3. 提交变更：`git commit -m "feat: your feature"`
4. 推送分支：`git push origin feat/your-feature`
5. 发起 Pull Request

> 欢迎 PR：爬虫适配、UI 优化、性能调优、文档完善、国际化...

---

## 📄 许可证

**MIT License** - 详见 [LICENSE](LICENSE)

> **永久免费承诺**：没有付费版、没有会员、没有内购。  
> 任何收费分发行为均为欺诈。分发请保留本声明与 LICENSE。

---

## 🔗 相关链接

- **GitHub 仓库**：https://github.com/wuliao00/PriceLens
- **Issue 反馈**：https://github.com/wuliao00/PriceLens/issues
- **Release 下载**：https://github.com/wuliao00/PriceLens/releases
- **Release Notes 详细版**：[RELEASE_NOTES_v2.3.0.md](RELEASE_NOTES_v2.3.0.md)