package com.pricelens.coupon.ai

import java.io.File
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 候选源回退的行为基线：镜像挂了/给了坏文件，必须继续试下一个源；
 * 但"下完"的唯一判据仍是文件名、字节数与 sha256 三件事同时对上。
 */
class ModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var primary: MockWebServer
    private lateinit var mirror: MockWebServer
    private lateinit var spec: ModelSpec

    private val payload = "price lens model".toByteArray()

    @Before
    fun setUp() {
        primary = MockWebServer().apply { start() }
        mirror = MockWebServer().apply { start() }
        spec = ModelSpec(
            fileName = "m.gguf",
            sha256 = MessageDigest.getInstance("SHA-256").digest(payload)
                .joinToString("") { "%02x".format(it) },
            bytes = payload.size.toLong(),
            urls = listOf(
                primary.url("/m.gguf").toString(),
                mirror.url("/m.gguf").toString()
            )
        )
    }

    @After
    fun tearDown() {
        primary.shutdown()
        mirror.shutdown()
    }

    @Test
    fun `falls back to the next source when the first one returns an error`() {
        primary.enqueue(MockResponse().setResponseCode(503))
        mirror.enqueue(MockResponse().setBody(Buffer().write(payload)))

        val store = ModelStore(tmp.newFolder("models"), spec)
        assertTrue(store.download(OkHttpClient()) { _, _ -> })
        assertArrayEquals(payload, store.modelFile.readBytes())
        assertEquals(1, primary.requestCount)
        assertEquals(1, mirror.requestCount)
    }

    @Test
    fun `falls back to the next source when a mirror fails sha256`() {
        primary.enqueue(MockResponse().setBody(Buffer().write("evil".toByteArray())))
        mirror.enqueue(MockResponse().setBody(Buffer().write(payload)))

        val store = ModelStore(tmp.newFolder("models"), spec)
        assertTrue(store.download(OkHttpClient()) { _, _ -> })
        assertArrayEquals(payload, store.modelFile.readBytes())
        assertEquals(1, primary.requestCount)
        assertEquals(1, mirror.requestCount)
    }

    @Test
    fun `a complete and valid part file is adopted without another request`() {
        val dir = tmp.newFolder("resume")
        File(dir, "m.gguf.part").writeBytes(payload)

        val store = ModelStore(dir, spec)
        assertTrue(store.download(OkHttpClient()) { _, _ -> })
        assertArrayEquals(payload, store.modelFile.readBytes())
        assertEquals(0, primary.requestCount)
    }
}
