# 端侧 AI 兜底：接缝、上机准入策略与解码资产

> 任务书 D 的交付面。这一版**只有接口、纯策略函数、prompt 模板与 GBNF 语法**。
> 仓库里没有模型权重、没有 `.so`、没有 `System.loadLibrary`、没有 llama.cpp/MNN 依赖。

## 0. 本版不做什么，以及为什么这不是偷懒

端侧推理要能对外说"做好了"，至少要三件本机给不了的东西：

| 缺口 | 本机事实 | 于是本版怎么做 |
| --- | --- | --- |
| 原生库 | llama.cpp / MNN 都要 NDK 交叉编译出 `libllama.so` 等，本机没有可用的 NDK 产物，也没人验证过能不能链接 | 不写 `expect/actual`、不写 JNI 壳：写了就是一段永不执行的代码 |
| 权重 | 任何一个模型都是几百 MB 的二进制；本仓库对外承诺 APK 小于 20MB（`docs/REGRESSION_BASELINE.md` 第 11 条） | 不下载、不打包、不放 `assets/models/`；只交付 `.gbnf` 与 prompt 两份 KB 级文本 |
| 验机 | 收益要用 golden set 量化，而找券流水线（任务书 A）与评测量具（任务书 B）还在别的分支上没合 | 只交付**不需要模型也能判定对错**的接缝与策略，并把它俩测穿 |

一句话：**能被本机证伪的东西才进仓库**。本文档剩下的每一节都对应至少一条 JVM 单测。

## 1. 接缝（`com.pricelens.coupon.ai`）

```kotlin
interface FallbackExtractor {
    fun supports(input: ExtractionInputKind): Boolean
    fun extract(clauses: List<String>): FallbackDraft?   // null = 我没参与
}
enum class ExtractionInputKind { CLIPBOARD, COMMUNITY_POST, PAGE_CLAUSES }
```

三条契约（`FallbackExtractor.kt` 的 KDoc 里逐条写着，测试逐条钉着）：

1. `supports` 为 false 的输入不得被调用 `extract`——调用方先问再调，实现方不重复判类。
2. **`null` 的含义是"我没参与"，不是"我失败了"**：上层原样沿用规则结果。实现方不许用 null 表达错误。
   空分句列表、没有任何候选填过金额，都走这条（`empty clause list never consults the candidate source`、
   `candidates carrying no amounts collapse to null instead of an empty draft` 两条用例）。
3. 实现不得有"必须装了模型才能存在"的前置条件：没下载权重时对象仍可构造、`supports` 可返回 true、
   `extract` 走确定性路径或返回 null。这是本版只交付 `ConsensusFallbackExtractor` 的原因。

入参是 `List<String>` 而不是流水线的 `Clause`：分句与角色判定归任务书 A，兜底层只吃已切句的原文，
这样未来换模型、换运行时都不用改签名。

### 1.1 中立结构与 model 落地后的接线点

`CouponSlot` 等类型由任务书 A 在 `com.pricelens.coupon.model` 定义，**本版不复制一套同名类型**。
兜底层只用自己的最小面：

```kotlin
data class FallbackCandidate(val source: ExtractionInputKind, val templateId: String, val discount: Double?, val threshold: Double?)
data class FallbackDraft(val discount: Double?, val threshold: Double?, val agreement: Int, val conflict: List<String>)
enum class ConsensusVerdict { UPLIFT, FLAT, DOWNWEIGHT }
```

等 `model` 合入后，由控制面（不在本文件范围内）做三件事，**只有这三件**：

- `ExtractSource` → `ExtractionInputKind` 的 `when` 映射（`CLIPBOARD→CLIPBOARD`、`COMMUNITY→COMMUNITY_POST`、`PAGE_NODE→PAGE_CLAUSES`）；
- 实现 `SlotCandidateSource`：把 `CouponTemplates.match()` 在各入口产出的 `CouponSlot.discount / .threshold` 灌成 `FallbackCandidate` 列表（`templateId` 用模板 id，`source` 用入口）；
- 把 `FallbackDraft` 的两个金额并回 `CouponSlot`，并按 `verdict()` 调 `Extraction.confidence`。

`scope / state / expiry / url / nodePath` **不经过兜底层**——兜底只仲裁"这两个金额是不是被多个来源共同认可"，
其余槽位的真源永远是流水线，避免两处各写一份打分公式。

## 2. 跨候选共识：今天就能落地的那半

单条规则命中就直出，等于把"某套模板赌对了"当成"这张券存在"。同一段文案往往在剪贴板入口和页面入口
各命中一次、且用的是不同模板——只有两边给出一样的面额与门槛，才够格升置信。

