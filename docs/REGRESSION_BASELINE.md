# 回归基线（Regression Baseline）

> **基线版本**：v2.4.4（versionCode 12）
> **基线日期**：2026-08-25
> **用途**：大型重构（v2.5.0）各阶段完成后，逐项对照本文档验证行为无回归。
> 本文档由「阶段0：测试安全网与回归基线」建立，后续阶段只可追加结果，不可删改基线条目。

---

## 1. 功能回归清单

对照 README「验收清单（优化文档 §11）」，每项在基线版本上均为已通过状态：

| # | 回归项 | 基线行为（v2.4.4） | 验证方式 | 自动化覆盖 |
|---|--------|--------------------|----------|------------|
| 1 | 无障碍浮窗自动弹出 | 开启无障碍后打开京东商品页，自动弹出浮窗（价格/商品名/按钮） | 真机 | 无（依赖无障碍服务，需真机） |
| 2 | 浮窗交互 | 浮窗不遮挡操作、可拖动、可关闭、15s 自动消失 | 真机 | 无（同上） |
| 3 | B 站翻车/推荐 Chip | "翻车"红色 Chip / "推荐"绿色 Chip | 真机 | 部分（`ContentRiskTest` 覆盖命中规则） |
| 4 | 价格曲线 | 最低/最高虚线 + 当前脉冲点 + 大促灰线 | 真机目视 | 无（纯绘制层） |
| 5 | "先涨后降"检测 | current ≥ 近 7 点均价 × 1.10 → 疑似；current ≤ 历史最低 × 1.05 → 低价 | 真机 + 单测 | `PriceJudgmentTest` |
| 6 | 券一键复制 | 复制券码 + Snackbar 反馈 | 真机 | 无 |
| 7 | 值得买信息 | 关键词高亮 + 值/不值进度条 | 真机 | 无 |
| 8 | 动画绘制通道 | 全部走 `graphicsLayer` / `drawBehind` | 代码走查 | 无 |
| 9 | 暗色模式 | 跟随系统深浅色切换 | 真机 | 无 |
| 10 | 后台盯价 | 30min 周期 + 电量约束（WorkManager） | 真机/日志 | 无 |
| 11 | 缓存预算 | 分级缓存 ≤ 30MB；APK < 20MB | 构建产物 + 真机 | 部分（`TLRUCacheTest` 覆盖淘汰/容量） |
| 12 | 真机性能指标 | 冷启动 < 800ms、60fps、Storage 占用 | 真机实机验证 | 无 |

> 浮点边界注记（回归项 5，v2.4.4 实测基线）：因 1.10 的 double 表示 ≈ 1.1000000000000001，均价恰为 100 时 current=110.0 实际判为 NORMAL（名义边界点等效严格 >）。重构时不得"修复"此行为，除非产品确认。

## 2. 自动化测试基线（阶段0 建立）

单测目录：`app/src/test/java/com/pricelens/`

| 测试类 | 被测对象 | 用例数 | 覆盖要点 |
|--------|----------|--------|----------|
| `util/PriceFormatterTest` | `PriceFormatter.format/formatRaw` | 5 | ¥ 前缀、千分位、两位小数、进位、零值 |
| `util/PriceJudgmentTest` | `judgePrice()` | 7 | 空历史、LOW/NORMAL/SUSPICIOUS 阈值边界、LOW 优先、近 7 点均价窗口、标签文案 |
| `data/cache/TLRUCacheTest` | `TLRUCache` | 10 | put/get、字节数统计、过期 stale-while-revalidate、clearExpired、过期优先/LRU 淘汰、pin 豁免、onStale 异步回调（虚拟时钟 + Turbine） |
| `util/RateLimiterTest` | `RateLimiter` / `UserAgents` | 7 | 结果透传、403 熔断生效与过期、同域节流间隔、跨域不互等、并发域名信号量上限、UA 池周期 5 轮换 |
| `util/ContentRiskTest` | `ContentRiskRules.assess` | 7 | 商单/夸大词命中、词库顺序取首中、大小写不敏感、联合投稿标记、双标记叠加 |

**合计 36 个用例**。重构各阶段提交前必须 `.\gradlew.bat test` 全绿。

### 说明

- `RateLimiter` 节流间隔直接读真实墙钟（不可注入时钟），测试采用「缩短间隔 + 真实计时」验证，未使用 Robolectric；该类无 `android.util.Log` 等 Android 依赖，可直接跑 JVM 单测。
- 5 个目标类均为纯 JVM 实现，无类因 Android 框架依赖被跳过。
- `util/` 其余类（`ShizukuHelper`、`ScriptStore`、`UrlOpener`、`WbiSigner` 等）依赖 Android 框架或本次范围外，暂不覆盖。

