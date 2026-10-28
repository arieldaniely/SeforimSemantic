package io.github.kdroidfilter.seforimlibrary.search

/** RRF combines ranks; its output scores are not semantic similarity or probabilities. */
object SemanticFusion {
    fun rank(
        lexical: List<DenseHit>,
        dense: List<DenseHit>,
        options: SemanticSearchOptions = SemanticSearchOptions(),
        rrfK: Int = 60,
    ): List<DenseHit> {
        require(rrfK > 0)
        val threshold = options.minimumSimilarity
        if (threshold != null) {
            // Only the small lexical list needs a map. An uncapped threshold search
            // must not allocate hash maps for millions of dense hits as well.
            val lexicalScores =
                lexical.withIndex().associate { (rank, hit) ->
                    hit.lineId to options.lexicalWeight / (rrfK.toDouble() + rank + 1)
                }
            val ranking =
                dense
                    .mapIndexedNotNull { rank, hit ->
                        if (hit.score < threshold) return@mapIndexedNotNull null
                        val score = options.denseWeight / (rrfK.toDouble() + rank + 1) + (lexicalScores[hit.lineId] ?: 0.0)
                        hit.copy(score = score.toFloat())
                    }.sortedWith(compareByDescending<DenseHit> { it.score }.thenBy { it.lineId })
            return options.maximumResults?.let(ranking::take) ?: ranking
        }
        val scores = HashMap<Long, Double>()
        val books = HashMap<Long, Long>()
        lexical.forEachIndexed { rank, hit ->
            scores.merge(hit.lineId, options.lexicalWeight / (rrfK.toDouble() + rank + 1), Double::plus)
            books[hit.lineId] = hit.bookId
        }
        dense.forEachIndexed { rank, hit ->
            scores.merge(hit.lineId, options.denseWeight / (rrfK.toDouble() + rank + 1), Double::plus)
            books.putIfAbsent(hit.lineId, hit.bookId)
        }
        val ranking =
            scores.entries
                .sortedByDescending { it.value }
        return ranking.map { DenseHit(it.key, books.getValue(it.key), it.value.toFloat()) }
    }
}
