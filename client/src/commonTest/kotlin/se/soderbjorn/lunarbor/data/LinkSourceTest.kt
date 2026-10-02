/*
 * LinkSourceTest.kt (commonTest)
 * ------------------------------
 * Tests for [LinkSource.at]: finding the Markdown or wiki link under a
 * column of a raw line.
 */

package se.soderbjorn.lunarbor.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkSourceTest {

    @Test
    fun finds_a_markdown_link_around_a_column() {
        val line = "* See [soups](lunarbor:/Recipes/Soups) now"
        val link = LinkSource.at(line, 9)!!
        assertEquals("[soups](lunarbor:/Recipes/Soups)", line.substring(link.start, link.end))
        assertEquals("soups", link.label)
        assertEquals(']', line[link.labelEnd])
    }

    @Test
    fun finds_a_wiki_link_and_uses_its_alias_as_the_label() {
        val line = "x [[Note|the note]] and [[Other]]"
        val first = LinkSource.at(line, 4)!!
        assertTrue(first.isWiki)
        assertEquals("the note", first.label)
        val second = LinkSource.at(line, line.length - 3)!!
        assertEquals("Other", second.label)
        assertEquals(line.length - 2, second.labelEnd)
    }

    @Test
    fun images_and_plain_text_are_no_links() {
        assertNull(LinkSource.at("![pic](a.png)", 3))
        assertNull(LinkSource.at("[not a link] here", 3))
        assertNull(LinkSource.at("[a](b) plain", 9))
    }

    @Test
    fun finds_a_bare_url_but_not_one_inside_a_markdown_link() {
        val line = "see https://x.org/a_b. and [site](https://y.org)"
        val bare = LinkSource.at(line, 8)!!
        assertEquals("https://x.org/a_b", line.substring(bare.start, bare.end))
        val md = LinkSource.at(line, line.length - 5)!!
        assertEquals("site", md.label)
    }
}
