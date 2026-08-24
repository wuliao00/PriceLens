# PriceLens v2.0

> 极简主义全网比价决策工具 —— 30 秒完成「种草 → 盯价 → 找券 → 社区验证」四步购物决策闭环。

![Platform](https://img.shields.io/badge/platform-Windows-blue) ![Electron](https://img.shields.io/badge/Electron-%E2%89%A528-9feaf9) ![Framework](https://img.shields.io/badge/UI-%E5%8E%9F%E7%94%9FJS%20%E9%9B%B6%E6%A1%86%E6%9E%B6-green)

## 功能一览

| 模块 | 数据源 | 说明 |
|---|---|---|
| 🌱 种草 | B 站（WBI 签名） | 评测视频聚合，自动标注 ⚠️避坑 / 👍推荐 |
| 📈 盯价 | 慢慢买 | 手写 SVG 历史价格曲线，历史低价/先涨后降检测，目标价系统通知 |
| 🎫 找券 | 购物党 | 隐藏优惠券 + 到手价 countUp 滚动，一键复制券码 |
| 💬 社区 | 什么值得买 | 最新爆料、值/不值比例条、热评关键词高亮、近 7 天更低价横幅 |
| 🛒 商品 | 京东 p.3.cn | 支持直接粘贴京东/淘宝/B 站链接搜索 |

本地 JSON 缓存（LRU 200MB / TTL / stale-while-revalidate），**断网重启仍可查看上次数据**。

## 环境要求

- Node.js ≥ 18
- Windows 10/11（打包目标）；开发调试在 macOS/Linux 亦可运行

## 快速开始

```bash
# 1. 安装依赖（会自动执行 postinstall 生成 build/icon.ico）
npm install

# 2. 启动应用
npm start

# ── 可选：带热重载的开发模式 ──
npm run dev:web     # 终端 1：Vite 静态服务 (localhost:5173)
npm run dev         # 终端 2：Electron 加载 DevServer
```

### 可选依赖

购物党趋势页是 JS 渲染页面，`undici` 抓不到时自动降级 Playwright：

```bash
npm i -D playwright
npx playwright install chromium
```

未安装时该源给出可操作的降级提示，不影响其他模块。

## 打包分发

```bash
npm run build        # NSIS 安装器 + 便携 zip，输出到 dist/
```

产物（目标 < 80MB）：

- `dist/PriceLens Setup x.x.x.exe` — NSIS 安装器（可自定义目录，卸载时询问是否清缓存）
- `dist/PriceLens-x.x.x-win.zip` — 便携版

## 快捷键

| 快捷键 | 功能 |
|---|---|
| `Ctrl + K` | 聚焦搜索框 |
| `Ctrl + 1~4` | 切换 趋势 / 评测 / 优惠券 / 评论 |
| `Esc` | 关闭弹窗 / 清空搜索 |
| `Ctrl + ,` | 打开设置 |
| `F5` | 强制刷新当前数据（跳过缓存） |

## 数据与缓存目录

```
%APPDATA%/pricelens/
├── cache/            # JSON 缓存（products/history/bilibili/smzdm/coupons）
│   └── _index.json   # 索引：key → { file, ts, ttl }
├── settings.json     # 主题 / 盯价配置
└── logs/crawl.log    # 爬虫日志
```

## 安全架构（红线清单）

- ✅ `nodeIntegration: false` + `contextIsolation: true` + `sandbox: true`
- ✅ 渲染进程仅通过 `preload.js` 的 `contextBridge` 白名单 API 通信
- ✅ 外部数据一律 `textContent` / DOM API 渲染，无 `innerHTML` 插入未消毒数据
- ✅ 禁止新窗口 / 导航劫持 / webview；拒绝全部权限申请
- ✅ 爬虫纪律：同域 ≤1 req/3s、并发域名 ≤3、超时 10s 重试 1 次、403 降级 5 分钟
- ✅ 主进程全部 `fs.promises` 异步 IO

安全自检：`npx electronegativity -c ./src`（应无 HIGH/CRITICAL）。

## 项目结构

```
pricelens/
├── src/main/            # 主进程：窗口 / IPC / 爬虫 / 缓存
│   ├── crawlers/        # bilibili(wbi) / smzdm / manmanbuy / gwdang / jd
│   ├── cache/           # LRU + TTL 缓存管理
│   └── utils/           # http-client / rate-limiter / logger / sanitizer
├── src/renderer/        # 渲染层：零框架原生 HTML/CSS/JS（ES modules）
│   ├── css/             # tokens / base / layout / components / animations
│   └── js/              # app / router / components / utils
├── build/               # icon.ico（脚本生成）+ installer.nsh
└── electron-builder.yml
```

## 免责声明

本项目仅供个人学习与比价研究。数据来自各平台公开页面/接口，请遵守各站点 ToS 与 robots 约定，控制请求频率（应用内置限流）。商业使用请接入官方 API（如京东联盟）。
