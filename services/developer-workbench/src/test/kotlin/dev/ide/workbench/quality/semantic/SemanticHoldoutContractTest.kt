package dev.ide.workbench.quality.semantic

import dev.ide.workbench.quality.PrimaryTheme
import dev.ide.workbench.quality.RecordIntent
import dev.ide.workbench.quality.RecordKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests scorer mechanics, NOT real corpus quality, reviewer independence or model accuracy. */
class SemanticHoldoutContractTest {
    private fun classified(id: String, theme: PrimaryTheme = PrimaryTheme.UI_UX) = SemanticLabel(
        id, theme, RecordIntent.BUG_REPORT, SemanticProblemFamily.LAYOUT_RENDERING, emptyList(), 0.9, SemanticReview.CLASSIFIED)
    private fun unknown(id: String) = SemanticLabel(id, PrimaryTheme.UNCLASSIFIED, RecordIntent.UNCLEAR,
        SemanticProblemFamily.UNKNOWN, emptyList(), 0.0, SemanticReview.UNKNOWN)
    private fun stratum(id: String, cluster: String = id, language: String = "en") =
        FrozenStratum(id, "synthetic/app", language, "predeclared_ui", "general", cluster)

    @Test fun `eighty percent thresholds use all frozen issue IDs not just classified rows`() {
        val ids = (1..10).map { "synthetic/app#$it" }.toSet()
        val labels = ids.associateWith { PrimaryTheme.UI_UX }
        val eight = ids.take(8).associateWith(::classified) + ids.drop(8).associateWith(::unknown)
        val boundary = semanticAgreement(ids, eight, labels)
        assertEquals(SemanticAgreement(10, 8, 8), boundary)
        assertTrue(boundary.passes)
        val seven = eight + (ids.first() to unknown(ids.first()))
        assertFalse(semanticAgreement(ids, seven, labels).passes)
        val wrong = eight + (ids.first() to classified(ids.first(), PrimaryTheme.DATA_STATE))
        val result = semanticAgreement(ids, wrong, labels)
        assertEquals(8, result.classified)
        assertEquals(7, result.agreed)
        assertFalse(result.passes)
        assertFalse(semanticAgreement(emptySet(), emptyMap(), emptyMap()).passes)
    }

    @Test fun `PR predictions never raise issue agreement and unknown does not match abstention labels`() {
        val issue = "synthetic/app#1"
        val pr = "synthetic/app#2"
        val predictions = mapOf(issue to unknown(issue), pr to classified(pr))
        val labels = mapOf(issue to PrimaryTheme.UNCLASSIFIED, pr to PrimaryTheme.UI_UX)
        assertEquals(SemanticAgreement(1, 0, 0), semanticAgreement(setOf(issue), predictions, labels))
        assertEquals(SemanticAgreement(1, 1, 1), semanticAgreement(setOf(pr), predictions, labels))
    }

    @Test fun `frozen split cannot cross observer supplied duplicate clusters`() {
        val first = syntheticRecord()
        val duplicate = syntheticRecord(2)
        val strata = listOf(stratum(first.id, "joined"), stratum(duplicate.id, "joined"))
        assertFailsWith<IllegalStateException> {
            FrozenSemanticHoldout("predeclared", setOf(first.id), strata).validateSplit(listOf(first, duplicate))
        }
        FrozenSemanticHoldout("predeclared", setOf(first.id, duplicate.id), strata).validateSplit(listOf(first, duplicate))
        assertFailsWith<IllegalStateException> {
            FrozenSemanticHoldout("predeclared", setOf(first.id, duplicate.id), listOf(stratum(first.id), stratum(duplicate.id)))
                .validateSplit(listOf(first, duplicate))
        }
    }

    @Test fun `holdout must include represented predeclared strata and exclude PR IDs`() {
        val first = syntheticRecord()
        val multilingual = syntheticRecord(2, title = "按钮重叠", body = "小屏按钮重叠")
        val strata = listOf(stratum(first.id), stratum(multilingual.id, language = "zh"))
        assertFailsWith<IllegalStateException> {
            FrozenSemanticHoldout("predeclared", setOf(first.id), strata).validateSplit(listOf(first, multilingual))
        }
        FrozenSemanticHoldout("predeclared", setOf(first.id, multilingual.id), strata).validateSplit(listOf(first, multilingual))
        val pr = multilingual.copy(kind = RecordKind.PULL_REQUEST)
        assertFailsWith<IllegalStateException> {
            FrozenSemanticHoldout("predeclared", setOf(first.id, pr.id), strata).validateSplit(listOf(first, pr))
        }
    }
}