`ConsensusFallbackExtractor.consensus(candidates)` 的判据（每条对应命名用例）：

| # | 判据 | 出处 / 理由 | 用例 |
| --- | --- | --- | --- |
| 1 | 槽位取**众数**；并列时取**较小值** | 券面额高估的代价是用户按不存在的优惠去下单；低估只是少展示一点。两者不对称 | `a tie resolves to the smaller amount because overpromising costs more` |
| 2 | 支持度按 `source + templateId` **去重** | 同一套模板命中 5 次不叫共识，只叫重复 | `five hits of the same template are repetition not consensus` |
| 3 | `agreement` 取两个槽位支持度的**较小值** | 面额 3 个来源一致、门槛只有 1 个来源给过 ⇒ 不能报成 3 或 6 | `agreement takes the weaker of the two slots` |
| 4 | 槽位出现过 ≥2 个不同取值 ⇒ 记 `slot:v1|v2` 标记，**全部取值都留着** | 冲突不丢弃结果，只降置信并留证据，让上层显示"待核验"而不是二选一 | `conflicting discounts are both kept in the marker and downweight the draft`、`majority wins but the minority value stays visible` |
| 5 | 两个槽位都没人填 ⇒ `null` | 宁缺毋滥，绝不返回"全 null 的草稿"让上层误当成结果 | `candidates carrying no amounts collapse to null instead of an empty draft` |
| 6 | `agreement >= 2` 且无冲突才 `UPLIFT` | 孤证相对"规则直出"没有信息增量，升置信就是给自己盖章 | `single populated slot is still a usable draft`（两个来源给同一个面额、门槛空缺 → 仍升）对 `five hits of the same template are repetition not consensus`（五个同源重复 → 只算平） |

标记里的金额写成 `12`/`12.5` 而不是 `12.0`（`markers print whole amounts without a trailing decimal point`）：
这些字符串会原样进日志与"待核验"提示，`12.0|15.0` 是噪音。

确定性实现的调用顺序固定为 `supports → extract`，`consensus` 也在伴生对象上公开，
流水线侧不想要接缝包装时可以直接调纯函数（`consensus is callable without an extractor for the pipeline to reuse`）。

## 3. 上机准入策略

`OnDeviceAiPolicy.eligible(freeRamMb, batteryPercent, onMeteredNetwork, modelDownloaded, userEnabled, deviceRamMb, cacheBudgetMb)`
七个标量进、一个 `AiDecision` 出，**不 import `android.*`**——所以采集侧（`ActivityManager.getMemoryInfo`、
`BatteryManager`、`ConnectivityManager`）留给后续任务，而判据本身今天就能被单测钉死。

三档语义按"**可恢复性**"分：`Run` / `QueueUntilIdle`（等得到：电量、空闲内存、网络）/ `Refused`（等不到：用户没开、机器不达标、预算装不下）。
把"电量低"报成"你的设备不支持"，用户会以为功能对他是关着的；把"机型不达标"报成"稍后再试"，上层会挂一个永远等不到的队列。

### 3.1 判据、顺序与出处

顺序本身就是行为规格：**永久性拒绝排在暂时性排队之前**（机型或预算不达标时，排队到什么时候结论都一样）。

| 序 | 判据 | 结论 | 出处与量级理由 |
| --- | --- | --- | --- |
| 1 | `!userEnabled` | `Refused("off")` | **默认关**。任务书 D 第 2 条。这是本 App 第一次在用户手机上跑别人的权重；收益未量化、体积不小，而"凭证绝不出库"这条人设之外还要回答"商品文案出库到哪张表"。没定论之前默认关是唯一诚实取值 |
| 2 | `deviceRamMb < 6144` | `Refused("device-ram")`（永久） | 600MB 权重 mmap + KV/运行时峰值约 1.2~1.5GB，再扣系统与前台应用，4GB 机型上的表现是"每次用完必掉进程"，比没有 AI 更糟。**中长尾机型降级回规则是特性不是缺陷**：降级后的结果仍然可用 |
| 3 | `!modelDownloaded && cacheBudgetMb < 600` | `Refused("cache-budget")`（永久） | 预算至少要装得下软上限的模型，否则下完就是半截文件——用户以为开了 AI，实际每次都在等一个永远不完整的权重 |
| 4 | `batteryPercent < 20` | `QueueUntilIdle` | 端侧 prefill 是持续多核高负载，比 WorkManager `setRequiresBatteryNotLow()` 更严；Android 省电模式默认 15% 开启，20% 是刻意留的缓冲（阈值由任务书 D 规定） |
| 5 | `freeRamMb < 2048` | `QueueUntilIdle` | 用**绝对值**而不是百分比：空闲低于 2GB 时 mmap 的权重页被低内存 killer 反复回收，prefill 从秒级退化到几十秒并把前台挤掉。这一条只作为策略输入，采集口径要等能验机的那版定 |
| 6 | `!modelDownloaded && onMeteredNetwork` | `QueueUntilIdle` | **只挡下载，不挡推理**：模型已在本地时跑一次抽取不产生任何流量，所以这条判据必须带 `modelDownloaded` 前缀（`cache budget only gates the download…`、`metered with model runs` 两条用例盯的就是这个区别） |
| 7 | 以上都不踩线 | `Run` | positive control：`nominal device with model on disk runs` |