## 3. 基线命令

```powershell
# 项目根目录执行
.\gradlew.bat test                 # 单元测试（必须全绿）
.\gradlew.bat :app:assembleDebug   # Debug 构建（必须成功）
```

## 4. 阶段对照记录

| 阶段 | 完成日期 | `test` 结果 | 真机回归项 | 备注 |
|------|----------|-------------|-----------|------|
| 阶段0（本基线） | 2026-08-25 | 见阶段0报告 | — | 建立基线 |
| | | | | |

## 5. 追加：2026-09-30 全量清单与两条基线修正（不改上文条目）

**全量单测清单**（v2.6.4 / versionCode 18，main `0a442b0`；命令
`gradle :app:testDebugUnitTest --offline`，在纯 ASCII 副本里跑 —— 仓库路径含中文时
`test` 任务必报 `ClassNotFoundException`）：

**36 个测试类 / 292 个用例 / 0 failures / 0 errors / 0 skipped**，ktlint 两个 check 退出码 0。
下表由本次运行的 `test-results/testDebugUnitTest/TEST-*.xml` 逐个求和生成，不是手抄：

| 测试类 | 用例 | 失败/错误 |
|---|---|---|
| `accessibility/InvisibleTextSanitizingTest` | 5 | 0 |
| `accessibility/PageGatingTest` | 8 | 0 |
| `accessibility/PriceExtractionTest` | 12 | 0 |
| `accessibility/RealDetailShapeGateTest` | 4 | 0 |
| `accessibility/RealDumpGatingTest` | 8 | 0 |
| `accessibility/TitleExtractionTest` | 6 | 0 |
| `data.cache/TLRUCacheTest` | 10 | 0 |
| `data.remote/CrawlerOutcomeMappingTest` | 8 | 0 |
| `data.remote/DangdangParserTest` | 4 | 0 |
| `data.remote/GwdangCouponTest` | 4 | 0 |
| `data.remote/JdItemPageTest` | 3 | 0 |
| `data.remote/ManmanbuyHistoryPageTest` | 5 | 0 |
| `data.remote/ShihuoParserTest` | 5 | 0 |
| `data.remote/SmzdmParserTest` | 3 | 0 |
| `data.remote/SourceUnreachableTest` | 6 | 0 |
| `data.repository/CachedSourceTest` | 8 | 0 |
| `data.repository/OverlayBundleDataAgeTest` | 6 | 0 |
| `data.repository/SourceHealthTest` | 5 | 0 |
| `data.repository/SourceUnreachableCacheTest` | 4 | 0 |
| `domain/CandidateRankingTest` | 13 | 0 |
| `domain/WatchPriceSourceTest` | 3 | 0 |
| `domain/WatchTargetPolicyTest` | 36 | 0 |
| `ui.common/EmptyStateCauseTest` | 7 | 0 |
| `ui.components/OverlayHistoryLineHonestyTest` | 8 | 0 |
| `ui.components/SourceChipStateTest` | 7 | 0 |
| `ui.onboarding/OnboardingLogicTest` | 5 | 0 |
| `ui.settings/ManmanbuyProbeCopyTest` | 3 | 0 |
| `update/UpdateEvaluatorTest` | 21 | 0 |
| `update/UpdateManifestTest` | 15 | 0 |
| `util/ContentRiskTest` | 7 | 0 |
| `util/PriceFormatterTest` | 5 | 0 |
| `util/PriceJudgmentTest` | 7 | 0 |
| `util/QueryRelevanceTest` | 22 | 0 |
| `util/RateLimiterTest` | 7 | 0 |
| `util/SearchQueryCleanerTest` | 9 | 0 |
| `util/TimeAgoTest` | 3 | 0 |

### 修正一：回归项 1、2 的"基线已通过"曾被证伪

第 1 节把「无障碍浮窗自动弹出」「浮窗交互」记为 v2.4.4 已通过。2026-09-29/30 真机取证证明：
A2 改造后浮窗门控的兜底写成 `hasBuyNow && priceHit.viaKnownId`，而**现版京东商详页
385 个节点里 127 个带 `resource-id`、语义命名的 id 为 0 个**（全是混淆短名），
`viaKnownId` 恒假 → 真机商详页**浮窗根本不弹**。也就是说这两项在 v2.5.x / v2.6.0~2.6.3 期间
是"基线记着通过、实际不可用"的状态。

