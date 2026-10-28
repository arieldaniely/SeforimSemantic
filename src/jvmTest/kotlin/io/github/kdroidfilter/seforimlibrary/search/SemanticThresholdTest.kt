package io.github.kdroidfilter.seforimlibrary.search

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.KnnByteVectorField
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.NoMergePolicy
import org.apache.lucene.index.Term
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.store.FSDirectory
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SemanticThresholdTest {
    private val query = floatArrayOf(1f, 0f, 0f)

    @Test
    fun thresholdCanReturnMoreThan150OrFewerAndNeverLeaksDeletedOrFilteredRows() {
        for (bytes in listOf(true, false)) {
            withIndex(bytes) { searcher ->
                val options = SemanticSearchOptions(minimumSimilarity = 0.9f)
                val all = searcher.search(query, options)
                assertEquals(398, all.size)
                assertTrue(all.all { it.score >= 0.9f && it.lineId % 210 < 200 })
                assertEquals(10, searcher.search(query, options.copy(maximumResults = 10)).size)
                assertEquals(199, searcher.search(query, options, bookIds = listOf(10L)).size)
                assertEquals(199, searcher.search(query, options, baseBookOnly = true).size)
                assertEquals(0, searcher.search(query, options, baseBookOnly = true, bookIds = listOf(10L)).size)
                assertEquals(0, searcher.search(query, options, bookIds = listOf(999L)).size)
                assertEquals(0, searcher.search(floatArrayOf(-1f, 0f, 0f), options).size)
                val top = searcher.search(query, options.copy(minimumSimilarity = null, exactTopK = true, candidates = 7))
                assertEquals(all.take(7), top)
                assertEquals(150, searcher.search(query, 150).size)
            }
        }
    }

    @Test
    fun zeroThresholdIncludesAllLiveVectorsAndCancellationPropagates() =
        withIndex(true) { searcher ->
            assertEquals(418, searcher.search(query, SemanticSearchOptions(minimumSimilarity = 0f)).size)
            assertFailsWith<CancellationException> {
                searcher.search(query, SemanticSearchOptions(minimumSimilarity = 0f)) { throw CancellationException() }
            }
            assertFailsWith<IllegalArgumentException> { searcher.search(floatArrayOf(1f), 10) }
            assertFailsWith<IllegalArgumentException> { SemanticSearchOptions(minimumSimilarity = Float.NaN) }
            assertFailsWith<IllegalArgumentException> { SemanticSearchOptions(maximumResults = 0) }
        }

    private fun withIndex(
        bytes: Boolean,
        block: (VectorSearcher) -> Unit,
    ) {
        val root = Files.createTempDirectory("semantic-threshold")
        try {
            repeat(2) { shard ->
                val path = Files.createDirectories(root.resolve("shard-%02d".format(shard)))
                FSDirectory.open(path).use { dir ->
                    IndexWriter(
                        dir,
                        IndexWriterConfig(StandardAnalyzer()).apply {
                            mergePolicy = NoMergePolicy.INSTANCE
                        },
                    ).use { writer ->
                        repeat(210) { row ->
                            val vector = if (row < 200) query else floatArrayOf(0f, 1f, 0f)
                            writer.addDocument(
                                Document().apply {
                                    add(StoredField("line_id", (shard * 210 + row).toLong()))
                                    add(StoredField("book_id", 10L + shard))
                                    add(IntPoint("book_id", 10 + shard))
                                    add(IntPoint("is_base_book", shard))
                                    add(StringField("row", row.toString(), Field.Store.NO))
                                    add(
                                        if (bytes) {
                                            KnnByteVectorField("vec", Int8Vectors.quantize(vector), VectorSimilarityFunction.COSINE)
                                        } else {
                                            KnnFloatVectorField("vec", vector, VectorSimilarityFunction.COSINE)
                                        },
                                    )
                                },
                            )
                            if (row == 100) writer.commit()
                        }
                        writer.deleteDocuments(Term("row", "0"))
                    }
                }
                val manifest =
                    Properties().apply {
                        setProperty("format", "zayit-round2-1")
                        setProperty("dimension", "3")
                        setProperty("shardIndex", shard.toString())
                        setProperty("shardCount", "2")
                        setProperty("eligible", "210")
                        setProperty("indexed", "210")
                        if (bytes) setProperty("vectorEncoding", Int8Vectors.ENCODING)
                    }
                Files.newOutputStream(path.resolve("semantic.properties")).use { manifest.store(it, null) }
            }
            VectorSearcher(root, expectedDim = 3).use(block)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
