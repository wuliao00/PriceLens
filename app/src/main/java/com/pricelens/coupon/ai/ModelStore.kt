package com.pricelens.coupon.ai

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 端侧模型在**分发仓库**里的身份（地址、大小、摘要）。
 *
 * 三个值必须与仓库里的发布件对得上，所以它们写死在这里而不是"运行时去问服务器"：
 *  ① 校验要用**编译期就知道的**摘要 —— 一个能改下载地址的中间人也能改它给的摘要；
 *  ② App 不需要为此多一次网络往返（首次进入检测性能时不该先连一次网）。
 * 发新版模型 = 换 tag/换摘要 = 一次 App 更新，这与"权重不进 APK"并不矛盾：
 * 进 APK 的是**几十字节的清单**，几百 MB 的权重始终走下载。
 *
 * [URLS] 是候选列表：GitHub 是主源（单文件上限 2GB、release 带 sha 校验），
 * 国内直连慢时可以先挂镜像（顺序即优先级）。**第一版只填主源**，镜像等有实测再补 ——
 * 没验过的地址写进来只会变成"下不动的时候多等一轮超时"。
 */
object ModelRepository {

    const val FILE_NAME = "Qwen3-0.6B-Q4_K_M.gguf"

    /** 与发布说明里的 sha256 一致（`gh release view model-qwen3-0.6b-q4km-v1`） */
    const val SHA256 = "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a"

    /** 字节数。用于进度与"下完但截断"的快速判断（真正的判据是 sha256） */
    const val BYTES = 396_705_472L

    const val RELEASE_TAG = "model-qwen3-0.6b-q4km-v1"

    val URLS = listOf(
        "https://github.com/wuliao00/PriceLens/releases/download/$RELEASE_TAG/$FILE_NAME"
    )
}

/**
 * 模型文件的落盘与下载（纯 JVM：只用 `java.io` + OkHttp，不 import `android.*`）。
 *
 * 三条纪律，每条都对应一种"看起来成功了"的坏结果：
 *  1. **下到临时名，校验通过才改名**（`.part` → 正式名）：否则一个断在半截的文件
 *     会被下一轮当成"已安装"，然后引擎加载失败被当成"模型不行"；
 *  2. **断点续传带 Range**，服务端不支持 Range（返回 200）时**从头写**而不是追加 ——
 *     追加到已有内容后面会得到一个长度正确、内容错位的文件，而且 sha256 还会告诉你"坏"，却不告诉你为什么；
 *  3. **校验不过就删**：留着只会让下一轮误判"已装"。
 */
class ModelStore(private val baseDir: File) {

    val modelFile: File get() = File(baseDir, ModelRepository.FILE_NAME)

    private val partFile: File get() = File(baseDir, ModelRepository.FILE_NAME + ".part")

    fun isInstalled(): Boolean {
        if (!isPresentQuick()) return false
        return sha256Of(modelFile) == ModelRepository.SHA256
    }

    /**
     * 给 UI 用的**快**判断：看文件在不在、大小对不对（不重算 sha256）。
     * 397MB 的摘要要算一两秒，放在每次重组里算会把设置页拖卡；
     * 而"大小对了但内容坏了"这种事只可能来自外部破坏 —— 真正的裁决在下载完成时（[download] 里算过）
     * 与即将接上的 `LlamaRuntime.load()`（它加载失败会返回 null，不会硬跑）。
     */
    fun isPresentQuick(): Boolean = modelFile.isFile && modelFile.length() == ModelRepository.BYTES

    /**
     * 下载（可续传）。返回 true = 校验通过、已就位。
     *
     * @param onProgress (已下载字节, 总字节)；总字节未知时给 [ModelRepository.BYTES]（我们有权威值）
     */
    fun download(client: OkHttpClient, onProgress: (Long, Long) -> Unit): Boolean {
        baseDir.mkdirs()
        val existing = if (partFile.isFile) partFile.length() else 0L
        val url = ModelRepository.URLS.first()
        val request = Request.Builder()
            .url(url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            // 206 = 服务端接受了 Range；200 = 它忽略了 Range，这时候必须从头写
            val resuming = resp.code == 206 && existing > 0
            if (!resuming && partFile.exists()) partFile.delete()
            val body = resp.body ?: throw IOException("空响应体")
            val total = ModelRepository.BYTES
            var written = if (resuming) existing else 0L
            body.byteStream().use { input ->
                partFile.outputStream().use { output ->
                    if (resuming) output.channel.position(existing)
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
        }

        if (partFile.length() != ModelRepository.BYTES || sha256Of(partFile) != ModelRepository.SHA256) {
            // 宁可不装，也不装一个坏文件：留着半截只会让下一轮误判"已安装"
            partFile.delete()
            return false
        }
        if (modelFile.exists()) modelFile.delete()
        return partFile.renameTo(modelFile)
    }

    /** 中断下载（清 .part），不动已装好的正式文件 */
    fun discardPartial() {
        if (partFile.exists()) partFile.delete()
    }

    fun delete(): Boolean = !modelFile.exists() || modelFile.delete()

    companion object {
        fun sha256Of(file: File): String? = try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: IOException) {
            null
        }
    }
}
