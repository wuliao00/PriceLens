# PriceLens 2.9.0 真机验证记录（2026-11-08）

> 对象：Android 端大更新（UI 熔金行情 + 信息源准确性引擎 + 自动追价/价保助手）。
> 设备：vivo V2156A（PD2156），Android 11（SDK 30），1080×2408 / 412dp 宽，中文。
> 包：调试包 `com.pricelens.dev`（versionCode 28 / versionName 2.9.0-dev / label「PriceLens Dev」），
> **与用户正式包 `com.pricelens`（2.8.0.6）共存，不覆盖、不删除用户数据**。
> 截图目录：`docs/verification-2.9.0/round1/`；机器可读证据：`docs/verification-2.9.0/device-db-evidence.json`。

---

## 1. 环境与方法

| 项 | 做法 | 备注 |
|---|---|---|
| 构建 | 本机 Gradle 8.11 + JDK 21，`:app:assembleDebug --configuration-cache --offline` | 4 核 8GB 机器，配置缓存复用后增量 ~24 分钟 |
| 安装 | `adb install -r -t` + vivo 安装页确认（安全检查提示） | 首装被 vivo 装进了访客用户（user 10），用 `pm install-existing --user 0` 修正 |
| 权限 | `settings put secure enabled_accessibility_services` + `accessibility_enabled=1`、`appops set SYSTEM_ALERT_WINDOW allow`、通知设置页手动开启 | **vivo 在 `am force-stop` 后会清掉无障碍开关**：验证流程改为「先启动 App 再开无障碍、之后不再 force-stop」；通知默认被系统关，需在应用通知设置里打开「允许通知」 |
| 网络 | 设备本身无 SIM 服务、WiFi 未连接（`Active default network: none`）。用 **adb reverse + 本机 Python HTTP 代理** 把 PC 互联网共享给设备（`adb reverse tcp:8888 tcp:8888` + 设备 `settings put global http_proxy 127.0.0.1:8888`），验证结束后应清除 | 代理脚本 `_pl_proxy.py`（仅监听 127.0.0.1）；PC 侧直连可达当当/百度，GitHub 在 PC 上不通 |
| 演示数据 | 真机首次启动后 pull `pricelens.db`，`_pl_seed.py` 注入 4 个盯价目标 / 65 个历史日点 / 6 条运行历史 / 3 条购买记录 / 1 个浮窗身份，再 push 回 `databases/` 并清 `-wal/-shm` | **演示价格是构造数据，不是真实行情**；仅用于让 UI 有可读状态；App 自身写入的行（运行历史 2 条）与演示行可区分 |

## 2. 截图证据（round1）

