package com.pricelens.data.backup

/**
 * 明文 HTTP 的放行范围：只允许本机与局域网（RFC1918 / 链路本地 / IPv6 ULA / `.local`）。
 *
 * 为什么需要它：桌面端「手机联动」的 WebDAV 端点只有 HTTP + Basic Auth（没有 TLS），
 * 而 App 的 Manifest 原先按"全部数据源都是 HTTPS"的假设禁用了明文流量，导致真机上
 * 连 `http://192.168.x.x:47821/` 会在 OkHttp 层直接抛 `UnknownServiceException`。
 * 打开 system 级明文开关后，用这一层把"能连"限制在可信网段，公网地址仍只许 HTTPS。
 *
 * 刻意不做的：不做 DNS 解析后的二次校验（OkHttp 的连接目标就是 host 字面量，解析由系统进行；
 * 用户填的是 IP 或 `.local` 名，不存在"域名解析到私网"的绕过面）。
 */
internal object PrivateHosts {

    fun isPrivate(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local")) return true

        if (h.contains(':')) {
            // IPv6 回环 / 链路本地 / ULA（fc00::/7）
            if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
            if (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return true
            return false
        }

        val parts = h.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        return a == 10 ||                         // 10/8
            a == 127 ||                           // 回环
            (a == 192 && b == 168) ||             // 192.168/16
            (a == 172 && b in 16..31) ||          // 172.16/12
            (a == 169 && b == 254)                // 链路本地
    }
}
