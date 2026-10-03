package com.pricelens.accessibility

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pricelens.R
import com.pricelens.data.local.entity.WatchIdentityEntity
import com.pricelens.data.repository.OverlayBundle
import com.pricelens.data.repository.PriceRepository
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.domain.OverlayIdentityPolicy
import com.pricelens.ui.components.PriceOverlay
import com.pricelens.util.LogT
import com.pricelens.util.SearchQueryCleaner
import com.pricelens.worker.ConfirmOutcome
import com.pricelens.worker.OverlayCurveRecorder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §1.5 浮窗管理（A2 重写）：无障碍检测到商详价格后，在其他 APP 之上显示比价浮窗。
 *
 * 与旧版的差异：
 *  - 取消 15s 自动消失 → **折叠胶囊常驻 + 手动关闭**；离开商详页时由服务调用
 *    [onLeftProductPage] 收窗并清空内容（旧版离开商详不收窗、残留内容串台）；
 *  - 拖动从挂在根 ComposeView 的 setOnTouchListener 改为子项 Compose pointerInput
 *    回调 [moveBy]（旧监听器与内部 AndroidComposeView 抢 ACTION_DOWN，拖动与点击不可兼得）；
 *  - 展开面板数据（历史价/券/多平台）通过 Hilt EntryPoint 取 [PriceRepository.overlayBundle]
 *    聚合，仅在确定性商品 ID（[PriceEvents.Detected.itemId]）时拉取历史与多平台，
 *    防错配硬规则；浮窗 UI 态（展开/收起）不放全局 object，留在 Composable 内部；
 *  - 窗口保持可触摸（不加 FLAG_NOT_TOUCHABLE）、WindowManager.LayoutParams.alpha 保持默认 1.0
 *    （Android 12+ Untrusted touch：半透明遮挡会丢弃穿越到下层的触摸；本浮窗不依赖触摸穿透，
 *    窗口尺寸紧贴内容，剩余区域由 FLAG_NOT_TOUCH_MODAL 直接放行给下层）。
 *
 * PL-29 新增形态：面板可收起为一枚小圆球（[OverlayMode.Panel] / [OverlayMode.Ball]），
 * 约束见「形态：面板 ↔ 小圆球」小节开头那四条；几何与形态判定都在纯文件 `BallGeometry.kt`。
 */
object OverlayManager {

    /**
     * 面板窗口的宽度上限（dp）。**不再是这里手写的一个数**：
     * 它由 [panelWindowMaxWidthDp] 从"内容最宽 + 左右外圈"算出来，而那两个输入同时是
     * `PriceOverlay` 排版用的数 —— 以前这条关系只活在注释里，改 UI 侧看不见，
     * 窗口就会把面板右边缘静默裁掉（只在数据最长的时候露出来）。
     */
    private val PANEL_MAX_WIDTH_DP: Float = panelWindowMaxWidthDp().toFloat()

    /** 球形态下"离开商详"的宽限期：门控一闪即断不立刻拆窗（见 [onLeftProductPage]） */
    private const val TEARDOWN_GRACE_MS = 1500L

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var host: OverlayHost? = null
    private var collectJob: Job? = null
    private var enrichJob: Job? = null
    private var identityJob: Job? = null
    private var params: WindowManager.LayoutParams? = null
    private var appContext: Context? = null
    private var serviceScope: CoroutineScope? = null
    private var screenMaxX = 0
    private var screenMaxY = 0

    /** 整块显示区宽高 + 状态栏/导航栏 inset：球的可用区是全宽（要能吸到左右两边），
     * 而 [screenMaxX]/[screenMaxY] 那对是面板"最多拖到中线"的老产品约束，两者不许互相顶替。 */
    private var displayWidth = 0
    private var displayHeight = 0
    private var insetTop = 0
    private var insetBottom = 0

    /** 球所在窗口的边长（px，含阴影留白）：面板 → 球切换时窗口就收成这个尺寸 */
    private var ballSidePx = 0

    /** 当前浮窗形态。**面板与球共用同一枚窗口**，切换只换尺寸与内容，绝不 addView 第二枚 */
    var mode by mutableStateOf<OverlayMode>(OverlayMode.Panel)
        private set

    /**
     * 用户是否主动把浮窗收成了球。与 [mode] 分开存：mode 描述"当前窗口是什么"，
     * 这一个描述"用户想要什么"，它要跨 hide()/show() 活下来（否则滚动一次球就变回胶囊条）。
     */
    private var userCollapsed = false

