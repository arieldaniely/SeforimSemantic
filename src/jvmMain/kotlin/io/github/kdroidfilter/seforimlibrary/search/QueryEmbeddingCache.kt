package io.github.kdroidfilter.seforimlibrary.search

/** Bounded, per-model LRU cache. Copies keep callers from corrupting subsequent queries. */
class QueryEmbeddingCache(
    private val capacity: Int = 128,
) {
    private data class Key(
        val text: String,
        val role: SeforimEmbedder.Role,
    )

    private val entries = LinkedHashMap<Key, FloatArray>(16, 0.75f, true)

    init {
        require(capacity > 0)
    }

    /** Normalization and role match the encoder contract; failed computations are never cached. */
    @Synchronized
    fun embed(
        text: String,
        role: SeforimEmbedder.Role = SeforimEmbedder.Role.QUERY,
        encodeClean: (String, SeforimEmbedder.Role) -> FloatArray,
    ): FloatArray {
        val key = Key(Round2Normalizer.clean(text), role)
        entries[key]?.let { return it.copyOf() }
        val vector = encodeClean(key.text, role)
        entries[key] = vector.copyOf()
        if (entries.size > capacity) entries.remove(entries.keys.first())
        return vector
    }

    /** Release cached vectors with the owning model. */
    @Synchronized
    fun clear() = entries.clear()
}
