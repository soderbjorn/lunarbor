/*
 * FolderNameTest.kt (commonTest)
 * Pins the folder-name encoding rules of TRF-3: percent-encoding of unsafe
 * characters, leading/trailing dots and spaces, control characters, the
 * 120-byte cap, case-insensitive collision suffixes, and reversibility.
 */

package se.soderbjorn.lunarbor.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderNameTest {

    @Test
    fun ticket_examples_encode_slash_and_percent() {
        assertEquals("Q3%2FQ4 plan", FolderName.forTitle("Q3/Q4 plan"))
        assertEquals("50%25 done", FolderName.forTitle("50% done"))
    }

    @Test
    fun every_unsafe_character_is_encoded() {
        assertEquals("a%2Fb%5Cc%3Ad%2Ae%3Ff%22g%3Ch%3Ei%7Cj%25k", FolderName.encode("a/b\\c:d*e?f\"g<h>i|j%k"))
    }

    @Test
    fun leading_dot_trailing_dots_and_spaces_and_controls_are_encoded() {
        assertEquals("%2Ehidden", FolderName.encode(".hidden"))
        assertEquals("end%2E%2E", FolderName.encode("end.."))
        assertEquals("pad%20%20", FolderName.encode("pad  "))
        assertEquals("mix%2E%20", FolderName.encode("mix. "))
        assertEquals("tab%09in", FolderName.encode("tab\tin"))
        // Inner dots and spaces are left alone.
        assertEquals("v1.2 notes", FolderName.encode("v1.2 notes"))
    }

    @Test
    fun markdown_markers_are_stripped_before_encoding() {
        assertEquals("Trip to Lisbon", FolderName.forTitle("Trip to **Lisbon**"))
        assertEquals("Heading", FolderName.forTitle("# Heading"))
        assertEquals("site", FolderName.forTitle("[site](https://example.com)"))
    }

    @Test
    fun empty_title_is_untitled() {
        assertEquals("Untitled", FolderName.forTitle(""))
        assertEquals("Untitled", FolderName.forTitle("![](Images/x.png)"))
    }

    @Test
    fun names_are_capped_at_120_bytes_without_splitting_escapes_or_chars() {
        val long = "x".repeat(200)
        assertEquals(120, FolderName.encode(long).length)
        // A cap that would land inside "%2F" drops the whole char instead.
        val slashy = "y".repeat(118) + "/z"
        val enc = FolderName.encode(slashy)
        assertTrue(FolderName.utf8Length(enc) <= 120)
        assertEquals("y".repeat(118), enc)
        // Multi-byte characters are never cut in half.
        val swedish = "å".repeat(100) // 200 bytes
        val encSw = FolderName.encode(swedish)
        assertEquals(60, encSw.length)
        // A space exposed at the end by the cut is itself encoded.
        val spaced = "w".repeat(119) + " tail"
        assertEquals("w".repeat(119), FolderName.encode(spaced).take(119))
        assertTrue(FolderName.utf8Length(FolderName.encode(spaced)) <= 120)
    }

    @Test
    fun decode_reverses_encode() {
        for (title in listOf("Q3/Q4 plan", "50% done", ".hidden..", "a:b*c?", "tab\there ", "Möten & %2F")) {
            assertEquals(title, FolderName.decode(FolderName.encode(title)))
        }
        // Malformed escapes stay literal.
        assertEquals("100%", FolderName.decode("100%"))
        assertEquals("%zz", FolderName.decode("%zz"))
    }

    @Test
    fun collisions_are_case_insensitive_and_suffixed() {
        assertEquals("Recipes", FolderName.unique("Recipes", setOf("notes")))
        assertEquals("Recipes (2)", FolderName.unique("Recipes", setOf("recipes")))
        assertEquals("Recipes (3)", FolderName.unique("Recipes", setOf("recipes", "recipes (2)")))
    }

    @Test
    fun variant_detection() {
        assertTrue(FolderName.isVariantOf("Untitled", "Untitled"))
        assertTrue(FolderName.isVariantOf("Untitled (2)", "Untitled"))
        assertFalse(FolderName.isVariantOf("Untitled (1)", "Untitled"))
        assertFalse(FolderName.isVariantOf("Untitled (x)", "Untitled"))
        assertFalse(FolderName.isVariantOf("untitled", "Untitled"))
    }

    @Test
    fun tags_are_left_out_of_names_with_one_adjoining_space() {
        assertEquals("1-1", FolderName.nameTextOf("1-1 #framna-sensitive"))
        assertEquals("1-1", FolderName.nameTextOf("1-1 #a #b"))
        assertEquals("Meet with Bob", FolderName.nameTextOf("Meet #x with Bob"))
        assertEquals("Plan", FolderName.nameTextOf("#work Plan"))
        assertEquals("Trip to Lisbon", FolderName.nameTextOf("**Trip** to Lisbon #travel"))
        assertEquals("", FolderName.nameTextOf("#private"))
        // Not tags: kept as written.
        assertEquals("Issue id#42", FolderName.nameTextOf("Issue id#42"))
        assertEquals("C# notes", FolderName.nameTextOf("C# notes"))
    }

    @Test
    fun a_title_without_tags_names_exactly_like_its_plain_text() {
        for (t in listOf("Q3/Q4 plan", "  spaced  out  ", "# Heading", "See [x](https://e.com)")) {
            assertEquals(FolderName.plainTextOf(t), FolderName.nameTextOf(t))
        }
    }

    @Test
    fun folder_names_leave_tags_out() {
        assertEquals("1-1", FolderName.forTitle("1-1 #framna-sensitive"))
        assertEquals(FolderName.UNTITLED, FolderName.forTitle("#private"))
    }
}
