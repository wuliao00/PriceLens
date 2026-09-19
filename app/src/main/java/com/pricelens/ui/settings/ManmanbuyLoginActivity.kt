package com.pricelens.ui.settings

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.pricelens.R
import com.pricelens.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import java.net.URI
import javax.inject.Inject

/**
 * 慢慢买内置登录页：WebView 登录后一键抓取 Cookie 存入本机设置。
 * 仅允许停留在 manmanbuy.com 域名，其余跳转直接拦截（防钓鱼 / 防下载器）。
 */
@AndroidEntryPoint
class ManmanbuyLoginActivity : ComponentActivity() {

    @Inject
    lateinit var settings: SettingsRepository

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = url == null || !isManmanbuy(url)
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#12B76A"))
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        header.addView(
            TextView(this).apply {
                setText(R.string.settings_mmb_login_title)
                setTextColor(Color.WHITE)
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        header.addView(
            Button(this).apply {
                setText(R.string.settings_mmb_login_save)
                setOnClickListener { saveCookieAndFinish() }
            }
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(
                webView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
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

        webView.loadUrl(LOGIN_URL)
    }

    private fun saveCookieAndFinish() {
        CookieManager.getInstance().flush()
        val cookie = CookieManager.getInstance().getCookie("https://www.manmanbuy.com").orEmpty()
        if (cookie.isBlank()) {
            Toast.makeText(this, R.string.settings_mmb_login_empty, Toast.LENGTH_LONG).show()
            return
        }
        settings.setManmanbuyCookie(cookie.take(4096))
        Toast.makeText(this, R.string.settings_mmb_login_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    companion object {
        private const val LOGIN_URL = "https://m.manmanbuy.com/login.aspx"

        private fun isManmanbuy(rawUrl: String): Boolean = try {
            val host = URI(rawUrl).host?.lowercase() ?: return false
            host == "manmanbuy.com" || host.endsWith(".manmanbuy.com")
        } catch (_: Exception) {
            false
        }
    }
}
