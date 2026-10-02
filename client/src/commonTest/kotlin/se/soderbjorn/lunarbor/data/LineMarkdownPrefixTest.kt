package se.soderbjorn.lunarbor.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineMarkdownPrefixTest {

    @Test
    fun detect_returns_null_for_plain_line() {
        val p = LineMarkdownPrefix.detect("Hello world")
        assertNull(p.style)
        assertEquals(0, p.markerStart)
        assertEquals(0, p.markerEnd)
    }

    @Test
    fun detect_h1_at_start_of_line() {
        val p = LineMarkdownPrefix.detect("# Title")
        assertEquals(LineStyle.HEADING_1, p.style)
        assertEquals(0, p.markerStart)
        assertEquals(2, p.markerEnd)
    }

    @Test
    fun detect_h3_prefers_longest_match() {
        val p = LineMarkdownPrefix.detect("### Title")
        assertEquals(LineStyle.HEADING_3, p.style)
        assertEquals(4, p.markerEnd)
    }

    @Test
    fun detect_h4() {
        val p = LineMarkdownPrefix.detect("#### Title")
        assertEquals(LineStyle.HEADING_4, p.style)
        assertEquals(5, p.markerEnd)
    }

    @Test
    fun detect_h5() {
        val p = LineMarkdownPrefix.detect("##### Title")
        assertEquals(LineStyle.HEADING_5, p.style)
        assertEquals(6, p.markerEnd)
    }

    @Test
    fun detect_h6_prefers_longest_match() {
        val p = LineMarkdownPrefix.detect("###### Title")
        assertEquals(LineStyle.HEADING_6, p.style)
        assertEquals(7, p.markerEnd)
    }

    @Test
    fun detect_quote_at_text_start_of_bullet() {
        // Bullet "* " then quote "> ": textStart = 2 in "* > Quoted"
        val p = LineMarkdownPrefix.detect("* > Quoted", textStart = 2)
        assertEquals(LineStyle.QUOTE, p.style)
        assertEquals(2, p.markerStart)
        assertEquals(4, p.markerEnd)
    }

    @Test
    fun detect_returns_null_when_marker_does_not_align_to_textStart() {
        // Heading marker is at col 0 but we're checking for one at col 2.
        val p = LineMarkdownPrefix.detect("# Heading", textStart = 2)
        assertNull(p.style)
    }

    // ---- apply ----------------------------------------------------------

    @Test
    fun apply_adds_marker_to_plain_line() {
        assertEquals("# Hello", LineMarkdownPrefix.apply("Hello", LineStyle.HEADING_1))
    }

    @Test
    fun apply_replaces_existing_prefix() {
        // H1 → H2 on "# Hello" ⇒ "## Hello"
        assertEquals("## Hello", LineMarkdownPrefix.apply("# Hello", LineStyle.HEADING_2))
    }

    @Test
    fun apply_h6_to_plain_line() {
        assertEquals("###### Hello", LineMarkdownPrefix.apply("Hello", LineStyle.HEADING_6))
    }

    @Test
    fun apply_replaces_h2_with_h5() {
        assertEquals("##### Hello", LineMarkdownPrefix.apply("## Hello", LineStyle.HEADING_5))
    }

    @Test
    fun apply_no_op_when_target_already_present() {
        val s = "# Title"
        assertEquals(s, LineMarkdownPrefix.apply(s, LineStyle.HEADING_1))
    }

    @Test
    fun apply_inserts_at_textStart_for_bullet_lines() {
        // "* Hello" → apply HEADING_1 at textStart=2 → "* # Hello"
        assertEquals(
            "* # Hello",
            LineMarkdownPrefix.apply("* Hello", LineStyle.HEADING_1, textStart = 2)
        )
    }

    // ---- toggle ---------------------------------------------------------

    @Test
    fun toggle_removes_when_already_present() {
        assertEquals("Hello", LineMarkdownPrefix.toggle("# Hello", LineStyle.HEADING_1))
    }

    @Test
    fun toggle_replaces_with_target_when_different() {
        assertEquals("> Hello", LineMarkdownPrefix.toggle("# Hello", LineStyle.QUOTE))
    }

    @Test
    fun toggle_adds_when_absent() {
        assertEquals("> Hello", LineMarkdownPrefix.toggle("Hello", LineStyle.QUOTE))
    }

    @Test
    fun toggle_off_on_bullet_line() {
        assertEquals(
            "* Hello",
            LineMarkdownPrefix.toggle("* # Hello", LineStyle.HEADING_1, textStart = 2)
        )
    }

    @Test
    fun toggle_h4_on_off_round_trip() {
        val applied = LineMarkdownPrefix.toggle("Notes", LineStyle.HEADING_4)
        assertEquals("#### Notes", applied)
        assertEquals("Notes", LineMarkdownPrefix.toggle(applied, LineStyle.HEADING_4))
    }

    // ---- remove ---------------------------------------------------------

    @Test
    fun remove_strips_existing_prefix() {
        assertEquals("Hello", LineMarkdownPrefix.remove("## Hello"))
    }

    @Test
    fun remove_is_no_op_when_absent() {
        assertEquals("Hello", LineMarkdownPrefix.remove("Hello"))
    }

    @Test
    fun remove_on_bullet_line_keeps_bullet() {
        assertEquals("* Hello", LineMarkdownPrefix.remove("* > Hello", textStart = 2))
    }

    @Test
    fun remove_strips_h6() {
        assertEquals("Hello", LineMarkdownPrefix.remove("###### Hello"))
    }
}