直到 v2.6.4（`43e16d9` 去掉死条件 + `ac374be` 清洗零宽字符）才第一次拿到窗口级证据：
焦点在 `com.jd.lib.productdetail.ProductDetailActivity` 的同时存在
`Window{com.pricelens:712ca01 u0 com.pricelens}`，画面为「页面价 ¥1,759」气泡，
展开后含标题、券、「来源 什么值得买 · 刚刚 · 非实时」。

**教训**：真机项写"已通过"必须同时留下**可复验的判据**（一条命令 + 期望输出），
否则一个假通过能跟着基线活好几个月。

### 修正二：浮窗与无障碍取证的两条判据（此前一直用错）

- 查浮窗在不在：`adb shell dumpsys window windows | grep -oE 'Window\{com\.pricelens:[0-9a-f]+ u0 com\.pricelens'`。
  浮窗窗口名是 `com.pricelens:HASH u0 com.pricelens`，**不带 activity 名**；
  用 `grep 'com.pricelens/'`（"包名/活动名"那种形状）会 100% 漏掉，
  本轮就是因此把已经弹出来的浮窗反复判成"没弹"。
- 无障碍服务运行时 `uiautomator dump` **必失败**（`IllegalStateException: UiAutomationService
  already registered!`）；而京东商详页即使关掉无障碍也 dump 不出来（秒跳倒计时 + 直播浮窗
  使窗口永不 idle）。要看服务实际看到的节点树，只能给它自己加 `BuildConfig.DEBUG` 门控的
  自 dump（写 `files/a11y_dump/`，`run-as` 取回；**取证完必须删除代码与设备文件**）。
- 覆盖安装（`adb install -r`）会重置无障碍授权；重新授权时 `settings put secure
  enabled_accessibility_services` 必须写**全限定组件名**
  `com.pricelens/com.pricelens.accessibility.PriceMonitorService`，
  写 `com.pricelens/.accessibility.X` 这种短名会被系统约 10 秒后收回（真机对照实测）。

## 6. 追加：2026-09-30 下午（盯价自建曲线 + v2→v3 迁移的真机取证）

上文第 5 节的 36 类 / 292 条基线之后，`feat/self-curve`（194fc14）与随后的两处口径修正、
一条 DI 修复合入 main，全量门禁变成 **40 类 / 322 条 / 0 失败**：

| 新增测试类 | 用例 | 守的是什么 |
|---|---|---|
| `data.local/DayCurveTest` | 14 | 收盘点=当日最后一个观测、当日至低只降不升、0 价与 referenceOnly 不产点、**出处记写入通道** |
| `data.repository/CurveMergeTest` | 6 | 外源点为骨架 + 自采点补缺、同日外源优先、0 价不进线、升序且每日一点 |
| `data.repository/CurveProvenanceTest` | 6 | 脚注的出处统计：认不出的来源只能写「来源未记录」，不许猜 |
| `data.local/DatabaseMigrationWiringTest` | 4 | **迁移有没有真被接上进运行时**（见下） |

计数核对：292（第 5 节）+ 19（曲线）+ 6（CurveMerge）+ 4（迁移接线）+ 1（DayCurve 补的一条）= 322。

### 这一轮最重要的一条：量具全绿，真机一开库就崩

`194fc14` 的交付说明自己标了"未验证：Room 运行时 identityHash 校验、真机盯价一轮"。
把那条补上之后立刻兑现 —— 装完 2.6.5 冷启动即 FATAL：

```
java.lang.IllegalStateException: A migration from 2 to 3 was required but not found.
    at androidx.room.BaseRoomConnectionManager.onMigrate(RoomConnectionManager.kt:224)
```

仓库里有两处 `Room.databaseBuilder`：Hilt 用的 `di/AppModule.provideDatabase()`（运行时真建库）
和 `AppDatabase.getInstance()`（**没有任何调用方**）。新迁移登记在了后者上。
离线量具抓不到是结构性的：迁移 SQL 在 sqlite3 上 25 项断言全过、Room 导出的 v3 schema
与迁移后的库逐列逐索引对过、JVM 单测 318 条全绿 —— 没有一条会走"运行时开库"这条路。
本模块没有 Robolectric / room-testing，所以这条只能真机验，而强制更新会把这条崩溃
原样推给每一个老用户（v2 库 + v3 包 = 必崩）。