`ModelBytesSoftCap = 600MB` 的量级：Q4_K_M 每权重约 4.8bit ≈ 0.6 字节 ⇒ 600MB ≈ **1B 参数**那一档。
兜底要的是"从一句券文案里抽 2~3 个金额槽位 + 状态词"，且输出被 `coupon_schema.gbnf` 逐 token 约束死——
窄任务 + 硬语法约束下，3B（约 1.8GB）多出来的 1.2GB 要同时付存储、流量、常驻内存三笔账，而收益在
golden set 还没到量能量化它的量。等能量化"1B vs 3B 的槽位准确率差"时再抬这个上限。

### 3.2 与"缓存上限 30MB / 凭证绝不出库"人设的冲突与取舍

既有红线：分级缓存 ≤30MB（`docs/REGRESSION_BASELINE.md` 第 11 条、`data/cache/CacheCleanupWorker`）；
备份 payload 用显式字段白名单、**永远不含凭证**（`data/backup/BackupPayload.kt`）。
600MB 的模型权重看起来直接顶穿了前者。取舍写死在 `OnDeviceAiPolicy` 的 KDoc 里：

- **两者管的东西不同**：30MB 那档管的是**可丢弃的用户数据缓存**（商品快照、搜索记录）——价值在数据本身，必须严控；
  模型权重是**可再生的下载物**——删了随时能再下，里面一个用户字节都没有。
- **所以本版把它们拆成两个预算**，不复用 30MB 那个数：`cacheBudgetMb` 是"用户为 AI 模型单独划的下载预算"，
  默认 0（等于不开），只在用户显式同意后分配。策略层不读设置页，也不允许把 30MB 直接当 AI 预算传进来——
  传 30 就会被判 `Refused("cache-budget")`（这正是表格里的 `预算 30MB 装不下模型` 用例）。
- **三条不越界的约定**：权重绝不进 Room、绝不进备份 payload（与 BackupPayload 白名单红线对齐）、
  `EMERGENCY` 清理时优先删除模型。
- **代价说清楚**：用户要多理解一个数字（"AI 模型占用"），换来的是"缓存涨到 600MB"这件事不再由 App 替他决定。
  凭证侧不受影响：兜底全程只处理**已经在用户手机上的文案字符串**，不新增任何对外请求，
  因此 `NET_CAPABILITY_NOT_METERED` 之外本层不引入任何新的出网面。

### 3.3 每条判据的 positive control

`each criterion maps to its documented decision` 是一张 17 行的表：前 11 行是"该拒的被拒/该排的被排"（含三条
判据顺序的交叉用例：开关关时不看电量、机型不达标时预算说了不算、预算不够时电量再高也不下），
后 6 行是"只改这一条就能放行"（8GB 机型、预算 601MB、电量 21%、空闲 2049MB、计费网络但模型已就位、
非计费网络可下模型）。`nominal…runs` 那条同时是用户开关的放行正例。
四条阈值边界（6144 / 20 / 600 / 2048）各有一条**恰好等于阈值应放行**的独立用例，
表格里刻意不出现这些取值——这样变异探针的归因是单点的（见 §5）。

## 4. 两份资产

### 4.1 `assets/ai/coupon_prompt_v1.txt`

固定三段：**槽位定义表（五种金额角色 + 状态词表 + 作用范围）→ 2 条 few-shot → `{text}` 占位**。

- 头部 `#` 行是元数据：`ai-prompt-version: 1`、`temperature=0 top_p=1 max_new_tokens=256 repeat_penalty=1.0`，
  以及"为什么 temperature=0"（同一句文案今天与昨天抽出的槽位必须一致，否则 `agreement` 会把采样抖动
  伪装成"模型有把握"）和"为什么 256 token"（一张券的 JSON 约 90~120 token，再长就说明在输出语法之外的东西）。
