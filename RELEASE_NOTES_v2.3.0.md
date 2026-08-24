# PriceLens v2.3.0 免费开源版 - Release Notes

> **发布日期**：2026-08-24  
> **作者**：莫  
> **协议**：MIT License  
> **GitHub**：https://github.com/wuliao00/PriceLens

---

## 📦 下载地址

| 平台 | 文件 | 大小 | 说明 | 蓝奏云 | 夸克网盘 | GitHub Release |
|------|------|------|------|--------|----------|----------------|
| **Android** | `PriceLens比价助手_v2.3_免费开源_作者莫.apk` | ~18 MB | 无障碍增强版，支持京东/淘宝/拼多多/哔哩哔哩/什么值得买/慢慢买等 | [下载](https://www.ilanzou.com/s/K1DKLuCg) | [下载](https://pan.quark.cn/s/33e192dc914d?pwd=WWnG) | [Release](https://github.com/wuliao00/PriceLens/releases) |
| **Windows** | `PriceLens-2.0.0-win.zip` | 116 MB | 便携版免安装，解压即用，Electron + Vite 构建 | [下载](https://www.ilanzou.com/s/K1DKLuCg) | [下载](https://pan.quark.cn/s/33e192dc914d?pwd=WWnG) | [Release](https://github.com/wuliao00/PriceLens/releases) |

> ⚠️ **安全提醒**：本项目永久免费、无内购、无广告、不收集用户数据。任何收费分发均为欺诈。  
> ⚠️ **蓝奏云无需提取码**，夸克网盘提取码：**WWnG**。两个网盘均为同一文件分享链接。

---

## ✨ v2.3.0 核心亮点

### 📱 Android 端
- **无障碍自动比价**：打开京东/淘宝/拼多多商品页，用本机登录账号实时读价弹浮窗
- **Shizuku 一键授权**（GKD 式）：授权后自动开启无障碍 + 悬浮窗 + 通知
- **自定义脚本**：经 Shizuku 以 ADB 权限执行 shell 脚本（预置 3 个安全脚本）
- **我的 / 设置页**：收藏、盯价管理、搜索历史、缓存治理、动态取色开关
- **60fps 动画铁律**：全部 scale/alpha 走 `graphicsLayer`，颜色动画走 `drawBehind`，时长 ≤ 350ms
- **Material You 动态取色**：Android 12+ 跟随系统主题，暗色模式完美适配
- **分级缓存 ≤ 30MB**：L1 内存 TLRU 8MB + L2 Room 10MB + Coil 10% + OkHttp 5MB
- **反爬规范**：同域名 ≤ 1 req/3s、UA 轮换、403 熔断 5min

### 🖥️ 桌面端（Electron + Vite，v2.0.0）
- **全平台爬虫复用**：京东/淘宝/拼多多/哔哩哔哩/什么值得买/慢慢买/咕咚/Keep 同一套解析器
- **本地优先架构**：SQLite + IndexedDB，无服务端依赖，数据不出本机
- **智能缓存**：LRU + TTL，自动清理过期数据
- **现代 UI**：Vite + 原生 CSS 变量，响应式布局，暗色模式
- **便携版分发**：免安装 ZIP，解压即运行，绿色无残留

---

## 🔧 从源码构建

### Android
```bash
# 1. 配置 SDK
echo "sdk.dir=<你的 Android SDK 路径>" > local.properties

# 2. 调试包（无需签名）
./gradlew :app:assembleDebug

# 3. 签名 Release 包（需在 local.properties 追加）
# PRICLENS_STORE_FILE=app/pricelens.keystore
# PRICLENS_STORE_PASSWORD=****
# PRICLENS_KEY_ALIAS=pricelens
# PRICLENS_KEY_PASSWORD=****
./gradlew :app:assembleRelease
```

### Windows 桌面端
```bash
cd desktop
npm install          # 首次安装依赖
npm run build        # 产出 dist/PriceLens-<version>-win.zip
```

> 💡 `desktop/electron-builder.yml` 已配置仅打包 ZIP 便携版（避免 NSIS 兼容性问题）

---

## 📁 仓库结构

```
PriceLens/
├── app/                          # Android 模块
├── desktop/                      # Electron 桌面端
│   ├── src/main/                 # 主进程（爬虫、缓存、IPC）
│   ├── src/renderer/             # 渲染进程（UI 组件）
│   ├── electron-builder.yml      # 仅打包 zip 便携版
│   └── package.json
├── build.gradle.kts
├── settings.gradle.kts
├── LICENSE
└── README.md
```

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
- [Room](https://developer.android.com/training/data-storage/room) - 本地数据库
- [Electron](https://www.electronjs.org/) / [Vite](https://vitejs.dev/) - 桌面端框架
- 所有贡献者与测试者

---

## 📄 许可证

MIT License - 详见 [LICENSE](LICENSE)

> **永久免费承诺**：没有付费版、没有会员、没有内购。分发请保留本声明与 LICENSE。