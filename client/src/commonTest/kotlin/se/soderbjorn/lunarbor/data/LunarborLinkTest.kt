/*
 * LunarborLinkTest.kt (commonTest)
 * --------------------------
 * Tests for [LunarborLink], the link codec of TRF-8: path encoding on top
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
        assertEquals("/Recipes/Soups", LunarborLink.rooted("Recipes/Soups"))
        assertEquals("/Recipes/granola.jpg", LunarborLink.rooted("Recipes/granola.jpg"))
        assertEquals("/Budget%202027.md", LunarborLink.rooted("Budget 2027.md"))
        assertEquals("/", LunarborLink.rooted(""))
    }

    @Test
    fun display_paths_read_as_titles_not_links() {
        assertEquals("/", LunarborLink.displayPath(""))
        assertEquals("/🌱 Garden project", LunarborLink.displayPath("🌱 Garden project"))
        assertEquals(
            "/Work: Warp Factor Pizza/Budget 2027.md",
            LunarborLink.displayPath("Work%3A Warp Factor Pizza/Budget 2027.md"),
        )
    }

    @Test
    fun encodes_characters_that_break_a_link_destination() {
        assertEquals("/a%28b%29%5Bc%5D%3Cd%3E%5Ce%23f%3Fg", LunarborLink.rooted("a(b)[c]<d>\\e#f?g"))
        assertEquals("/tab%09x", LunarborLink.rooted("tab\tx"))
    }

    @Test
    fun a_folder_name_that_holds_an_escape_round_trips() {
        // FolderName encodes a `/` in a title as %2F on disk; the link
        // encodes that `%` once more.
        val onDisk = FolderName.forTitle("Q3/Q4 plan")
        assertEquals("Q3%2FQ4 plan", onDisk)
        val link = LunarborLink.rooted(onDisk)
        assertEquals("/Q3%252FQ4%20plan", link)
        assertEquals(onDisk, LunarborLink.parseRooted(link))
    }

    @Test
    fun non_ascii_names_are_kept_readable() {
        assertEquals("/Rätter/Smörgås", LunarborLink.rooted("Rätter/Smörgås"))
        assertEquals("Rätter/Smörgås", LunarborLink.parseRooted("/R%C3%A4tter/Smörgås"))
    }

    @Test
    fun round_trips_arbitrary_names() {
        for (name in listOf("a b", "50%25 done", "x (2)", "%", "Ω≈ç √", "emoji 😀 name", "trailing%20")) {
            assertEquals("Top/$name", LunarborLink.parseRooted(LunarborLink.rooted("Top/$name")), name)
        }
    }

    // ------------------------------------------------------------- parsing

    @Test
    fun parses_folders_files_and_the_root() {
        assertEquals("Recipes/Soups", LunarborLink.parseRooted("/Recipes/Soups"))
        assertEquals("Recipes/Soups", LunarborLink.parseRooted("/Recipes/Soups/"))
        assertEquals("Budget 2027.md", LunarborLink.parseRooted("/Budget%202027.md"))
        assertEquals("", LunarborLink.parseRooted("/"))
    }

    @Test
    fun rejects_other_urls_and_malformed_paths() {
        assertNull(LunarborLink.parseRooted("https://example.com"))
        assertNull(LunarborLink.parseRooted("Recipes/Soups"))
        assertNull(LunarborLink.parseRooted("#Recipes"))
        assertNull(LunarborLink.parseRooted("//host/Recipes"))
        // An older vault's link is no in-app href (files still read it, see resolve).
        assertNull(LunarborLink.parseRooted("lunarbor:/Recipes"))
        assertNull(LunarborLink.parseRooted("/../b"))
        assertNull(LunarborLink.parseRooted("/a%2Fb"))
        assertNull(LunarborLink.parseRooted("/bad%zz"))
        assertNull(LunarborLink.parseRooted("/bad%2"))
    }

    // ------------------------------------------------------ in files

    @Test
    fun resolves_relative_rooted_and_old_links_against_the_lines_folder() {
        assertEquals("Recipes/Soups", LunarborLink.resolve("Recipes/Soups/_node.md", ""))
        assertEquals("Recipes/Pasta", LunarborLink.resolve("../Pasta/_node.md", "Recipes/Soups"))
        assertEquals("Recipes/Soups", LunarborLink.resolve("_node.md", "Recipes/Soups"))
        assertEquals("", LunarborLink.resolve("../../_node.md", "Recipes/Soups"))
        assertEquals("Budget 2027.md", LunarborLink.resolve("../Budget%202027.md#Q1", "Plans"))
        assertEquals("My notes/a.md", LunarborLink.resolve("<My notes/a.md>", ""))
        assertEquals("Recipes/Soups", LunarborLink.resolve("/Recipes/Soups", "Anywhere/Else"))
        assertEquals("Recipes/Soups", LunarborLink.resolve("lunarbor:/Recipes/Soups", "Anywhere"))
        assertEquals("Work%3A Pizza", LunarborLink.resolve("Work%253A%20Pizza", ""))
        // External, anchors and escapes from the vault are no places in it.
        assertNull(LunarborLink.resolve("https://example.com/a", ""))
        assertNull(LunarborLink.resolve("mailto:a@b.c", ""))
        assertNull(LunarborLink.resolve("obsidian://open?x", ""))
        assertNull(LunarborLink.resolve("#heading", ""))
        assertNull(LunarborLink.resolve("../outside.md", ""))
    }

    @Test
    fun writes_relative_links_a_node_by_its_outline() {
        assertEquals("Recipes/Soups/_node.md", LunarborLink.relative("Recipes/Soups", true, ""))
        assertEquals("../Pasta/_node.md", LunarborLink.relative("Recipes/Pasta", true, "Recipes/Soups"))
        assertEquals("_node.md", LunarborLink.relative("Recipes/Soups", true, "Recipes/Soups"))
        assertEquals("./Lentil/_node.md", LunarborLink.relative("Recipes/Soups/Lentil", true, "Recipes/Soups"))
        assertEquals("../../_node.md", LunarborLink.relative("", true, "Recipes/Soups"))
        assertEquals("../granola.jpg", LunarborLink.relative("Recipes/granola.jpg", false, "Recipes/Soups"))
        assertEquals("Budget%202027.md", LunarborLink.relative("Budget 2027.md", false, ""))
        assertEquals("./Recipes/_node.md", LunarborLink.relative("Recipes", true, ""))
        assertEquals("./Work%253A%20Pizza/_node.md", LunarborLink.relative("Work%3A Pizza", true, ""))
        // Round trip from any folder.
        for (base in listOf("", "Recipes", "Recipes/Soups", "Other/Deep/Place")) {
            for ((path, folder) in listOf("Recipes/Soups" to true, "" to true, "Recipes/granola.jpg" to false)) {
                assertEquals(path, LunarborLink.resolve(LunarborLink.relative(path, folder, base), base))
            }
        }
    }

    @Test
    fun finds_links_but_not_images_child_links_or_web_links() {
        val text = "- See [soups](Recipes/Soups/_node.md) and ![img](Recipes/granola.jpg) [old](lunarbor:/Old)\n" +
            "- Plans [↳](<Plans/_node.md>)\n" +
            "> [site](https://x.test) [note](<My notes/a.md> \"title\") [[Wiki]]\n"
        val links = LunarborLink.findLinks(text, "")
        assertEquals(listOf("Recipes/Soups", "Old", "My notes/a.md"), links.map { it.pathRel })
        assertEquals(listOf(true, false, true), links.map { it.isRelative })
        assertTrue(links[0].namesOutline)
    }

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
    fun rebase_follows_a_renamed_target() {
        val text = "* [a](Recipes/Soups/_node.md) [b](Recipes/Soups/granola.jpg) [c](Recipes/Soupsmore/_node.md)"
        val out = LunarborLink.rebaseText(text, "", "", listOf(PathMove("Recipes/Soups", "Recipes/Soup stock")))
        assertEquals("* [a](Recipes/Soup%20stock/_node.md) [b](Recipes/Soup%20stock/granola.jpg) [c](Recipes/Soupsmore/_node.md)", out)
    }

    @Test
    fun rebase_follows_a_line_into_another_folder() {
        // A row moved from the root into Recipes/Soups.
        val text = "* see [pasta](Recipes/Pasta/_node.md) and [home](_node.md) and [web](https://x.test)"
        assertEquals(
            "* see [pasta](../Pasta/_node.md) and [home](../../_node.md) and [web](https://x.test)",
            LunarborLink.rebaseText(text, "", "Recipes/Soups"),
        )
    }

    @Test
    fun rebase_keeps_outline_structure_and_turns_old_links_relative() {
        val line = "- See [soups](lunarbor:/Recipes/Soups) [x](/Recipes/a.png) [↳](<See soups/_node.md>)"
        val out = LunarborLink.rebaseText(line, "", "")
        assertEquals("- See [soups](Recipes/Soups/_node.md) [x](Recipes/a.png) [↳](<See soups/_node.md>)", out)
        val parsed = SubtreeCodec.parseNodeFile(out!!).single() as NodeLine.Folder
        assertEquals("See [soups](Recipes/Soups/_node.md) [x](Recipes/a.png)", parsed.title)
        assertEquals("See soups", parsed.folder)
    }

    @Test
    fun rebase_ignores_trash_moves_and_reports_no_change() {
        val text = "* [a](Recipes/Soups/_node.md) [b](../x.md)"
        assertNull(LunarborLink.rebaseText(text, "Notes", "Notes", listOf(PathMove("Recipes/Soups", ".trash/1970 Soups"))))
        assertNull(LunarborLink.rebaseText(text, "Notes", "Notes", listOf(PathMove("Other", "Else"))))
        assertNull(LunarborLink.rebaseText("no links here", "", "", listOf(PathMove("a", "b"))))
        assertTrue(PathMove(".trash/x", "Recipes/x").touchesTrash)
    }

    @Test
    fun copied_text_is_vault_rooted() {
        assertEquals(
            "* [p](/Recipes/Pasta/_node.md) [w](https://x.test)",
            LunarborLink.rootedText("* [p](../Pasta/_node.md) [w](https://x.test)", "Recipes/Soups"),
        )
    }
}
