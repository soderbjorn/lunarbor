package se.soderbjorn.treefacts.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromotionPolicyTest {

    private fun span(
        descendantCount: Int,
        globalDepth: Int = 1,
        titleLength: Int = 5,
    ) = SubtreeSpan(
        startRow = 0,
        endRowInclusive = descendantCount,
        indent = globalDepth * 2,
        descendantCount = descendantCount,
        globalDepth = globalDepth,
        titleLength = titleLength,
    )

    // ---- shouldPromote ------------------------------------------------------

    @Test
    fun shouldPromote_threshold_is_inclusive_at_promote_min_descendants() {
        val min = PromotionPolicy.promoteMinDescendants
        assertFalse(PromotionPolicy.shouldPromote(span(min - 1), alreadyPromoted = false))
        assertTrue(PromotionPolicy.shouldPromote(span(min), alreadyPromoted = false))
    }

    @Test
    fun shouldPromote_returns_false_when_already_promoted() {
        val min = PromotionPolicy.promoteMinDescendants
        assertFalse(PromotionPolicy.shouldPromote(span(min + 100), alreadyPromoted = true))
    }

    @Test
    fun shouldPromote_returns_false_above_max_depth() {
        val min = PromotionPolicy.promoteMinDescendants
        val cappedDepth = PromotionPolicy.MAX_DEPTH_TO_PROMOTE
        assertTrue(
            PromotionPolicy.shouldPromote(span(min, globalDepth = cappedDepth), alreadyPromoted = false)
        )
        assertFalse(
            PromotionPolicy.shouldPromote(span(min, globalDepth = cappedDepth + 1), alreadyPromoted = false)
        )
    }

    @Test
    fun shouldPromote_returns_false_for_empty_title() {
        val min = PromotionPolicy.promoteMinDescendants
        assertFalse(
            PromotionPolicy.shouldPromote(span(min, titleLength = 0), alreadyPromoted = false)
        )
    }

    // ---- shouldDemote -------------------------------------------------------

    @Test
    fun shouldDemote_returns_true_at_or_below_demote_max() {
        val max = PromotionPolicy.demoteMaxDescendants
        assertTrue(PromotionPolicy.shouldDemote(0))
        assertTrue(PromotionPolicy.shouldDemote(max))
    }

    @Test
    fun shouldDemote_returns_false_above_demote_max() {
        val max = PromotionPolicy.demoteMaxDescendants
        assertFalse(PromotionPolicy.shouldDemote(max + 1))
    }

    @Test
    fun hysteresis_gap_between_promote_and_demote_thresholds() {
        // Promote requires more descendants than demote tolerates: a subtree
        // with descendantCount in (demoteMax, promoteMin) is in neither zone,
        // so a freshly-promoted subtree can lose a few descendants without
        // being immediately demoted.
        assertTrue(PromotionPolicy.demoteMaxDescendants < PromotionPolicy.promoteMinDescendants)
    }

    @Test
    fun debug_thresholds_are_lower_than_production() {
        // Sanity check: in DEBUG mode the values must be lower than the
        // production constants documented in the plan, otherwise we forgot
        // to switch to debug mode for testing or the values were swapped.
        if (PromotionPolicy.DEBUG) {
            assertTrue(PromotionPolicy.promoteMinDescendants < 40)
            assertTrue(PromotionPolicy.demoteMaxDescendants < 15)
        } else {
            assertEquals(40, PromotionPolicy.promoteMinDescendants)
            assertEquals(15, PromotionPolicy.demoteMaxDescendants)
        }
    }
}
