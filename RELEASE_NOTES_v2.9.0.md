# PriceLens 2.9.0

> Android 大更新：「熔金行情」UI 全量重做 + 信息源准确性引擎 + 自动追价 + 价保助手。
> 真机验证：vivo V2156A / Android 11 / 1080×2408（调试包 `com.pricelens.dev`，与正式包共存）。
> 验证证据链见 `docs/verification-2.9.0/README.md`，研究文档见 `docs/research/`。

## 新增：信息源准确性（`com.pricelens.accuracy`）

- 每个价格都带**来源、口径（页面价 / 券后价 / 到手价）、数据年龄、置信度**，可疑就降权，
  不再只给一个裸价格：概览结果卡与商品详情页共用「置信徽标 + 一键展开证据面板 + 口径标签 +
  冲突说明 + 来源健康板」。
- `PriceAccuracyEngine`（纯函数）：口径分离、5%/15% 双阈值一致性、中位数 + MAD 离群、
  24h 陈旧与「时刻未知」不够新鲜、来源健康板归一化、可解释评分 40/20/20/10/10。
- 数据纪律：空/负/NaN/∞ 一律剔除；爆料价等非同类证据不参与现价一致性（比值带 0.5–2.0），
  避免「配件价把主商品判成离群」的假冲突；新搜索立即置空，不把上一件商品的置信度挂到新结果上。
- 「这条不对」四选一（价格 / 商品 / 过期 / 口径）本机 JSONL 记录，不上传。

## 新增：自动追价（关键词自动搜索 + 自适应调度）

- 非京东平台也能自动追价：新增 `WatchChannel.KEYWORD_SEARCH`，用商品标题关键词走既有搜索源
  （当当 / 识货 / 值得买 + 京东 / 星罗直查兜底）找「同款搜索价」，严格条件（相关性 + 正价 +
  与历史/目标价比值 0.5–2.0）才允许当本轮现价；直查样本优先、搜索价只补缺口，
  参考价（星罗券后历史低价）仍不得触发通知或进曲线。
- `AutoTrackPolicy`（纯函数）：自适应间隔 30/60/120/180/360 分钟（近目标降频、夜间暂停/降频、
  低电 ≤ 20%、仅 WiFi 时等 WiFi）、静默时段、同款匹配、比值带、买前防套信号
  （≤2% 近历史低 / >10% 追 7 天先涨后降 / 单源低置信提示核价）。
- `AutoTrackScheduler`：唯一 WorkManager 一次性链 `auto_track_chain`，同轮不重复查价、不重复通知、
  不重复写曲线；`AutoTrackRepository` tryLock 合并。
- 新增 `auto_track_runs` 表与自动化中心（运行历史、上次/下次、目标/查到价/命中、失败源说明、纯本机隐私说明）。

## 新增：价保助手（买后被套的补救）

- 新增 `purchase_records` 表与 `PriceProtectionRepository` / `PriceProtectionPolicy`（纯函数）：
  按平台档位计算价保窗口（京东自营 7/15/30/90/180/365 天且按签收次日 00:00 起算；京东第三方
  168/360/720/2160 小时；天猫/淘宝 15/30 天；拼多多 7/30/72 小时；唯品会 10 天；其他 7 天并注明以平台为准），
  「可申请金额 = 实付价 − 当前同款最低可信价」（10 元且 ≥ 实付，门槛 max(¥1, 实付×0.5%)）。
- 价保提醒幂等：每 ¥10 一档只提醒一次，更高档且间隔 ≥ 12h 才再提醒，剩余 ≤ 24h 升为高优先级；
  `PriceProtectionWorker` 6 小时巡检；无价保平台只展示价格变化，不叫「价保」。
- UI：价保助手全屏页（录入购买 / 逐条「现价 vs 实付 / 可申请 ¥N / 截止日 / 去平台申请 / 复制申请文案 /
  标记已申请 / 删除」）；商品详情页「我买了 → 加入价保」预填入口；购买记录纳入备份 payload（schema 1，双向兼容）。

## 变更：UI「熔金行情」全量重做

- 品牌色板从「通用蓝」改为「熔金行情」：主金色 `#A16207` / 暗金 `#FBBF24`、亮色暖纸、暗色冷墨；
  29 个 M3 角色显式给值（动态取色默认关，品牌色即默认体验）。
- 卡片圆角 16→20dp、胶囊搜索框、金色品牌标 `ic_brand_mark`、行情曲线（金色主线 + 面积渐变 +
  网格 + 红绿最低最高虚线）、选中态金胶囊 + 图标形变。
