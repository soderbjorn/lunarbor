/*
 * PromotionPolicy.kt
 * ------------------
 * Auto-promote / auto-demote thresholds for splitting one outline across many
 * `.nogr` files. Lives at the persistence boundary — `NoteRepository` consults
 * this on every save tick to decide whether each subtree should be inlined or
 * spun out into its own file. The view and document VMs never see this code.
 *
 * commonMain only — no platform imports.
 */

package se.soderbjorn.notegrow.data

/**
 * One subtree's metrics, fed to [PromotionPolicy.shouldPromote] /
 * [PromotionPolicy.shouldDemote].
 *
 * @property startRow Absolute row of the subtree's root bullet within its file.
 * @property endRowInclusive Last row that still belongs to the subtree.
 * @property indent Leading-space count of the root bullet.
 * @property descendantCount Number of bullets strictly under the root
 *   (`endRowInclusive - startRow`). Zero means a leaf.
 * @property globalDepth The subtree root's depth in the *composed* outline
 *   (root.nogr's top-level bullets are depth 0; descendants of a promoted
 *   subtree are still counted from the original root's perspective). Used to
 *   cap how deep promotions may chain.
 * @property titleLength Length of the bullet's title text (the line content
 *   after the leading indent + `"* "`). Empty titles are not promoted because
 *   they would map to meaningless filenames.
 */
data class SubtreeSpan(
    val startRow: Int,
    val endRowInclusive: Int,
    val indent: Int,
    val descendantCount: Int,
    val globalDepth: Int,
    val titleLength: Int,
)

/**
 * Holds the thresholds and exposes simple decision predicates. Single source
 * of truth for tuning — touch [DEBUG] to flip between testing and production
 * thresholds without re-running production-scoped saves.
 *
 * The hysteresis gap between promote and demote thresholds is preserved in
 * both modes so edits near the promote line don't flap a subtree in/out of
 * its own file on every autosave tick.
 */
object PromotionPolicy {
    /**
     * Set to `true` during development to use very low thresholds so the
     * behaviour is observable after typing only a handful of bullets. Flip
     * to `false` for production-shipping. The corpus-calibrated production
     * values catch ~1.65% of bullets — roughly the natural "chapters" — and
     * leave routine sub-lists inline.
     */
    const val DEBUG: Boolean = true

    /**
     * Subtree must have at least this many descendants to be promoted into
     * its own file. Production: 40 (≈ a screenful). Debug: 10 — small
     * enough to see promotions while editing a few bullets, large enough
     * that a depth-3 chain like `Ämnen → Teknik → Programmering → Kotlin`
     * doesn't fragment into a file per level.
     */
    val promoteMinDescendants: Int get() = if (DEBUG) 10 else 40

    /**
     * An already-promoted subtree shrinks back below this descendant count
     * gets inlined again. The gap to [promoteMinDescendants] is the
     * hysteresis band. Production: 15. Debug: 4.
     */
    val demoteMaxDescendants: Int get() = if (DEBUG) 4 else 15

    /**
     * Subtrees deeper than this in the global outline are never promoted.
     * Beyond depth 4 the corpus's p99 subtree size is ≤ 34, so most won't
     * clear the descendant threshold anyway and the cap stops pathological
     * deep-and-wide branches from spawning a tower of files.
     */
    const val MAX_DEPTH_TO_PROMOTE: Int = 4

    /**
     * Don't promote a bullet whose title is empty — the resulting filename
     * would be the placeholder `untitled`, which is rarely useful.
     */
    const val MIN_TITLE_LENGTH: Int = 1

    /**
     * Decides whether [span] should be split into its own file.
     *
     * @param span Metrics of the subtree under consideration.
     * @param alreadyPromoted Whether this subtree is currently spun out.
     *   Already-promoted subtrees are not re-promoted; they only get
     *   evaluated for *demotion* via [shouldDemote].
     */
    fun shouldPromote(span: SubtreeSpan, alreadyPromoted: Boolean): Boolean =
        !alreadyPromoted &&
            span.descendantCount >= promoteMinDescendants &&
            span.globalDepth <= MAX_DEPTH_TO_PROMOTE &&
            span.titleLength >= MIN_TITLE_LENGTH

    /**
     * Decides whether an already-promoted subtree has shrunk enough that it
     * should be inlined back into its parent.
     *
     * Adopted-foreign files carry [PromotedRef.noAutoPromote] = `true` so
     * the caller short-circuits this check entirely; the policy itself
     * remains a pure predicate over the descendant count.
     *
     * @param descendantCount Current descendant count of the subtree.
     */
    fun shouldDemote(descendantCount: Int): Boolean =
        descendantCount <= demoteMaxDescendants
}
