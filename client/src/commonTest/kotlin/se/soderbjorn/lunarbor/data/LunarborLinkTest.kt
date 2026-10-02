/*
 * LunarborLinkTest.kt (commonTest)
 * --------------------------
 * Tests for [LunarborLink], the `lunarbor:` link codec of TRF-8: path encoding on top
 * of the on-disk folder names, parsing, finding links in raw text (inline
 * and inside an outline's escaped `+` titles), and the rewrite applied
 * after renames and moves.
 */

package se.soderbjorn.lunarbor.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LunarborLinkTest {

    // ------------------------------------------------------------ encoding

    @Test
    fun formats_the_ticket_examples() {
        assertEquals("lunarbor:/Recipes/Soups", LunarborLink.format("Recipes/Soups"))
        assertEquals("lunarbor:/Recipes/granola.jpg", LunarborLink.format("Recipes/granola.jpg"))
        assertEquals("lunarbor:/Budget%202027.md", LunarborLink.format("Budget 2027.md"))
        assertEquals("lunarbor:/", LunarborLink.format(""))
    }

    @Test
    fun encodes_characters_that_break_a_link_destination() {
        assertEquals("lunarbor:/a%28b%29%5Bc%5D%3Cd%3E%5Ce%23f%3Fg", LunarborLink.format("a(b)[c]<d>\\e#f?g"))
        assertEquals("lunarbor:/tab%09x", LunarborLink.format("tab\tx"))
    }

    @Test
    fun a_folder_name_that_holds_an_escape_round_trips() {
        // FolderName encodes a `/` in a title as %2F on disk; the link
        // encodes that `%` once more.
        val onDisk = FolderName.forTitle("Q3/Q4 plan")
        assertEquals("Q3%2FQ4 plan", onDisk)
        val link = LunarborLink.format(onDisk)
        assertEquals("lunarbor:/Q3%252FQ4%20plan", link)
        assertEquals(onDisk, LunarborLink.parse(link))
    }

    @Test
    fun non_ascii_names_are_kept_readable() {
        assertEquals("lunarbor:/Rätter/Smörgås", LunarborLink.format("Rätter/Smörgås"))
        assertEquals("Rätter/Smörgås", LunarborLink.parse("lunarbor:/R%C3%A4tter/Smörgås"))
    }

    @Test
    fun round_trips_arbitrary_names() {
        for (name in listOf("a b", "50%25 done", "x (2)", "%", "Ω≈ç √", "emoji 😀 name", "trailing%20")) {
            assertEquals("Top/$name", LunarborLink.parse(LunarborLink.format("Top/$name")), name)
        }
    }

    // ------------------------------------------------------------- parsing

    @Test
    fun parses_folders_files_and_the_root() {
        assertEquals("Recipes/Soups", LunarborLink.parse("lunarbor:/Recipes/Soups"))
        assertEquals("Recipes/Soups", LunarborLink.parse("lunarbor:/Recipes/Soups/"))
        assertEquals("Budget 2027.md", LunarborLink.parse("lunarbor:/Budget%202027.md"))
        assertEquals("", LunarborLink.parse("lunarbor:/"))
    }

    @Test
    fun rejects_other_urls_and_malformed_paths() {
        assertNull(LunarborLink.parse("https://example.com"))
        assertNull(LunarborLink.parse("Recipes/Soups"))
        assertNull(LunarborLink.parse("#Recipes"))
        assertNull(LunarborLink.parse("lunarbor:Recipes"))
        assertNull(LunarborLink.parse("lunarbor:/a//b"))
        assertNull(LunarborLink.parse("lunarbor:/a/../b"))
        assertNull(LunarborLink.parse("lunarbor:/./a"))
        assertNull(LunarborLink.parse("lunarbor:/a%2Fb"))
        assertNull(LunarborLink.parse("lunarbor:/bad%zz"))
        assertNull(LunarborLink.parse("lunarbor:/bad%2"))
    }

    // ------------------------------------------------------ finding links

    @Test
    fun finds_links_inline_angle_bracketed_and_in_escaped_outline_titles() {
        val text = "* See [soups](lunarbor:/Recipes/Soups) and ![img](<lunarbor:/Recipes/granola.jpg>)\n" +
            "+ [Plan \\[in\\](lunarbor:/Budget%202027.md)](Plan)\n" +
            "* not a link: lunarbor:/Loose and https://x.test/lunarbor:/nope\n"
        assertEquals(
            listOf("Recipes/Soups", "Recipes/granola.jpg", "Budget 2027.md"),
            LunarborLink.findLinks(text).map { it.pathRel },
        )
    }

    // ------------------------------------------------------------- rewrite

    @Test
    fun remap_follows_the_longest_matching_move() {
        val moves = listOf(PathMove("Recipes", "Food"), PathMove("Recipes/Soups", "Food/Broths"))
        assertEquals("Food/Broths", LunarborLink.remap("Recipes/Soups", moves))
        assertEquals("Food/Broths/granola.jpg", LunarborLink.remap("Recipes/Soups/granola.jpg", moves))
        assertEquals("Food/Cakes", LunarborLink.remap("Recipes/Cakes", moves))
        assertNull(LunarborLink.remap("RecipesOld/x", moves))
        assertNull(LunarborLink.remap("Other", moves))
    }

    @Test
    fun rewrites_links_at_and_through_a_renamed_folder() {
        val text = "* [a](lunarbor:/Recipes/Soups) [b](lunarbor:/Recipes/Soups/granola.jpg) [c](lunarbor:/Recipes/Soupsmore)"
        val out = LunarborLink.rewriteText(text, listOf(PathMove("Recipes/Soups", "Recipes/Soup stock")))
        assertEquals(
            "* [a](lunarbor:/Recipes/Soup%20stock) [b](lunarbor:/Recipes/Soup%20stock/granola.jpg) [c](lunarbor:/Recipes/Soupsmore)",
            out,
        )
    }

    @Test
    fun rewrite_keeps_outline_structure() {
        val line = "- See [soups](lunarbor:/Recipes/Soups) [↳](<See soups/_node.md>)"
        val out = LunarborLink.rewriteText(line, listOf(PathMove("Recipes", "Food")))
        assertEquals("- See [soups](lunarbor:/Food/Soups) [↳](<See soups/_node.md>)", out)
        val parsed = SubtreeCodec.parseNodeFile(out!!).single() as NodeLine.Folder
        assertEquals("See [soups](lunarbor:/Food/Soups)", parsed.title)
        assertEquals("See soups", parsed.folder)
    }

    @Test
    fun rewrite_ignores_trash_moves_and_reports_no_change() {
        val text = "* [a](lunarbor:/Recipes/Soups)"
        assertNull(LunarborLink.rewriteText(text, listOf(PathMove("Recipes/Soups", ".trash/1970 Soups"))))
        assertNull(LunarborLink.rewriteText(text, listOf(PathMove("Other", "Else"))))
        assertNull(LunarborLink.rewriteText("no links here", listOf(PathMove("a", "b"))))
        assertTrue(PathMove(".trash/x", "Recipes/x").touchesTrash)
    }
}