- 概览页重写：金色 Hero 区 + CTA、最近搜索、结果 Hero 卡（超大价格 + 口径胶囊 + 准确性栏 +
  买前防套徽标 + 来源健康板 + 曲线卡 + 详情 CTA）；盯价页重排为「自动追价中心」；
  我的 / 设置 / 社区 / 引导 / 详情全部收口；新增「自动化中心」「价保助手」两个全屏路由。
- 深色模式逐屏复验；字体缩放与 48dp 触控、contentDescription 补齐。

## 测试与验证

- 新增 `PriceAccuracyTest` 30 条、`AutoTrackPolicyTest` 23 条、`PriceProtectionTest` 16 条、
  `WatchTargetPolicyTest` 38 条（其中 8 条旧断言按「非京东平台可走关键词搜索盯价」更新）。
- 本机 JUnitCore 全量 1071 条通过；ktlint 通过；CI：Android Build & Test / Desktop / Manifest /
  Security / Pages 全绿。
- 真机取证：自动追价真实运行 2 轮（16:22 / 16:24）——目标 3、查到价 1、命中 1、同款搜索 3，
  失败源识货；通知栏出现「亚瑟士 GEL-KAYANO 31 … 同款搜索价 ¥829 ≤ 目标价 ¥899」；
  DB `user_version=5`、`auto_track_runs` 8 行、`purchase_records` 3 行。

## 版本

- `versionCode 28 / versionName 2.9.0`；调试包 `applicationId` 后缀 `.dev` + 应用名 **PriceLens Dev**
  （真机验证与用户正式包共存，不覆盖用户数据）。

## 安装

- 与 2.7.0–2.8.0.6 使用同一把 release 密钥（证书 SHA-256 `c759cbb8…05b5e0`），
  **可直接覆盖升级，本机数据不丢**；从 ≤ 2.6.5 升级仍需卸载重装一次。
- 正式包由 GitHub Actions release 流程用正式密钥签名产出，产物：
  `PriceLens-2.9.0.apk`。

## 已知限制

- 京东 p.3.cn / item 系端点在本机网络仍全部不可达（实测 TLS 失败/超时），京东目标的自动价
  目前优先走「同款搜索价」（当当/识货），到价通知文案会明确标注来源，不冒充京东实时价。
- 关键词搜索的「同款」判定是启发式（相关性 + 比值带 + 否定词），极端营销标题仍可能误判；
  可按数据继续调阈值（集中在引擎顶部常量）。
- 价保金额为「名义差额」，以平台扣除已退/赠品/返卡后为准；界面标注「以平台规则为准」。

## 产物与校验（2026-11-09 正式发布）

- `PriceLens-2.9.0.apk` — 3,689,348 B
  - sha256 `056650f5f4b3740eb0f762e93fd7695f1604b729ee63c2a90b85cfd7cf8de9c8`
  - 证书 DN `CN=wuliao00, OU=PriceLens, O=PriceLens, L=Internet, C=CN`
  - 证书 SHA-256 `c759cbb8ca2bda75799d75336d615390ec5737d83cb588703bef09821205b5e0`
    （与 2.7.0–2.8.0.6 同一把 release 密钥，可直接覆盖升级）
  - Gitee 免登录直链：`https://gitee.com/wuliao11541/PriceLens/raw/dist/PriceLens-2.9.0.apk`
  - GitHub Release：`https://github.com/wuliao00/PriceLens/releases/download/v2.9.0/PriceLens-2.9.0.apk`
- 宣传片（随 Release 附件）：
  - `PriceLens-2.9.0-promo-1080p.mp4` — 50,734,773 B，sha256 `d3b9122e7423cf83afcf289fd2e1579f63998ea53ce7034cec8b33c3ce005bb9`
  - `PriceLens-2.9.0-promo-720p.mp4` — 7,447,178 B，sha256 `2509a4a1b17bb7ba8ebeb22e3979888790f5f977837994453a151d8508b9b550`
  - `PriceLens-2.9.0-promo-poster.png` — 2,142,546 B
- `update.json` 已回填 `latest.versionCode=28 / versionName=2.9.0 / releaseDate=2026-11-09`、
  `sha256`、`sizeBytes`、双源直链；Gitee `dist` 分支与 GitHub Release 附件由同一签名包同步。
- 构建链路：GitHub Actions release 事件（ubuntu-latest / JDK 21）`assembleRelease`，用正式 release
  keystore 签名；本机另跑过一次 `assembleRelease` R8 烟雾验证（无签名配置，仅验证可编译可混淆）。
