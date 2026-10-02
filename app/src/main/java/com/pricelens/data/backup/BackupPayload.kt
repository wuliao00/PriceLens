package com.pricelens.data.backup

import com.pricelens.data.local.entity.PriceTargetEntity
import com.pricelens.data.local.entity.ProductEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * §五 备份格式常量（WebDAV 与 SAF 兜底共用同一份 payload 格式）。
 *
 * payload 结构：`{app, schema, exportedAt, favorites[], targets[]}`。
 * **红线**：payload 里没有、也永远不会有 Cookie / apikey / WebDAV 凭证的位置 ——
 * 凭证全在 [com.pricelens.util.SecretStore]，本文件的编码函数连读它的入口都没有。
 */
object BackupFormat {
    const val APP = "PriceLens"
    const val SCHEMA = 1

    /** WebDAV 备份目录（服务器相对路径） */
    const val DIR = "pricelens"

    /** 备份文件名前缀 / 后缀：`pricelens-<yyyyMMdd-HHmmss>.json` */
    const val PREFIX = "pricelens-"
    const val SUFFIX = ".json"

    /** 远端保留份数（按 getlastmodified 降序，超出的删旧） */
    const val KEEP_LATEST = 10

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)

    /** WebDAV 备份文件名（时间戳精确到秒；同秒内只可能有一次备份） */
    fun fileName(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = PREFIX +
        STAMP.format(Instant.ofEpochMilli(atMs).atZone(zone)) + SUFFIX

    /** SAF 导出默认文件名：`pricelens-backup-<yyyyMMdd>.json` */
    fun safExportFileName(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = "pricelens-backup-" +
        DAY.format(Instant.ofEpochMilli(atMs).atZone(zone)) + SUFFIX

    /** 是否本应用产出的备份文件（清理保留策略只许动这种文件，别的文件一概不碰） */
    fun isBackupFileName(name: String): Boolean = name.startsWith(PREFIX) && name.endsWith(SUFFIX)
}

/** 备份格式错误（schema 不认识 / 不是本应用的备份 / JSON 坏）——message 就是给用户看的原因 */
class BackupFormatException(message: String) : Exception(message)

/**
 * 收藏条目 DTO（备份格式的一部分，与 Room 实体 [ProductEntity] **刻意分离**：
 * 数据库改字段不会破坏备份兼容；编码是显式字段白名单，不是反射整对象）。
 *
 * [updatedAt] = max(cachedAt, lastAccessedAt)：恢复合并「新者胜」的比较口径，随文件落盘，
 * 让合并规则自描述、不依赖各端对实体字段的解读。
 */
data class FavoriteDto(
    val id: String,
    val title: String,
    val currentPrice: Double,
    val originalPrice: Double?,
    val platform: String,
    val imageUrl: String,
    val cachedAt: Long,
    val lastAccessedAt: Long,
    val ttl: Long,
    val updatedAt: Long
) {
    /** 落库形态：收藏条目一律 pinned=true（favorites[] 的定义就是"被收藏的"） */
    fun toEntity(): ProductEntity = ProductEntity(
        id = id,
        title = title,
        currentPrice = currentPrice,
        originalPrice = originalPrice,
        platform = platform,
        imageUrl = imageUrl,
        cachedAt = cachedAt,
        lastAccessedAt = lastAccessedAt,
        ttl = ttl,
        pinned = true
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("currentPrice", currentPrice)
        put("originalPrice", originalPrice ?: JSONObject.NULL)
        put("platform", platform)
        put("imageUrl", imageUrl)
        put("cachedAt", cachedAt)
        put("lastAccessedAt", lastAccessedAt)
        put("ttl", ttl)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun from(entity: ProductEntity): FavoriteDto = FavoriteDto(
            id = entity.id,
            title = entity.title,
            currentPrice = entity.currentPrice,
            originalPrice = entity.originalPrice,
            platform = entity.platform,
            imageUrl = entity.imageUrl,
            cachedAt = entity.cachedAt,
            lastAccessedAt = entity.lastAccessedAt,
            ttl = entity.ttl,
            updatedAt = maxOf(entity.cachedAt, entity.lastAccessedAt)
        )

        fun fromJson(json: JSONObject): FavoriteDto = FavoriteDto(
            id = json.optString("id"),
            title = json.optString("title"),
            currentPrice = json.optDouble("currentPrice", 0.0),
            originalPrice = if (json.isNull("originalPrice")) null else json.optDouble("originalPrice"),
            platform = json.optString("platform"),
            imageUrl = json.optString("imageUrl"),
            cachedAt = json.optLong("cachedAt"),
            lastAccessedAt = json.optLong("lastAccessedAt"),
            ttl = json.optLong("ttl"),
            updatedAt = json.optLong("updatedAt")
        )
    }
}

/**
 * 盯价目标 DTO（与 Room 实体 [PriceTargetEntity] 分离，理由同 [FavoriteDto]）。
 *
 * [updatedAt] 取 createdAt：实体只有这一个时间戳（改价没有时间列，如实照搬，不发明"修改时间"）。
 */
data class TargetDto(
    val productId: String,
    val title: String,
    val platform: String,
    val targetPrice: Double,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toEntity(): PriceTargetEntity = PriceTargetEntity(
        productId = productId,
        title = title,
        platform = platform,
        targetPrice = targetPrice,
        active = active,
        createdAt = createdAt
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("productId", productId)
        put("title", title)
        put("platform", platform)
        put("targetPrice", targetPrice)
        put("active", active)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun from(entity: PriceTargetEntity): TargetDto = TargetDto(
            productId = entity.productId,
            title = entity.title,
            platform = entity.platform,
            targetPrice = entity.targetPrice,
            active = entity.active,
            createdAt = entity.createdAt,
            updatedAt = entity.createdAt
        )

        fun fromJson(json: JSONObject): TargetDto = TargetDto(
            productId = json.optString("productId"),
            title = json.optString("title"),
            platform = json.optString("platform"),
            targetPrice = json.optDouble("targetPrice", 0.0),
            active = json.optBoolean("active", true),
            createdAt = json.optLong("createdAt"),
            updatedAt = json.optLong("updatedAt")
        )
    }
}

/** 备份 payload（WebDAV 与 SAF 导出/导入同一份格式） */
data class BackupPayload(
    val app: String,
    val schema: Int,
    val exportedAt: Long,
    val favorites: List<FavoriteDto>,
    val targets: List<TargetDto>
)

/**
 * payload 编解码（org.json 手写，仓库红线：不引任何序列化库）。
 *
 * 为什么不直接序列化 Room 实体：备份格式要能扛住数据库演进 —— 这里每件条目都是
 * **显式字段白名单**，实体将来加列（哪怕误加了一个凭证列）也不会自动流进备份 JSON。
 */
object BackupCodec {

    /** 由实体快照构造 payload（唯一的编码入口；参数里没有、也不会加凭证） */
    fun capture(favorites: List<ProductEntity>, targets: List<PriceTargetEntity>, exportedAt: Long): BackupPayload = BackupPayload(
        app = BackupFormat.APP,
        schema = BackupFormat.SCHEMA,
        exportedAt = exportedAt,
        favorites = favorites.map { FavoriteDto.from(it) },
        targets = targets.map { TargetDto.from(it) }
    )

    fun encode(payload: BackupPayload): String {
        val favorites = JSONArray()
        payload.favorites.forEach { favorites.put(it.toJson()) }
        val targets = JSONArray()
        payload.targets.forEach { targets.put(it.toJson()) }
        return JSONObject().apply {
            put("app", payload.app)
            put("schema", payload.schema)
            put("exportedAt", payload.exportedAt)
            put("favorites", favorites)
            put("targets", targets)
        }.toString()
    }

    /**
     * 解码 + 校验。schema 不认识的备份**拒绝并给原因**（[BackupFormatException] 的 message
     * 就是给用户看的原因，UI 原样展示，不吞成"操作失败"）。
     */
    fun decode(text: String): BackupPayload {
        val root = try {
            JSONObject(text)
        } catch (e: org.json.JSONException) {
            throw BackupFormatException("不是合法的 JSON，文件可能损坏（${e.message ?: e.javaClass.simpleName}）")
        }
        val app = root.optString("app")
        if (app != BackupFormat.APP) throw BackupFormatException("不是 PriceLens 的备份（app=\"$app\"）")
        val schema = root.optInt("schema", -1)
        if (schema > BackupFormat.SCHEMA) {
            throw BackupFormatException("备份 schema=$schema 比本版本支持的 ${BackupFormat.SCHEMA} 更新，请先升级 App")
        }
        if (schema != BackupFormat.SCHEMA) {
            throw BackupFormatException("备份 schema=$schema 不认识（本版本支持 ${BackupFormat.SCHEMA}）")
        }
        // 主键为空的条目无法落库：静默丢弃比写一条坏数据诚实（备份文件本身仍可用）
        val favorites = mapObjects(root.optJSONArray("favorites")) { FavoriteDto.fromJson(it) }.filter { it.id.isNotBlank() }
        val targets = mapObjects(root.optJSONArray("targets")) { TargetDto.fromJson(it) }.filter { it.productId.isNotBlank() }
        return BackupPayload(
            app = app,
            schema = schema,
            exportedAt = root.optLong("exportedAt"),
            favorites = favorites,
            targets = targets
        )
    }

    /** JSONArray → 对象列表（缺字段/非对象的元素跳过，不做整文件判死） */
    private fun <T> mapObjects(array: JSONArray?, from: (JSONObject) -> T): List<T> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(from) }
    }
}