| 截图 | 内容 | 结论 |
|---|---|---|
| `00-overview.png` / `00b-overview-granted.png` / `12-overview-resumed.png` | 概览首屏（金英雄区/双 CTA/追价小卡/更多入口）；权限提示条在授权后 ON_RESUME 消失 | 主题成立；**CTA 文案截断**（已列为 P0-2） |
| `01-watch.png` / `24-watch-top.png` | 盯价页＝自动追价中心首屏（开关/目标/上次·下次/立即运行/频率/仅 WiFi/夜间暂停/隐私） | 信息完整 |
| `10-search-result.png` / `11b-search-proxy.png` | 无网络时的诚实降级：来源健康 0/6，空态写「暂时取不到数据」；接上网络后 3/6 | 失败与空态分开表述，符合设计 |
| `13-search-iphone15.png` / `14-accuracy-expanded.png` | 搜索「苹果 iPhone 15 5G手机」：候选 ¥5,999 + 到手价口径 + 低置信徽标 + 展开证据（冲突/离群/不可达来源） | 准确性链路真机跑通；**爆料价假冲突/布局过窄**（P0-1/P0-3，已修） |
| `16-overview-scroll2.png` | 各源报价（商品现价/爆料价/券后到手/识货）+ 来源健康板 + 详情入口 | 口径标注清楚 |
| `20-product-detail.png` | 商品详情（价格/找券/评测分段、来源健康、历史曲线空态说明） | 空态如实说明「没有归属到京东 SKU 就不猜」 |
| `23-protection-screen.png` | 价保助手（概览 2 笔可申请/¥429/3 笔在盯 + 录入购买表单 6 平台） | 可用；拼多多/京东第三方/唯品会档位齐全 |
| `25-autotrack-center.png` / `27-autotrack-scrolled2.png` / `30-autotrack-history-after-run.png` | 自动化中心：开关/频率/立即运行/来源健康/运行历史/隐私说明 | 运行历史含真机新增行（16:22）；**行摘要重复、异常类名截断**（P1-5/P1-6，已修） |
| `29-autotrack-after-run.png` / `32-run2-done.png` | 真机点「立即运行」后：上次运行「刚刚」、查到价 1、命中 1、已提醒 1、失败源识货、下次预计 16:53 | **自动追价真机全链路成立** |
| `33-notification-shade.png` | 通知栏：「亚瑟士 GEL-KAYANO 31 男款稳定…」正文「同款搜索价 ¥829 ≤ 目标价 ¥899，点按核…」 | 到价通知真机可见，来源口径写明「同款搜索价」 |
| `40–43-dark-*.png` / 价保暗色 | 深色模式：概览/社区/我的/价保助手 | 深色对比度与金色强调成立 |
| `42-dark-community.png` | 社区页：GitHub 镜像加载失败如实提示；但识货列表混入「稳健医疗 Apple…¥13.4」「资源机…」 | **相关性过滤不足**（P1-7，已修） |
| `43-dark-profile.png` | 我的：金卡资料头/统计格/搜索历史 chips/盯价目标列表 | **已暂停行标题被挤成「…」**（P0-4，已修） |

## 3. 功能实测

### 3.1 自动追价（核心新功能）
- 真机连点两次「立即运行」，两轮均落库：`auto_track_runs` 由 6（演示）→ 8 行，新行 16:22 / 16:24，
  `targets=3 checked=1 hits=1`，note 含「同款搜索 3，已提醒 1，失败源 识货」。
- 搜索源真实请求（PC 代理日志）：`search.dangdang.com`（多次，关键词为盯价目标标题）、
  `search.smzdm.com`、`m.shihuo.cn`、`api.bilibili.com`。
- 到价通知进入通知栏（`dumpsys notification`：`channel=price_alert importance=4 actions=2`），
  文案明确「同款搜索价」。
- 调度唯一性：`auto_track_chain` 唯一链 + `WatchCheckRunner` Mutex/60s 合并；
  连点期间未出现重复通知/重复曲线写入（DB 中每个 productId 每天仍只有 1 行日点）。

### 3.2 价保助手
- `purchase_records` 3 行：拼多多（可申请 ¥49、截止 11-12）、京东（可申请 ¥380、截止 11-10）、
  淘宝（已过期，仅展示价格变化，不显示可申请）。
- UI 深色下「现价/实付/可申请/截止日/最近检查 + 去平台申请/复制文案/标记已申请/删除」完整。

### 3.3 信息源准确性
- 引擎输出与 UI 一致：置信档位、headline、reasons（带具体数字与来源）、证据列表（来源/价格/口径/
  年龄/陈旧）、冲突、来源健康板。
- 来源健康板真机数据：当当/购物党/值得买正常；B站/识货「源不可达」；历史价与页面「本轮未查询/未读」。
  ——与 `docs/research/2026-11-信息源准确性-方法论.md` 的端点实测矩阵一致（京东系全部不可达、
  识货返回 null、购物党滑块、慢慢买移动页退化）。
- 已知修正：什么值得买「爆料价」不再作为现价证据参与一致性/离群判定（比值带 0.5~2.0 + basis=REFERENCE），
  避免「配件/引流价把主商品判成离群」。

### 3.4 DB 升级
- 全新安装即 v5（`PRAGMA user_version=5`）：`auto_track_runs` / `purchase_records` 两表按
  `MIGRATION_4_5_SQL` 建出，索引齐全（`index_auto_track_runs_atMs`、`index_purchase_records_platform`、
  `index_purchase_records_purchasedAtMs`）。
