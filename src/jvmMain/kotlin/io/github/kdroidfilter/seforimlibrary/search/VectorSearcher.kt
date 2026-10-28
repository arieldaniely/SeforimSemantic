package io.github.kdroidfilter.seforimlibrary.search

import org.apache.lucene.document.IntPoint
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.FieldInfos
import org.apache.lucene.index.VectorEncoding
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.KnnFloatVectorQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.ScoreMode
import org.apache.lucene.search.TwoPhaseIterator
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.store.NIOFSDirectory
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.PriorityQueue
import java.util.Properties

/** A dense semantic hit. */
data class DenseHit(
    val lineId: Long,
    val bookId: Long,
    val score: Float,
)

/**
 * Runs filtered KNN over a unified semantic index, with support for legacy sharded bundles.
 *
 * Returns line ids that are joined back to the DB by the caller.
 */
class VectorSearcher(
    indexDir: Path,
    private val expectedDim: Int = 256,
    dbPath: Path? = null,
    modelDir: Path? = null,
) : Closeable {
    // GraalVM native image can't instantiate MMapDirectory's MemorySegmentIndexInputProvider
    // (Panama foreign downcalls) — use NIOFSDirectory there, like LuceneSearchEngine does.
    private val shardPaths =
        Files.list(indexDir).use { stream ->
            stream
                .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("shard-") }
                .sorted()
                .toList()
        }
    private val dirs =
        shardPaths.map { path ->
            if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
                NIOFSDirectory(path)
            } else {
                FSDirectory.open(path)
            }
        }
    private val readers = mutableListOf<DirectoryReader>()
    private val searchers: List<IndexSearcher>
    private val byteVectors: Boolean

    init {
        try {
            require(dirs.isNotEmpty()) { "No semantic index shards in $indexDir" }
            val manifests =
                shardPaths.map { path ->
                    Properties().apply {
                        Files.newInputStream(path.resolve("semantic.properties")).use { load(it) }
                    }
                }
            val first = manifests.first()
            val encoding = first.getProperty("vectorEncoding", "float32")
            require(encoding == "float32" || encoding == Int8Vectors.ENCODING) { "Unknown vector encoding: $encoding" }
            byteVectors = encoding == Int8Vectors.ENCODING
            val shardCount = first.getProperty("shardCount")?.toIntOrNull()
            require(first.getProperty("format") == "zayit-round2-1" && shardCount == dirs.size)
            require(
                manifests.map { it.getProperty("shardIndex")?.toIntOrNull() }.toSet() ==
                    (0 until dirs.size).toSet(),
            ) { "Missing semantic index shard" }
            require(
                manifests.all {
                    it.getProperty("format") == first.getProperty("format") &&
                        it.getProperty("vectorEncoding", "float32") == encoding &&
                        it.getProperty("databaseSha256") == first.getProperty("databaseSha256") &&
                        it.getProperty("modelSha256") == first.getProperty("modelSha256") &&
                        it.getProperty("tokenizerSha256") == first.getProperty("tokenizerSha256") &&
                        it.getProperty("dimension") == expectedDim.toString() &&
                        it.getProperty("indexed") == it.getProperty("eligible")
                },
            ) { "Inconsistent semantic index shards" }
            if (dbPath != null) {
                require(SemanticFileFingerprint.hash(dbPath) == first.getProperty("databaseSha256")) {
                    "Semantic index belongs to a different database"
                }
            }
            if (modelDir != null) {
                require(
                    SemanticFileFingerprint.hash(modelDir.resolve("seforim-embed-round2-int8.onnx")) == first.getProperty("modelSha256"),
                ) {
                    "Semantic index belongs to a different model"
                }
                require(SemanticFileFingerprint.hash(modelDir.resolve("tokenizer.json")) == first.getProperty("tokenizerSha256")) {
                    "Semantic index belongs to a different tokenizer"
                }
            }
            dirs.forEach { dir ->
                val reader = DirectoryReader.open(dir)
                readers.add(reader)
                val field = FieldInfos.getMergedFieldInfos(reader).fieldInfo("vec")
                val dimension = field?.vectorDimension
                require(dimension == expectedDim) {
                    "Semantic index has dimension $dimension; expected $expectedDim"
                }
                require(field.vectorEncoding == if (byteVectors) VectorEncoding.BYTE else VectorEncoding.FLOAT32) {
                    "Semantic index vector encoding does not match its manifest"
                }
                require(field.vectorSimilarityFunction == VectorSimilarityFunction.COSINE) {
                    "Semantic index must use cosine similarity"
                }
            }
            searchers = readers.map { IndexSearcher(it) }
        } catch (failure: Throwable) {
            readers.forEach { runCatching { it.close() } }
            dirs.forEach { runCatching { it.close() } }
            throw failure
        }
    }

    private fun filterQuery(
        baseBookOnly: Boolean,
        bookIds: Collection<Long>?,
    ): Query? {
        val b = BooleanQuery.Builder()
        var any = false
        if (baseBookOnly) {
            // Field name matches the fused text index (LuceneTextIndexWriter.FIELD_IS_BASE_BOOK).
            b.add(IntPoint.newExactQuery("is_base_book", 1), BooleanClause.Occur.FILTER)
            any = true
        }
        if (!bookIds.isNullOrEmpty()) {
            b.add(IntPoint.newSetQuery("book_id", *bookIds.map { it.toInt() }.toIntArray()), BooleanClause.Occur.FILTER)
            any = true
        }
        return if (any) b.build() else null
    }

    fun search(
        query: FloatArray,
        k: Int,
        baseBookOnly: Boolean = false,
        bookIds: Collection<Long>? = null,
    ): List<DenseHit> {
        require(k > 0)
        validateQuery(query)
        val byteQuery = if (byteVectors) Int8Vectors.quantize(query) else null
        val filter = filterQuery(baseBookOnly, bookIds)
        return searchers
            .flatMap { searcher ->
                val knn: Query =
                    if (byteQuery != null) {
                        CompleteKnnByteVectorQuery("vec", byteQuery, k, filter)
                    } else {
                        KnnFloatVectorQuery("vec", query, k, filter)
                    }
                val top = searcher.search(knn, k)
                // StoredFields is not thread-safe; obtain one for each query.
                val stored = searcher.storedFields()
                top.scoreDocs.map { sd ->
                    val d = stored.document(sd.doc)
                    DenseHit(
                        lineId = d.getField("line_id").numericValue().toLong(),
                        bookId = d.getField("book_id").numericValue().toLong(),
                        score = sd.score,
                    )
                }
            }.sortedByDescending { it.score }
            .take(k)
    }

    /**
     * Complete retrieval over stored vectors, including legacy shards. No ANN candidate
     * ceiling is used in threshold mode. Exact top-K retains only K ids in memory;
     * uncapped threshold mode retains matching ids (text is resolved by the caller).
     * [checkCancelled] runs during traversal and before sorting; interruption propagates.
     */
    fun search(
        query: FloatArray,
        options: SemanticSearchOptions,
        baseBookOnly: Boolean = false,
        bookIds: Collection<Long>? = null,
        checkCancelled: () -> Unit = {},
    ): List<DenseHit> {
        if (options.minimumSimilarity == null && !options.exactTopK) {
            checkCancelled()
            return search(query, options.candidates, baseBookOnly, bookIds)
        }
        validateQuery(query)
        val byteQuery = if (byteVectors) Int8Vectors.quantize(query) else null
        val filter = filterQuery(baseBookOnly, bookIds)
        val limit = if (options.minimumSimilarity == null) options.candidates else options.maximumResults
        val order = compareByDescending<DenseHit> { it.score }.thenBy { it.lineId }.thenBy { it.bookId }
        val best = limit?.let { PriorityQueue(minOf(it, 1024), order.reversed()) }
        val matches = if (best == null) ArrayList<DenseHit>() else null
        var visited = 0
        for (searcher in searchers) {
            val weight = filter?.let { searcher.createWeight(searcher.rewrite(it), ScoreMode.COMPLETE_NO_SCORES, 1f) }
            for (leaf in searcher.indexReader.leaves()) {
                checkCancelled()
                val reader = leaf.reader()
                val scorer =
                    if (byteQuery != null) {
                        reader.getByteVectorValues("vec")?.scorer(byteQuery)
                    } else {
                        reader.getFloatVectorValues("vec")?.scorer(query)
                    }
                if (scorer == null) continue
                val filterScorer = weight?.scorer(leaf)
                if (weight != null && filterScorer == null) continue
                val accepted =
                    filterScorer?.let {
                        it.twoPhaseIterator()?.let(TwoPhaseIterator::asDocIdSetIterator) ?: it.iterator()
                    }
                val vectors = scorer.iterator()
                val live = reader.liveDocs
                val stored = reader.storedFields()
                var doc = vectors.nextDoc()
                while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                    if (++visited % 1024 == 0) checkCancelled()
                    if (accepted != null && accepted.docID() < doc) accepted.advance(doc)
                    if (accepted != null && accepted.docID() != doc) {
                        doc = vectors.advance(accepted.docID())
                        continue
                    }
                    if (live == null || live.get(doc)) {
                        val score = scorer.score()
                        if (score >= (options.minimumSimilarity ?: 0f) &&
                            (best == null || best.size < limit || score >= best.peek().score)
                        ) {
                            val fields = stored.document(doc)
                            val hit =
                                DenseHit(
                                    fields.getField("line_id").numericValue().toLong(),
                                    fields.getField("book_id").numericValue().toLong(),
                                    score,
                                )
                            if (best == null) {
                                matches!!.add(hit)
                            } else {
                                best.add(hit)
                                if (best.size > limit) best.poll()
                            }
                        }
                    }
                    doc = vectors.nextDoc()
                }
            }
        }
        checkCancelled()
        return (matches ?: best!!.toList()).sortedWith(order)
    }

    private fun validateQuery(query: FloatArray) {
        require(query.size == expectedDim && query.all { it.isFinite() } && query.any { it != 0f }) {
            "Invalid semantic query vector"
        }
    }

    override fun close() {
        try {
            readers.forEach { it.close() }
        } finally {
            dirs.forEach { it.close() }
        }
    }
}
