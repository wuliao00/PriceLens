package com.pricelens.domain

import com.pricelens.data.remote.DangdangApi
import com.pricelens.data.remote.JdApi
import com.pricelens.data.remote.ShihuoApi
import com.pricelens.data.remote.SmzdmApi
import com.pricelens.data.repository.PriceRepository
import com.pricelens.ui.common.AsyncValue
import com.pricelens.util.LogT
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 商品候选（占位商品）的统一模型。
 *
 * 旧实现里概览/盯价/找券各自手工构造 `JdApi.JdProduct(skuId = "", ...)` 占位对象，
 * 语义混乱且重复。这里用一个与具体数据源无关的候选结构承载"当前要展示的商品"，
 * 并提供到 [JdApi.JdProduct] 的适配转换以兼容下游渲染。
 */
data class ProductCandidate(
    val title: String,
    val price: Double,
    val originalPrice: Double?,
    val image: String,
    val url: String,
    /** 京东 SKU；非京东来源为 null（对应旧的 skuId="" 占位） */
    val skuId: String? = null
) {
    /** 适配下游：转成旧的 JdProduct 结构，渲染层零改动 */
    fun toJdProduct(): JdApi.JdProduct = JdApi.JdProduct(
        skuId = skuId ?: "",
        title = title,
        price = price,
        originalPrice = originalPrice,
        image = image,
        url = url
    )

    companion object
}

/**
 * 商品候选解析器（阶段2：从上帝 MainViewModel 拆出的领域层单例）。
 *
 * 收编原 `search()` 中的商品候选兜底链与京东 SKU 提取逻辑：
 *  - 纯关键词：当当（SSR 稳定）→ 值得买爆料 → 识货并行兜底
 *  - 京东链接 / 纯数字 SKU：直查京东
 *  - 无障碍实时价：占位展示
 *
 * 候选结果由本单例持有共享 [candidate] StateFlow，概览 / 盯价 / 找券三页共用，
 * 消除原先散落在 ViewModel 中的 6 处手工 `JdProduct(skuId="")` 构造。
 */
@Singleton
class ProductCandidateResolver @Inject constructor(
    private val repository: PriceRepository
) {

    private val _candidate = MutableStateFlow<AsyncValue<ProductCandidate>>(AsyncValue.Idle)

    /** 当前商品候选（概览 / 盯价 / 找券共用） */
    val candidate: StateFlow<AsyncValue<ProductCandidate>> = _candidate

    /** 关键词里若含京东链接 / 纯数字 SKU，提取商品 ID；否则视为纯关键词搜索 */
    fun extractJdSku(keyword: String): String? {
        Regex("item\\.jd\\.com/(\\d+)").find(keyword)?.let { return it.groupValues[1] }
        return keyword.takeIf { it.matches(Regex("\\d{6,}")) }
    }

    /**
     * 主候选兜底链（串行，先于并行任务执行）：当当 → 值得买。
     * 返回值得买爆料列表（社区页与历史价 URL 推导复用）。
     */
    suspend fun resolvePrimary(keyword: String): List<SmzdmApi.SmzdmPost> {
        var filled = false

        val ddItems = repository.searchDangdang(keyword)
        LogT.i("当当结果: ${ddItems.size} 条")
        val ddCandidate = ddItems.firstOrNull { it.price > 0 }
        if (ddCandidate != null) {
            _candidate.value = AsyncValue.Success(ProductCandidate.fromDangdang(ddCandidate))
            filled = true
        }

        val posts = repository.searchSmzdm(keyword)
        LogT.i("值得买结果: ${posts.size} 条")
        if (!filled) {
            val candidate = posts.firstOrNull { it.price != null && it.url.startsWith("http") }
            if (candidate != null) {
                _candidate.value = AsyncValue.Success(ProductCandidate.fromSmzdm(candidate))
                filled = true
            }
        }
        if (!filled) {
            LogT.w("关键词 [$keyword] 当当/值得买无候选，识货并行兜底")
        }
        return posts
    }

    /** 识货兜底：仅当允许（无京东 SKU 且尚无任何候选）且当前无候选时填充 */
    fun maybeFillFromShihuo(items: List<ShihuoApi.ShihuoItem>, allow: Boolean) {
        if (!allow) return
        if (_candidate.value is AsyncValue.Success) return
        val sh = items.firstOrNull { it.price > 0 } ?: return
        _candidate.value = AsyncValue.Success(ProductCandidate.fromShihuo(sh))
    }

    /** 京东 SKU 直查命中：覆盖候选（真实商品优先于占位） */
    fun fillFromJd(product: JdApi.JdProduct) {
        _candidate.value = AsyncValue.Success(ProductCandidate.fromJd(product))
    }

    /**
     * 无障碍实时价占位：网络搜索还没出结果时先用账号实时价展示（§1.1）。
     * 已有候选则不覆盖（保持"占位"语义，与原 `st.product ?: placeholder` 一致）。
     */
    fun fillFromDetection(price: Double, title: String?) {
        if (_candidate.value is AsyncValue.Success) return
        _candidate.value = AsyncValue.Success(
            ProductCandidate(
                title = title ?: "正在查看的商品",
                price = price,
                originalPrice = null,
                image = "",
                url = "",
                skuId = null
            )
        )
    }

    /** 是否已有候选（用于判断是否需要识货兜底 / 无障碍占位） */
    fun hasCandidate(): Boolean = _candidate.value is AsyncValue.Success
}

private fun ProductCandidate.Companion.fromDangdang(d: DangdangApi.DangdangItem) = ProductCandidate(
    title = d.title,
    price = d.price,
    originalPrice = d.originalPrice,
    image = d.image,
    url = d.url,
    skuId = null
)

private fun ProductCandidate.Companion.fromSmzdm(p: SmzdmApi.SmzdmPost) = ProductCandidate(
    title = p.title,
    price = p.price ?: 0.0,
    originalPrice = null,
    image = p.image,
    url = p.url,
    skuId = null
)

private fun ProductCandidate.Companion.fromShihuo(s: ShihuoApi.ShihuoItem) = ProductCandidate(
    title = s.title,
    price = s.price,
    originalPrice = null,
    image = s.image,
    url = s.url,
    skuId = null
)

private fun ProductCandidate.Companion.fromJd(p: JdApi.JdProduct) = ProductCandidate(
    title = p.title,
    price = p.price,
    originalPrice = p.originalPrice,
    image = p.image,
    url = p.url,
    skuId = p.skuId.takeIf { it.isNotEmpty() }
)
