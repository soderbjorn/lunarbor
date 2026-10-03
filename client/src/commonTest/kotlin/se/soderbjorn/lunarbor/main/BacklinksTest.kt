/*
 * BacklinksTest.kt (commonTest)
 * -----------------------------
 * Backlinks (LBR-7): the lines linking to a page — `lunarbor:` links to it
 * exactly, and `[[wiki]]` links resolving to it (unique, not ambiguous or
 * unresolved; alias and heading forms count) — never from inside the page,
 * never what a privacy mode hides, kept current after renames and moves.
 * Runs the real stack — [DocumentRegistry] + [NoteRepository] on
 * [InMemoryFileSystem], and a [PaneBackingViewModel] for the page's target.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.PrivacyMode
import se.soderbjorn.lunarbor.data.TextHit
import se.soderbjorn.lunarbor.data.WikiLink
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BacklinksTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    /** The backlinks of [path], computed by the registry. */
    private suspend fun TestScope.backlinks(registry: DocumentRegistry, path: String): List<TextHit> {
        registry.requestBacklinks(path)?.let { return it }
        runCurrent()
        return registry.backlinksFlow.value.getValue(path)
    }

    private fun List<TextHit>.texts() = map { it.text }

    @Test
    fun wiki_link_names_are_read_with_alias_and_heading_forms() {
        assertEquals(listOf("Soups", "Plan", "Budget.md"), WikiLink.namesIn("See [[Soups]], [[Plan|the plan]] and [[Budget.md#Q3]]"))
        assertEquals(emptyList(), WikiLink.namesIn("no [[ ]] here [[a]b]]"))
    }

    @Test
    fun lunarbor_links_to_the_page_count_but_not_to_things_inside_it_or_from_inside() = runTest {
        seed("_node.md", "- Recipes [↳](<Recipes/_node.md>)\n- Cook [soups](lunarbor:/Recipes/Soups)\n- All [recipes](lunarbor:/Recipes)\n")
        seed("Recipes/_node.md", "- Soups [↳](<Soups/_node.md>)\n- Back to [all](lunarbor:/Recipes)\n")
        seed("Recipes/Soups/_node.md", "- Tomato\n")
        seed("Plan.md", "Weekly: [soups](lunarbor:/Recipes/Soups) and [the outline](lunarbor:/Recipes/_node.md)\n")
        val r = DocumentRegistry(repo, backgroundScope)
        assertEquals(listOf("All recipes", "Weekly: soups and the outline"), backlinks(r, "Recipes").texts())
        val soups = backlinks(r, "Recipes/Soups")
        assertEquals(listOf("_node.md", "Plan.md"), soups.map { it.fileRel })
        assertEquals(1, soups.first().itemIndex)
        assertEquals(emptyList(), backlinks(r, "").texts())
    }

    @Test
    fun wiki_links_count_when_they_resolve_to_the_page_alone() = runTest {
        seed("_node.md", "- Budget [↳](<Budget/_node.md>)\n- See [[budget]]\n- And [[Budget#Q3|the Q3 part]]\n- Twice: [[Notes]]\n- Nowhere: [[Gone]]\n")
        seed("Budget/_node.md", "- Q3\n")
        seed("Notes.md", "a\n")
        seed("Budget/Notes.md", "b\n")
        seed("Plan.md", "Read [[Plan.md]] and [[Budget]]\n")
        val r = DocumentRegistry(repo, backgroundScope)
        assertEquals(listOf("See [[budget]]", "And [[Budget#Q3|the Q3 part]]", "Read [[Plan.md]] and [[Budget]]"), backlinks(r, "Budget").texts())
        // An ambiguous name links nowhere.
        assertEquals(emptyList(), backlinks(r, "Notes.md").texts())
        // A note's link to itself is from inside the page.
        assertEquals(emptyList(), backlinks(r, "Plan.md").texts())
    }

    @Test
    fun backlinks_follow_renames_and_moves() = runTest {
        seed("_node.md", "- Trip [↳](<Trip/_node.md>)\n- Plans [↳](<Plans/_node.md>)\n- Go on [the trip](lunarbor:/Trip) and [[Trip]]\n")
        seed("Trip/_node.md", "- Pack\n")
        seed("Plans/_node.md", "- x\n")
        val r = DocumentRegistry(repo, backgroundScope)
        assertEquals(1, backlinks(r, "Trip").size)
        // The node moves: the link is rewritten, the wiki name stays and still resolves.
        r.moveForAgent("Trip", "Plans", isNode = true, index = null, newName = null)
        r.refreshVaultListings()
        assertEquals(listOf("Go on the trip and [[Trip]]"), backlinks(r, "Plans/Trip").texts())
        // Renamed, the wiki name no longer resolves; the path link still points there.
        r.moveForAgent("Plans/Trip", "Plans", isNode = true, index = null, newName = "Journey")
        r.refreshVaultListings()
        assertEquals(listOf("Go on the trip and [[Trip]]"), backlinks(r, "Plans/Journey").texts())
    }

    @Test
    fun hidden_lines_are_no_backlinks() = runTest {
        seed("_node.md", "- Budget [↳](<Budget/_node.md>)\n- Ok [b](lunarbor:/Budget)\n- Secret [b](lunarbor:/Budget) #private\n")
        seed("Budget/_node.md", "- x\n")
        seed("diary.md", "#private see [[Budget]]\n")
        val r = DocumentRegistry(repo, backgroundScope)
        r.setPrivacyModes(listOf(PrivacyMode("m1", "Colleagues", listOf("private"))))
        r.setPrivacyMode("m1")
        assertEquals(listOf("Ok b"), backlinks(r, "Budget").texts())
        r.setPrivacyMode(null)
        assertEquals(listOf("Ok b", "Secret b #private", "#private see [[Budget]]"), r.backlinksFlow.value.getValue("Budget").texts())
    }

    @Test
    fun the_pane_lists_the_backlinks_of_its_page() = runTest {
        seed("_node.md", "- Recipes [↳](<Recipes/_node.md>)\n- Leaf\n- [r](lunarbor:/Recipes)\n")
        seed("Recipes/_node.md", "- Soup\n")
        val r = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(r, backgroundScope, "Recipes/_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        assertEquals("Recipes", pane.backlinksTarget())
        pane.backlinksOf(pane.stateFlow.value)
        runCurrent()
        assertEquals(listOf("r"), pane.backlinksOf(pane.stateFlow.value)!!.texts())
        assertEquals(false, pane.stateFlow.value.backlinksCollapsed)
        pane.toggleBacklinksCollapsed()
        assertEquals(true, pane.stateFlow.value.backlinksCollapsed)
        // A zoomed leaf has no folder: nothing can link to it.
        val home = PaneBackingViewModel(r, backgroundScope, "_node.md")
        home.stateFlow.first { it.isLoaded }
        runCurrent()
        home.zoomInto(home.stateFlow.value.lines.indexOf("* Leaf"))
        runCurrent()
        assertNull(home.backlinksTarget())
    }
}
