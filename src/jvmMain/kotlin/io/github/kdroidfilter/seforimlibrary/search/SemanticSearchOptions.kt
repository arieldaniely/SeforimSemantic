package io.github.kdroidfilter.seforimlibrary.search

/**
 * Optional experiments. Defaults preserve the extraction baseline.
 * [minimumSimilarity] is Lucene's normalized cosine `(1 + cosine) / 2`, not a
 * calibrated probability. Null retains top-K; a value selects every matching
 * vector above that threshold, unless [maximumResults] is explicitly supplied.
 */
data class SemanticSearchOptions(
    val candidates: Int = 150,
    val minimumSimilarity: Float? = null,
    val maximumResults: Int? = null,
    val exactTopK: Boolean = false,
    val cacheQueries: Boolean = false,
    val lexicalWeight: Double = 1.0,
    val denseWeight: Double = 1.0,
) {
    init {
        require(candidates > 0)
        require(minimumSimilarity == null || minimumSimilarity.isFinite() && minimumSimilarity in 0f..1f)
        require(maximumResults == null || maximumResults > 0)
        require(lexicalWeight.isFinite() && lexicalWeight > 0.0)
        require(denseWeight.isFinite() && denseWeight > 0.0)
    }
}