- `AiPromptAsset.isDirective/body/placeholderCount/render` 是这份资产的**读取契约**：`#` 行不进 prompt，
  `{text}` 必须恰好出现一次，数量不对时 `render` 直接抛而不是静默替换第一处——
  prompt 是给人手改的，改坏了要在第一次调用就炸出来，而不是发一条没有文案的 prompt 再对空结果调三天。
  （`render refuses a template whose placeholder count is not one` 与真资产渲染成功那条互为正负对照。）
- 两个 few-shot 的输出**必须能被 §4.2 的语法解码**：`grammar field order matches the few-shot outputs`
  逐字段比对语法里 `obj` / `coupon-obj` 的字段次序与示例 JSON 的键次序，并对每条示例断言 12 个槽位都在。
  这条用例是"改 prompt 或改 grammar 时必须同时改另一边"的机械强制。
- 状态与作用域词表：`grammar states are a subset of the prompt vocabulary` 保证语法枚举的每个值
  在 prompt 正文里都有解释，反之不会出现"语法允许、prompt 没教"的状态。

### 4.2 `assets/ai/coupon_schema.gbnf`

约束解码语法，覆盖 `platform / coupons[] / discount / threshold / scope / state / expiry / url /
price{final,list,drop} / confidence`，42 条产生式。

两条刻意的写法约束（文件头也写着）：**字面量一律双引号、字符类一律方括号、不用正则终端**。
原因是老版 llama.cpp 的 grammar-parser 没有 `/.../` 正则支持；不用正则终端，语法就不依赖运行时版本，
也让本仓库的自检只用一个字符串扫描器即可完成。日期写成 `date ::= "\"" d4 "-" d2 "-" d2 "\""`，
数字写成 `number ::= negative? integer fraction?`（不放开指数），平台与作用域/状态用枚举字面量。

自洽性由 `AiAssetContractTest.GbnfScan` 检查（只读字符串、无新依赖，符合任务书要求）：

| 检查 | 含义 | 自检器自身的对照 |
| --- | --- | --- |
| 每条产生式右侧引用的符号都在左侧定义过 | 有未定义符号 ⇒ llama.cpp 加载即失败 | `scanner flags an undefined symbol and nothing else`（恰好一条缺陷） |
| 字面量/字符类里的文本**不算**符号引用 | 否则 `"ghost"`、`[0-9]` 会永远报假错 | `scanner does not mistake literal text for a symbol reference` |
| 无重复定义 | 同名规则会让后定义覆盖前定义，次序断言随之失真 | `scanner flags duplicates…`（恰好一条） |
| 每个已定义符号都能从 `root` 到达 | 不可达规则 = 死语法，白占文件体积 | `…unreachable…`（恰好一条） |
| 没有空产生式；必须有 `root` | `leaf ::=` 与缺 root 都会在加载期炸 | `…empty productions`、`scanner reports a grammar without a root rule` |

**自检能力的边界要说明白**：它证明的是"符号图自洽"，不是"这份语法能解码出正确答案"。
后者要等能编译、能验机的那一版用真模型跑 golden set。

## 5. 测试纪律与红绿证据（含没做到的那部分）

**本轮实际跑过什么**（构建只能走 `bash E:/dev/pl-build.sh E:/dev/pl-coupon-ai <任务>`，全机锁串行，预算 3 次，已用满）：

| 次 | 任务 | GRADLE_EXIT | 结果 |
| --- | --- | --- | --- |
| 1 | `ktlintCheck :app:testDebugUnitTest --rerun` | 1 | `ktlintMainSourceSetCheck` 抓到两处 enum 尾逗号（`FallbackDraft.kt:39`、`FallbackExtractor.kt:12`）；构建提前失败，测试源集检查与单测未跑 |
| 2 | 同上（含两处 `>= → >` 变异） | 1 | ktlint 未再报错，死在 `compileDebugUnitTestKotlin`：`ConsensusFallbackExtractorTest.kt:56` 的 `withAmount!!.discount` 被工具侧 PostToolUse hook 吃掉一个 `!!` ⇒ `Double?` 传不进 `assertEquals(double,double,double)`。变异红因此没机会露出 |
| 3 | 同上（变异已还原为 `>=`，不带 `--tests` 过滤） | **0** | BUILD SUCCESSFUL in 9m33s；`ktlintTestSourceSetCheck` 执行并通过；`:app:testDebugUnitTest` **770 条用例 / 0 failed / 0 error / 0 skipped**（93 个测试类，含本任务新增 3 类 40 条：`ConsensusFallbackExtractorTest` 14、`OnDeviceAiPolicyTest` 11、`AiAssetContractTest` 15） |

