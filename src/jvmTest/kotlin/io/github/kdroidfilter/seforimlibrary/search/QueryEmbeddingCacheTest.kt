package io.github.kdroidfilter.seforimlibrary.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QueryEmbeddingCacheTest {
    @Test
    fun normalizesSeparatesRolesAndProtectsCachedArrays() {
        val cache = QueryEmbeddingCache(2)
        var calls = 0
        val encode = { _: String, _: SeforimEmbedder.Role -> floatArrayOf((++calls).toFloat()) }
        cache.embed("שָׁלוֹם", encodeClean = encode)[0] = 99f
        assertEquals(1f, cache.embed("שלום", encodeClean = encode)[0])
        assertEquals(2f, cache.embed("שלום", SeforimEmbedder.Role.PASSAGE, encode)[0])
        cache.embed("עולם", encodeClean = encode)
        assertEquals(4f, cache.embed("שלום", encodeClean = encode)[0])
        cache.clear()
        assertEquals(5f, cache.embed("שלום", encodeClean = encode)[0])
    }

    @Test
    fun failureIsRetried() {
        val cache = QueryEmbeddingCache()
        assertFailsWith<IllegalStateException> {
            cache.embed("שלום") { _, _ -> error("unavailable") }
        }
        assertEquals(1f, cache.embed("שלום") { _, _ -> floatArrayOf(1f) }[0])
    }
}
