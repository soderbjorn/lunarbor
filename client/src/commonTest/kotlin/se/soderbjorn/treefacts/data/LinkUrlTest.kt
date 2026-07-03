package se.soderbjorn.treefacts.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkUrlTest {

    // ---- parse --------------------------------------------------------------

    @Test
    fun parse_returns_null_for_non_treefacts_urls() {
        assertNull(LinkUrl.parse(""))
        assertNull(LinkUrl.parse("https://example.com"))
        assertNull(LinkUrl.parse("Recipes/Pasta.md"))
        assertNull(LinkUrl.parse("#section"))
        assertNull(LinkUrl.parse("#treefacts"))
        assertNull(LinkUrl.parse("#treefacts-link=Foo"))
    }

    @Test
    fun parse_returns_null_for_empty_path() {
        assertNull(LinkUrl.parse("#treefacts-bullet="))
        assertNull(LinkUrl.parse("#treefacts-bullet=/"))
    }

    @Test
    fun parse_returns_null_for_empty_segments() {
        assertNull(LinkUrl.parse("#treefacts-bullet=A//B"))
        assertNull(LinkUrl.parse("#treefacts-bullet=/A//B"))
    }

    @Test
    fun parse_single_segment_relative() {
        val url = LinkUrl.parse("#treefacts-bullet=Pasta")
        assertEquals(LinkUrl(listOf("Pasta"), isAbsolute = false), url)
    }

    @Test
    fun parse_multi_segment_relative() {
        val url = LinkUrl.parse("#treefacts-bullet=Recipes/Dinner/Pasta")
        assertEquals(
            LinkUrl(listOf("Recipes", "Dinner", "Pasta"), isAbsolute = false),
            url,
        )
    }

    @Test
    fun parse_absolute_path() {
        val url = LinkUrl.parse("#treefacts-bullet=/Recipes/Pasta")
        assertEquals(LinkUrl(listOf("Recipes", "Pasta"), isAbsolute = true), url)
    }

    @Test
    fun parse_decodes_percent_escapes() {
        val url = LinkUrl.parse("#treefacts-bullet=A%2FB/C%23/D%25")
        assertEquals(
            LinkUrl(listOf("A/B", "C#", "D%"), isAbsolute = false),
            url,
        )
    }

    @Test
    fun parse_decodes_lowercase_hex_for_2f() {
        // Encoders may emit lowercase hex (`%2f`) — accept both.
        val url = LinkUrl.parse("#treefacts-bullet=A%2fB")
        assertEquals(LinkUrl(listOf("A/B"), isAbsolute = false), url)
    }

    @Test
    fun parse_leaves_unknown_percent_escapes_alone() {
        // `%20` is a valid URL-encoded space, but our encoder doesn't emit it
        // (spaces survive verbatim). We leave it untouched so a title with a
        // literal `%20` substring survives parse → format → parse.
        val url = LinkUrl.parse("#treefacts-bullet=A%20B")
        assertEquals(LinkUrl(listOf("A%20B"), isAbsolute = false), url)
    }

    @Test
    fun parse_treats_dotdot_as_navigation_segment() {
        val url = LinkUrl.parse("#treefacts-bullet=../../Foo")
        assertEquals(
            LinkUrl(listOf("..", "..", "Foo"), isAbsolute = false),
            url,
        )
    }

    // ---- format -------------------------------------------------------------

    @Test
    fun format_single_segment_relative() {
        assertEquals(
            "#treefacts-bullet=Pasta",
            LinkUrl.format(listOf("Pasta"), isAbsolute = false),
        )
    }

    @Test
    fun format_absolute() {
        assertEquals(
            "#treefacts-bullet=/Recipes/Pasta",
            LinkUrl.format(listOf("Recipes", "Pasta"), isAbsolute = true),
        )
    }

    @Test
    fun format_encodes_special_chars() {
        assertEquals(
            "#treefacts-bullet=A%2FB/C%23/D%25",
            LinkUrl.format(listOf("A/B", "C#", "D%"), isAbsolute = false),
        )
    }

    @Test
    fun format_passes_dotdot_through_verbatim() {
        assertEquals(
            "#treefacts-bullet=../Foo",
            LinkUrl.format(listOf("..", "Foo"), isAbsolute = false),
        )
    }

    // ---- round-trip ---------------------------------------------------------

    @Test
    fun round_trip_preserves_segments_and_absoluteness() {
        val cases = listOf(
            LinkUrl(listOf("Pasta"), false),
            LinkUrl(listOf("Recipes", "Pasta"), false),
            LinkUrl(listOf("Recipes", "Pasta"), true),
            LinkUrl(listOf("..", "..", "Foo"), false),
            LinkUrl(listOf("Has space", "Has(paren)", "Has,comma"), false),
            LinkUrl(listOf("A/B/C"), true),                  // slash inside title
            LinkUrl(listOf("Header #1"), false),             // hash inside title
            LinkUrl(listOf("100%", "of users"), true),       // percent inside title
            LinkUrl(listOf("Mixed", "A/B%C#D"), false),      // all three at once
        )
        for (input in cases) {
            val formatted = LinkUrl.format(input.segments, input.isAbsolute)
            val parsed = LinkUrl.parse(formatted)
            assertEquals(input, parsed, "round-trip failed for $input via $formatted")
        }
    }

    @Test
    fun format_then_parse_for_absolute_keeps_leading_slash() {
        val formatted = LinkUrl.format(listOf("Foo"), isAbsolute = true)
        assertTrue(formatted.startsWith("#treefacts-bullet=/"))
        val parsed = LinkUrl.parse(formatted)!!
        assertTrue(parsed.isAbsolute)
        assertEquals(listOf("Foo"), parsed.segments)
    }
}
