package com.pricelens.domain

/**
 * §2.4 入场动效的纯判据：阶梯延迟封顶 + 交叉淡入两层不透明度。
 * 无 Compose 依赖（时长/阶梯令牌本身在 ui/theme/Motion.kt，这里只按参数吃，避免两处真值）。
 */
object MotionEnter {

    /**
     * 列表项 stagger 延迟：`min(index, cap - 1) * stepMillis`。
     * 封顶的理由很实在——8 项之后不再累加，长列表末尾不必等半秒才出现。
     * 负索引按第 0 项处理；cap ≤ 1 表示不阶梯（全部同时入场）。
     */
    fun staggerDelay(index: Int, stepMillis: Int, cap: Int): Int {
        if (cap <= 1) return 0
        val level = index.coerceAtLeast(0).coerceAtMost(cap - 1)
        return level * stepMillis.coerceAtLeast(0)
    }

    /** 内容层不透明度（钳到 0f..1f） */
    fun contentAlpha(progress: Float): Float = progress.coerceIn(0f, 1f)

    /** 骨架层不透明度：与内容层互补，两层之和恒为 1f */
    fun skeletonAlpha(progress: Float): Float = 1f - contentAlpha(progress)

    /** 入场位移系数：1f（还没进来）→ 0f（落位），乘 EnterOffsetY 后只喂 graphicsLayer.translationY */
    fun enterTravel(progress: Float): Float = 1f - contentAlpha(progress)
}

/**
 * "一次入场"守卫：同一个 key 只放行一次，之后永远拒绝。
 *
 * 为什么需要它（本批次最容易做错的点）：骨架→内容交叉淡入、列表项 stagger 都必须只在
 * **首次数据到达**播一次。而 `key = 数据本身` 会让 remember/AnimatedContent 在每次数据变化、
 * 每次滚出滚回时重新走一遍入场 → 动画变成闪烁。所以入场键必须是**与数据无关的稳定标识**
 * （如 "price:block:curve"），列表项则用它的稳定 LazyColumn key（如 "identity:$productId"），
 * 由这里做一次性放行。
 *
 * 生命周期说明：守卫实例挂在屏幕级 composable 的 remember 上——tab 被销毁重建后
 * 视为"新的一次进入页面"，会再放行一次，这符合"首次数据到达淡一次"的口径。
 */
class EnterReplayGuard {

    private val revealed = mutableSetOf<String>()

    /** 首次请求该 key 时返回 true 并登记；之后恒 false（Set.add 的返回值正好就是这条判据） */
    fun acquire(key: String): Boolean = revealed.add(key)

    /** 该 key 是否已经放行过（只读，不消费） */
    fun hasRevealed(key: String): Boolean = key in revealed
}
