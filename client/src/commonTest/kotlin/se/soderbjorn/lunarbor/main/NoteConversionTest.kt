/* NoteConversionTest.kt (commonTest)
 *
 * Pins the rows "Convert to node" builds from a Markdown note
 * ([NoteConversion.nodeRowsFor]) and the bullets "Convert block to nodes"
 * builds from a block ([NoteConversion.nodeGroupsOfBlock]). */
package se.soderbjorn.lunarbor.main

import kotlin.test.Test
import kotlin.test.assertEquals

class NoteConversionTest {

    private fun first(indent: Int, c: String = "") = BlockLayout.firstLine(indent, c)
    private fun next(indent: Int, c: String = "") = BlockLayout.nextLine(indent, c)

    @Test
    fun title_bullet_with_the_note_in_a_child_block() {
        assertEquals(
            listOf("* Budget 2027", first(2, "Rent: 12"), next(2, ""), next(2, "* food")),
            NoteConversion.nodeRowsFor("Budget%202027.md".replace("%20", " "), "Rent: 12\n\n* food\n", 0),
        )
    }

    @Test
    fun a_heading_repeating_the_name_and_surrounding_blanks_are_dropped() {
        assertEquals(
            listOf("    * Plan", first(6, "Body")),
            NoteConversion.nodeRowsFor("Work/Plan.md", "# Plan\n\n\nBody\n\n", 4),
        )
    }

    @Test
    fun another_heading_is_kept() {
        assertEquals(
            listOf("* Plan", first(2, "# Goals"), next(2, "x")),
            NoteConversion.nodeRowsFor("Plan.md", "# Goals\nx", 0),
        )
    }

    @Test
    fun code_fences_become_code_rows_and_an_empty_note_an_empty_block() {
        val rows = NoteConversion.nodeRowsFor("Snip.md", "```\nval a = 1\n```", 0)
        assertEquals("* Snip", rows[0])
        assertEquals(listOf("val a = 1"), rows.drop(1).map { BlockLayout.textOf(it) })
        assertEquals(true, BlockLayout.isCodeLine(rows[1]))
        assertEquals(listOf("* Empty", first(2)), NoteConversion.nodeRowsFor("Empty.md", "", 0))
    }

    @Test
    fun block_paragraphs_become_bullets_their_lines_joined() {
        assertEquals(
            listOf(listOf("  * One two"), listOf("  * # Head"), listOf("  * Three"), listOf("  * Four")),
            NoteConversion.nodeGroupsOfBlock(listOf("One", "two  ", "", "# Head", "Three", "", "", "Four"), 2),
        )
    }

    @Test
    fun block_list_items_become_bullets_and_nest() {
        assertEquals(
            listOf(
                listOf("* Intro"),
                listOf("* a", "  * a1 more", "  * about a"),
                listOf("* 2. b"),
            ),
            NoteConversion.nodeGroupsOfBlock(
                listOf("Intro", "- a", "  * a1", "    more", "", "  about a", "2. b"),
                0,
            ),
        )
    }

    @Test
    fun block_code_stays_a_block_and_an_empty_block_gives_an_empty_bullet() {
        val c = BlockLayout.CODE
        assertEquals(
            listOf(listOf("* Run"), listOf(first(0, "${c}x()"), next(0, "${c}y()")), listOf("* Done")),
            NoteConversion.nodeGroupsOfBlock(listOf("Run", "${c}x()", "${c}y()", "Done"), 0),
        )
        assertEquals(listOf(listOf("    * ")), NoteConversion.nodeGroupsOfBlock(listOf("", " "), 4))
    }
}