`grep -rn "MUTATION" app/src` 在最终态执行过，**无匹配**（退出码 1）：仓库里不留任何被注释掉的变异代码。

### 5.1 变异自证：Gradle 侧这一版**没有**跑成，下面是它的替身与补法

三次预算被两处真实的构建期错误吃掉（ktlint 尾逗号、`!!` 被 hook 改写），而"变异红"与"还原后的全量绿"
无法在同一次构建里同时拿到。所以下面的归因证据来自**逐条镜像仿真**，不是 Gradle 运行结果：
把 `OnDeviceAiPolicy` 的四条判据（`deviceRamSupported` / `batterySupported` / `freeRamMb` / `budgetFitsModel`）
的 `>=` 各改成 `>`，用与单测完全相同的判据实现和同一张 30 行用例表跑一遍，结果是每个变异**恰好打红一条**、
其余全绿：

```
mutation battery -> red: ['battery exactly at the threshold is eligible']
mutation device  -> red: ['device exactly at the six gigabyte floor is eligible']
mutation free    -> red: ['free ram exactly at the floor is eligible']
mutation budget  -> red: ['ai budget exactly at the model soft cap is eligible for the download']
```

这份单点归因之所以成立，是因为用例表里刻意**不出现任何等于阈值的取值**（用 19/21、4096/8192、599/601），
每条阈值只有一条"恰好等于阈值应放行"的独立用例。补跑一次 Gradle 侧的变异红，成本是 1 次构建 + 2 行改动：
把 `batterySupported` 与 `deviceRamSupported` 的 `>=` 改成 `>`，跑
`bash E:/dev/pl-build.sh E:/dev/pl-coupon-ai :app:testDebugUnitTest --rerun`，
预期且应当**只有**上面列出的那 2 条用例红，还原后再跑一次全量。

同一套镜像还校验了 `consensus(...)` 的 12 组候选（含并列取小、去重来源、min 支持度、冲突标记文本）
与 §4.2 的语法自洽/字段次序断言——本文件的 §4 与 §2 表格里的每条判据都在这张镜像表里有对应行。

## 6. grammar 硬约束 vs CPU prefill 性能（trade-off）

| 路线 | 换来什么 | 付出什么 |
| --- | --- | --- |
| **llama.cpp + GBNF 约束解码** | 输出**结构上不可能**畸形：字段名、枚举值、日期格式、字段次序都由语法逐 token 强制，`state` 里不会突然冒出"已失效"这种没定义过的写法 | 要自己维护 `.gbnf` 与 prompt 的同步（§4.1 那条用例就是干这个的）；prefill 速度取决于所选后端与量化，CPU 上长文案会慢；要 NDK 编译产物 |
| **MNN（或同类）CPU 后端** | prefill/decode 在中低端机上的实测吞吐更好，端侧时延风险低 | **没有 GBNF**。约束只能退化为"生成完整 JSON → 按 JSON Schema 后置校验 → 失败重试一次"，而重试一次意味着最坏情况双耗时，且校验失败的那些 case 会集中出现在**最难的文案**上（正是兜底最想救的那批） |
| 折中（本版不做） | 双后端：优先 grammar，MNN 上走 schema 后置校验 | 两条解码路径要各自测一遍，量具成本翻倍 |

**本版两者都不接**，理由就是 §0 那三行：本机既编不出 `.so`，也没有设备能量化"grammar 拒掉了多少非法输出"
与"MNN 快多少"，而现在连 golden set 都没到能给出这个量的规模。留在这个版本的只有**能被证伪的那部分**：
接缝、纯策略函数、两份文本资产，以及把它们钉住的 40 条 JVM 用例。

## 7. 留给后续任务接线的东西（本版明确不做）

1. **model 类型映射**：`ExtractSource ↔ ExtractionInputKind`、`CouponSlot → FallbackCandidate → CouponSlot`
   的 `when`；等 `com.pricelens.coupon.model` 合入 main 后由控制面写，只碰 §1.1 列的三件事。
2. **`SlotCandidateSource` 的真实实现**：从 `CouponTemplates.match()` 的命中里取 `templateId + discount + threshold`。
   现在只有测试里的固定表。
3. **设置页开关**（`userEnabled` 的来源）与"AI 模型占用"独立预算项（`cacheBudgetMb` 的来源）——
   本任务被明令不许碰 `res/values/strings*.xml`、设置页、Room、UI。
4. **采集侧**：`freeRamMb`（`ActivityManager.getMemoryInfo`）、`batteryPercent`（`BatteryManager`）、
   `onMeteredNetwork`（`ConnectivityManager.NET_CAPABILITY_NOT_METERED`）、`deviceRamMb`（`TotalMem`）、
   `modelDownloaded`（权重文件存在性 + sha256 校验）。这些都要真机口径，本机给了也只能是猜。
