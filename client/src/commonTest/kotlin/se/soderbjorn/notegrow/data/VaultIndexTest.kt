package se.soderbjorn.notegrow.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VaultIndexTest {

    /**
     * Map-backed disk fake: each entry is a file's lines (with promoted
     * refs already in `* Title` form, mirroring [NoteRepository.parseFileShallow]'s
     * output) plus its `promotedByRow` map.
     */
    private class FakeVault(
        val rootFileName: String = "Root.md",
        val files: MutableMap<String, NoteRepository.Loaded> = mutableMapOf(),
    ) {
        fun put(fileRel: String, lines: List<String>, promoted: Map<Int, PromotedRef> = emptyMap()) {
            files[fileRel] = NoteRepository.Loaded(lines, promoted)
        }

        suspend fun loadFile(fileRel: String): NoteRepository.Loaded =
            files[fileRel] ?: NoteRepository.Loaded(listOf(""), emptyMap())

        suspend fun listAllMdFiles(): List<String> = files.keys.toList()

        fun newIndex(): VaultIndex = VaultIndex(
            loadFromDisk = ::loadFile,
            listAllMdFiles = ::listAllMdFiles,
            rootFileName = rootFileName,
            openDocuments = { emptyMap() },
        )
    }

    private fun ref(fileRel: String) = PromotedRef(fileRel = fileRel, noAutoPromote = false)

    // ---- search ----------------------------------------------------------

    @Test
    fun search_finds_top_level_bullets_of_root_file() = runTest {
        val vault = FakeVault().apply {
            put("Root.md", listOf(
                "* Inbox",
                "* Projects",
                "  * Alpha",
                "* Recipes",
            ))
        }
        val index = vault.newIndex()
        val hits = index.search("Alpha")
        assertEquals(1, hits.size)
        assertEquals("Alpha", hits[0].title)
        assertEquals(listOf("Projects", "Alpha"), hits[0].titlePathFromRoot)
    }

    @Test
    fun search_walks_promoted_ref_boundaries_transparently() = runTest {
        val vault = FakeVault().apply {
            put("Root.md", listOf(
                "* Recipes",
            ), promoted = mapOf(0 to ref("Recipes/Recipes.md")))
            put("Recipes/Recipes.md", listOf(
                "* Pasta",
                "  * Bolognese",
                "* Salads",
            ))
        }
        val index = vault.newIndex()
        val pasta = index.search("Bolognese")
        assertEquals(1, pasta.size)
        assertEquals(listOf("Recipes", "Pasta", "Bolognese"), pasta[0].titlePathFromRoot)
        assertEquals("Recipes/Recipes.md", pasta[0].fileRel)
        assertEquals(listOf("Pasta", "Bolognese"), pasta[0].titlePathInFile)
    }

    @Test
    fun search_returns_empty_for_blank_query() = runTest {
        val vault = FakeVault().apply { put("Root.md", listOf("* Foo")) }
        val index = vault.newIndex()
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
    }

    @Test
    fun search_ranks_earlier_match_position_higher() = runTest {
        val vault = FakeVault().apply {
            put("Root.md", listOf(
                "* My foo recipe",
                "* Foo",
            ))
        }
        val index = vault.newIndex()
        val hits = index.search("foo")
        assertEquals(2, hits.size)
        // Position 0 ("Foo") beats position 3 ("My foo recipe").
        assertEquals("Foo", hits[0].title)
    }

    // ---- resolve: absolute paths ----------------------------------------

    @Test
    fun resolve_absolute_walks_from_root() = runTest {
        val vault = FakeVault().apply {
            put("Root.md", listOf(
                "* Recipes",
            ), promoted = mapOf(0 to ref("Recipes/Recipes.md")))
            put("Recipes/Recipes.md", listOf(
                "* Pasta",
            ))
        }
        val index = vault.newIndex()
        val res = index.resolve(
            url = LinkUrl(listOf("Recipes", "Pasta"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertTrue(res is VaultIndex.Resolution.Found)
        assertEquals("Recipes/Recipes.md", res.fileRel)
        assertEquals(listOf("Pasta"), res.titlePathInFile)
    }

    @Test
    fun resolve_absolute_walks_inline_then_promoted_ref_then_inline() = runTest {
        val vault = FakeVault().apply {
            put("Root.md", listOf(
                "* Top",
                "  * Recipes",   // a promoted-ref nested under "Top"
            ), promoted = mapOf(1 to ref("Recipes/Recipes.md")))
            put("Recipes/Recipes.md", listOf(
                "* Pasta",
                "  * Carbonara",
            ))
        }
        val index = vault.newIndex()
        val res = index.resolve(
            url = LinkUrl(listOf("Top", "Recipes", "Pasta", "Carbonara"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertTrue(res is VaultIndex.Resolution.Found)
        assertEquals("Recipes/Recipes.md", res.fileRel)
        assertEquals(listOf("Pasta", "Carbonara"), res.titlePathInFile)
    }

    // ---- resolve: relative paths ----------------------------------------

    @Test
    fun resolve_relative_single_segment_finds_sibling() = runTest {
        val vault = FakeVault().apply {
            put("Root.md", listOf(
                "* Inbox",
                "* Projects",
            ))
        }
        val index = vault.newIndex()
        val res = index.resolve(
            url = LinkUrl(listOf("Inbox"), isAbsolute = false),
            cursorTitlePath = listOf("Projects"),
        )
        assertTrue(res is VaultIndex.Resolution.Found)
        assertEquals("Root.md", res.fileRel)
        assertEquals(listOf("Inbox"), res.titlePathInFile)
    }

    @Test
    fun resolve_relative_with_dotdot_walks_up() = runTest {
        val vault = FakeVault().apply {
            put("Root.md", listOf(
                "* Top",
                "  * Mid",
                "    * Leaf",
                "* Other",
            ))
        }
        val index = vault.newIndex()
        // From cursor at Top/Mid/Leaf, walk back up two and over to Other.
        val res = index.resolve(
            url = LinkUrl(listOf("..", "..", "Other"), isAbsolute = false),
            cursorTitlePath = listOf("Top", "Mid", "Leaf"),
        )
        assertTrue(res is VaultIndex.Resolution.Found)
        assertEquals(listOf("Other"), res.titlePathInFile)
    }

    @Test
    fun resolve_returns_NotFound_when_path_does_not_match() = runTest {
        val vault = FakeVault().apply {
            put("Root.md", listOf("* Inbox"))
        }
        val index = vault.newIndex()
        val res = index.resolve(
            url = LinkUrl(listOf("Nope"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertEquals(VaultIndex.Resolution.NotFound, res)
    }

    @Test
    fun resolve_is_case_insensitive() = runTest {
        val vault = FakeVault().apply {
            put("Root.md", listOf("* Recipes"))
        }
        val index = vault.newIndex()
        val res = index.resolve(
            url = LinkUrl(listOf("recipes"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertTrue(res is VaultIndex.Resolution.Found)
    }

    @Test
    fun resolve_picks_first_match_in_document_order_on_collision() = runTest {
        val vault = FakeVault().apply {
            put("Root.md", listOf(
                "* Foo",
                "  * inner",
                "* Foo",
                "  * other",
            ))
        }
        val index = vault.newIndex()
        val res = index.resolve(
            url = LinkUrl(listOf("Foo", "inner"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertTrue(res is VaultIndex.Resolution.Found)
        assertEquals(listOf("Foo", "inner"), res.titlePathInFile)
    }

    // ---- promotion / demotion stability --------------------------------
    //
    // The headline guarantee: a link's URL stays the same whether the
    // target lives inline or in its own promoted file. We exercise both
    // shapes with the same URL and assert both resolve to the same node.

    @Test
    fun resolve_same_url_works_inline_and_promoted() = runTest {
        // 1. Inline shape: target lives directly in Root.md.
        val inlineVault = FakeVault().apply {
            put("Root.md", listOf(
                "* Recipes",
                "  * Pasta",
            ))
        }
        val inlineRes = inlineVault.newIndex().resolve(
            url = LinkUrl(listOf("Recipes", "Pasta"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertTrue(inlineRes is VaultIndex.Resolution.Found)

        // 2. Promoted shape: same logical tree, but Recipes is now its own file.
        val promotedVault = FakeVault().apply {
            put("Root.md", listOf(
                "* Recipes",
            ), promoted = mapOf(0 to ref("Recipes/Recipes.md")))
            put("Recipes/Recipes.md", listOf(
                "* Pasta",
            ))
        }
        val promotedRes = promotedVault.newIndex().resolve(
            url = LinkUrl(listOf("Recipes", "Pasta"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertTrue(promotedRes is VaultIndex.Resolution.Found)
        // The user-visible target path inside-its-current-file changes
        // shape (different fileRel, different titlePathInFile) — but
        // crucially, the URL the link carries is unchanged. The view
        // layer will navigate to the right file for each shape.
        assertEquals(listOf("Pasta"), promotedRes.titlePathInFile)
    }

    // ---- loose files ----------------------------------------------------

    @Test
    fun search_finds_loose_files_not_reachable_from_root() = runTest {
        val vault = FakeVault().apply {
            put("Root.md", listOf("* Inbox"))
            put("Starred.md", listOf("* Bookmark"))
            put("subdir/Random.md", listOf("* something"))
        }
        val index = vault.newIndex()
        val starred = index.search("Starred")
        assertEquals(1, starred.size)
        assertEquals("Starred.md", starred[0].fileRel)
        assertEquals(emptyList(), starred[0].titlePathInFile)
        // Bullets inside loose files surface too, prefixed by the file's title.
        val bookmark = index.search("Bookmark").single()
        assertEquals(listOf("Starred", "Bookmark"), bookmark.titlePathFromRoot)
        assertEquals(listOf("Bookmark"), bookmark.titlePathInFile)
        // Files in subdirs are findable by their basename, not their path.
        val random = index.search("Random").single()
        assertEquals("subdir/Random.md", random.fileRel)
        assertEquals(listOf("Random"), random.titlePathFromRoot)
    }

    @Test
    fun resolve_absolute_path_finds_loose_file_root() = runTest {
        val vault = FakeVault().apply {
            put("Root.md", listOf("* Inbox"))
            put("Starred.md", listOf("* Bookmark"))
        }
        val index = vault.newIndex()
        val res = index.resolve(
            url = LinkUrl(listOf("Starred"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertTrue(res is VaultIndex.Resolution.Found)
        assertEquals("Starred.md", res.fileRel)
        assertEquals(emptyList(), res.titlePathInFile)
    }

    @Test
    fun resolve_walks_into_a_loose_files_bullets() = runTest {
        val vault = FakeVault().apply {
            put("Root.md", listOf("* Inbox"))
            put("Starred.md", listOf(
                "* Group",
                "  * Bookmark",
            ))
        }
        val index = vault.newIndex()
        val res = index.resolve(
            url = LinkUrl(listOf("Starred", "Group", "Bookmark"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertTrue(res is VaultIndex.Resolution.Found)
        assertEquals("Starred.md", res.fileRel)
        assertEquals(listOf("Group", "Bookmark"), res.titlePathInFile)
    }

    // ---- shortestUrlFor -------------------------------------------------

    @Test
    fun shortestUrlFor_uses_single_segment_when_target_is_a_sibling() = runTest {
        val vault = FakeVault().apply {
            put("Root.md", listOf(
                "* A",
                "* B",
            ))
        }
        val index = vault.newIndex()
        val target = index.search("A").single()
        val url = index.shortestUrlFor(target, cursorTitlePath = listOf("B"))
        assertEquals(LinkUrl(listOf("A"), isAbsolute = false), url)
    }

    @Test
    fun shortestUrlFor_uses_dotdot_to_escape_upward() = runTest {
        // Build a layout where the relative `../Target` form is strictly
        // shorter than the absolute `/Deep/Outer/Target` form, so the
        // shortest-wins choice unambiguously picks the `..` shape.
        val vault = FakeVault().apply {
            put("Root.md", listOf(
                "* Deep",
                "  * Outer",
                "    * Sub",
                "      * Cursor",
                "    * Target",
            ))
        }
        val index = vault.newIndex()
        val target = index.search("Target").single()
        val url = index.shortestUrlFor(
            target,
            cursorTitlePath = listOf("Deep", "Outer", "Sub", "Cursor"),
        )
        assertEquals(LinkUrl(listOf("..", "Target"), isAbsolute = false), url)
    }

    @Test
    fun shortestUrlFor_falls_back_to_absolute_for_distant_targets() = runTest {
        val vault = FakeVault().apply {
            put("Root.md", listOf(
                "* A",
                "  * B",
                "    * C",
                "      * D",   // depth 4
                "        * E", // depth 5
                "* Far",
            ))
        }
        val index = vault.newIndex()
        val target = index.search("Far").single()
        // Cursor at A/B/C/D/E — that's 4 levels away from the parent of Far.
        val url = index.shortestUrlFor(target, cursorTitlePath = listOf("A", "B", "C", "D", "E"))
        // Capped at MAX_PARENT_HOPS (3); falls back to absolute /Far.
        assertEquals(LinkUrl(listOf("Far"), isAbsolute = true), url)
    }

    // ---- cache invariants ----------------------------------------------

    @Test
    fun invalidate_drops_only_the_named_file() = runTest {
        val vault = FakeVault().apply {
            // Promoted ref so the walk actually reaches the child file.
            put("Root.md", listOf("* A"), promoted = mapOf(0 to ref("A/A.md")))
            put("A/A.md", listOf("* leaf"))
        }
        val readCounts = HashMap<String, Int>()
        val index = VaultIndex(
            loadFromDisk = { fileRel ->
                readCounts[fileRel] = (readCounts[fileRel] ?: 0) + 1
                vault.loadFile(fileRel)
            },
            listAllMdFiles = vault::listAllMdFiles,
            rootFileName = "Root.md",
            openDocuments = { emptyMap() },
        )
        // Prime the cache for both files.
        index.search("leaf")
        val priorRoot = readCounts["Root.md"] ?: 0
        val priorChild = readCounts["A/A.md"] ?: 0
        assertEquals(1, priorRoot)
        assertEquals(1, priorChild)
        // Re-query: cache should serve everything, no extra reads.
        index.search("leaf")
        assertEquals(priorRoot, readCounts["Root.md"])
        assertEquals(priorChild, readCounts["A/A.md"])
        // Invalidate only Root.md; querying re-reads it but not the child.
        index.invalidate("Root.md")
        index.search("leaf")
        assertEquals(priorRoot + 1, readCounts["Root.md"])
        assertEquals(priorChild, readCounts["A/A.md"])
    }
}
