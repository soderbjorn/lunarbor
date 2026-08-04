/*
 * SpaceMetadata.kt
 * ----------------
 * Pure helpers for TreeFacts's per-space metadata, stored as namespaced
 * keys inside the YAML frontmatter of a space's anchor file
 * (`<Space>/<Space>.md`). `NoteRepository` already round-trips user
 * frontmatter verbatim through every load/save cycle, so persistence is
 * free; this file owns only the parsing and (surgical) rewriting of the
 * `treefacts-*` keys, leaving every other user-authored line untouched.
 *
 * Two keys live here today:
 *
 * ```
 * ---
 * treefacts-space: true      # this anchored folder IS a space (structural
 * treefacts-ai: allowed      #   boundary); AI eligibility (opt-in)
 * ---
 * ```
 *
 * **`treefacts-space`** is the *declarative* space marker. A folder is a
 * space when its anchor carries this key — an explicit, position-free
 * fact that replaces the older inferred "root-level anchored tree that
 * the root file doesn't reference" heuristic. A space is a structural
 * boundary that never auto-demotes and can carry its own settings.
 *
 * **`treefacts-ai`** is one such setting: AI eligibility. Semantics are
 * deliberately **opt-in / fail-closed** — the stored fact is that AI *is*
 * allowed. Absence of the key (including a lost or stripped frontmatter
 * block) means AI-ineligible, so the failure mode of losing one is
 * revoked access, never granted access.
 *
 * Spaces are the right metadata carriers because they are demote-proof:
 * a promoted tree's anchor file is deleted when the tree demotes (its
 * frontmatter would vanish), but a space's anchor can never be demoted.
 *
 * No I/O, no state — total, deterministic functions. commonMain only.
 */

package se.soderbjorn.treefacts.data

/**
 * Parses and rewrites the `treefacts-*` metadata keys of a space anchor's
 * frontmatter block.
 *
 * ### Callers
 * - `NoteRepository.readSpaceAiAllowed` / `writeSpaceAiAllowed` — the
 *   only I/O-side users; everything above them (registry, pane VM, the
 *   Space settings modal) goes through those.
 *
 * All functions take/return frontmatter in the exact shape
 * [NoteRepository.splitFrontmatter] produces: the full block including
 * the surrounding `---\n…\n---\n` fences and trailing newline, or `null`
 * for "no frontmatter block at all".
 */
object SpaceMetadata {

    /** Frontmatter key declaring the anchored folder to be a space. */
    const val SPACE_KEY: String = "treefacts-space"

    /** The [SPACE_KEY] value that declares a space. */
    const val SPACE_TRUE_VALUE: String = "true"

    /** Frontmatter key holding the AI opt-in. */
    const val AI_KEY: String = "treefacts-ai"

    /** The [AI_KEY] value that grants AI eligibility. */
    const val AI_ALLOWED_VALUE: String = "allowed"

    /**
     * `true` iff [frontmatter] contains a `treefacts-space: true` line —
     * i.e. the anchored folder is a declared space. Any other value (or
     * no key, or `null` frontmatter) reads as "not a space".
     *
     * @param frontmatter Full frontmatter block including fences, as
     *   returned by `NoteRepository.splitFrontmatter`, or `null` when
     *   the file has none.
     */
    fun isSpaceOf(frontmatter: String?): Boolean {
        if (frontmatter == null) return false
        for (line in innerLinesOf(frontmatter)) {
            val (key, value) = keyValueOf(line) ?: continue
            if (key == SPACE_KEY) return value == SPACE_TRUE_VALUE
        }
        return false
    }

