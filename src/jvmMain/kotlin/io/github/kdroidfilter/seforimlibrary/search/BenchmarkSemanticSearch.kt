package io.github.kdroidfilter.seforimlibrary.search

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

fun main(args: Array<String>) = benchmarkSemanticSearch(args)

/** Reproducible comparison on caller-supplied assets; never exports model or corpus text. */
fun benchmarkSemanticSearch(args: Array<String>) {
    require(args.size in 3..5) { "Usage: <model-dir> <index-dir> <report.csv> [query-count=3] [repetitions=3]" }
    val queries =
        listOf("השבת אבדה", "כיבוד אב ואם", "שמירת שבת", "מצוות התלויות בארץ", "ברכת המזון")
            .take(args.getOrNull(3)?.toInt() ?: 3)
    val repetitions = args.getOrNull(4)?.toInt() ?: 3
    require(queries.isNotEmpty() && repetitions > 0)
    val report = Path.of(args[2])
    report.toAbsolutePath().parent?.let(Files::createDirectories)
    Files.newBufferedWriter(report).use { output ->
        output.appendLine("query,scope,method,repetition,milliseconds,count,recallAt150AgainstExact,minScore,maxScore")
        val started = System.nanoTime()
        requireNotNull(SeforimEmbedder.tryLoad(Path.of(args[0]))).use { embedder ->
            println("Model load: ${(System.nanoTime() - started) / 1_000_000} ms")
            VectorSearcher(Path.of(args[1])).use { searcher ->
                val cache = QueryEmbeddingCache()
                for ((queryIndex, query) in queries.withIndex()) {
                    // Warm native inference before comparing cached and uncached runs.
                    val vector = embedder.embed(query)
                    cache.embed(query, encodeClean = embedder::embedClean)
                    repeat(repetitions) { repetition ->
                        for (cached in if (repetition % 2 == 0) listOf(false, true) else listOf(true, false)) {
                            val begin = System.nanoTime()
                            if (cached) cache.embed(query, encodeClean = embedder::embedClean) else embedder.embed(query)
                            output.appendLine(
                                "$queryIndex,none,${if (cached) "embedding_cached" else "embedding"},$repetition," +
                                    "${milliseconds(begin)},0,,,",
                            )
                        }
                    }
                    for (baseOnly in listOf(false, true)) {
                        val scope = if (baseOnly) "base" else "all"
                        val exactOptions = SemanticSearchOptions(exactTopK = true)
                        val truth = searcher.search(vector, exactOptions, baseOnly).map { it.lineId }.toSet()
                        val methods =
                            listOf(
                                "ann150" to SemanticSearchOptions(),
                                "exact150" to exactOptions,
                                "threshold080" to SemanticSearchOptions(minimumSimilarity = 0.8f),
                                "threshold085" to SemanticSearchOptions(minimumSimilarity = 0.85f),
                                "threshold090" to SemanticSearchOptions(minimumSimilarity = 0.9f),
                            )
                        repeat(repetitions) { repetition ->
                            val ordered = if (repetition % 2 == 0) methods else methods.reversed()
                            for ((name, options) in ordered) {
                                val begin = System.nanoTime()
                                val hits = searcher.search(vector, options, baseOnly)
                                val elapsed = milliseconds(begin)
                                val recall =
                                    if (options.minimumSimilarity == null && truth.isNotEmpty()) {
                                        hits.count { it.lineId in truth }.toDouble() / truth.size
                                    } else {
                                        null
                                    }
                                output.appendLine(
                                    "$queryIndex,$scope,$name,$repetition,$elapsed,${hits.size}," +
                                        "${recall ?: ""},${hits.minOfOrNull { it.score } ?: ""},${hits.maxOfOrNull { it.score } ?: ""}",
                                )
                                output.flush()
                                println("query=$queryIndex scope=$scope method=$name run=$repetition ms=$elapsed hits=${hits.size}")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun milliseconds(begin: Long): String = String.format(Locale.ROOT, "%.4f", (System.nanoTime() - begin) / 1_000_000.0)