- 4→5 迁移的 SQL 与实体 `@ColumnInfo(defaultValue)` 逐字对齐由 `DatabaseMigrationWiringTest` 与
  单测守卫（本机 JVM 测试运行结果见 §5）。

## 4. 本轮发现并修复的缺陷

| # | 缺陷（真机现象） | 根因 | 修复 | round2 复验 |
|---|---|---|---|---|
| P0-1 | 搜索 iPhone 15 被判「低置信/口径冲突」 | 什么值得买爆料价 ¥2,898 被当现价证据（非同款） | 比值带 0.5~2.0 + basis=REFERENCE，不参与现价一致性 | ✅ 变为「中置信 · 单源数据 · 建议页面核价」，假冲突/假离群消失（`round2/10-search-accuracy.png`） |
| P0-2 | 英雄区 CTA「粘贴链接比」「打开自动追」截断 | 两按钮等宽 + maxLines=1 | 缩短标签 + 两行自适应 | ✅ 完整显示「粘贴链接 / 自动追价」（`round2/00-overview.png`） |
| P0-3 | 准确性折叠态右侧竖排长文 | 徽标与 reasons 并排抢宽 | reasons 单独整宽 + maxLines + 展开 | ✅ 「徽标行 + 正文整宽两行」（`round2/10-search-accuracy.png`） |
| P0-4 | 我的/盯价页暂停目标行标题变「…」、无平台徽标 | 行内 title 与徽标/操作抢宽 | 标题两行优先 + `PlatformBadge` + 操作分行 | ✅ 标题完整、平台/暂停徽标齐全（`round2/02-profile.png`、`round2/23-watch-targets.png`） |
| P1-5 | 「降级 · 网络失败: SourceUnreacha…」 | 直接展示异常类名 | 映射中文原因（源不可达/超时/反爬/失败） | ✅ 「B站 · 降级 · 源不可达」，无类名（`round2/30-autotrack-history.png`） |
| P1-6 | 运行历史行摘要重复 | summary 与 note 都含数字 | 摘要行只留时间+命中徽标，note≤2 行 | ✅ （`round2/30-autotrack-history.png`） |
| P1-7 | 社区/识货出现「医疗」「资源机」等不同款 | 仅关键词匹配、无否定词 | `RelevanceGuard`（否定词 + 比值带）+ 列表过滤 | ✅ 仅剩「苹果 iPhone 15 5G手机 ¥5,999 天猫精选」（`round2/20-community-filtered.png`） |

> round1 → round2 的复验流程：增量构建 16m54s → `adb install -r`（vivo 安装页需确认）→ 重新授予无障碍/悬浮窗
> （vivo 在 force-stop 后清开关，安装后必须重授）→ 同一批截图重拍。截图为 round2，见 `docs/verification-2.9.0/round2/`。

## 4.1 复验后的残留与观察（不阻塞发布）

- 盯价页当前商品空态仍写「京东商品请粘贴 item.jd.com 链接或 6 位以上 SKU」——v2.9.0 起淘宝/拼多多/当当/识货
  也能盯（关键词自动搜索），这段旧文案建议下一版改为「粘贴商品链接后即可盯价；无平台 ID 的关键词候选无法建目标」。
- 概览「各源报价」里「爆料价 ¥2,898」仍展示（它已不参与准确性判定），是「社区爆料原值」而非同款现价，后续可加
  「参考，不参与比价」小字。
- 社区「加载失败（网络异常或被反爬拦截）」是 GitHub 镜像在本机网络/代理下不可达的真实呈现，非 UI 缺陷。

## 5. 质量门禁

| 门禁 | 结果 | 证据 |
|---|---|---|
| `:app:assembleDebug` | ✅ 成功（首次 2 个编译错误修复后；最终包 23,755,744 B，sha256 `CEC727FA7058BFC4BFD2AB9151E3D404FE05D31CE0EF7B0A5CB303E13A6CA62D`） | `app/build/outputs/apk/debug/app-debug.apk`（2026-11-08 17:55 构建） |
| `:app:ktlintCheck` | ✅ 通过（先跑一次 `ktlintFormat` 修掉新增文件的换行/参数对齐/import 顺序） | `app/build/reports/ktlint/**`（无 violations 输出） |
| 单元测试（1071 条） | ✅ **OK (1071 tests)**，含新增 accuracy 50 / autotrack 39 / WatchTargetPolicy 38 | `docs/verification-2.9.0/junit-console.txt` |

