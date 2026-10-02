/*
 * SearchQueryTest.kt (commonTest)
 * -------------------------------
 * Pins the search expression language ([SearchQuery]): parsing (precedence,
 * implicit AND, NOT forms, phrases, tag prefixes, `in:`, lenient recovery
 * from half-typed queries) and evaluation against a line's text and tags.
 */
package se.soderbjorn.lunarbor.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchQueryTest {

    private fun m(query: String, text: String, vararg tags: String): Boolean =
        SearchQuery.parse(query).expr!!.matches(SearchQuery.normalize(text), tags.toSet())

    @Test
    fun a_space_means_and_and_capital_operators_combine() {
        assertTrue(m("#work #urgent", "x", "work", "urgent"))
        assertFalse(m("#work #urgent", "x", "work"))
        assertTrue(m("#work OR #home", "x", "home"))
        // AND binds tighter than OR.
        assertTrue(m("#a OR #b AND #c", "x", "a"))
        assertFalse(m("(#a OR #b) AND #c", "x", "a"))
        // Lowercase and / or are words.
        assertTrue(m("salt and pepper", "salt and pepper"))
        assertFalse(m("salt or pepper", "salt"))
    }

    @Test
    fun not_and_minus_negate() {
        assertTrue(m("#work -#done", "x", "work"))
        assertFalse(m("#work -#done", "x", "work", "done"))
        assertFalse(m("#work AND NOT #done", "x", "work", "done"))
        assertTrue(m("-(#a OR #b)", "x", "c"))
        assertTrue(m("pre-war", "the pre-war era"))
    }

    @Test
    fun tags_match_whole_unless_starred_and_phrases_are_exact() {
        assertFalse(m("#work", "x", "workshop"))
        assertTrue(m("#work*", "x", "workshop"))
        assertTrue(m("#Work", "x", "work"))
        assertTrue(m("\"call anna\"", "Please Call Anna today"))
        assertFalse(m("\"call anna\"", "anna, call me"))
    }

    @Test
    fun in_names_a_tree_and_half_typed_queries_still_parse() {
        assertEquals("Work/Projects", SearchQuery.parse("in:/Work/Projects/ #x").scopePath)
        assertEquals("", SearchQuery.parse("#x in:/").scopePath)
        assertEquals("My folder", SearchQuery.parse("in:\"/My folder\" #x").scopePath)
        assertEquals("Q3%2FQ4 plan", SearchQuery.parse("in:/Q3%252FQ4%20plan #x").scopePath)
        assertTrue(SearchQuery.parse("in:/Work").isEmpty)
        // An open group, a trailing operator, a stray parenthesis.
        assertTrue(m("#a AND (#b OR", "x", "a", "b"))
        assertTrue(m("#a OR", "x", "a"))
        assertTrue(m("#a ) #b", "x", "a", "b"))
        assertTrue(SearchQuery.parse("( AND )").isEmpty)
        assertEquals(listOf("#a", "word"), SearchQuery.parse("#a word -#b").highlightTerms())
        assertTrue(SearchQuery.parse("#a order:reverse").reversed)
        assertFalse(SearchQuery.parse("#a").reversed)
    }
}
