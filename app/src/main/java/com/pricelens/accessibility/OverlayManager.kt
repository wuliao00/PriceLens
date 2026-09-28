package com.pricelens.accessibility

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
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
import com.pricelens.data.repository.OverlayBundle
import com.pricelens.data.repository.PriceRepository
import com.pricelens.ui.components.PriceOverlay
import com.pricelens.util.SearchQueryCleaner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
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
                show(context)
                // 确定性 ID 命中：立即预取聚合数据（展开时第②③④行才有内容）
                if (detected.itemId != null) enrich(detected)
            }
        }
    }

    /** 离开商详页（首页/列表/购物车/其他 App/读不到价）：收窗 + 清内容 */
    fun onLeftProductPage() {
        enrichJob?.cancel()
        content = null
        bundle = null
        hide()
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        enrichJob?.cancel()
        enrichJob = null
        serviceScope = null
        appContext = null
        onLeftProductPage()
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

    private fun show(context: Context) {
        if (overlayView != null) return // 已显示，仅更新 content/bundle 状态

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        if (screenMaxX == 0) {
            val metrics = context.resources.displayMetrics
            screenMaxX = (metrics.widthPixels * 0.5f).toInt() // 胶囊最远拖到屏幕中线
            screenMaxY = (metrics.heightPixels * 0.85f).toInt()
        }

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

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(overlayHost)
            setViewTreeSavedStateRegistryOwner(overlayHost)
            setContent {
                val detected = content
                if (detected != null) {
                    PriceOverlay(
                        detected = detected,
                        bundle = bundle,
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