修法不是"两处都补一行"，而是消掉重复建库点：`AppDatabase.MIGRATIONS` 唯一清单 +
DI 从它取 + 删掉死 builder + `DatabaseMigrationWiringTest` 四条静态守卫
（迁移链从 1 起逐级连续且末端 == @Database 的 version；DI 必须引用 MIGRATIONS；
全仓 `Room.databaseBuilder(` 只允许出现在 AppModule.kt；2→3 的 SQL 形状固定）。
这条守卫自己也先红了两轮才钉准：Room 的 `@Database` 不是 RUNTIME retention（反射拿不到，
只能读源码），而匹配 `Room.databaseBuilder` 会连注释一起命中（要带左括号）。

### 真机迁移取证做法（可重跑）

vivo V2156A，release 包不可 run-as ⇒ 先装 **v2 的 debug 包**（同一枚 debug.keystore，
`install -r` 保数据）拿到 run-as 权限，再：

1. `adb exec-out run-as com.pricelens cat databases/pricelens.db{,-wal}` 备份真实库
   （**WAL 必须一起拉**：本机 WAL 有 515 KB 未回放，只拉 .db 会读成"空库"）；
2. 本地用 `sqlite3` 从这份真库派生脏 v2 库：插 4 个盯价目标 + 同一日多行（含一行 0 价）；
3. `push` 到 `/data/local/tmp` 再 `run-as … cat > databases/pricelens.db`（先删 -wal/-shm），
   两侧 md5 复核；
4. 装 v3 包 → 冷启动 → 再拉一次库，用 `tools_verify/read_device_db.py` 读。

实测结果（修复后）：`user_version` 2→3、三列带默认值就位、
`index_price_history_productId_date` unique=1、8 行脏数据清成 4 行且每天留的是 `MAX(id)`
（268 / 262 / 271 / 249），老行 `source=UNRECORDED`、`dayLow=0` ⇒ 读侧回退到收盘点；
盯价页曲线正常渲染，脚注显示「曲线出处：来源未记录 4 天」，「历史最低 ¥249 / 最高 ¥271」
与库里逐值一致。最后把备份原样还原，再装**签名 release** 走一遍真实升级路径：无 FATAL、
界面为空态（0 目标、未跑过检查），确认没有把测试数据留在用户库里。

### 攒点率实测（不拿"功能已加"当结果）

同一台机、同一轮：盯价检查状态显示 **4 个目标 · 查到价格 0 · 达标提醒 0**，
落库 `SELF_WATCH` 行数 **0**。原因是价格通道本身不通，不是回写逻辑没跑：

- `p.3.cn` 权威 DNS 返回 RFC1918 私网地址（第 5 节已记），手机 WiFi 亦不通；
- `item.m.jd.com/product/<sku>.html` 的 `priceFloor.jdPrice` 实测 4 个 SKU 全是
  `1??9` / `5?` / `1??0` / `2??`（除首末位外每一位都是字面 0x3F 问号），同页带
  `priceLoginText:"登录查看价格"` ⇒ 服务端就没给数字，不是解析问题；
- 同页 `realPriceExt.ORIGINAL.jdprice_amount` 4 个 SKU 只有 1 个有值（1041.0，且该商品是
  "需预约购买"），字段语义无从确认 ⇒ 不写进曲线；
- 慢慢买 Cookie 与星罗 apikey 均需用户凭证，本机 `shared_prefs` 里两者为空。

⇒ 免凭证时曲线一天也不会涨。这不是 bug，但**交付时必须说清楚**，否则用户会以为
"加了自建曲线"= "明天就有图"。要让它真的长出来，只有三条路：配凭据、
或做"用户在浮窗上确认一次商品身份"的录入通道（产品决策，未拍板）。

### 顺带钉住的一条事实：装回旧版必崩（没有 3→2 迁移）

取证过程中把 2.6.4 覆盖装回（`adb install -r -d`）后冷启动即 FATAL：

```
java.lang.IllegalStateException: A migration from 3 to 2 was required but not found.
```

这是**设计如此**：本仓库刻意不启用 `fallbackToDestructiveMigration`（用户收藏与盯价目标不可丢），
也没有反向迁移。所以"库被新包升到 v3 之后再用旧包打开"没有出路，只能装新版本。
两个实际约束：
 1. 想复跑"应用内更新"的端到端，不能靠"装回旧包"来制造旧版本 —— 旧包配新库直接崩，
    必须先有一份**干净的 v2 库**（本次是用 `run-as` 还原备份才走通的）；
 2. 一旦发布带迁移的新版，就等于把用户库推到了不可回滚的位置。因此迁移必须
    **只加列/只加索引、不动既有列**，且上线前在真机上用**用户真实库的副本**跑一遍
    （本次 8 行脏数据 → 4 行，逐值核对通过）。