    /** 球形态下延迟收窗的待办（门控一闪即断时不该立刻拆窗；下一次命中会取消它） */
    private var pendingHideJob: Job? = null

    /**
     * 这一次拖动的起点（球窗口的左上角，ACTION_DOWN 那一刻由 [beginBallDrag] 钉住）。
     *
     * 每一帧的窗口位置都从"起点 + 屏幕坐标累计位移"反算，而不是"当前位置 + 本帧增量"：
     * 后者会把逐帧取整与窗口移动带来的反馈累加成滞后（"不跟手"的数学成因）。
     * 松手吸附走窗口当前位置，所以这两个值不需要在 [endBallDrag] 之后复位。
     */
    private var dragOriginX = 0
    private var dragOriginY = 0

    /** 当前展示内容（无障碍折叠胶囊/展开面板的唯一数据入口） */
    var content by mutableStateOf<PriceEvents.Detected?>(null)
        private set

    /** 展开面板聚合数据（null=未加载/无确定性 ID） */
    var bundle by mutableStateOf<OverlayBundle?>(null)
        private set

    /** 免凭证曲线：当前浮窗商品已确认的身份（null = 未确认）；每次 detection 现查表 */
    var confirmedIdentity by mutableStateOf<WatchIdentityEntity?>(null)
        private set

    /** 该身份已记录的日点数（配合上行显示「已记 N 天」） */
    var identityDays by mutableStateOf(0)
        private set

    /**
     * M7：该身份 `ovl:` 本机曲线的最低日点（null = 还没有可用点）。
     * 它是 recorder.lowestRecorded / [com.pricelens.data.repository.PriceRepository.identityCurve]
     * 这条窄接口的消费端：只写不读 = 用户确认完什么都看不到。UI 据它显「本机最低」。
     */
    var identityLowest by mutableStateOf<Double?>(null)
        private set

