package com.pricelens.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 启动关联应用：商品/爆料/视频链接优先唤起原生 App（京东/淘宝/拼多多/B站），
 * 未安装或唤起失败时回退系统浏览器。
 */
object UrlOpener {

    private data class Rule(val hostContains: List<String>, val packageName: String)

    private val RULES = listOf(
        Rule(listOf("jd.com", "jd.hk", "3.cn"), "com.jingdong.app.mall"),
        Rule(listOf("taobao.com", "tmall.com", "tb.cn"), "com.taobao.taobao"),
        Rule(listOf("pinduoduo.com", "yangkeduo.com", "pdd.com"), "com.xunmeng.pinduoduo"),
        Rule(listOf("bilibili.com", "b23.tv", "biligame.com"), "tv.danmaku.bili"),
        Rule(listOf("smzdm.com"), "com.smzdm.client.android")
    )

    fun open(context: Context, url: String) {
        if (url.isBlank()) return
        val uri = Uri.parse(url)
        val pkg = RULES.firstOrNull { r -> r.hostContains.any { uri.host?.contains(it) == true } }?.packageName

        if (pkg != null) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (_: Exception) {
                // 未安装该 App → 回退
            }
        }
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // 无任何可打开的组件
        }
    }
}
