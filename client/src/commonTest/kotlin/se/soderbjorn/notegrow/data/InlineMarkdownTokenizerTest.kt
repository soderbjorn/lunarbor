package se.soderbjorn.notegrow.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class InlineMarkdownTokenizerTest {

    private fun tokenize(text: String) = InlineMarkdownTokenizer.tokenize(text)

    // ---- plain text -----------------------------------------------------

    @Test
    fun plain_text_yields_a_single_run_with_no_styles() {
        val t = tokenize("hello world")
        assertEquals("hello world", t.displayText)
        assertEquals(1, t.runs.size)
        assertEquals(emptySet(), t.runs[0].styles)
        assertEquals(0, t.runs[0].modelStart)
        assertEquals(11, t.runs[0].modelEnd)
        assertTrue(t.markerCols.isEmpty())
    }

    @Test
    fun empty_input_yields_no_runs_and_a_one_entry_map() {
        val t = tokenize("")
        assertEquals("", t.displayText)
        assertEquals(0, t.runs.size)
        assertContentEquals(intArrayOf(0), t.domToModel)
        assertContentEquals(intArrayOf(0), t.modelToDom)
    }

    // ---- bold ----------------------------------------------------------

    @Test
    fun bold_strips_markers_and_reports_styles() {
        val t = tokenize("**X**")
        assertEquals("X", t.displayText)
        assertEquals(1, t.runs.size)
        assertEquals(setOf(InlineStyle.BOLD), t.runs[0].styles)
        assertEquals("X", t.runs[0].text)
        assertEquals(2, t.runs[0].modelStart)
        assertEquals(3, t.runs[0].modelEnd)
        // Marker chars at cols 0,1 (open) and 3,4 (close).
        assertEquals(setOf(0, 1, 3, 4), t.markerCols)
    }

    @Test
    fun bold_column_maps_round_trip() {
        // **X**  — model cols 0..5, display "X" — display cols 0..1
        val t = tokenize("**X**")
        // modelToDom: open markers + X-start collapse to 0; close markers + EOL to 1
        assertContentEquals(intArrayOf(0, 0, 0, 1, 1, 1), t.modelToDom)
        // domToModel: display 0 → model 2 (X), display 1 → end of line at model col 5
        assertContentEquals(intArrayOf(2, 5), t.domToModel)
    }

    @Test
    fun bold_with_multiple_chars() {
        val t = tokenize("**hello**")
        assertEquals("hello", t.displayText)
        assertEquals(1, t.runs.size)
        assertEquals(setOf(InlineStyle.BOLD), t.runs[0].styles)
    }

    // ---- italic --------------------------------------------------------

    @Test
    fun italic_yields_italic_run() {
        val t = tokenize("*foo*")
        assertEquals("foo", t.displayText)
        assertEquals(setOf(InlineStyle.ITALIC), t.runs[0].styles)
    }

    @Test
    fun italic_does_not_open_at_double_asterisk() {
        // `**foo` — bold opener but no matching close, so falls back to literal.
        // Italic must NOT open here either.
        val t = tokenize("**foo")
        assertEquals("**foo", t.displayText)
        assertEquals(emptySet(), t.runs[0].styles)
    }

    // ---- bold + italic mix ---------------------------------------------

    @Test
    fun bold_with_italic_inside() {
        val t = tokenize("**bold *and* end**")
        assertEquals("bold and end", t.displayText)
        // Three runs: "bold " (bold), "and" (bold+italic), " end" (bold).
        assertEquals(3, t.runs.size)
        assertEquals(setOf(InlineStyle.BOLD), t.runs[0].styles)
        assertEquals(setOf(InlineStyle.BOLD, InlineStyle.ITALIC), t.runs[1].styles)
        assertEquals(setOf(InlineStyle.BOLD), t.runs[2].styles)
    }

    @Test
    fun italic_with_bold_inside() {
        val t = tokenize("*it **bo** it*")
        assertEquals("it bo it", t.displayText)
        assertEquals(3, t.runs.size)
        assertEquals(setOf(InlineStyle.ITALIC), t.runs[0].styles)
        assertEquals(setOf(InlineStyle.ITALIC, InlineStyle.BOLD), t.runs[1].styles)
        assertEquals(setOf(InlineStyle.ITALIC), t.runs[2].styles)
    }

    // ---- inline code ---------------------------------------------------

    @Test
    fun inline_code_strips_backticks() {
        val t = tokenize("a `c` b")
        assertEquals("a c b", t.displayText)
        // Three runs: "a " plain, "c" code, " b" plain.
        assertEquals(3, t.runs.size)
        assertEquals(setOf(InlineStyle.INLINE_CODE), t.runs[1].styles)
    }

    @Test
    fun inline_code_suppresses_other_markers() {
        val t = tokenize("`**not bold**`")
        assertEquals("**not bold**", t.displayText)
        assertEquals(1, t.runs.size)
        assertEquals(setOf(InlineStyle.INLINE_CODE), t.runs[0].styles)
    }

    // ---- strikethrough -------------------------------------------------

    @Test
    fun strikethrough_strips_markers() {
        val t = tokenize("~~gone~~")
        assertEquals("gone", t.displayText)
        assertEquals(setOf(InlineStyle.STRIKETHROUGH), t.runs[0].styles)
    }

    // ---- unmatched / empty ---------------------------------------------

    @Test
    fun unmatched_bold_marker_is_literal() {
        val t = tokenize("**no close")
        assertEquals("**no close", t.displayText)
        assertEquals(emptySet(), t.runs[0].styles)
        assertTrue(t.markerCols.isEmpty())
    }

    @Test
    fun empty_bold_pair_emits_no_runs_but_collapses_markers() {
        val t = tokenize("****")
        assertEquals("", t.displayText)
        assertEquals(0, t.runs.size)
        assertEquals(setOf(0, 1, 2, 3), t.markerCols)
    }

    // ---- column-map round trip -----------------------------------------

    @Test
    fun every_display_position_round_trips_through_domToModel_and_modelToDom() {
        // For every well-formed input, walking display text by display col K
        // and looking up domToModel[K] then modelToDom[that] must give back K.
        val cases = listOf(
            "plain",
            "**bold**",
            "*it*",
            "<u>u</u>",
            "~~s~~",
            "`c`",
            "a **b** c",
            "**bold *and* end**",
        )
        for (case in cases) {
            val t = tokenize(case)
            for (displayCol in 0..t.displayText.length) {
                val modelCol = t.domToModel[displayCol]
                val backToDisplay = t.modelToDom[modelCol]
                assertEquals(
                    displayCol, backToDisplay,
                    "case=$case displayCol=$displayCol modelCol=$modelCol"
                )
            }
        }
    }

    @Test
    fun marker_cols_collapse_to_neighboring_display_col() {
        val t = tokenize("**X**")
        // Open marker cols (0,1) collapse to display col 0 (where X starts).
        assertEquals(0, t.modelToDom[0])
        assertEquals(0, t.modelToDom[1])
        // Close marker cols (3,4) collapse to display col 1 (after X).
        assertEquals(1, t.modelToDom[3])
        assertEquals(1, t.modelToDom[4])
    }

    // ---- stylesAt / stylesAcross --------------------------------------

    @Test
    fun stylesAt_returns_styles_inside_a_run() {
        val t = tokenize("**bold**")
        // Cursor at col 3 is between 'b' (col 2) and 'o' (col 3) → inside bold.
        // (modelStart=2, modelEnd=5 for the bold run "bo|ld" at cols 2,3,4.)
        // Wait: run text is "bold" so modelEnd=6.
        assertEquals(setOf(InlineStyle.BOLD), t.stylesAt(4))
    }

    @Test
    fun stylesAt_at_run_boundary_returns_empty() {
        val t = tokenize("**bold**")
        // Cursor at col 2 (just before 'b') is on the boundary — not strictly inside the run.
        assertEquals(emptySet(), t.stylesAt(2))
    }

    @Test
    fun stylesAcross_returns_intersection() {
        val t = tokenize("*italic*")
        // Whole inner text — italic across the whole range.
        assertEquals(setOf(InlineStyle.ITALIC), t.stylesAcross(1, 7))
    }

    // ---- adjacent inline runs ------------------------------------------

    @Test
    fun adjacent_bold_and_italic() {
        val t = tokenize("**a***b*")
        // Should produce: bold "a" + italic "b". Display: "ab".
        assertEquals("ab", t.displayText)
    }

    @Test
    fun text_with_no_markers_has_no_marker_cols() {
        val t = tokenize("Just plain text.")
        assertFalse(t.markerCols.isNotEmpty())
    }

    // ---- inline markdown links -----------------------------------------

    @Test
    fun plain_link_strips_syntax_and_carries_href() {
        val t = tokenize("[Title](Root.md)")
        assertEquals("Title", t.displayText)
        assertEquals(1, t.runs.size)
        val run = t.runs[0]
        assertEquals("Title", run.text)
        assertEquals("Root.md", run.linkHref)
        assertEquals(emptySet(), run.styles)
        // The label runs over model cols 1..6; everything else is markers.
        assertEquals(1, run.modelStart)
        assertEquals(6, run.modelEnd)
        // `[` at 0, `]` at 6, `(` at 7, `R o o t . m d` at 8..14, `)` at 15.
        for (i in listOf(0, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)) {
            assertTrue(i in t.markerCols, "expected marker at $i")
        }
    }

    @Test
    fun angle_bracketed_link_href_strips_brackets() {
        val t = tokenize("[A](<file with space.md>)")
        assertEquals("A", t.displayText)
        assertEquals("file with space.md", t.runs[0].linkHref)
    }

    @Test
    fun link_with_row_anchor_keeps_full_href() {
        val t = tokenize("[X](Notes.md#r=4)")
        assertEquals("X", t.displayText)
        assertEquals("Notes.md#r=4", t.runs[0].linkHref)
    }

    @Test
    fun link_inside_bold_inherits_styles() {
        val t = tokenize("**[X](u)**")
        assertEquals("X", t.displayText)
        assertEquals(1, t.runs.size)
        val run = t.runs[0]
        assertEquals("X", run.text)
        assertEquals("u", run.linkHref)
        assertTrue(InlineStyle.BOLD in run.styles)
    }

    @Test
    fun unmatched_link_falls_back_to_literal_brackets() {
        // No `(` after `]` — must NOT be consumed as a link.
        val t = tokenize("[oops]")
        assertEquals("[oops]", t.displayText)
        assertEquals(0, t.runs[0].linkHref?.length ?: 0)
        assertTrue(t.markerCols.isEmpty())
    }

    @Test
    fun unclosed_link_paren_falls_back_to_literal() {
        val t = tokenize("[oops](no-close")
        assertEquals("[oops](no-close", t.displayText)
        assertNull(t.runs[0].linkHref)
    }

    @Test
    fun text_around_link_splits_into_three_runs() {
        val t = tokenize("see [docs](d.md) here")
        assertEquals("see docs here", t.displayText)
        assertEquals(3, t.runs.size)
        assertEquals(null, t.runs[0].linkHref)
        assertEquals("d.md", t.runs[1].linkHref)
        assertEquals(null, t.runs[2].linkHref)
    }

    @Test
    fun link_column_maps_round_trip() {
        val t = tokenize("[Title](Root.md)")
        for (displayCol in 0..t.displayText.length) {
            val modelCol = t.domToModel[displayCol]
            assertEquals(displayCol, t.modelToDom[modelCol])
        }
    }
}