5. **`QueueUntilIdle` 的落地机制**：目前只是结论，没有执行者。要接 WorkManager
   （`setRequiresBatteryNotLow()` + `setRequiredNetworkType(NOT_ROAMING)`/非计费约束）才算真的"排队等条件"。
6. **运行时与权重**：NDK 产物、下载器（含断点续传与 sha256）、`AiDecision` 到 UI 文案的映射、
   EMERGENCY 清理里"优先删模型"的实装。
7. **`verdict()` 到 confidence 的具体系数**：三档（UPLIFT/FLAT/DOWNWEIGHT）已在代码里定死语义，
   但"乘多少"要和来源可靠度（剪贴板 1.0 / 页面 0.95 / 社区 0.8）一起算，那是流水线侧的打分公式，
   两处各写一份必然漂移，所以留给它。

## 相关文件

- `app/src/main/java/com/pricelens/coupon/ai/FallbackExtractor.kt`（接缝 + `ExtractionInputKind`）
- `app/src/main/java/com/pricelens/coupon/ai/FallbackDraft.kt`（中立结构 + 三档 verdict 枚举）
- `app/src/main/java/com/pricelens/coupon/ai/ConsensusFallbackExtractor.kt`（确定性共识）
- `app/src/main/java/com/pricelens/coupon/ai/OnDeviceAiPolicy.kt`（准入策略 + `AiDecision`）
- `app/src/main/java/com/pricelens/coupon/ai/AiPromptAsset.kt`（资产读取契约）
- `app/src/main/assets/ai/coupon_prompt_v1.txt`、`app/src/main/assets/ai/coupon_schema.gbnf`
- `app/src/test/java/com/pricelens/coupon/ai/`（三个测试类）

## 运行时已落地（2026-10-04）：llama.cpp 编进 APK 了，但**默认关**

上面第 6 条"运行时与权重"今晚落了一半，边界写在明面上：

**做了什么**
- `app/src/main/cpp/llm_jni.cpp`：最小 JNI —— `nativeLoad` / `nativeRun`（prompt + GBNF 约束解码）/ `nativeFree` /
  `nativeLastStats`。纯 CPU、`load_mode=MMAP`、只算末位 logits、语法解析失败**直接返回 null**
  （绝不静默退回无约束解码：那会吐出不合 schema 的 JSON，比"没结果"更糟）。
- `app/src/main/cpp/CMakeLists.txt`：只建库本体（examples/tools/server/common 全关，OpenMP 关 —— NDK 不带）；
  llama.cpp 源码目录由 `-DPRICELENS_LLAMA_DIR=` 传进来，**不进本仓库**。
- `app/build.gradle.kts`：`-Ppricelens.llamaDir=<目录>` 是唯一开关。**默认不开**：
  ① 现有 CI 的 runner 没装 NDK，无条件开会让 release 链直接红；
  ② 35MB 第三方源码进 git 会让每次 checkout 变重。
  开的时候只编 `arm64-v8a`。
- `com.pricelens.coupon.ai.LlamaNative`（`System.loadLibrary` 唯一一处，`isAvailable` 与 `loadError` 分开：
  "没编进来"和"编进来了但加载失败"不许混成同一种沉默）与 `LlamaRuntime`（加载/推理/释放，失败一律
  返回 null = "没参与"，不把整条找券链路带崩）。
- `app/src/debug/`：一个**只存在于 debug 包**的广播探针 `LlmProbeReceiver`，用 adb 触发一次推理并把
  结果写进 logcat 与 `filesDir/llm-probe.txt`。正式包不会合并这个文件。

**验证状态（照实写）**
- ✅ 交叉编译通过：NDK 26.1 + CMake 3.22，产出 `arm64-v8a/libpricelens_llm.so`。
- ⚠️ **还没在真机上跑过模型**：那个晚上的手机中途掉线，而"模型能加载、能按 GBNF 吐 JSON、每秒多少 token"
  三件事**只有真机能回答**。跑通之前，这一步都算未验证。
- 已知要处理的两件事：① debug 变体的 `.so` 有 **61MB**（未 strip、未优化），release 变体应当显著更小，
  但得实测；② `LlamaRuntime.chatWrap` 的聊天模板是**按 Qwen3 写死**的，换模型必须回来改（见那里的注释）。

