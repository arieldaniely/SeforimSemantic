package io.github.kdroidfilter.seforimlibrary.search

import kotlin.test.Test
import kotlin.test.assertEquals

class SemanticFusionTest {
    private val lexical = listOf(DenseHit(1, 10, 10f), DenseHit(2, 10, 2f))
    private val dense = listOf(DenseHit(3, 10, 0.95f), DenseHit(2, 10, 0.85f))

    @Test
    fun defaultFusionRetainsBothRoutesAndRewardsAgreement() {
        assertEquals(listOf(2L, 1L, 3L), SemanticFusion.rank(lexical, dense).map { it.lineId })
    }

    @Test
    fun thresholdCannotBeBypassedByLexicalMatchesAndCapIsAppliedAfterFusion() {
        assertEquals(
            listOf(3L),
            SemanticFusion.rank(lexical, dense, SemanticSearchOptions(minimumSimilarity = 0.9f)).map { it.lineId },
        )
        assertEquals(
            listOf(2L),
            SemanticFusion
                .rank(lexical, dense, SemanticSearchOptions(minimumSimilarity = 0.8f, maximumResults = 1))
                .map { it.lineId },
        )
    }

    @Test
    fun optionalWeightCanFavorMeaningWithoutChangingInclusion() {
        assertEquals(
            listOf(2L, 3L, 1L),
            SemanticFusion.rank(lexical, dense, SemanticSearchOptions(denseWeight = 1.5)).map { it.lineId },
        )
    }
}
