package com.pricelens.accessibility

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
import com.pricelens.ui.components.PriceOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * §1.5 浮窗管理：无障碍服务检测到价格后，在其他 APP 之上弹出极简比价浮窗。
 *
 * - TYPE_APPLICATION_OVERLAY，不抢焦点（不遮挡用户操作）
 * - 可拖动、可关闭，15s 无操作自动消失
 * - 需要 SYSTEM_ALERT_WINDOW 授权（引导用户去系统设置开启）
 * - "查看历史价 & 优惠券" → 拉起 MainActivity 并带上商品信息
 */
object OverlayManager {

    private const val AUTO_DISMISS_MS = 15_000L

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var host: OverlayHost? = null
    private var collectJob: Job? = null
    private var dismissJob: Job? = null

    var content by mutableStateOf<PriceEvents.Detected?>(null)
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
        collectJob = scope.launch(Dispatchers.Main) {
            PriceEvents.detections.collect { detected ->
                if (!canDrawOverlays(context)) return@collect
                content = detected
                show(context)
                scheduleAutoDismiss(scope)
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        hide()
    }

    fun hide() {
        dismissJob?.cancel()
        dismissJob = null
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
    }

    private fun scheduleAutoDismiss(scope: CoroutineScope) {
        dismissJob?.cancel()
        dismissJob = scope.launch(Dispatchers.Main) {
            delay(AUTO_DISMISS_MS)
            hide()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        if (overlayView != null) return // 已显示，仅更新 content 状态

        val overlayHost = OverlayHost()
        host = overlayHost

        val params = WindowManager.LayoutParams(
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

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(overlayHost)
            setViewTreeSavedStateRegistryOwner(overlayHost)
            setContent {
                val detected = content
                if (detected != null) {
                    PriceOverlay(
                        price = detected.rawPriceText,
                        title = detected.title ?: "当前商品",
                        onCompare = {
                            hide()
                            context.startActivity(
                                Intent(context, com.pricelens.ui.main.MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    .putExtra("focus_title", detected.title)
                                    .putExtra("focus_price", detected.price)
                            )
                        },
                        onDismiss = { hide() }
                    )
                }
            }
            // 拖动：标题区域按下移动即移动浮窗，不拦截子控件点击
            setOnTouchListener { v: View, e: MotionEvent ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (params.x - (e.rawX - downX).toInt()).coerceAtLeast(0)
                        params.y = (params.y - (e.rawY - downY).toInt()).coerceAtLeast(0)
                        downX = e.rawX; downY = e.rawY
                        wm.updateViewLayout(v, params)
                        true
                    }
                    else -> false
                }
            }
        }
        overlayHost.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        wm.addView(view, params)
        overlayHost.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        overlayView = view
    }

    private var downX = 0f
    private var downY = 0f

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