**怎么建 / 怎么验（下次照抄）**
```bash
# 1) 外部检出 llama.cpp（不要放进本仓库）
git clone --depth 1 --filter=blob:none --sparse https://github.com/ggml-org/llama.cpp E:/dev/pl-model
cd E:/dev/pl-model && git sparse-checkout set src include ggml cmake vendor   # vendor 不能漏，根 CMakeLists 会 add_subdirectory(vendor)
# 2) 出带引擎的包（本机 NDK 26.1.10909125）
bash E:/dev/pl-build.sh <worktree> :app:assembleDebug --rerun -Ppricelens.llamaDir=E:/dev/pl-model
# 3) 模型（split delivery：不进 APK、不进仓库；验证阶段用 adb push）
#    Qwen3-0.6B-Q4_K_M.gguf ≈ 397MB，放仓库外的独立目录
# 4) 真机探针
adb push Qwen3-0.6B-Q4_K_M.gguf /sdcard/Download/
adb shell am broadcast -a com.pricelens.dev.LLM_PROBE \
  -n com.pricelens.dev/com.pricelens.debug.LlmProbeReceiver --es text "满199减50，券码：ABCD1234"
adb logcat -d -s PriceLensLLMProbe:'*'      # 或 adb exec-out run-as com.pricelens.dev cat files/llm-probe.txt
```
**PC 侧提示词探针**：`tools/llm_prompt_probe.py`（同一份资产、同一个 GGUF，在电脑上先调 prompt/语法，
省得"改一行装一次包"）。注意它和 `LlamaRuntime` 有三处必须同步（脚本头里写明了）；
这台机器上跑它踩过一个坑：从脚本里调 `llama-cli` 必须 `stdin=DEVNULL`，否则它生成完会进交互模式**一直等输入**，
现象是"超时且零输出"，看起来像模型加载不动。

## 模型的分发位置与"首次进入检测 + 推荐安装"（2026-10-04 晚）

**模型进了分发仓库（不是 git）**：

| 项 | 值 |
|---|---|
| 位置 | GitHub Release：tag `model-qwen3-0.6b-q4km-v1`（资产 `Qwen3-0.6B-Q4_K_M.gguf`） |
| 大小 | 396705472 字节（与本地逐字节同，下载链 `curl -I` 实测 Content-Length 相同） |
| sha256 | `ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a` |
| 为什么不是 git | 397MB 进 git 会让每次 checkout/CI 都变重；release 单文件上限 2GB、自带 sha 元数据 |
| 国内速度 | GitHub 直连可能慢；下载器支持 **Range 断点续传**（失败可重试，`*.part` 会保留），
  CN 专用镜像等有实测再补进 `ModelRepository.URLS` |

**首次进入检测 + 推荐安装**（`DeviceProbe` / `ModelAdvisor` / `ModelStore` / `AiModelViewModel` / `AiModelSection`）：
- 检测只读六个标量（总内存/空闲内存/电量/是否计费网络/ABI/SDK），**不做跑分**——
  真正的"这台机器行不行"由第一次推理的 tok/s 说话（模型下载后可跑一次极短推理得出）；
- `ModelAdvisor.advice(...)` 把结论翻成六种界面状态；**最要紧的一条**：全新安装（预算 0、开关关）
  也必须给 `SUGGEST_INSTALL` —— 第一版把真实预算喂给策略，结果全新安装被判成"机型装不下"，
  而那正是最该推荐的时刻（`ModelAdvisorTest` 里钉住了这条）；
- 下载三条纪律：下到 `.part`、**校验通过才改名**、校验不过就删；
- 默认关：点"下载"才算同意（`ai_model_enabled`），`ai_device_checked` 只是"别再弹"的标记、**不是权限**。

## 真机结果（PLB110 / Android 15，2026-10-04 晚）

**引擎与模型（跑通了，不是"应该能跑"）**
- `libpricelens_llm.so`（arm64，Release 12.5MB）+ `/data/data/com.pricelens.dev/files/models/Qwen3-0.6B-Q4_K_M.gguf`（396705472 字节，sha256 与 `ModelRepository.SHA256` 一致）。
- 性能：**~14 token/s**；一次抽取约 **22–27 秒**（含加载；其中 931 token 的提示词预填充占大头 ——
  想提速该精简 few-shot，换更大模型是反方向）。
- 从设置页下载的真实链路：WiFi 下 ≈8.8MB/s（GitHub CDN），397MB → sha256 校验 → 改名 → UI 变"已启用"，全程无人工干预。

**模型抽对了一半，而那一半正是规则层做不到的**
输入（规则层当初整条丢掉的那类社区帖）：
`天猫精选此款目前活动售价5998元，参与官方限时补贴减499元，国家补贴15%减500元优惠活动，实付低至4999元`
输出：`platform=taobao` ✓、两张券 `discount=499 / 500` ✓ —— 与 golden 一致；
**但两张券都被编了 `threshold=5998`**（5998 是售价，golden 里 threshold 是 null）。

