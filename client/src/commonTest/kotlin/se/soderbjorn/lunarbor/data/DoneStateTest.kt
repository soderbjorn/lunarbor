/*
 * DoneStateTest.kt (commonTest)
 * -----------------------------
 * Pins the done rule of LBR-24 ([DoneState]): an item is done when its
 * whole title — tags, search queries and whitespace at either end left
 * out — is struck through; the wrap / unwrap rule Toggle done uses; rows
 * as `Document.lines` holds them (bullets, headings, blocks, code), and
 * plain `.md` note lines.
 */
package se.soderbjorn.lunarbor.data

import se.soderbjorn.lunarbor.main.BlockLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoneStateTest {

    @Test
    fun the_whole_title_struck_is_done_tags_outside_do_not_count() {
        assertTrue(DoneState.isDoneText("~~Buy oat milk~~"))
        assertTrue(DoneState.isDoneText("~~Buy oat milk~~ #todo"))
        assertTrue(DoneState.isDoneText("#todo ~~Buy oat milk~~ #home "))
        assertTrue(DoneState.isDoneText("~~Open tasks~~ {{search: #todo is:open}}"))
        assertTrue(DoneState.isDoneText("~~Buy **oat** milk #todo~~"))
        // Partly struck, or two separate spans: not done.
        assertFalse(DoneState.isDoneText("Buy ~~oat~~ milk"))
        assertFalse(DoneState.isDoneText("~~Buy~~ oat milk"))
        assertFalse(DoneState.isDoneText("~~Buy~~ ~~milk~~"))
        // Nothing struck, nothing to strike.
        assertFalse(DoneState.isDoneText("Buy oat milk"))
        assertFalse(DoneState.isDoneText("~~~~"))
        assertFalse(DoneState.isDoneText("~~ spaced ~~"))
        assertFalse(DoneState.isDoneText(""))
    }

    @Test
    fun toggling_wraps_the_whole_title_keeping_tags_outside_and_unwraps_it_again() {
        assertEquals("~~Buy oat milk~~ #todo", DoneState.toggledText("Buy oat milk #todo"))
        assertEquals("Buy oat milk #todo", DoneState.toggledText("~~Buy oat milk~~ #todo"))
        assertEquals("#a ~~call Anna~~ #b", DoneState.toggledText("#a call Anna #b"))
        // Strike marks already inside the title are merged into one span.
        assertEquals("~~Buy oat milk~~", DoneState.toggledText("Buy ~~oat~~ milk"))
        assertEquals("~~Open tasks~~ {{search: #todo}}", DoneState.toggledText("Open tasks {{search: #todo}}"))
        // Nothing to strike: unchanged.
        assertEquals("#todo", DoneState.toggledText("#todo"))
        assertEquals("", DoneState.toggledText(""))
        // Asking for the state it already has changes nothing.
        assertEquals("~~x~~", DoneState.withDoneText("~~x~~", true))
        assertEquals("x", DoneState.withDoneText("x", false))
    }

    @Test
    fun rows_keep_their_markers_and_prefixes() {
        assertTrue(DoneState.isDoneRow("  * ~~Task~~ #todo"))
        assertFalse(DoneState.isDoneRow("  * Task ~~x~~"))
        assertEquals("  * ~~Task~~ #todo", DoneState.toggledRow("  * Task #todo"))
        assertEquals("* # ~~Heading~~", DoneState.toggledRow("* # Heading"))
        assertTrue(DoneState.isDoneRow("* # ~~Heading~~"))
        // A block row: after its hidden marker (and a list item's prefix).
        val block = "  ${BlockLayout.FIRST}Paragraph text"
        assertEquals("  ${BlockLayout.FIRST}~~Paragraph text~~", DoneState.toggledRow(block))
        assertTrue(DoneState.isDoneRow("  ${BlockLayout.FIRST}~~Paragraph text~~"))
        // A code row is verbatim: never done, never changed.
        val code = "  ${BlockLayout.FIRST}${BlockLayout.CODE}~~x~~"
        assertFalse(DoneState.isDoneRow(code))
        assertEquals(code, DoneState.toggledRow(code))
    }

    @Test
    fun note_lines_are_done_after_their_list_marker() {
        assertTrue(DoneState.isDoneNoteLine("- ~~Buy milk~~ #todo"))
        assertTrue(DoneState.isDoneNoteLine("  1. ~~Buy milk~~"))
        assertTrue(DoneState.isDoneNoteLine("- [x] ~~Buy milk~~"))
        assertTrue(DoneState.isDoneNoteLine("~~Plain line~~"))
        assertTrue(DoneState.isDoneNoteLine("## ~~Heading~~"))
        assertFalse(DoneState.isDoneNoteLine("- Buy ~~milk~~"))
    }
}
