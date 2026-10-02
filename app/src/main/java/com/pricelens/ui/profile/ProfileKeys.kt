package com.pricelens.ui.profile

/**
 * 「我的」页两类行在**同一个 LazyColumn** 里：收藏（`products.id`）与盯价目标
 * （`price_targets.productId`）。两者用的是**同一个 key 空间**（都是 `jd:100012043978` 这种形态），
 * 所以"收藏过又盯价"的同一个商品会产出两个相同 key，Compose 直接抛
 * `IllegalArgumentException: Key "jd:…" was already used`。
 *
 * 这不是构造出来的场景：PLB110 真机走查第一轮就撞上了（`files/crashlogs/crash-1790963263664.log`，
 * 2026-10-03 01:47，app=2.8.0-dev(21) / android=15 / OPPO PLB110），栈顶在
 * `LayoutNodeSubcompositionsState.subcompose`。单测抓不到它（本仓库没有 Robolectric），
 * 所以把"key 必须带段前缀"抽成纯函数并钉住，至少下次改这一页时会被测试拦住。
 */
object ProfileKeys {
    /** 收藏行的 key */
    fun pin(productId: String): String = "pin:$productId"

    /** 盯价目标行的 key */
    fun target(productId: String): String = "target:$productId"

    /**
     * 给整页的行 key 去重校验用：同一商品同时被收藏和盯价时，两个 key 不许相同。
     * 返回重复的 key（正常应为空表）。
     */
    fun duplicates(keys: List<String>): List<String> = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
}
