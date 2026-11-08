## [2.6.2] - 2026-09-29

> 为什么要单独升一个版本号：F4 的修复是在 2.6.1 的包**已经发出去之后**才验证完成的。
> 客户端只比整数 `versionCode`，继续写 15 就等于让已装 2.6.1 的手机收不到任何提示——
> 修复只存在于仓库里，对真实用户等于没修。

### Fixed

- **源不可达不再被说成"你关键词写错了"（F4）**：真机复现（2.6.1，全机无网络路径）搜
  `mate 80`，概览页显示「历史价 · 未查询」「当当 · 正常」「B 站 · 正常」，正文写
  「未匹配到与「mate 80」直接相关的商品，试试更完整的型号名」——而联网后同一关键词立刻返回
  「华为 Mate 80 手机 12GB+512GB 雪域白 ¥4,079.15」。App 当时断言了一个**没有发生过的原因**。
  塌缩发生在四层：`ApiClient.getHtml` 用可空桥 `asNullable` 把四态 `CrawlerResult` 压成
  `String?` ⇒ 解析类 `?: return emptyList()` ⇒ 仓储 `?: emptyList()` ⇒ `loadList` 写
  `Success(emptyList())`。两个连带副作用：`SourceHealth` 的"连续 3 次失败 → 2 分钟冷却"
  对断网/反爬恒不触发（解析类从不抛异常），`AsyncValue.Error` 在这些路径上根本不可达。
  - `data/remote/CrawlerResult.toSourceFailure()` 成为四态 → 类型化失败的**唯一映射点**
    （`Blocked`→`CrawlerBlockedException`、`Network`→`SourceUnreachableException`；
    只有 `Success`/`Empty` 算"够着了"）。
  - 五个关键词搜索解析类（当当 / 值得买 / 找券 / 识货 / B 站）改走 `*Result` 四态方法，
    空表从此只剩一种含义：够着了该源、过滤后确实 0 条。B 站顺带补上业务码 `code`
    （`code=-412` 风控不再与"零结果"同值，与桌面端 `crawlers/bilibili.js:114-116` 拉平）。
  - `CachedSource`：没有旧快照可兜时把失败原样抛出；失败冷却期内（本轮压根没去访问该源）
    同样算"取不到"；新增 `getList()` 给列表通道用，`null` 只剩"契约违约"一种解释。
    合法空结果依旧不记失败、不抛出。有 L2 快照时仍回吐旧数据 + `staleKeys` 标记（改版/断网
    先看旧数据而不是报错，这条不变）。
  - `ApiClient.recordOutcome`：成功改为**清除**该域名的旧失败——原先只写不删，恢复网络后
    诊断徽标会反向写着「反爬」。识货的域名诊断键修正为 `m.shihuo.cn`（旧值 `www.shihuo.cn`
    永远查不到记录，反爬与失败无从区分）。
  - 展示层两处判定抽成纯函数并各自带单测：`sourceChipStateOf()`（`Success(空表)` 从「正常」
    里拆出「无结果」）、`EmptyStateCauseOf.of()`（`NOT_SEARCHED` / `UNREACHABLE` / `NO_MATCH`）。
    新文案：「「关键词」暂时取不到数据：网络不可达或数据源被拦截，与关键词写法无关
    （联网后重新搜索即可）」。概览页只传"商品候选真正依赖的源"（当当→值得买 + 识货），
    不把 B 站/券/历史价混进来——它们的 L1/L2 缓存在断网时照样是 `Success`，混传会让
    `NO_MATCH` 盖掉 `UNREACHABLE`，谎话又回到关键词上。
  - 回归：`SourceUnreachableTest` + `SourceUnreachableCacheTest`（共 10 项）只用修复前就有的
    公开签名，先红后绿：只留测试、实现层回退到 `7c3438b` 跑出
    **`227 tests completed, 8 failed`**（8 项全是本轮新增断言，其余 219 项保持通过 ⇒
    失败来自被测行为而不是夹具坏了），恢复实现后 **`251 tests completed, 0 failed`**。
    另有 `CrawlerOutcomeMappingTest`(8) / `SourceChipStateTest`(7) / `EmptyStateCauseTest`(7)
    覆盖新纯函数；这 3 个类测的是本次新建的符号，在旧树上不可编译，故红跑时移出、绿跑时移回。
    `app/build.gradle.kts` 新增 `testOptions.unitTests.returnDefaultValues = true`：不加则
    `LogT`→`android.util.Log` 在 JVM 单测里抛 "not mocked"，会把"源不可达"伪装成另一种异常、
    让断言假绿。
  - ktlint 冷缓存后另报 19 处（前几轮构建是 `UP-TO-DATE` 假绿）：`EmptyStateCause.kt` 枚举
    末项多余尾逗号、3 个新测试类的换行/注释位置，以及 `PriceRepository.kt` 五个 `kvSource`
    调用的 `standard:argument-list-wrapping` ——后 10 处的格式与 `7c3438b` 逐字相同，即已发布的
    2.6.1 同样违规、只是这次改到相邻行才暴露，按同文件 `getPriceHistory`（一参一行）的既有
    约定统一，纯格式零行为改动。
  - 桌面端无需改动（两端同规则）：`desktop/src/main/crawlers/index.js:102` 关键词通道不吞异常，
    `smzdm.js:58/93`、`gwdang.js:64/73`、`bilibili.js:115`、`manmanbuy.js:90/97` 一律 `throw`；
    `ipc-handlers.js:91-99` 把异常转成 `{ok:false,error}`，`ipc-handlers.js:50-54` 还按
    `ENOTFOUND`/`TimeoutError`/`ERATE_LIMIT` 分别给成因，`renderer/js/app.js:202`、`233-251`
    按 `ok` 分流到各模块的错误态。本轮是 Android 向桌面端拉平。`node desktop/_unit_check.js`
    34 项通过（未改桌面代码）。
  - 真机前后对比（2026-09-29，vivo，adb 侧走 gnirehtet 反向网络）见
    `~/Desktop/PriceLens-F4修复报告-2026-09-29.md`。
