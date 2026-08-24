package com.pricelens.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import com.pricelens.R

/**
 * 统一图片加载：按 CDN 域名补 Referer/UA（防 403 防盗链），
 * RGB_565 + 300px 降采样（§4.4），占位/失败图兜底。
 */
@Composable
fun AppImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val request = ImageRequest.Builder(context)
        .data(url?.takeIf { it.startsWith("http") })
        .crossfade(true)
        .size(300)
        .bitmapConfig(Bitmap.Config.RGB_565)
        .apply {
            refererFor(url)?.let { addHeader("Referer", it) }
            addHeader("User-Agent", UA_MOBILE)
        }
        .build()

    coil.compose.AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = contentScale,
        placeholder = painterResource(R.drawable.img_placeholder),
        error = painterResource(R.drawable.img_placeholder),
        modifier = modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(corner))
    )
}

private const val UA_MOBILE =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Mobile Safari/537.36"

/** 常见图床防盗链：不带 Referer 直接 403 */
private fun refererFor(url: String?): String? = when {
    url == null -> null
    url.contains("hdslb.com") -> "https://www.bilibili.com"          // B 站封面
    url.contains("360buyimg.com") -> "https://www.jd.com"            // 京东商品图
    url.contains("zdmimg.com") || url.contains("smzdm.com") -> "https://www.smzdm.com"
    url.contains("taobaocdn") || url.contains("alicdn") -> "https://www.taobao.com"
    url.contains("yangkeduo") || url.contains("pddpic") -> "https://mobile.yangkeduo.com"
    else -> "https://www.google.com"
}