执行记录（本机 Gradle 8.11 + JDK 21，4 核 8GB）：

```
> Task :app:assembleDebug           BUILD SUCCESSFUL in 16m 54s（首轮增量）→ 4m 30s（最终增量）
> Task :app:ktlintCheck             通过（ktlintFormat 之后）
JUnitCore: Time: 27.912  OK (1071 tests)
```

### 关于「本机 Gradle test 任务红、JUnitCore 绿」

本机仓库路径是中文 `E:\项目\PriceLens`。Gradle 给 Test Worker 写的 `@argfile` 把
`E:\项目` 编码成 `E:\椤圭洰`（UTF-8/GBK 解释错位），worker 的 classpath 里所有项目内路径都不存在，
于是 128 个测试类全部 `ClassNotFoundException` —— 这是**本机路径编码问题**，不是测试失败。
处理方式（两条都验证过）：

1. 建立 ASCII junction `E:\dev\pl-cur -> E:\项目\PriceLens`，用 JUnitCore 直接跑 Gradle 实际解析出的
   classpath（`_pl_run_tests.ps1`），得到 **OK (1071 tests)**；CI 在 ASCII 路径（`/home/runner/work/...`）
   下不受此问题影响。
2. 仓库既有本机流程用 `-I E:\dev\redirect-app-build.init.gradle` 把 `:app` 构建目录整体挪到
   `E:/dev/app-build-out`（ASCII），也是绕开同类路径问题的正规做法。

本轮修掉的 2 条单测失败（都在新增测试里，属真实缺陷而非环境问题）：

- `AutoTrackPolicyTest.no price asks...`：`buySignal` 拿不到价时 detail 里没有「核价」字样 → 文案改为
  「请打开商品页核价（确认当前售价与规格）」；
- `PriceProtectionTest.tier calculation...`：`roundYuan(12.345)` 用二进制浮点四舍五入得 12.34（应 12.35）
  → 改用 `BigDecimal + HALF_UP`（价保金额差一分钱的真实缺陷）。

## 6. 未覆盖 / 限制

1. **正式签名 APK 未出**：本机无 `local.properties` 签名配置；`update.json` 与新正式包需走 CI/维护机流程，本轮不冒进抬清单（避免清单指向不存在的资产）。
2. 京东系端点不可达是客观现状，真机验证的是「如实标注 + 关键词搜索兜底」，不是京东实时价打通。
3. 设备无 SIM/WiFi，网络经 PC 代理共享；代理只做转发，不改变 App 行为。
4. 演示数据中的价格/购买记录为构造值（见 §1），真机自身写入的只有两轮自动追价记录。
5. vivo 会清第三方无障碍开关（force-stop 后），真实用户操作不受影响；这是验证流程注意事项。

## 7. 复验命令（可复制）

```powershell
# 构建（本机 Gradle 8.11）
$g = 'E:\dev\gradle-home\wrapper\dists\gradle-8.11-bin\2eu93z43d2ii82czw0cxl9we6\gradle-8.11\bin\gradle.bat'
Set-Location E:\项目\PriceLens
& $g :app:assembleDebug --configuration-cache --offline --console=plain

# 安装 + 权限（先启动 App，再开无障碍；不要 force-stop）
adb install -r -t app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.pricelens.dev/com.pricelens.ui.main.MainActivity
adb shell settings put secure enabled_accessibility_services com.pricelens.dev/com.pricelens.accessibility.PriceMonitorService
adb shell settings put secure accessibility_enabled 1
adb shell appops set com.pricelens.dev SYSTEM_ALERT_WINDOW allow

# 网络（PC 有网、设备无网时）
py -3 _pl_proxy.py --port 8888        # 另一个终端
adb reverse tcp:8888 tcp:8888
adb shell settings put global http_proxy 127.0.0.1:8888
# 验证结束后清除：adb shell settings put global http_proxy :0
```
