/*
 * WikiLinkTest.kt (commonTest)
 * ----------------------------
 * Tests for `[[Name]]` wiki links: the syntax ([WikiLink.endAt],
 * [WikiLink.nameOf]), resolution against the vault's link targets
 * ([WikiLink.resolve]), and the tokenizer run that marks them
 * ([StyledRun.wikiName]).
 */

package se.soderbjorn.lunarbor.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WikiLinkTest {

    private val targets = listOf(
        LinkTarget("", "Home", VaultEntryKind.FOLDER),
        LinkTarget("Reading", "Reading", VaultEntryKind.FOLDER),
        LinkTarget("Reading/How to be concise.md", "How to be concise", VaultEntryKind.MARKDOWN),
        LinkTarget("Reading/Signposting", "Signposting", VaultEntryKind.FOLDER),
        LinkTarget("Reading/cover.png", "cover.png", VaultEntryKind.IMAGE),
        LinkTarget("A/Notes.md", "Notes", VaultEntryKind.MARKDOWN),
        LinkTarget("B/Notes.md", "Notes", VaultEntryKind.MARKDOWN),
    )

    @Test
    fun parses_the_syntax() {
        assertEquals(13, WikiLink.endAt("[[Some note]] (x)", 0))
        assertNull(WikiLink.endAt("[[ ]]", 0))
        assertNull(WikiLink.endAt("[[open", 0))
        assertNull(WikiLink.endAt("[[a [b]]", 0))
        assertEquals("Note", WikiLink.nameOf("Note|shown"))
        assertEquals("Note", WikiLink.nameOf(" Note #Part"))
    }

    @Test
    fun collapsed_links_show_the_name_or_the_alias() {
        fun shown(link: String) = link.substring(WikiLink.shownRangeOf(link))
        assertEquals("Spot", shown("[[Spot]]"))
        assertEquals("the cat", shown("[[Spot|the cat]]"))
        assertEquals("Note#Part", shown("[[Note#Part]]"))
        assertEquals("Spot| ", shown("[[Spot| ]]"))
    }

    @Test
    fun a_name_matching_one_target_resolves_to_it() {
        assertEquals("Reading/How to be concise.md", WikiLink.resolve("how to be  CONCISE", targets))
        assertEquals("Reading/How to be concise.md", WikiLink.resolve("How to be concise.md", targets))
        assertEquals("Reading/Signposting", WikiLink.resolve("Signposting", targets))
        assertEquals("Reading/cover.png", WikiLink.resolve("cover.png", targets))
    }

    @Test
    fun no_match_or_several_matches_resolve_to_nothing() {
        assertNull(WikiLink.resolve("Missing", targets))
        assertNull(WikiLink.resolve("Notes", targets))
        assertNull(WikiLink.resolve("Home", targets))
    }

    @Test
    fun decomposed_names_match_composed_ones() {
        val decomposed = LinkTarget("Café.md", "Café", VaultEntryKind.MARKDOWN)
        assertEquals("Café.md", WikiLink.resolve("Café", listOf(decomposed)))
    }

    @Test
    fun the_tokenizer_marks_a_wiki_link_and_keeps_it_visible() {
        val t = InlineMarkdownTokenizer.tokenize("See [[How to be concise]] (Wes)")
        assertEquals("See [[How to be concise]] (Wes)", t.displayText)
        assertTrue(t.markerCols.isEmpty())
        val run = t.runs.single { it.wikiName != null }
        assertEquals("[[How to be concise]]", run.text)
        assertEquals("How to be concise", run.wikiName)
        assertNull(run.linkHref)
    }

    @Test
    fun a_wiki_link_inside_bold_keeps_the_style() {
        val t = InlineMarkdownTokenizer.tokenize("**[[A]]**")
        val run = t.runs.single { it.wikiName != null }
        assertEquals(setOf(InlineStyle.BOLD), run.styles)
    }

    @Test
    fun a_bare_url_is_a_visible_link_to_itself() {
        val t = InlineMarkdownTokenizer.tokenize("(see https://x.org/a*b*c). **bold**")
        assertEquals("(see https://x.org/a*b*c). bold", t.displayText)
        val run = t.runs.single { it.linkHref != null }
        assertEquals("https://x.org/a*b*c", run.text)
        assertEquals(run.text, run.linkHref)
        assertEquals(null, InlineMarkdownTokenizer.tokenize("xhttps://no").runs.firstOrNull { it.linkHref != null })
    }
}
