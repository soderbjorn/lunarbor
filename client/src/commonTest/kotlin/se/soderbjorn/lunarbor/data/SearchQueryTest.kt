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

    @Test
    fun is_done_and_is_open_test_the_done_flag_and_combine() {
        fun d(query: String, done: Boolean, vararg tags: String) =
            SearchQuery.parse(query).expr!!.matches("x", tags.toSet(), done)
        assertTrue(d("is:done", done = true))
        assertFalse(d("is:done", done = false))
        assertTrue(d("is:open", done = false))
        assertFalse(d("is:open", done = true))
        assertTrue(d("-is:done", done = false))
        assertEquals(SearchQuery.parse("is:open").expr, SearchQuery.parse("-is:done").expr)
        assertTrue(d("#todo -is:done", false, "todo"))
        assertFalse(d("#todo -is:done", true, "todo"))
        assertTrue(d("#todo is:done", true, "todo"))
        assertFalse(d("#todo is:open", false))
        assertTrue(d("IS:DONE OR #x", true))
        // The scope and order still come out of the query around them.
        val q = SearchQuery.parse("#todo is:open in:/Journal order:reverse")
        assertEquals("Journal", q.scopePath)
        assertTrue(q.reversed)
        assertEquals(listOf("#todo"), q.highlightTerms())
        // Without a done flag a line counts as open.
        assertTrue(m("is:open", "x"))
    }

    @Test
    fun sort_tag_skips_the_querys_own_tags() {
        val q = SearchQuery.parse("#todo (#work OR #home*) -#done sort:tag")
        val sort = q.tagSort!!
        assertEquals("beta", sort.keyOf(listOf("#todo", "#Beta", "#alpha")))
        assertEquals("alpha", sort.keyOf(listOf("#work", "#homeoffice", "#alpha")))
        // A NOT term is no search tag: it is a key like any other.
        assertEquals("done", sort.keyOf(listOf("#todo", "#done")))
        assertEquals(null, sort.keyOf(listOf("#todo")))
        // Not a search term, and off unless asked for.
        assertEquals(listOf("#todo", "#work", "#home"), q.highlightTerms())
        assertEquals(null, SearchQuery.parse("#todo").tagSort)
        assertEquals(null, SearchQuery.parse("#todo sort:tag sort:none").tagSort)
    }
}
