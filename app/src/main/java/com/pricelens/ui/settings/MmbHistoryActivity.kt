package com.pricelens.ui.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.pricelens.R
import com.pricelens.data.remote.ManmanbuyApi
import com.pricelens.data.remote.MmbPageSeries
import com.pricelens.data.repository.PriceRepository
import com.pricelens.data.repository.SettingsRepository
import com.pricelens.domain.PriceSource
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 「用慢慢买网页取历史价」：把用户**自己账号**的慢慢买桌面历史页开在应用内 WebView 里，
 * 由站点自己的 JS 去取数，我们只读它渲染用的那份数据（`flotChart.data`）并写进本机曲线。
 *
 * 为什么必须走 WebView（2026-10-02 实测后定的）：
 *  - 移动端那条 `m/history.aspx?type=history_mobile_tool` 对任何程序化请求都先弹阿里云滑块，
 *    带不带 Cookie 都一样（4 组对照同 4,135 字节）⇒ 那条路永远出不了数据；
 *  - 桌面页 `HistoryLowest.aspx` 不弹滑块，但它的数据接口 `POST /api.ashx` 要一页一签的
 *    `ticket` + 站点**混淆过**的签名脚本 —— 复刻签名＝逆向站点反爬，不做；
 *  - 页面自己会完成"检查京东授权 → 取数 → 渲染"，我们**只读结果**，不碰票据与签名。
 *
 * 红线：本页不代过验证码、不代点授权。卡在授权/验证时只把页面原样呈现并提示用户自己完成。
 */
@AndroidEntryPoint
class MmbHistoryActivity : ComponentActivity() {

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var repository: PriceRepository

    private lateinit var webView: WebView
    private lateinit var status: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var polls = 0
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val productUrl = intent.getStringExtra(EXTRA_PRODUCT_URL) ?: ManmanbuyApi.PROBE_PRODUCT_URL
        val productId = intent.getStringExtra(EXTRA_PRODUCT_ID) ?: deriveProductId(productUrl)