    /** 悬浮窗权限是否已授予 */
    fun canDrawOverlays(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
        else true

    /** 引导用户开启悬浮窗权限 */
    fun requestPermission(context: Context) {
        if (canDrawOverlays(context)) return
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 服务启动后调用：订阅价格事件流并展示浮窗 */
    fun start(context: Context, scope: CoroutineScope) {
        if (collectJob?.isActive == true) return
        appContext = context.applicationContext
        serviceScope = scope
        collectJob = scope.launch(Dispatchers.Main) {
            PriceEvents.detections.collect { detected ->
                if (!canDrawOverlays(context)) return@collect
                // 重新命中 = 还在商详页：把"延迟收窗"撤掉（球形态下滚动会一闪一闪地读不到价）
                pendingHideJob?.cancel()
                pendingHideJob = null
                content = detected
                bundle = null
                // M4：确认态在「换商品」这一刻归零（不是在展开时），下一次 trackIdentity 重算回填
                confirmedIdentity = null
                identityDays = 0
                identityLowest = null
                show(context)
                // 确定性 ID 命中：立即预取聚合数据（展开时第②③④行才有内容）
                if (detected.itemId != null) enrich(detected)
                trackIdentity(detected)
            }
        }
    }

    /**
     * 免凭证曲线：把已确认身份的读价交给 recorder（纯查表 + 本地写，不碰网络）。
     * 失败只记日志——记点绝不能拖累浮窗本身。
     *
     * M3（竞态）：与 [enrich] 共用同一套防护 —— 握 Job 句柄（新商品先 `cancel()` 上一条）、
     * 写状态前校验 `content?.signature` 仍是本次 detection。没这两条时，A 商品的慢查询
     * 回填会把 B 商品的 confirmedIdentity 盖掉，用户接着点「取消」删的就是 A 的身份
     * + A 的全部日点（误删用户数据）。
     *
     * M8（边界）：有确定性商品 ID（[PriceEvents.Detected.itemId]）的商品不进场。
     * 它已经有 `jd:<sku>` 那条曲线（服务端历史 + 盯价轮次），再往 `ovl:` 写一份隐形点
     * 就是同一商品两条线、两个「历史最低」，还会把没确认过的商品写进自采池。
     */
    private fun trackIdentity(detected: PriceEvents.Detected) {
        if (detected.itemId != null) return
        val scope = serviceScope ?: return
        val recorder = curveRecorder() ?: return
        identityJob?.cancel()
        identityJob = scope.launch(Dispatchers.Default) {
            try {
                val identity = recorder.matchedIdentity(detected)
                recorder.onDetected(detected)
                // 天数与最低价在写点之后读：今天刚记的那一点也算在内（刚确认就能看到「已记 1 天」）
                val days = if (identity != null) recorder.recordedDays(identity.productId) else 0
                val lowest = if (identity != null) recorder.lowestRecorded(identity.productId) else null
                if (content?.signature != detected.signature) return@launch
                withContext(Dispatchers.Main) {
                    confirmedIdentity = identity
                    identityDays = days
                    identityLowest = lowest
                }
            } catch (e: CancellationException) {
                throw e // 被新商品取消：不是故障，别伪装成「记点失败」
            } catch (e: Exception) {
                LogT.e("浮窗自采记点失败：" + e.javaClass.simpleName)
            }
        }
    }

    /**
     * 浮窗「就是这个商品」：建/认领身份并立即记第一点；成功后回表拿最新确认态。
     *
     * M6：Full / NotEligible 不再静默默默失败 —— 用户按了按钮就必须知道为什么没记上。
     */
    fun confirmIdentity() {
        val detected = content ?: return
        val scope = serviceScope ?: return
        val recorder = curveRecorder() ?: return
        identityJob?.cancel()
        identityJob = scope.launch(Dispatchers.IO) {
            val outcome = runCatching { recorder.confirm(detected) }
                .getOrElse { e ->
                    LogT.e("浮窗确认身份失败：" + e.javaClass.simpleName)
                    return@launch
                }
            try {
                when (outcome) {
                    is ConfirmOutcome.Confirmed, is ConfirmOutcome.Already -> {
                        val identity = recorder.matchedIdentity(detected)
                        val days = identity?.let { recorder.recordedDays(it.productId) } ?: 0
                        val lowest = identity?.let { recorder.lowestRecorded(it.productId) }
                        if (content?.signature != detected.signature) return@launch
                        withContext(Dispatchers.Main) {
                            confirmedIdentity = identity
                            identityDays = days
                            identityLowest = lowest
                        }
                    }

                    ConfirmOutcome.Full ->
                        toastOnMain(R.string.ovl_confirm_full_toast, OverlayIdentityPolicy.MAX_IDENTITIES)

                    ConfirmOutcome.NotEligible ->
                        toastOnMain(R.string.ovl_confirm_noteligible_toast)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 身份已经建好了，只是回填/提示这一步没读到：绝不能拖垮服务作用域
                LogT.e("浮窗确认后回填失败：" + e.javaClass.simpleName)
            }
        }
    }

    /**
     * 浮窗「取消」：删身份连带删它的日点（§5.4），确认态即刻回退。
     *
     * M3：删之前重新核对「屏幕上的商品现在匹配到的还是不是同一条身份」。
     * 核对不上（确认态被晚到的回填盖成了别的商品）就只回退 UI 状态，绝不碰库。
     */
    fun cancelIdentity() {
        val identity = confirmedIdentity ?: return
        val scope = serviceScope ?: return
        val recorder = curveRecorder() ?: return
        identityJob?.cancel()
        identityJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val matched = content?.let { recorder.matchedIdentity(it) }
                if (matched?.productId == identity.productId) {
                    recorder.cancel(identity.productId)
                } else {
                    LogT.w("取消浮窗身份被跳过：当前商品已不再匹配这条身份 " + identity.productId)
                }
            }.onFailure { e -> LogT.e("取消浮窗身份失败：" + e.javaClass.simpleName) }
            withContext(Dispatchers.Main) {
                confirmedIdentity = null
                identityDays = 0
                identityLowest = null
            }
        }
    }

    /** M6：Toast 要 Looper —— 工作线程里只负责「投回主线程再弹」 */
    private fun toastOnMain(resId: Int, vararg args: Any) {
        val ctx = appContext ?: return
        val scope = serviceScope ?: return
        scope.launch(Dispatchers.Main) {
            Toast.makeText(ctx, ctx.getString(resId, *args), Toast.LENGTH_LONG).show()
        }
    }

    /** 无障碍服务无构造注入：经 Hilt EntryPoint 取记录器单例（模式同 enrich） */
    private fun curveRecorder(): OverlayCurveRecorder? {
        val ctx = appContext ?: return null
        return runCatching {
            EntryPointAccessors.fromApplication(ctx.applicationContext, OverlayEntryPoint::class.java).curveRecorder()
        }.getOrNull()
    }

    /** 离开商详页（首页/列表/购物车/其他 App/读不到价）：收窗 + 清内容 */
    fun onLeftProductPage() {
        enrichJob?.cancel()
        identityJob?.cancel()
        if (shouldDeferTeardown(mode)) {
            // 球形态下不立刻拆窗：京东商详页往下一滚主价就出屏，门控会一闪一闪地判"离开商详"，
            // 每次都 remove/add 一遍 ⇒ 用户看到的"小圆球闪烁 + 又变回胶囊条"。
            // 给 1.5s 宽限：期间任何一次重新命中都取消拆除，球继续显示上一次读到的价。
            if (pendingHideJob?.isActive == true) return
            pendingHideJob = serviceScope?.launch(Dispatchers.Main) {
                delay(TEARDOWN_GRACE_MS)
                pendingHideJob = null
                confirmedIdentity = null
                identityDays = 0
                identityLowest = null
                content = null
                bundle = null
                hide()
            }
            return
        }
        confirmedIdentity = null
        identityDays = 0
        identityLowest = null
        content = null
        bundle = null
        hide()
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        pendingHideJob?.cancel()
        pendingHideJob = null
        // 服务真的被拆了才忘掉用户的形态选择（窗口都不在了，下一次是全新一次浮窗）
        userCollapsed = false
        enrichJob?.cancel()
        identityJob?.cancel()
        identityJob = null
        confirmedIdentity = null
        identityDays = 0
        identityLowest = null
        enrichJob = null
        serviceScope = null
        appContext = null
        // B2：服务被系统重建后不能再带上一代的显示度量（旋转/折叠过一次的屏幕尺寸已经变了）
        screenMaxX = 0
        screenMaxY = 0
        displayWidth = 0
        displayHeight = 0
        insetTop = 0
        insetBottom = 0
        ballSidePx = 0
        onLeftProductPage()
    }

    /**
     * B2：旋转 / 分屏 / 折叠展开后重置拖动边界。
     *
     * 旧实现只在进程内首算一次（`if (screenMaxX == 0)`），横屏后仍按竖屏宽高 clamp，
     * 胶囊能被拖到可见区之外（用户症状：浮窗拖没了，只能杀进程找回）。
     * 浮窗正显示时顺手把窗口拉回新边界内，不等下一次 show()。
     *
     * PL-29：球形态另外重跑一次"夹回可用区 + 吸附 + 换边长"——旋转后的右边缘已经不是
     * 原来那个右边缘，折叠屏展开后的 `ballSidePx`（按 density 算）也可能变了。
     */
    fun onConfigurationChanged() {
        screenMaxX = 0
        screenMaxY = 0
        displayWidth = 0
        displayHeight = 0
        val ctx = appContext ?: return
        val view = overlayView ?: return // 未显示：下一次 show() 自己会算
        val wm = windowManager ?: return
        val p = params ?: return
        updateScreenBounds(ctx)
        if (mode == OverlayMode.Ball) {
            val x = snappedBallX(p.x)
            val (nx, ny) = clampPosition(x, p.y, ballSidePx, displayWidth, displayHeight, insetTop, insetBottom)
            p.width = ballSidePx
            p.height = ballSidePx
            p.x = nx
            p.y = ny
            applyLayout(wm, view, p)
            persistBallPosition(nx, ny)
            return
        }
        val x = p.x.coerceIn(0, screenMaxX)
        val y = p.y.coerceIn(0, screenMaxY)
        if (x == p.x && y == p.y) return
        p.x = x
        p.y = y
        applyLayout(wm, view, p)
    }

    /** 手动关闭/跳转收起：只收窗；下一次 emit 会重新弹出。球与面板共用一枚窗口，removeView 一次就全清 */
    fun hide() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: IllegalArgumentException) {
                // 视图尚未附着或已移除
            }
        }
        overlayView = null
        host?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        host = null
        windowManager = null
        params = null
        // 形态**不在这里复位**：用户主动收起的球是一次选择，不该因为页面滚动让门控闪断一次
        // 就被抹掉（真机症状：球一闪变回胶囊条）。复位只发生在 stop()（服务真的拆了）
        // 或用户自己展开回面板。下一次 show() 按 [userCollapsed] 决定用哪种尺寸建窗。
    }

    /** Composable 展开时回调：无确定性 ID 的延迟预取（TITLE_ONLY 只允许出券行，且标仅供参考） */
    fun onExpanded(detected: PriceEvents.Detected) {
        if (bundle != null) return
        enrich(detected)
    }

    /** pointerInput 拖动回调：窗口 offset 更新（gravity TOP|END：向右拖 x 减小、向下拖 y 增大） */
    fun moveBy(dx: Float, dy: Float) {
        if (mode != OverlayMode.Panel) return // 球的拖动走 [moveBallBy]：它是 TOP|START 坐标系，别混
        val p = params ?: return
        val wm = windowManager ?: return
        val view = overlayView ?: return
        p.x = (p.x - dx.toInt()).coerceIn(0, screenMaxX)
        p.y = (p.y + dy.toInt()).coerceIn(0, screenMaxY)
        applyLayout(wm, view, p)
    }

    // ---------- 形态：面板 ↔ 小圆球 ----------
    //
    // 四条约束（都是真机上会出问题的那类，别改）：
    //  1. **只有一枚窗口**。换形态只做 `updateViewLayout`（尺寸 / 重力 / 坐标），绝不 addView
    //     第二枚 —— 否则面板↔球来回点几次就攒出一叠窗体（收起态的泄漏版本）。
    //  2. **窗口尺寸必须跟着收**。面板是 WRAP_CONTENT；球必须换成固定边长（[ballSidePx]），
    //     否则面板那一条透明区域照样吃掉屏幕上的触摸，用户点下面的商品页会失灵。
    //     球窗口比球大的那圈只是阴影留白（`BALL_SHADOW_GUTTER_DP`），边界与吸附一律按**窗口**边长算。
    //  3. **坐标系随形态切换**：面板用 TOP|END（x = 离屏幕右边多远，配合 [moveBy] 那条
    //     "最远拖到中线"的约束），球用 TOP|START（x = 离左边多远），左右吸附才不用猜面板宽度。
    //     两套坐标的换算只有这一处（[collapseToBall] / [expandToPanel]）。
    //  4. **动画在 Composable 里跑，窗口尺寸在这里改**：`PriceOverlay` 先把旧形态 scale+fade
    //     收拢（250ms / PriceLensEasing），动画结束才回调 [collapseToBall] / [expandToPanel]。
    //     反过来（先缩窗口再播动画）会被窗口裁掉，用户看到的是"啪"地跳没。
    //
    // 为什么**不**用系统 Bubble API（`android.widget.bubbles` / `Bundle`）：
    //  - 它挂在通知体系上（要一枚气泡通知、由用户长按图标展开），接不上"无障碍刚读到这一页价格"
    //    这条事件驱动的路径；
    //  - 展开尺寸、吸附边、层级、锁屏/分屏行为全由 ROM 决定，国产 ROM 大多阉割或改义 ——
    //    真机表现不可控，也在本仓库的测试面之外（这里连 Robolectric 都没有）；
    //  - 本浮窗要的恰恰是"贴着当前商品页的一枚可控小窗"，自建 WindowManager 浮窗是上面
    //    那四条学费换来的成熟路径，换形态只是给它加一个尺寸。

    /**
     * 面板 → 球。由 `PriceOverlay` 在收拢动画跑完后调用（[nextOverlayMode] 是唯一闸门：
     * 非法转移 = 原地不动，双指连点 / 动画重入都只会有一次窗口尺寸变更）。
     *
     * 位置优先用持久化的那一份（球的家），从没拖过才对齐聚当前面板的右边缘；随后吸附到最近的边。
     */
    fun collapseToBall() {
        val next = nextOverlayMode(mode, OverlayEvent.COLLAPSE)
        if (next == mode) return
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val p = params ?: return
        val ctx = appContext ?: return
        ensureBounds(ctx)
        mode = next
        userCollapsed = true
        val stored = readBallPosition()
        val rawX = stored?.first ?: (displayWidth - p.x - ballSidePx)
        val rawY = stored?.second ?: p.y
        val (homeX, homeY) = clampPosition(rawX, rawY, ballSidePx, displayWidth, displayHeight, insetTop, insetBottom)
        val (x, y) = clampPosition(snappedBallX(homeX), homeY, ballSidePx, displayWidth, displayHeight, insetTop, insetBottom)
        p.gravity = Gravity.TOP or Gravity.START
        p.width = ballSidePx
        p.height = ballSidePx
        p.x = x
        p.y = y
        applyLayout(wm, view, p)
        persistBallPosition(x, y)
    }

    /** 球 → 面板。同样由 `PriceOverlay` 在收拢动画跑完后调用；窗口回到贴内容，坐标换回 TOP|END */
    fun expandToPanel() {
        val next = nextOverlayMode(mode, OverlayEvent.EXPAND)
        if (next == mode) return
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val p = params ?: return
        val ctx = appContext ?: return
        ensureBounds(ctx)
        mode = next
        userCollapsed = false
        // 球的右边缘 = 面板的右边缘。面板最宽 PANEL_MAX_WIDTH_DP，x 再大就会把它整个推到
        // 屏幕左外侧；这条上限比"最远拖到中线"更严时听上限的。
        val density = ctx.resources.displayMetrics.density
        val panelEdgeX = (displayWidth - PANEL_MAX_WIDTH_DP * density).roundToInt().coerceAtLeast(0)
        p.gravity = Gravity.TOP or Gravity.END
        p.width = WindowManager.LayoutParams.WRAP_CONTENT
        p.height = WindowManager.LayoutParams.WRAP_CONTENT
        p.x = (displayWidth - (p.x + ballSidePx)).coerceIn(0, minOf(screenMaxX, panelEdgeX))
        p.y = p.y.coerceIn(0, screenMaxY)
        applyLayout(wm, view, p)
    }

    /**
     * 球：按下那一刻钉住拖动起点（窗口左上角）。之后每一帧都用"起点 + 屏幕坐标累计位移"
     * 反算窗口位置，所以这里必须在 ACTION_DOWN 时被调一次 —— 漏掉的话起点是上一次拖动的值，
     * 球会整段跳偏。
     */
    fun beginBallDrag() {
        if (mode != OverlayMode.Ball) return
        val p = params ?: return
        ensureBounds(appContext ?: return)
        dragOriginX = p.x
        dragOriginY = p.y
    }

    /**
     * 球：拖动中的**每一帧**搬窗口（[dx]/[dy] 是相对按下那一刻的屏幕坐标累计位移）。
     *
     * 为什么不像以前那样"拖动期间只画 graphicsLayer.translationX/Y、松手才搬一次窗口"：
     * 球形态下窗口本身就只有球径大小（[collapseToBall] 那条"窗口跟着收成球径，否则面板的
     * 透明区会吃掉商品页触摸"的约束），而 translation 是画在**窗口表面**上的 —— 内容一旦
     * 被画到窗口边界之外就被裁掉，球最多只能离开窗口半个身位，手指继续走它就"停在那儿"。
     * 用户 2026-10-03 复测报的"球还是不跟手"就是这个，不是坐标反馈问题。
     * `FLAG_LAYOUT_NO_LIMITS` 也不会放大表面，只有把窗口搬起来才对。
     *
     * 每帧一次 `updateViewLayout` 是这类悬浮球的通行做法（gkd / EasyFloat / FloatWindow 都是），
     * 不需要额外节流；位置没变的那一帧直接跳过，省一次 binder 调用。
     */
    fun dragBallBy(dx: Float, dy: Float) {
        if (mode != OverlayMode.Ball) return
        val p = params ?: return
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val (x, y) = ballDragPosition(
            dragOriginX, dragOriginY, dx, dy, ballSidePx, displayWidth, displayHeight, insetTop, insetBottom
        )
        if (x == p.x && y == p.y) return
        p.x = x
        p.y = y
        applyLayout(wm, view, p)
    }

    /**
     * 松手：从**窗口当前位置**吸附最近边 + 夹进可用区 + 落盘。
     *
     * 不再接收累计位移 —— 拖动期间窗口已经跟着手指走完了，位置就在 params 里；
     * 再累加一次就是双倍位移（这正是"每帧搬窗口"与"松手补一次"两套算法混用时会出现的错）。
     */
    fun endBallDrag() {
        if (mode != OverlayMode.Ball) return
        val p = params ?: return
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val (x, y) = ballDropPosition(
            p.x, p.y, 0f, 0f, ballSidePx, displayWidth, displayHeight, insetTop, insetBottom
        )
        p.x = x
        p.y = y
        applyLayout(wm, view, p)
        persistBallPosition(x, y)
    }

    /** [Side] → 该边的窗口 x：左贴 0、右贴 `屏宽 - 窗口边长`（可见内缩由阴影留白负责） */
    private fun snappedBallX(x: Int): Int = when (snapEdge(x, ballSidePx, displayWidth)) {
        Side.Left -> 0
        Side.Right -> (displayWidth - ballSidePx).coerceAtLeast(0)
        Side.Keep -> x
    }

    // ---------- 内部 ----------

    private fun enrich(detected: PriceEvents.Detected) {
        val ctx = appContext ?: return
        val scope = serviceScope ?: return
        val keyword = SearchQueryCleaner.clean(detected.title) ?: return
        enrichJob?.cancel()
        // M4：展开面板不能清确认态 —— 已确认的商品一点展开，「已记 N 天」那一行和
        // 「取消确认」入口就全部消失（再点收起再点回来也回不来，直到下一次 detection）。
        // 确认态的清空只发生在 collect 收到新商品（trackIdentity 会重算）与 onLeftProductPage/stop。
        enrichJob = scope.launch(Dispatchers.Default) {
            val repository = runCatching {
                EntryPointAccessors.fromApplication(
                    ctx.applicationContext,
                    OverlayEntryPoint::class.java
                ).repository()
            }.getOrNull() ?: return@launch
            val result = runCatching { repository.overlayBundle(keyword, detected.itemId) }.getOrNull()
            // 回填前校验仍是同一商品，防止慢响应把旧商品数据盖到新商品上
            if (result != null && content?.signature == detected.signature) {
                withContext(Dispatchers.Main) { bundle = result }
            }
        }
    }

    /**
     * 显示度量（B2）：API 30+ 走 `WindowMetrics`，`resources.displayMetrics` 只作 minSdk 26 的回退分支
     * （后者在部分机型上不含装饰区、且不随显示区域变化及时更新）。
     *
     * 「最远拖到屏幕中线」是有意的产品约束（见旧版注释），这里只换测量来源，不改比例。
     * PL-29：整块宽高 + 状态栏/导航栏 inset 一并算好交给纯函数 [clampPosition]（它自己不读系统栏）。
     */
    private fun updateScreenBounds(context: Context) {
        val wm = context.getSystemService(WindowManager::class.java)
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
        val (top, bottom) = systemBarInsets(context, wm)
        displayWidth = width
        displayHeight = height
        insetTop = top
        insetBottom = bottom
        val density = context.resources.displayMetrics.density
        ballSidePx = (ballWindowSideDp() * density).roundToInt().coerceAtLeast(1)
        screenMaxX = (width * 0.5f).toInt()
        screenMaxY = (height * 0.85f).toInt()
    }

    /** 状态栏 / 导航栏高度（球不许压上去）。inset 只在这里读，纯函数只用参数。 */
    private fun systemBarInsets(context: Context, wm: WindowManager): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return frameworkDimen(context, "status_bar_height") to frameworkDimen(context, "navigation_bar_height")
        }
        val insets = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        return insets.top to insets.bottom
    }

    /** 低版本回退：framework dimen 读不到就是 0（宁可少让几十像素，也不要一个读不到的崩溃面） */
    private fun frameworkDimen(context: Context, name: String): Int = runCatching {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        if (id == 0) 0 else context.resources.getDimensionPixelSize(id)
    }.getOrDefault(0)

    private fun ensureBounds(context: Context) {
        if (screenMaxX == 0 || displayWidth == 0) updateScreenBounds(context)
    }

    /** 球的位置落在 SharedPreferences("pricelens") 的 `ball_x`/`ball_y`（读写收口在 [SettingsRepository]） */
    private fun persistBallPosition(x: Int, y: Int) {
        val settings = settingsRepository() ?: return
        settings.ballX = x
        settings.ballY = y
    }

    /** null = 从没拖过（任一分量还是哨兵值 -1 都算没有，不能一半新一半旧） */
    private fun readBallPosition(): Pair<Int, Int>? {
        val settings = settingsRepository() ?: return null
        val x = settings.ballX
        val y = settings.ballY
        if (x < 0 || y < 0) return null
        return x to y
    }

    /** 无障碍服务无构造注入：同 [curveRecorder]，经 Hilt EntryPoint 取设置单例 */
    private fun settingsRepository(): SettingsRepository? {
        val ctx = appContext ?: return null
        return runCatching {
            EntryPointAccessors.fromApplication(ctx.applicationContext, OverlayEntryPoint::class.java).settings()
        }.getOrNull()
    }

    /** 换形态/拖动共用的落窗口子：视图可能在这一步之前已经分离（hide / onLeftProductPage），不能抛 */
    private fun applyLayout(wm: WindowManager, view: ComposeView, p: WindowManager.LayoutParams) {
        try {
            wm.updateViewLayout(view, p)
        } catch (_: IllegalArgumentException) {
            // 视图已分离
        }
    }

    private fun show(context: Context) {
        if (overlayView != null) return // 已显示，仅更新 content/bundle 状态（球形态下换商品：只改文案，不收窗）

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        ensureBounds(context)

        val overlayHost = OverlayHost()
        host = overlayHost
        // 新建窗口跟随用户上一次的主动选择：球形态下门控一闪、窗口重建，
        // 不该把球"变回"胶囊条（真机报的"又显示继续滑动查看图文详细"就是这个）
        mode = initialForm(userCollapsed)

        val ball = mode == OverlayMode.Ball
        val lp = WindowManager.LayoutParams(
            if (ball) ballSidePx else WindowManager.LayoutParams.WRAP_CONTENT,
            if (ball) ballSidePx else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (ball) {
                // 球用 TOP|START（x = 离左边多远），左右吸附才不用猜面板宽度；
                // 位置优先取持久化的那一份，没有就落在右侧默认位
                gravity = Gravity.TOP or Gravity.START
                val stored = readBallPosition()
                val (x, y) = clampPosition(
                    stored?.first ?: (displayWidth - ballSidePx), stored?.second ?: 200,
                    ballSidePx, displayWidth, displayHeight, insetTop, insetBottom
                )
                this.x = x
                this.y = y
            } else {
                gravity = Gravity.TOP or Gravity.END
                x = 32
                y = 200
            }
        }
        params = lp

        // B3：ComposeView 的 Context 不能是 Service 实例（主题/配置上下文错，且组合内容一旦捕获
        // context 就长期持有已销毁的服务）——用 applicationContext + 应用主题包一层。
        val viewContext = ContextThemeWrapper(appContext ?: context, R.style.Theme_PriceLens)
        val view = ComposeView(viewContext).apply {
            setViewTreeLifecycleOwner(overlayHost)
            setViewTreeSavedStateRegistryOwner(overlayHost)
            setContent {
                val detected = content
                if (detected != null) {
                    PriceOverlay(
                        detected = detected,
                        bundle = bundle,
                        identity = confirmedIdentity,
                        identityDays = identityDays,
                        identityLowest = identityLowest,
                        mode = mode,
                        onDrag = { dx, dy -> moveBy(dx, dy) },
                        onBallDragStart = { beginBallDrag() },
                        onBallDrag = { dx, dy -> dragBallBy(dx, dy) },
                        onBallDragEnd = { endBallDrag() },
                        onCollapseWindow = { collapseToBall() },
                        onExpandWindow = { expandToPanel() },
                        onToggleExpanded = { expanded ->
                            if (expanded) onExpanded(detected)
                        },
                        onCompare = {
                            val cleaned = SearchQueryCleaner.clean(detected.title) ?: detected.title
                            hide()
                            context.startActivity(
                                Intent(context, com.pricelens.ui.main.MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    .putExtra("focus_title", cleaned)
                                    .putExtra("focus_price", detected.price)
                            )
                        },
                        onConfirmIdentity = { confirmIdentity() },
                        onCancelIdentity = { cancelIdentity() },
                        onDismiss = {
                            content = null
                            bundle = null
                            hide()
                        }
                    )
                }
            }
        }
        overlayHost.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        wm.addView(view, lp)
        overlayHost.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        overlayView = view
    }

    /** 无障碍服务无构造注入，展开面板数据经 Hilt EntryPoint 从 Application 取仓储单例 */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface OverlayEntryPoint {
        fun repository(): PriceRepository

        fun curveRecorder(): OverlayCurveRecorder

        fun settings(): SettingsRepository
    }

    /**
     * WindowManager 里的 ComposeView 需要手动提供 Lifecycle / SavedState 宿主。
     */
    private class OverlayHost : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        init {
            savedStateController.performRestore(null)
        }

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            lifecycleRegistry.handleLifecycleEvent(event)
        }
    }
}
