package se.soderbjorn.notegrow.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtreeCodecTest {

    // ---- parseRef -----------------------------------------------------------

    @Test
    fun parseRef_returns_null_for_non_bullet_line() {
        assertNull(SubtreeCodec.parseRef(""))
        assertNull(SubtreeCodec.parseRef("plain text"))
        assertNull(SubtreeCodec.parseRef("  not a bullet [X](X/X.md)"))
    }

    @Test
    fun parseRef_returns_null_for_bullet_without_link() {
        assertNull(SubtreeCodec.parseRef("* Hello"))
        assertNull(SubtreeCodec.parseRef("  * indented bullet"))
    }

    @Test
    fun parseRef_returns_null_for_legacy_double_bracket_format() {
        // The old `* Title  [[X/X.nogr]]` shape must NOT parse as a ref under
        // the markdown-link codec — files using it surface as plain text so
        // a stale import is obvious instead of silently corrupting.
        assertNull(SubtreeCodec.parseRef("  * Recipes  [[Recipes/Recipes.nogr]]"))
    }

    @Test
    fun parseRef_extracts_indent_label_and_path() {
        val ref = SubtreeCodec.parseRef("  * [Recipes](Recipes/Recipes.md)")
        assertEquals(2, ref?.indent)
        assertEquals("  * Recipes", ref?.bulletText)
        assertEquals("Recipes/Recipes.md", ref?.refPath)
    }

    @Test
    fun parseRef_handles_unicode_titles_and_paths() {
        val ref = SubtreeCodec.parseRef("* [Möten](Bontouch/Möten/Möten.md)")
        assertEquals("* Möten", ref?.bulletText)
        assertEquals("Bontouch/Möten/Möten.md", ref?.refPath)
    }

    @Test
    fun parseRef_accepts_angle_bracket_url_for_paths_with_spaces() {
        val ref = SubtreeCodec.parseRef("* [Shopping list](<Shopping list/Shopping list.md>)")
        assertEquals("* Shopping list", ref?.bulletText)
        assertEquals("Shopping list/Shopping list.md", ref?.refPath)
    }

    @Test
    fun parseRef_unescapes_label_brackets() {
        val ref = SubtreeCodec.parseRef("* [a\\[b\\]c](X/X.md)")
        assertEquals("* a[b]c", ref?.bulletText)
        assertEquals("X/X.md", ref?.refPath)
    }

    @Test
    fun parseRef_returns_null_for_empty_label_or_url() {
        assertNull(SubtreeCodec.parseRef("* [](X/X.md)"))
        assertNull(SubtreeCodec.parseRef("* [Title]()"))
        assertNull(SubtreeCodec.parseRef("* [Title](<>)"))
    }

    // ---- formatRef + round-trip --------------------------------------------

    @Test
    fun formatRef_emits_bare_url_for_simple_paths() {
        val line = SubtreeCodec.formatRef(0, "Recipes", "Recipes/Recipes.md")
        assertEquals("* [Recipes](Recipes/Recipes.md)", line)
    }

    @Test
    fun formatRef_wraps_url_in_angle_brackets_when_path_contains_spaces() {
        val line = SubtreeCodec.formatRef(2, "Shopping list", "Shopping list/Shopping list.md")
        assertEquals("  * [Shopping list](<Shopping list/Shopping list.md>)", line)
    }

    @Test
    fun formatRef_escapes_label_specials() {
        val line = SubtreeCodec.formatRef(0, "a[b]c", "ab/ab.md")
        assertEquals("* [a\\[b\\]c](ab/ab.md)", line)
    }

    @Test
    fun formatRef_then_parseRef_round_trips_simple() {
        val line = SubtreeCodec.formatRef(2, "Recipes", "Recipes/Recipes.md")
        val parsed = SubtreeCodec.parseRef(line)
        assertEquals("  * Recipes", parsed?.bulletText)
        assertEquals("Recipes/Recipes.md", parsed?.refPath)
    }

    @Test
    fun formatRef_then_parseRef_round_trips_spaces_and_angle_brackets() {
        val line = SubtreeCodec.formatRef(0, "Shopping list", "Shopping list/Shopping list.md")
        val parsed = SubtreeCodec.parseRef(line)
        assertEquals("* Shopping list", parsed?.bulletText)
        assertEquals("Shopping list/Shopping list.md", parsed?.refPath)
    }

    @Test
    fun formatRef_then_parseRef_round_trips_label_brackets() {
        val line = SubtreeCodec.formatRef(0, "a[b]c", "ab/ab.md")
        val parsed = SubtreeCodec.parseRef(line)
        assertEquals("* a[b]c", parsed?.bulletText)
        assertEquals("ab/ab.md", parsed?.refPath)
    }

    // ---- findSubtrees -------------------------------------------------------

    @Test
    fun findSubtrees_emits_one_entry_per_bullet_with_correct_descendant_counts() {
        val lines = listOf(
            "* A",
            "  * B",
            "    * C",
            "    * D",
            "  * E",
            "* F",
        )
        val ms = SubtreeCodec.findSubtrees(lines)
        assertEquals(6, ms.size)
        assertEquals(4, ms[0].descendantCount)
        assertEquals(4, ms[0].endRowInclusive)
        assertEquals(2, ms[1].descendantCount)
        assertEquals(0, ms.last().descendantCount)
    }

    @Test
    fun findSubtrees_skips_non_bullet_lines() {
        val lines = listOf("not a bullet", "* X", "* Y")
        val ms = SubtreeCodec.findSubtrees(lines)
        assertEquals(2, ms.size)
        assertEquals(1, ms[0].startRow)
        assertEquals(2, ms[1].startRow)
    }

    // ---- reindentBy ---------------------------------------------------------

    @Test
    fun reindentBy_zero_is_identity() {
        val input = listOf("* A", "  * B")
        assertEquals(input, SubtreeCodec.reindentBy(input, 0))
    }

    @Test
    fun reindentBy_positive_adds_leading_spaces_to_bullets() {
        val out = SubtreeCodec.reindentBy(listOf("* A", "  * B"), 2)
        assertEquals(listOf("  * A", "    * B"), out)
    }

    @Test
    fun reindentBy_negative_drops_leading_spaces_capped_at_indent() {
        val out = SubtreeCodec.reindentBy(listOf("    * A", "      * B"), -2)
        assertEquals(listOf("  * A", "    * B"), out)
    }

    // ---- titleOf ------------------------------------------------------------

    @Test
    fun titleOf_returns_text_after_bullet_marker() {
        assertEquals("Hello", SubtreeCodec.titleOf("* Hello"))
        assertEquals("World", SubtreeCodec.titleOf("    * World"))
        assertEquals("", SubtreeCodec.titleOf("* "))
        assertEquals("", SubtreeCodec.titleOf("not a bullet"))
    }

    @Test
    fun titleOf_extracts_label_from_markdown_link() {
        assertEquals("Recipes", SubtreeCodec.titleOf("* [Recipes](Recipes/Recipes.md)"))
        assertEquals(
            "Shopping list",
            SubtreeCodec.titleOf("* [Shopping list](<Shopping list/Shopping list.md>)"),
        )
    }

    // ---- safeFilename -------------------------------------------------------

    @Test
    fun safeFilename_preserves_case_spaces_and_unicode() {
        assertEquals("Shopping list", SubtreeCodec.safeFilename("Shopping list"))
        assertEquals("Möten 2024", SubtreeCodec.safeFilename("Möten 2024"))
    }

    @Test
    fun safeFilename_replaces_slash_with_dash() {
        assertEquals("a-b", SubtreeCodec.safeFilename("a/b"))
        assertEquals("path-segment", SubtreeCodec.safeFilename("path/segment"))
    }

    @Test
    fun safeFilename_strips_leading_dots() {
        assertEquals("hidden", SubtreeCodec.safeFilename(".hidden"))
        assertEquals("nested", SubtreeCodec.safeFilename("...nested"))
    }

    @Test
    fun safeFilename_collapses_whitespace_runs() {
        assertEquals("a b c", SubtreeCodec.safeFilename("a   b\tc"))
    }

    @Test
    fun safeFilename_falls_back_to_untitled_when_empty() {
        assertEquals("untitled", SubtreeCodec.safeFilename(""))
        assertEquals("untitled", SubtreeCodec.safeFilename("   "))
        assertEquals("untitled", SubtreeCodec.safeFilename("..."))
    }

    @Test
    fun safeFilename_caps_long_titles() {
        val title = "x".repeat(500)
        val result = SubtreeCodec.safeFilename(title)
        assertTrue(result.length <= 200, "expected <= 200 chars but was ${result.length}")
        assertNotEquals(title, result)
    }

    // ---- uniqueFilename -----------------------------------------------------

    @Test
    fun uniqueFilename_returns_base_when_free() {
        assertEquals("Recipes", SubtreeCodec.uniqueFilename("Recipes", emptySet()))
    }

    @Test
    fun uniqueFilename_appends_2_3_on_collision() {
        val used = mutableSetOf("Recipes")
        assertEquals("Recipes 2", SubtreeCodec.uniqueFilename("Recipes", used))
        used += "Recipes 2"
        assertEquals("Recipes 3", SubtreeCodec.uniqueFilename("Recipes", used))
    }

    @Test
    fun uniqueFilename_does_not_mutate_used_set() {
        val used = mutableSetOf("Recipes")
        SubtreeCodec.uniqueFilename("Recipes", used)
        assertEquals(setOf("Recipes"), used)
    }
}
