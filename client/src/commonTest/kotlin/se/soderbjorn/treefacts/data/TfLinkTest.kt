/*
 * TfLinkTest.kt (commonTest)
 * --------------------------
 * Tests for [TfLink], the `tf:` link codec of TRF-8: path encoding on top
 * of the on-disk folder names, parsing, finding links in raw text (inline
 * and inside an outline's escaped `+` titles), and the rewrite applied
 * after renames and moves.
 */

package se.soderbjorn.treefacts.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TfLinkTest {

    // ------------------------------------------------------------ encoding

    @Test
    fun formats_the_ticket_examples() {
        assertEquals("tf:/Recipes/Soups", TfLink.format("Recipes/Soups"))
        assertEquals("tf:/Recipes/granola.jpg", TfLink.format("Recipes/granola.jpg"))
        assertEquals("tf:/Budget%202027.md", TfLink.format("Budget 2027.md"))
        assertEquals("tf:/", TfLink.format(""))
    }

    @Test
    fun encodes_characters_that_break_a_link_destination() {
        assertEquals("tf:/a%28b%29%5Bc%5D%3Cd%3E%5Ce%23f%3Fg", TfLink.format("a(b)[c]<d>\\e#f?g"))
        assertEquals("tf:/tab%09x", TfLink.format("tab\tx"))
    }

    @Test
    fun a_folder_name_that_holds_an_escape_round_trips() {
        // FolderName encodes a `/` in a title as %2F on disk; the link
        // encodes that `%` once more.
        val onDisk = FolderName.forTitle("Q3/Q4 plan")
        assertEquals("Q3%2FQ4 plan", onDisk)
        val link = TfLink.format(onDisk)
        assertEquals("tf:/Q3%252FQ4%20plan", link)
        assertEquals(onDisk, TfLink.parse(link))
    }

    @Test
    fun non_ascii_names_are_kept_readable() {
        assertEquals("tf:/Rätter/Smörgås", TfLink.format("Rätter/Smörgås"))
        assertEquals("Rätter/Smörgås", TfLink.parse("tf:/R%C3%A4tter/Smörgås"))
    }

    @Test
    fun round_trips_arbitrary_names() {
        for (name in listOf("a b", "50%25 done", "x (2)", "%", "Ω≈ç √", "emoji 😀 name", "trailing%20")) {
            assertEquals("Top/$name", TfLink.parse(TfLink.format("Top/$name")), name)
        }
    }

    // ------------------------------------------------------------- parsing

    @Test
    fun parses_folders_files_and_the_root() {
        assertEquals("Recipes/Soups", TfLink.parse("tf:/Recipes/Soups"))
        assertEquals("Recipes/Soups", TfLink.parse("tf:/Recipes/Soups/"))
        assertEquals("Budget 2027.md", TfLink.parse("tf:/Budget%202027.md"))
        assertEquals("", TfLink.parse("tf:/"))
    }

    @Test
    fun rejects_other_urls_and_malformed_paths() {
        assertNull(TfLink.parse("https://example.com"))
        assertNull(TfLink.parse("Recipes/Soups"))
        assertNull(TfLink.parse("#treefacts-bullet=/Recipes"))
        assertNull(TfLink.parse("tf:/a//b"))
        assertNull(TfLink.parse("tf:/a/../b"))
        assertNull(TfLink.parse("tf:/./a"))
        assertNull(TfLink.parse("tf:/a%2Fb"))
        assertNull(TfLink.parse("tf:/bad%zz"))
        assertNull(TfLink.parse("tf:/bad%2"))
    }

    // ------------------------------------------------------ finding links

    @Test
    fun finds_links_inline_angle_bracketed_and_in_escaped_outline_titles() {
        val text = "* See [soups](tf:/Recipes/Soups) and ![img](<tf:/Recipes/granola.jpg>)\n" +
            "+ [Plan \\[in\\](tf:/Budget%202027.md)](Plan)\n" +
            "* not a link: tf:/Loose and https://x.test/tf:/nope\n"
        assertEquals(
            listOf("Recipes/Soups", "Recipes/granola.jpg", "Budget 2027.md"),
            TfLink.findLinks(text).map { it.pathRel },
        )
    }

    // ------------------------------------------------------------- rewrite

    @Test
    fun remap_follows_the_longest_matching_move() {
        val moves = listOf(PathMove("Recipes", "Food"), PathMove("Recipes/Soups", "Food/Broths"))
        assertEquals("Food/Broths", TfLink.remap("Recipes/Soups", moves))
        assertEquals("Food/Broths/granola.jpg", TfLink.remap("Recipes/Soups/granola.jpg", moves))
        assertEquals("Food/Cakes", TfLink.remap("Recipes/Cakes", moves))
        assertNull(TfLink.remap("RecipesOld/x", moves))
        assertNull(TfLink.remap("Other", moves))
    }

    @Test
    fun rewrites_links_at_and_through_a_renamed_folder() {
        val text = "* [a](tf:/Recipes/Soups) [b](tf:/Recipes/Soups/granola.jpg) [c](tf:/Recipes/Soupsmore)"
        val out = TfLink.rewriteText(text, listOf(PathMove("Recipes/Soups", "Recipes/Soup stock")))
        assertEquals(
            "* [a](tf:/Recipes/Soup%20stock) [b](tf:/Recipes/Soup%20stock/granola.jpg) [c](tf:/Recipes/Soupsmore)",
            out,
        )
    }

    @Test
    fun rewrite_keeps_escaped_outline_structure() {
        val line = "+ [See \\[soups\\](tf:/Recipes/Soups)](See soups)"
        val out = TfLink.rewriteText(line, listOf(PathMove("Recipes", "Food")))
        assertEquals("+ [See \\[soups\\](tf:/Food/Soups)](See soups)", out)
        val parsed = SubtreeCodec.parseNodeFile(out!!).single() as NodeLine.Folder
        assertEquals("See [soups](tf:/Food/Soups)", parsed.title)
        assertEquals("See soups", parsed.folder)
    }

    @Test
    fun rewrite_ignores_trash_moves_and_reports_no_change() {
        val text = "* [a](tf:/Recipes/Soups)"
        assertNull(TfLink.rewriteText(text, listOf(PathMove("Recipes/Soups", ".trash/1970 Soups"))))
        assertNull(TfLink.rewriteText(text, listOf(PathMove("Other", "Else"))))
        assertNull(TfLink.rewriteText("no links here", listOf(PathMove("a", "b"))))
        assertTrue(PathMove(".trash/x", "Recipes/x").touchesTrash)
    }
}
