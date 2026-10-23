# PriceLens 2.8.0.6

端侧模型下载加速版：国内镜像回退 + 桌面依赖安全升级。

## 新增

- **端侧模型下载支持国内镜像**：候选源按 `gh-proxy.com → ghproxy.net → GitHub` 顺序回退；
  任一源下载完成后仍用内置 SHA-256 校验，坏文件不会安装。
- **网盘下载入口更新**：README 已换成蓝奏云 / 夸克 / 百度 / 123 云盘四个分享链接与提取码。
- **模型镜像说明**：新增 `docs/模型下载镜像.md`，含文件大小、SHA-256、镜像与校验命令。

## 维护

- 桌面依赖锁文件升级：vite 8.3.5、undici 7.30.1 / 6.29.1、fast-uri 3.1.9、
  source-map-js 1.2.2、brace-expansion 1.1.21 / 2.1.7 / 5.0.12；`npm test` 78/78 通过。
- `http-cache-semantics@4.2.0`、`sprintf-js@1.1.3` 上游暂无修复版本，保留告警，不做破坏性替换。
- 桌面安装器（NSIS）本轮未重发；Release 附带的 2.6.0 便携 zip 已用升级后的依赖重新构建。

## 安装

- 与 2.7.0–2.8.0.5 使用同一把 release 钥匙（证书 SHA-256 `c759cbb8…205b5e0`），
  可直接覆盖升级，本机数据不丢。
- 从 2.6.5 及更早版本升级仍需卸载重装一次。

## 产物与校验

- `PriceLens-2.8.0.6.apk` — 3,325,827 B
  - sha256 `4191158593511017f65bbc5c4ba4b653e30ebad2ca5b7fc2e58ba7d642a9232a`
  - 证书 SHA-256 `c759cbb8ca2bda75799d75336d615390ec5737d83cb588703bef09821205b5e0`
  - Gitee 免登录直链：`https://gitee.com/wuliao11541/PriceLens/raw/dist/PriceLens-2.8.0.6.apk`
  - GitHub Release：`https://github.com/wuliao00/PriceLens/releases/download/v2.8.0.6/PriceLens-2.8.0.6.apk`

## 说明

- 端侧模型约 378 MiB（396,705,472 字节），默认不下载，只在设置里手动开启后才会用到。
- 网盘分享页不是直链，应用内自动更新仍以 Gitee raw 为主源；网盘只作为手动下载入口。