    /**
     * Returns [frontmatter] with the space marker set to [isSpace],
     * preserving every non-`treefacts-space` line byte-for-byte. Mirrors
     * [withAiAllowed]: setting appends a single `treefacts-space: true`
     * at the end of the block (growing a fresh block from `null`);
     * clearing removes every [SPACE_KEY] line and collapses a
     * now-empty block to `null`.
     *
     * @param frontmatter Current block including fences, or `null`.
     * @param isSpace `true` writes the marker; `false` removes it.
     * @return The new block including fences, or `null` for "write no
     *   frontmatter".
     */
    fun withSpaceMarker(frontmatter: String?, isSpace: Boolean): String? =
        withScalarKey(frontmatter, SPACE_KEY, if (isSpace) SPACE_TRUE_VALUE else null)

    /**
     * `true` iff [frontmatter] contains a `treefacts-ai: allowed` line.
     * Any other value for the key (or no key, or `null` frontmatter)
     * reads as not allowed — fail closed.
     *
     * @param frontmatter Full frontmatter block including fences, as
     *   returned by `NoteRepository.splitFrontmatter`, or `null` when
     *   the file has none.
     */
    fun aiAllowedOf(frontmatter: String?): Boolean {
        if (frontmatter == null) return false
        for (line in innerLinesOf(frontmatter)) {
            val (key, value) = keyValueOf(line) ?: continue
            if (key == AI_KEY) return value == AI_ALLOWED_VALUE
        }
        return false
    }

    /**
     * Returns [frontmatter] with the AI opt-in set to [allowed],
     * preserving every non-`treefacts-ai` line byte-for-byte (Obsidian
     * `tags`, `aliases`, comments, …).
     *
     * - `allowed = true`: any existing [AI_KEY] lines are replaced by a
     *   single `treefacts-ai: allowed` appended at the end of the block;
     *   a `null` input grows a fresh block.
     * - `allowed = false`: [AI_KEY] lines are removed. A block left with
     *   no other content collapses to `null` (no frontmatter at all), so
     *   an untouched-by-the-user file stays fence-free on disk.
     *
     * @param frontmatter Current block including fences, or `null`.
     * @return The new block including fences, or `null` for "write no
     *   frontmatter".
     */
    fun withAiAllowed(frontmatter: String?, allowed: Boolean): String? =
        withScalarKey(frontmatter, AI_KEY, if (allowed) AI_ALLOWED_VALUE else null)

    /**
     * Returns [frontmatter] with [key] set to [value], or removed when
     * [value] is `null`, preserving every other line byte-for-byte. All
     * existing lines for [key] are dropped first, so a set replaces (and
     * de-duplicates) rather than appends a second copy. A block left with
     * no inner lines collapses to `null` (no frontmatter at all).
     *
     * Shared by [withSpaceMarker] and [withAiAllowed]; the surgical,
     * fail-closed rewrite semantics are identical for every `treefacts-*`
     * scalar key.
     */
    private fun withScalarKey(frontmatter: String?, key: String, value: String?): String? {
        val kept = (frontmatter?.let(::innerLinesOf) ?: emptyList())
            .filter { keyValueOf(it)?.first != key }
        val inner = if (value != null) kept + "$key: $value" else kept
        if (inner.isEmpty()) return null
        return "---\n" + inner.joinToString("\n") + "\n---\n"
    }

    /**
     * The lines strictly between the opening and closing `---` fences.
     * Tolerates a block whose closing fence lacks a trailing newline.
     * Returns an empty list for a malformed block (no closing fence) —
     * `NoteRepository.splitFrontmatter` never produces one, but totality
     * is cheap.
     */
    private fun innerLinesOf(frontmatter: String): List<String> {
        val lines = frontmatter.split("\n")
        if (lines.isEmpty() || lines[0] != "---") return emptyList()
        val closing = lines.indexOfLast { it == "---" }
        if (closing <= 0) return emptyList()
        return lines.subList(1, closing)
    }

    /**
     * Splits a `key: value` frontmatter line into its trimmed parts, or
     * `null` when the line has no `:` (list items, comments, blanks).
     */
    private fun keyValueOf(line: String): Pair<String, String>? {
        val colon = line.indexOf(':')
        if (colon < 0) return null
        return line.substring(0, colon).trim() to line.substring(colon + 1).trim()
    }
}