        status = TextView(this).apply {
            setPadding(28, 20, 28, 20)
            textSize = 13f
            setText(R.string.mmb_fetch_status_loading)
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 桌面 UA：这一页本来就是桌面版（真机实测：WebView 默认的 "wv" UA 更容易被弹人机验证）
            settings.userAgentString = DESKTOP_UA
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    polls = 0
                    handler.postDelayed(pollTick, 1200)
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        injectStoredCookie()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 16, 28, 16)
            addView(
                TextView(this@MmbHistoryActivity).apply {
                    setText(R.string.mmb_fetch_title)
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            addView(
                Button(this@MmbHistoryActivity).apply {
                    setText(R.string.mmb_fetch_reload)
                    setOnClickListener { reload() }
                }
            )
            addView(
                Button(this@MmbHistoryActivity).apply {
                    setText(R.string.mmb_fetch_done)
                    setOnClickListener { finish() }
                }
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(status)
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) webView.goBack() else finish()
                }
            }
        )

        MmbHistoryFetch.begin(productId)
        load(productUrl)
    }

    private var currentUrl: String = ""

    private fun load(productUrl: String) {
        currentUrl = productUrl
        status.setText(R.string.mmb_fetch_status_loading)
        webView.loadUrl(HISTORY_URL_PREFIX + java.net.URLEncoder.encode(productUrl, "UTF-8"))
        handler.removeCallbacks(pollTick)
        handler.postDelayed(pollTick, 2500)
    }

    private fun reload() {
        done = false
        polls = 0
        load(currentUrl)
    }

    /**
     * 手动粘贴进设置的那份 Cookie 只躺在 SharedPreferences 里，WebView 的 CookieStore 里没有
     * （只有走「登录自动抓取」时才在）。这里把它补进 CookieStore，两条来源都能用。
     */
    private fun injectStoredCookie() {
        val cookie = settings.manmanbuyCookie
        if (cookie.isBlank()) return
        val manager = CookieManager.getInstance()
        // ⚠ 真机实测（2026-10-02）：`setCookie(host, "a=1; b=2; c=3")` 只会存下**第一条**，
        // 于是登录 Cookie（60014_mmmuser / mmbuser_ext）全丢，页面看起来像"未登录"。
        // 必须逐条拆开注入 —— 拉到 WebView 的 cookie 库里数过条目才发现。
        val saved = cookie.split(';').map { it.trim() }.filter { it.contains('=') }
            .associateBy { it.substringBefore('=') }
        for (host in COOKIE_HOSTS) {
            // 只补 WebView 里没有的名字：站点会在会话里轮换部分 cookie（如 60014_mmmuser），
            // 无脑覆盖会把更新鲜的会话值顶掉 —— 那是"昨天还好好的、今天又要重新登录"的成因。
            val live = manager.getCookie(host).orEmpty().split(';').map { it.trim() }
                .filter { it.contains('=') }.associateBy { it.substringBefore('=') }
            for ((name, pair) in saved) {
                if (!live.containsKey(name)) manager.setCookie(host, pair)
            }
        }
        manager.flush()
    }

    private val pollTick: Runnable = object : Runnable {
        override fun run() {
            if (done || isFinishing) return
            webView.evaluateJavascript(MmbPageSeries.READ_PROBE_JS) { raw ->
                val probe = MmbPageSeries.parsePageProbe(unescapeJsString(raw))
                when {
                    probe.series.size >= 2 -> persist(probe.series)
                    probe.series.size == 1 -> {
                        // 只有一个点：说出来，别让用户对着空白等（曲线至少要两个点）
                        status.text = getString(R.string.mmb_fetch_status_one_point, probe.series.first().date)
                    }
                    MmbPageSeries.pageWantsJdAuth(probe.visibleText) -> {
                        status.text = getString(R.string.mmb_fetch_status_need_auth)
                    }
                    MmbPageSeries.pageLooksCrawling(probe.visibleText) -> {
                        status.text = getString(R.string.mmb_fetch_status_crawling)
                    }
                    MmbPageSeries.pageWantsCaptcha(probe.visibleText) -> {
                        status.text = getString(R.string.mmb_fetch_status_captcha)
                    }
                    else -> {
                        polls++
                        if (polls < MAX_POLLS) {
                            handler.postDelayed(pollTick, POLL_INTERVAL_MS)
                        } else {
                            status.text = getString(R.string.mmb_fetch_status_no_series)
                        }
                    }
                }
            }
        }
    }

    private fun persist(points: List<ManmanbuyApi.PricePoint>) {
        if (done) return
        done = true
        val collapsed = MmbPageSeries.collapseByDay(points)
        val prices = collapsed.map { it.price }
        val history = ManmanbuyApi.History(
            current = prices.last(),
            lowest = prices.min(),
            highest = prices.max(),
            points = collapsed
        )
        val productId = intent.getStringExtra(EXTRA_PRODUCT_ID) ?: deriveProductId(currentUrl)
        status.text = getString(R.string.mmb_fetch_status_saving, collapsed.size)
        lifecycleScope.launch {
            val ok = runCatching {
                repository.persistHistory(productId, history, PriceSource.MANMANBUY)
                true
            }.getOrDefault(false)
            status.text = if (ok) {
                MmbHistoryFetch.succeed(productId, collapsed.size, history.lowest, history.highest)
                getString(
                    R.string.mmb_fetch_status_saved,
                    collapsed.size,
                    com.pricelens.util.PriceFormatter.formatRaw(history.lowest),
                    com.pricelens.util.PriceFormatter.formatRaw(history.highest)
                )
            } else {
                MmbHistoryFetch.fail(productId)
                getString(R.string.mmb_fetch_status_save_failed)
            }
        }
        handler.removeCallbacks(pollTick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollTick)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PRODUCT_URL = "product_url"
        const val EXTRA_PRODUCT_ID = "product_id"

        private const val HISTORY_URL_PREFIX = "https://tool.manmanbuy.com/HistoryLowest.aspx?url="

        /** 桌面版 Chrome UA（与站点给桌面浏览器的一致） */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val MAX_POLLS = 20
        private const val POLL_INTERVAL_MS = 1500L
        private val COOKIE_HOSTS = listOf(
            "https://tool.manmanbuy.com",
            "https://www.manmanbuy.com",
            "https://m.manmanbuy.com"
        )

        /** 从京东链接里取 `jd:<sku>`（与盯价目标同一个 key 空间） */
        internal fun deriveProductId(productUrl: String): String {
            val sku = Regex("item(?:\\.m)?\\.jd\\.com/(?:product/)?(\\d{6,})").find(productUrl)?.groupValues?.get(1)
            return if (sku != null) "jd:$sku" else productUrl.take(64)
        }

        /**
         * `evaluateJavascript` 的回调把 JS 字符串结果按 JSON 编码返回（带引号、内部转义）。
         * 非字符串结果（null/undefined）形如 `null`。这里统一还原成原始字符串。
         */
        internal fun unescapeJsString(raw: String?): String? {
            if (raw == null || raw == "null") return null
            return runCatching { JSONObject("{\"v\":$raw}").optString("v") }.getOrNull()
        }
    }
}
