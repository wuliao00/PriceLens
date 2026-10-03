package com.pricelens.coupon.ai

/**
 * `app/src/main/assets/ai/` 下两份文本资产的读取契约。
 *
 * 刻意只做**字符串进、字符串出**：资产字节由接线方从 `Context.assets` 读出后交进来。
 * 本版没有任何调用者（模型还没接），所以这里不出现 `android.content.res.AssetManager` ——
 * 一个 import 了 android.* 的"读取器"在本机既编译不进单测、也没设备上可验，等于假交付。
 *
 * 两条规则是资产文件的硬约束，被 `GbnfGrammarTest` 逐条钉住：
 *  1. 以 `#` 起始的行是元数据（版本、解码参数、给维护者看的话），**不进 prompt**；
 *  2. `{text}` 是唯一切入点，全文必须恰好出现一次——出现两次意味着有一处示例被
 *     用户文案覆盖（few-shot 失效），出现零次意味着模板被改坏而调用方毫无感知。
 */
object AiPromptAsset {

    /** 元数据行前缀：与 GBNF 的行注释同一字符，两份资产的头部写法保持一致 */
    const val DirectivePrefix = "#"

    /** 待抽取文案的占位符 */
    const val TextPlaceholder = "{text}"

    /** 解码参数（写进资产头部供人核对，不是本对象能生效的地方——生效在接线那版） */
    const val ExpectedTemperature = "temperature=0"
    const val ExpectedMaxNewTokens = "max_new_tokens=256"

    /** 这一行是不是元数据行（前导空白后紧跟 #） */
    fun isDirective(line: String): Boolean = line.trimStart().startsWith(DirectivePrefix)

    /** 去掉元数据行、并裁掉首尾空白后的 prompt 正文 */
    fun body(raw: String): String = raw.lines().filterNot { isDirective(it) }.joinToString("\n").trim()

    /** 正文里 [TextPlaceholder] 出现的次数（固定串计数，不用正则以免模板里别的正则字符惹事） */
    fun placeholderCount(raw: String): Int = body(raw).split(TextPlaceholder).size - 1

    /**
     * 绑定文案：先剥元数据，再把**唯一一个** `{text}` 换成待抽取文本。
     *
     * 占位符数量不对时抛 [IllegalStateException] 而不是静默替换第一处：
     * prompt 资产是给人手改的，改坏了必须在第一次调用就炸出来，
     * 而不是把一条没有文案的 prompt 发给模型、然后对着空结果调试三天。
     */
    fun render(raw: String, text: String): String {
        val count = placeholderCount(raw)
        check(count == 1) { "prompt 资产里 $TextPlaceholder 应恰好出现一次，实际 $count 次" }
        return body(raw).replace(TextPlaceholder, text)
    }
}
