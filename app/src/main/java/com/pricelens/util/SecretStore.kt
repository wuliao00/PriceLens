package com.pricelens.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 凭证键名（secret_prefs 里的键） */
object SecretKeys {
    const val COOKIE_MMB = "mmb_cookie"
    const val APIKEY_XL = "xingluo_apikey"
    const val COOKIE_FETCHED_AT = "cookie_fetched_at"

    // §五 WebDAV 备份：地址/账号/密码一律走加密存储（绝不入库、绝不进备份 JSON）
    const val WEBDAV_URL = "webdav_url"
    const val WEBDAV_USER = "webdav_user"
    const val WEBDAV_PASSWORD = "webdav_password"
}

/**
 * 凭证的加密存储：AndroidKeyStore 里的 AES-256-GCM 密钥 + 密文落 SharedPreferences。
 *
 * 为什么不用 `EncryptedSharedPreferences`：security-crypto 长期处于维护停滞状态，
 * 而这里要的就三件事（生成不可导出的密钥、加密、解密），手写 40 行零依赖、行为可控。
 *
 * 安全模型：
 *  - 密钥在 KeyStore 里生成，**不可导出、不随备份/换机迁移**；
 *  - 密文按 `IV || CipherText` 存 Base64（GCM 每次加密随机 IV，绝不复用）；
 *  - 解密失败（换机恢复后密钥不在）→ 返回 null，UI 引导重新获取，**不抛异常**；
 *  - `secret_prefs` 被排除在云备份/换机迁移之外（见 res/xml/data_extraction_rules.xml）：
 *    就算密文被带走，没有那把密钥也解不开。
 *
 * 2026-10-02 起 [migrateFromPlainPrefs] 会把历史明文（`pricelens` prefs 里的
 * `mmb_cookie` / `linkstars_apikey`）搬进这里并**删除明文**。
 */
object SecretStore {

    private const val PREFS = "secret_prefs"
    private const val ALIAS = "pricelens_secret_key"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** 历史明文所在的老位置（迁移读取用；迁移成功后这些键会被删除） */
    private const val PLAIN_PREFS = "pricelens"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    /** 写入（空白 = 删除）。异常吞掉并记一行日志：凭证写失败不该让界面崩。 */
    fun putString(context: Context, key: String, value: String?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value.isNullOrBlank()) {
            prefs.edit().remove(key).apply()
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(key, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
        }.onFailure {
            LogT.e("SecretStore 写入失败(${it.javaClass.simpleName})：凭证未保存")
        }
    }

    /** 读取；密钥不在/密文损坏 → null（调用方按"没有凭证"处理，不猜） */
    fun getString(context: Context, key: String): String? {
        val b64 = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return null
        return runCatching {
            val payload = Base64.decode(b64, Base64.NO_WRAP)
            require(payload.size > IV_LEN) { "payload too short" }
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, payload, 0, IV_LEN))
            }
            String(cipher.doFinal(payload, IV_LEN, payload.size - IV_LEN), Charsets.UTF_8)
        }.onFailure {
            // 常见于"换机恢复后密钥不在"：这不叫错误，叫"凭证需要重新获取"
            LogT.w("SecretStore 读取失败(${it.javaClass.simpleName})：按没有凭证处理")
        }.getOrNull()
    }

    /**
     * 一次性明文迁移：把老 prefs 里的 Cookie / apikey 加密搬走并删掉明文。
     *
     * 幂等：老键删掉后再调就是空操作；每次 App 启动调一次足够便宜。
     * **只有回读校验通过才删明文** —— 迁移把凭证弄丢比明文多留一天更糟。
     *
     * @return 迁移了几条（供日志/诊断）
     */
    fun migrateFromPlainPrefs(context: Context): Int {
        val plain = context.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)
        val pairs = listOf(SecretKeys.COOKIE_MMB to "mmb_cookie", SecretKeys.APIKEY_XL to "linkstars_apikey")
        var moved = 0
        for ((target, legacyKey) in pairs) {
            val value = plain.getString(legacyKey, null)
            if (value.isNullOrBlank()) continue
            putString(context, target, value)
            if (getString(context, target) == value) {
                plain.edit().remove(legacyKey).apply()
                moved++
            }
        }
        if (moved > 0) LogT.i("SecretStore 迁移完成：$moved 条明文凭证已加密（旧键已删）")
        return moved
    }
}

/**
 * 掩码：默认只露前 4 后 4（长度也说出来，便于用户核对"是不是我这串"）。
 * 纯函数，JVM 可测；太短的串一律全遮，不做"露一半等于没露"的假动作。
 */
object SecretMask {
    fun mask(secret: String, keep: Int = 4): String = when {
        secret.isBlank() -> ""
        secret.length <= keep * 2 -> "•".repeat(secret.length.coerceAtMost(12))
        else -> "${secret.take(keep)} …… ${secret.takeLast(keep)}（共 ${secret.length} 字符）"
    }
}
