package se.soderbjorn.treefacts.data

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
        val rootFileName: String = "Home.md",
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
            put("Home.md", listOf(
                "* Inbox",
                "* Projects",
                "  * Alpha",
                "    * note",
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
            put("Home.md", listOf(
                "* Recipes",
            ), promoted = mapOf(0 to ref("Recipes/Recipes.md")))
            put("Recipes/Recipes.md", listOf(
                "* Pasta",
                "  * Bolognese",
                "    * notes",
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
        val vault = FakeVault().apply { put("Home.md", listOf("* Foo")) }
        val index = vault.newIndex()
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
    }

    @Test
    fun search_ranks_content_pages_above_external_files() = runTest {
        // "FooContent" has 5 children (content page); "FooFile" is an
        // empty loose file (external file boundary). Both should
        // surface, with the content page first.
        val vault = FakeVault().apply {
            put("Home.md", listOf("* leaf"))
            put("FooContent.md", listOf(
                "* a", "* b", "* c", "* d", "* e",
            ))
            put("FooFile.md", listOf(""))
        }
        val index = vault.newIndex()
        val hits = index.search("Foo")
        assertEquals(2, hits.size)
        assertEquals("FooContent", hits[0].title)
        assertEquals("FooFile", hits[1].title)
    }

    @Test
    fun search_filters_out_leaf_bullets() = runTest {
        // "Foo" inline bullet has zero descendants — should be filtered
        // out. "FooParent" with one child should remain.
        val vault = FakeVault().apply {
            put("Home.md", listOf(
                "* Foo",
                "* FooParent",
                "  * leaf",
            ))
        }
        val index = vault.newIndex()
        val hits = index.search("Foo")
        // Only the bullet with descendants survives. The leaf "Foo" and
        // the inline "leaf" child are both filtered (the latter doesn't
        // match the query anyway).
        assertEquals(listOf("FooParent"), hits.map { it.title })
    }

    @Test
    fun search_marks_promoted_ref_bullets_as_file_boundaries() = runTest {
        val vault = FakeVault().apply {
            put("Home.md", listOf("* Topic"), promoted = mapOf(0 to ref("Topic/Topic.md")))
            put("Topic/Topic.md", listOf("* leaf"))
        }
        val index = vault.newIndex()
        val topic = index.search("Topic").single()
        assertTrue(topic.isFileBoundary, "promoted-ref bullet should be flagged as a file boundary")
        // descendantCount counts everything reachable in the unified tree:
        // Topic itself contains "leaf" as its only child.
        assertEquals(1, topic.descendantCount)
    }

    @Test
    fun search_ranks_earlier_match_position_higher() = runTest {
        // Both bullets need at least one child so they survive the
        // leaf filter — matters here because we're testing position
        // ranking, not the leaf rule.
        val vault = FakeVault().apply {
            put("Home.md", listOf(
                "* My foo recipe",
                "  * a",
                "* Foo",
                "  * a",
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
            put("Home.md", listOf(
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
            put("Home.md", listOf(
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
            put("Home.md", listOf(
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
        assertEquals("Home.md", res.fileRel)
        assertEquals(listOf("Inbox"), res.titlePathInFile)
    }

    @Test
    fun resolve_relative_with_dotdot_walks_up() = runTest {
        val vault = FakeVault().apply {
            put("Home.md", listOf(
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
            put("Home.md", listOf("* Inbox"))
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
            put("Home.md", listOf("* Recipes"))
        }
        val index = vault.newIndex()
        val res = index.resolve(
            url = LinkUrl(listOf("recipes"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertTrue(res is VaultIndex.Resolution.Found)
    }

    @Test
    fun resolve_falls_back_to_unique_suffix_match_for_underqualified_url() = runTest {
        // Mirror the user's vault: Home.md hosts Ämnen, Ämnen.md hosts Teknik,
        // Teknik.md hosts Programmering → Kotlin. The URL omits the leading
        // "Ämnen" segment (a stale link from before reorganisation). With one
        // matching path tail, the fallback should resolve to Kotlin anyway.
        val vault = FakeVault().apply {
            put("Home.md", listOf("* Ämnen"), promoted = mapOf(0 to ref("Ämnen/Ämnen.md")))
            put("Ämnen/Ämnen.md", listOf("* Teknik"), promoted = mapOf(0 to ref("Ämnen/Teknik/Teknik.md")))
            put("Ämnen/Teknik/Teknik.md", listOf(
                "* Programmering",
                "  * Kotlin",
                "    * some stuff",
            ))
        }
        val index = vault.newIndex()
        val res = index.resolve(
            url = LinkUrl(listOf("Teknik", "Programmering", "Kotlin"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertTrue(res is VaultIndex.Resolution.Found)
        assertEquals("Ämnen/Teknik/Teknik.md", res.fileRel)
        assertEquals(listOf("Programmering", "Kotlin"), res.titlePathInFile)
    }

    @Test
    fun resolve_does_not_fall_back_when_suffix_is_ambiguous() = runTest {
        val vault = FakeVault().apply {
            put("Home.md", listOf(
                "* A",
                "  * Leaf",
                "* B",
                "  * Leaf",
            ))
        }
        val index = vault.newIndex()
        // "Leaf" alone is ambiguous — two nodes with the same suffix.
        val res = index.resolve(
            url = LinkUrl(listOf("Mystery", "Leaf"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertEquals(VaultIndex.Resolution.NotFound, res)
    }

    @Test
    fun resolve_picks_first_match_in_document_order_on_collision() = runTest {
        val vault = FakeVault().apply {
            put("Home.md", listOf(
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
        // 1. Inline shape: target lives directly in Home.md.
        val inlineVault = FakeVault().apply {
            put("Home.md", listOf(
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
            put("Home.md", listOf(
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
            put("Home.md", listOf("* Inbox"))
            put("Starred.md", listOf(
                "* Bookmark",
                "  * note",
            ))
            put("subdir/Random.md", listOf("* something"))
        }
        val index = vault.newIndex()
        val starred = index.search("Starred")
        assertEquals(1, starred.size)
        assertEquals("Starred.md", starred[0].fileRel)
        assertEquals(emptyList(), starred[0].titlePathInFile)
        // Bullets inside loose files surface too, prefixed by the file's
        // title path (which now includes the directory chain).
        val bookmark = index.search("Bookmark").single()
        assertEquals(listOf("Starred", "Bookmark"), bookmark.titlePathFromRoot)
        assertEquals(listOf("Bookmark"), bookmark.titlePathInFile)
        // Files in subdirs are findable by basename, but their full
        // title path includes the directory so duplicate basenames in
        // different directories stay disambiguated.
        val random = index.search("Random").single()
        assertEquals("subdir/Random.md", random.fileRel)
        assertEquals(listOf("subdir", "Random"), random.titlePathFromRoot)
    }

    @Test
    fun resolve_absolute_path_finds_loose_file_root() = runTest {
        val vault = FakeVault().apply {
            put("Home.md", listOf("* Inbox"))
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
    fun loose_files_with_same_basename_get_distinct_paths() = runTest {
        // Mirrors the user's vault: two `Framna/Framna.md` files in
        // different directories. Each must resolve independently.
        val vault = FakeVault().apply {
            put("Home.md", listOf("* dummy"))
            put("Framna/Framna.md", listOf("* a", "  * note"))
            put("Work/Framna/Framna.md", listOf("* b", "  * note"))
        }
        val index = vault.newIndex()

        val res1 = index.resolve(
            url = LinkUrl(listOf("Framna"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertTrue(res1 is VaultIndex.Resolution.Found)
        assertEquals("Framna/Framna.md", res1.fileRel)

        val res2 = index.resolve(
            url = LinkUrl(listOf("Work", "Framna"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertTrue(res2 is VaultIndex.Resolution.Found)
        assertEquals("Work/Framna/Framna.md", res2.fileRel)
    }

    @Test
    fun loose_files_walk_into_inner_bullets_via_directory_segments() = runTest {
        // `/Work/Framna/topic` should walk through the synthetic
        // "Work" directory node, into the Framna FileRoot, then to the
        // file's top-level bullet "topic".
        val vault = FakeVault().apply {
            put("Home.md", listOf("* dummy"))
            put("Work/Framna/Framna.md", listOf(
                "* topic",
                "  * detail",
            ))
        }
        val index = vault.newIndex()
        val res = index.resolve(
            url = LinkUrl(listOf("Work", "Framna", "topic"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertTrue(res is VaultIndex.Resolution.Found)
        assertEquals("Work/Framna/Framna.md", res.fileRel)
        assertEquals(listOf("topic"), res.titlePathInFile)
    }

    @Test
    fun suffix_fallback_dedupes_duplicate_bullets_in_same_file() = runTest {
        // The user's `Work/Framna/Framna.md` had two `* [1-on-1s](...)`
        // bullets that both reduced to `* 1-on-1s` after parsing.
        // Without dedupe in the suffix fallback, the under-qualified
        // URL `/Framna/1-on-1s` (omitting the leading "Work" segment)
        // would see two matches and bail. Same target → should resolve.
        val vault = FakeVault().apply {
            put("Home.md", listOf("* dummy"))
            put("Work/Framna/Framna.md", listOf(
                "* 1-on-1s",
                "  * detail",
                "* 1-on-1s",   // duplicate-titled bullet; same in-file path
                "  * detail2",
            ))
        }
        val index = vault.newIndex()
        val res = index.resolve(
            url = LinkUrl(listOf("Framna", "1-on-1s"), isAbsolute = true),
            cursorTitlePath = emptyList(),
        )
        assertTrue(res is VaultIndex.Resolution.Found)
        assertEquals("Work/Framna/Framna.md", res.fileRel)
        assertEquals(listOf("1-on-1s"), res.titlePathInFile)
    }

    @Test
    fun resolve_walks_into_a_loose_files_bullets() = runTest {
        val vault = FakeVault().apply {
            put("Home.md", listOf("* Inbox"))
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
        // Each top-level bullet has a child so it survives the leaf
        // filter — search() needs to surface them for the test.
        val vault = FakeVault().apply {
            put("Home.md", listOf(
                "* A",
                "  * a-child",
                "* B",
                "  * b-child",
            ))
        }
        val index = vault.newIndex()
        val target = index.search("A").single { it.title == "A" }
        val url = index.shortestUrlFor(target, cursorTitlePath = listOf("B"))
        assertEquals(LinkUrl(listOf("A"), isAbsolute = false), url)
    }

    @Test
    fun shortestUrlFor_uses_dotdot_to_escape_upward() = runTest {
        // Build a layout where the relative `../Target` form is strictly
        // shorter than the absolute `/Deep/Outer/Target` form, so the
        // shortest-wins choice unambiguously picks the `..` shape.
        val vault = FakeVault().apply {
            put("Home.md", listOf(
                "* Deep",
                "  * Outer",
                "    * Sub",
                "      * Cursor",
                "    * Target",
                "      * payload",
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
            put("Home.md", listOf(
                "* A",
                "  * B",
                "    * C",
                "      * D",   // depth 4
                "        * E", // depth 5
                "* Far",
                "  * payload",
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
            put("Home.md", listOf("* A"), promoted = mapOf(0 to ref("A/A.md")))
            put("A/A.md", listOf("* leaf"))
        }
        val readCounts = HashMap<String, Int>()
        val index = VaultIndex(
            loadFromDisk = { fileRel ->
                readCounts[fileRel] = (readCounts[fileRel] ?: 0) + 1
                vault.loadFile(fileRel)
            },
            listAllMdFiles = vault::listAllMdFiles,
            rootFileName = "Home.md",
            openDocuments = { emptyMap() },
        )
        // Prime the cache for both files.
        index.search("leaf")
        val priorRoot = readCounts["Home.md"] ?: 0
        val priorChild = readCounts["A/A.md"] ?: 0
        assertEquals(1, priorRoot)
        assertEquals(1, priorChild)
        // Re-query: cache should serve everything, no extra reads.
        index.search("leaf")
        assertEquals(priorRoot, readCounts["Home.md"])
        assertEquals(priorChild, readCounts["A/A.md"])
        // Invalidate only Home.md; querying re-reads it but not the child.
        index.invalidate("Home.md")
        index.search("leaf")
        assertEquals(priorRoot + 1, readCounts["Home.md"])
        assertEquals(priorChild, readCounts["A/A.md"])
    }
}
