package com.pricelens.accessibility

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 */
object OverlayManager {

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
        onLeftProductPage()
    }

    /**
     * B2：旋转 / 分屏 / 折叠展开后重置拖动边界。
     *
     * 旧实现只在进程内首算一次（`if (screenMaxX == 0)`），横屏后仍按竖屏宽高 clamp，
     * 胶囊能被拖到可见区之外（用户症状：浮窗拖没了，只能杀进程找回）。
     * 浮窗正显示时顺手把窗口拉回新边界内，不等下一次 show()。
     */
    fun onConfigurationChanged() {
        screenMaxX = 0
        screenMaxY = 0
        val ctx = appContext ?: return
        val view = overlayView ?: return // 未显示：下一次 show() 自己会算
        val wm = windowManager ?: return
        val p = params ?: return
        updateScreenBounds(ctx)
        val x = p.x.coerceIn(0, screenMaxX)
        val y = p.y.coerceIn(0, screenMaxY)
        if (x == p.x && y == p.y) return
        p.x = x
        p.y = y
        try {
            wm.updateViewLayout(view, p)
        } catch (_: IllegalArgumentException) {
            // 视图已分离
        }
    }

    /** 手动关闭/跳转收起：只收窗；下一次 emit 会重新弹出 */
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
    }

    /** Composable 展开时回调：无确定性 ID 的延迟预取（TITLE_ONLY 只允许出券行，且标仅供参考） */
    fun onExpanded(detected: PriceEvents.Detected) {
        if (bundle != null) return
        enrich(detected)
    }

    /** pointerInput 拖动回调：窗口 offset 更新（gravity TOP|END：向右拖 x 减小、向下拖 y 增大） */
    fun moveBy(dx: Float, dy: Float) {
        val p = params ?: return
        val wm = windowManager ?: return
        val view = overlayView ?: return
        p.x = (p.x - dx.toInt()).coerceIn(0, screenMaxX)
        p.y = (p.y + dy.toInt()).coerceIn(0, screenMaxY)
        try {
            wm.updateViewLayout(view, p)
        } catch (_: IllegalArgumentException) {
            // 视图已分离
        }
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
     * 拖动边界（B2）：API 30+ 走 `WindowMetrics`，`resources.displayMetrics` 只作 minSdk 26 的回退分支
     * （后者在部分机型上不含装饰区、且不随显示区域变化及时更新）。
     *
     * 「最远拖到屏幕中线」是有意的产品约束（见 `:188` 旧注释），这里只换测量来源，不改比例。
     */
    private fun updateScreenBounds(context: Context) {
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
        screenMaxX = (width * 0.5f).toInt()
        screenMaxY = (height * 0.85f).toInt()
    }

    private fun show(context: Context) {
        if (overlayView != null) return // 已显示，仅更新 content/bundle 状态

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        if (screenMaxX == 0) updateScreenBounds(context)

        val overlayHost = OverlayHost()
        host = overlayHost

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 32
            y = 200
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
                        onDrag = { dx, dy -> moveBy(dx, dy) },
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