**这条反例把设计里的那句话变成了硬约束**：`LLM 只抽 span、金额交给确定性解析器复核`。
接线时必须做：模型给的 threshold/discount 要能在原文里找到对应锚点（门槛要有「满/门槛」类词、
面额要有「减/省/券」类词），找不到就置 null —— 否则 5998 会变成一张"满5998减499"的不存在券。
这也解释了为什么"模型抽到的"不能直接进 `Extraction`：它要先过 `CouponPipeline` 的那把尺子。

## 接进流水线与「模型 vs 规则」A/B（2026-10-04 深夜，四轮）

**接线形态**（都在 `feat/2.8.0.2`）
- `CouponPipeline.extract(..., fallback = emptyList())`：**只在规则什么都没抽到、或只给出形状词级
  低置信（0.5）时才唤起模型**；模板命中（≥0.75）一次都不调 —— "规则命中就不许调模型"有测试钉住。
- `LlamaFallbackExtractor`：模型只当"把文本读成两个数字"的抽取器，输出过三条确定性复核
  （数字必须原文逐字可寻；角色由规则尺子判：同角色采信 / 异角色丢弃 / 判不出-降权采信）；
  **只做加法不做减法**（第一轮的教训：替换策略把规则抽对的一张券吃掉了）。
- 取数口：debug 探针 `--es ab <jsonl>` 逐条跑两臂、各写一份 golden 格式 predictions，
  打分只由 `tools/eval_coupons.py` 做（探针不判胜负）。

**四轮的账**（真机 PLB110，同一 10 条子集、同一把尺子；第五轮是同夜规则层补完判据后的复测，一并记在这里）

| 轮 | 提示词 | 规则臂 | 规则+模型臂 | 说明 |
|----|--------|--------|-------------|------|
| 1 | v1 | 0.750 | 0.696 | 我的"替换"策略把规则抽对的一张吃掉 ⇒ 改"只做加法" |
| 2 | v1 | 0.750 | 0.750 | 不加不减 |
| 3 | v2（抽象规则：补贴/餐补/红包/返现 = 券面额替身） | 0.750 | 0.750 | 逐条 diff 0：**0.6B 不执行"讲道理"式条款** |
| 4 | v3（v2 + 同形示例「25元外卖餐补 → discount:25」） | 0.750 | 0.750 | 逐条 diff 0：**抄格式不抄映射**（见下） |
| 5 | v3（规则层同夜补了"数字在前"等四类判据后重跑） | **0.9630** | 0.9630 | diff 仍 0，但原因换了：子集里四类漏抽**规则已经接住**，模型没有增量位置（餐补句模板置信 0.8 ⇒ 兜底根本不唤起） |

**第四轮的决定性单发证据**（`E:/dev/pl-builds/round4-ab4-single-raw.txt`）：对「17元外卖餐补」，
v3 下模型输出 `discount:null, threshold:null` —— 但把示例 3 独有的 `scope:"CATEGORY"` 照抄了过来。
⇒ 示例确实进入了它的上下文、影响了输出格式；可它就是不做「数字 → discount」这一步映射。
复核层不是瓶颈（若模型给 17，这条路会"无角色词 → 降权采信"放行）；**是模型自己没给**。

**结论**：0.6B 的提示词杠杆已经到底（规则、抽象条款、同形示例三条路都试过）。再撬只有两条路，
且都要单独立项：① 换 1.7B 同类量化、同一子集重跑；② 规则层补 number-first 模板（`X元 + 餐补/补贴`
这种数字在前的形状 —— `AmountRole.of` 只看数字**之前**的词，天生够不着）。两条「无金额券」golden
（「领券」「试用专享券」）在模型路径上结构性不可达：复核层要求至少一个金额可复核，
产不出 null|null 券，要拿只能靠规则层的"形状词 → 空券"确定性规则。

**ab-run.sh 的防哑闸（都是被真事故教出来的）**：装包前查 ①模型在位、②APK 带 `libpricelens_llm.so`
（漏 `-Ppricelens.llamaDir` 时构建照常成功、两臂分数"恰好一致"地骗人 —— 第四轮第一跑就栽在这）；
跑完 ③「AB 完成」硬闸、④ `raw=` 全 null 拒判；外加 install → launch + 35s 静置、预删设备侧旧产物、
本轮产物带时间戳留档。提示词 v3 的 token 数未复测；CN 专用镜像地址仍未定。
