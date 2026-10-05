/*
 * McpToolsTest.kt (commonTest)
 * ----------------------------
 * Tests for the agent (MCP) surface: the outline text agents read and
 * write ([AgentOutline]), the tools working on a vault through the
 * registry ([McpTools] — reads, edits that follow the folder save rules,
 * refusals that keep nodes from being deleted by accident, create and
 * delete, privacy scopes, a call stuck on file access timing out, the
 * journal's `today` — LBR-23), and the JSON-RPC layer ([McpServer]).
 */

package se.soderbjorn.lunarbor.mcp

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.PrivacyMode
import se.soderbjorn.lunarbor.main.CalendarDate
import se.soderbjorn.lunarbor.main.DocumentRegistry
import se.soderbjorn.lunarbor.main.PaneBackingViewModel
import se.soderbjorn.lunarbor.platform.FileSystem
import se.soderbjorn.lunarbor.platform.VaultDirectoryEntry
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class McpToolsTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    private fun TestScope.tools(): McpTools = McpTools(DocumentRegistry(repo, backgroundScope))

    private suspend fun McpTools.run(name: String, vararg args: Pair<String, Any>): McpTools.Result =
        call(name, JsonObject(args.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) }), allowEdits = true)!!

    // --------------------------------------------------------- outline text

    @Test
    fun outline_text_round_trips_bullets_blocks_and_node_paths() {
        val text = "* Milk\n* Recipes  <!-- /Recipes -->\n  * Soup\n:::\n**Packing**\n\n- passport\n:::"
        val items = AgentOutline.parse(text)
        assertEquals(3, items.size)
        assertEquals("Recipes", items[1].node)
        assertEquals("Soup", items[1].children.single().text)
        assertEquals(listOf("**Packing**", "", "- passport"), items[2].content)
        assertEquals(text, AgentOutline.format(items))
    }

    @Test
    fun any_line_is_a_bullet_and_unclosed_blocks_are_refused() {
        val items = AgentOutline.parse("- one\nplain line\n+ three\n\t* nested")
        assertEquals(listOf("one", "plain line", "three"), items.map { it.text })
        assertEquals("nested", items[2].children.single().text)
        assertFailsWith<AgentOutline.ParseException> { AgentOutline.parse(":::\nnever closed") }
    }

    @Test
    fun paths_are_normalized_and_cannot_leave_the_vault() {
        assertEquals("Recipes/Soups", AgentOutline.normalizePath("/Recipes/Soups/"))
        assertEquals("A b", AgentOutline.normalizePath("/A b"))
        assertEquals("", AgentOutline.normalizePath("/"))
        assertFailsWith<AgentOutline.ParseException> { AgentOutline.normalizePath("/../etc") }
        assertFailsWith<AgentOutline.ParseException> { AgentOutline.normalizePath("/.trash/x") }
    }

    // ------------------------------------------------------------------ read

    @Test
    fun read_shows_the_nodes_items_paths_and_other_files() = runTest {
        seed("_node.md", "- Milk\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Soup\n")
        seed("Plan.md", "# Plan\n")
        val t = tools()
        val text = t.run("read", "path" to "/").text
        assertTrue("* Milk\n* Recipes  <!-- /Recipes -->" in text, text)
        assertTrue("/Plan.md (note)" in text, text)
        val deep = t.run("read", "path" to "/", "depth" to 2).text
        assertTrue("  * Soup" in deep, deep)
        assertEquals("Note /Plan.md (Markdown)\n\n# Plan\n", t.run("read", "path" to "/Plan.md").text)
        assertTrue(t.run("read", "path" to "/Nope").isError)
    }

    @Test
    fun read_gives_a_nodes_stamps_but_never_its_front_matter() = runTest {
        seed("_node.md", "---\ncreated: 2026-10-04T12:34:56Z\nupdated: 2026-10-04T13:02:11Z\ntags: [home]\n---\n- Milk\n")
        seed("Recipes/_node.md", "- Soup\n")
        val t = tools()
        val text = t.run("read", "path" to "/").text
        assertTrue("Path: /\nCreated: 2026-10-04T12:34:56Z\nUpdated: 2026-10-04T13:02:11Z\n\n* Milk" in text, text)
        assertFalse("tags" in text || "---" in text, text)
        val old = t.run("read", "path" to "/Recipes").text
        assertTrue("Created: unknown\nUpdated: unknown\n" in old, old)
        // An agent edit stamps the node like any other edit (the test clock is 0).
        assertFalse(t.run("append", "path" to "/Recipes", "text" to "* Stew").isError)
        val after = t.run("read", "path" to "/Recipes").text
        assertTrue("Created: unknown\nUpdated: 1970-01-01T00:00:00Z\n" in after, after)
    }

    // ----------------------------------------------------------------- edits

    @Test
    fun retitling_a_node_keeps_and_renames_its_folder() = runTest {
        seed("_node.md", "- Milk\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Soup\n")
        val t = tools()
        val r = t.run("edit", "path" to "/", "old_text" to "* Recipes  <!-- /Recipes -->", "new_text" to "* Cooking  <!-- /Recipes -->")
        assertFalse(r.isError, r.text)
        assertEquals("- Soup\n", fs.read(root, "Cooking/_node.md"))
        assertEquals("- Milk\n- Cooking [↳](<Cooking/_node.md>)\n", fs.read(root, "_node.md"))
        assertTrue("<!-- /Cooking -->" in r.text, r.text)
    }

    @Test
    fun indenting_lines_under_a_bullet_makes_it_a_node() = runTest {
        seed("_node.md", "- Milk\n- Trip\n")
        val t = tools()
        val r = t.run("edit", "path" to "/", "old_text" to "* Trip", "new_text" to "* Trip\n  * Book flights\n  * Pack")
        assertFalse(r.isError, r.text)
        assertEquals("- Book flights\n- Pack\n", fs.read(root, "Trip/_node.md"))
        assertTrue("* Trip  <!-- /Trip -->" in r.text, r.text)
    }

    @Test
    fun dropping_a_node_needs_delete_nodes() = runTest {
        seed("_node.md", "- Milk\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Soup\n")
        val t = tools()
        val refused = t.run("edit", "path" to "/", "old_text" to "\n* Recipes  <!-- /Recipes -->", "new_text" to "")
        assertTrue(refused.isError)
        assertNotNull(fs.read(root, "Recipes/_node.md"))
        val done = t.run("edit", "path" to "/", "old_text" to "\n* Recipes  <!-- /Recipes -->", "new_text" to "", "delete_nodes" to true)
        assertFalse(done.isError, done.text)
        assertNull(fs.read(root, "Recipes/_node.md"))
        assertEquals("- Milk\n", fs.read(root, "_node.md"))
    }

    @Test
    fun edits_must_match_exactly_once_and_cannot_add_to_a_node_from_its_parent() = runTest {
        seed("_node.md", "- A\n- A\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Soup\n")
        val t = tools()
        assertTrue(t.run("edit", "path" to "/", "old_text" to "* A", "new_text" to "* B").isError)
        assertTrue(t.run("edit", "path" to "/", "old_text" to "* Z", "new_text" to "* B").isError)
        val nested = t.run("edit", "path" to "/", "old_text" to "* Recipes  <!-- /Recipes -->", "new_text" to "* Recipes  <!-- /Recipes -->\n  * Stew")
        assertTrue(nested.isError)
        assertEquals("- Soup\n", fs.read(root, "Recipes/_node.md"))
    }

    @Test
    fun append_adds_items_and_note_text() = runTest {
        seed("_node.md", "- Milk\n")
        seed("Plan.md", "# Plan\n")
        val t = tools()
        assertFalse(t.run("append", "path" to "/", "text" to "* Eggs\n:::\nA longer text\n:::").isError)
        assertEquals("- Milk\n- Eggs\n> A longer text\n", fs.read(root, "_node.md"))
        assertFalse(t.run("append", "path" to "/Plan.md", "text" to "More").isError)
        assertEquals("# Plan\nMore\n", fs.read(root, "Plan.md"))
        assertFalse(t.run("edit", "path" to "/Plan.md", "old_text" to "More", "new_text" to "Less").isError)
        assertEquals("# Plan\nLess\n", fs.read(root, "Plan.md"))
    }

    @Test
    fun create_node_create_file_and_delete() = runTest {
        seed("_node.md", "- Milk\n")
        val t = tools()
        val r = t.run("create_node", "parent" to "/", "title" to "Projects", "items" to "* Website\n  * Launch")
        assertFalse(r.isError, r.text)
        assertEquals("- Website [↳](<Website/_node.md>)\n", fs.read(root, "Projects/_node.md"))
        assertEquals("- Launch\n", fs.read(root, "Projects/Website/_node.md"))
        assertFalse(t.run("create_file", "folder" to "/Projects", "name" to "Brief", "text" to "Hello").isError)
        assertEquals("Hello", fs.read(root, "Projects/Brief.md"))
        assertTrue(t.run("create_file", "folder" to "/Projects", "name" to "evil.exe", "text" to "x").isError)
        assertFalse(t.run("delete", "path" to "/Projects/Brief.md").isError)
        assertNull(fs.read(root, "Projects/Brief.md"))
        assertFalse(t.run("delete", "path" to "/Projects").isError)
        assertNull(fs.read(root, "Projects/_node.md"))
        assertEquals("- Milk\n", fs.read(root, "_node.md"))
        assertTrue(t.run("delete", "path" to "/").isError)
    }

    @Test
    fun search_groups_lines_by_node() = runTest {
        seed("_node.md", "- Call Anna #work\n- Work [↳](<Work/_node.md>)\n")
        seed("Work/_node.md", "- Budget #work\n")
        val text = tools().run("search", "query" to "#work").text
        assertTrue(text.startsWith("2 matching lines"), text)
        assertTrue("/Work (Home › Work)\n  - Budget #work" in text, text)
    }

    @Test
    fun edits_are_refused_when_turned_off() = runTest {
        seed("_node.md", "- Milk\n")
        val t = tools()
        val r = t.call("append", JsonObject(mapOf("path" to JsonPrimitive("/"), "text" to JsonPrimitive("* x"))), allowEdits = false)!!
        assertTrue(r.isError)
        assertEquals("- Milk\n", fs.read(root, "_node.md"))
    }

    @Test
    fun a_privacy_scoped_connection_never_sees_what_its_mode_hides() = runTest {
        seed("_node.md", "- Secret #private\n- Work [↳](<Work/_node.md>)\n- Health #Private [↳](<Health/_node.md>)\n- Milk\n")
        seed("Work/_node.md", "- Budget #work\n- Diary #private\n")
        seed("Health/_node.md", "- Pills\n")
        seed("Plan.md", "Ideas #private\n")
        seed("Open.md", "Hello #privateer\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        registry.setPrivacyModes(listOf(PrivacyMode("m1", "Colleagues", listOf("private"))))
        val t = McpTools(registry)
        suspend fun call(name: String, vararg args: Pair<String, Any>) =
            t.call(name, JsonObject(args.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) }), allowEdits = true, privacyModeId = "m1")!!

        // Reading: hidden items, nodes and notes do not exist.
        val rootText = call("read", "path" to "/").text
        assertTrue("* Work  <!-- /Work -->" in rootText && "* Milk" in rootText, rootText)
        assertFalse("Secret" in rootText || "Health" in rootText || "Plan.md" in rootText, rootText)
        assertTrue("/Open.md" in rootText, rootText)
        for (hidden in listOf("/Health", "Health/_node.md", "/Plan.md", "/_privacy.config")) {
            val r = call("read", "path" to hidden)
            assertTrue(r.isError && "Nothing at" in r.text, "$hidden: ${r.text}")
        }
        assertTrue(call("search", "query" to "Pills").text.startsWith("No lines"))
        val work = call("search", "query" to "#work OR #private").text
        assertTrue("Budget" in work && "Diary" !in work && "Secret" !in work, work)
        val tags = call("list_tags").text.lines().map { it.substringBefore(" (").trimStart('#').lowercase() }
        assertTrue("work" in tags && "privateer" in tags && "private" !in tags, tags.toString())
        val listing = call("list_folder", "path" to "/").text
        assertFalse("Health" in listing || "Plan.md" in listing || "_privacy" in listing, listing)

        // Edits keep what the agent never saw, in place.
        assertFalse(call("append", "path" to "/", "text" to "* Eggs").isError)
        assertEquals(
            "- Secret #private\n- Work [↳](<Work/_node.md>)\n- Health #Private [↳](<Health/_node.md>)\n- Milk\n- Eggs\n",
            fs.read(root, "_node.md"),
        )
        assertFalse(call("edit", "path" to "/Work", "old_text" to "* Budget #work", "new_text" to "* Budget 2027 #work").isError)
        assertEquals("- Budget 2027 #work\n- Diary #private\n", fs.read(root, "Work/_node.md"))
        // Read, then rewrite the whole node from what was read: the hidden
        // items survive, where they were.
        val shown = call("read", "path" to "/").text.substringAfter("Path: /\n").substringAfter("\n\n").substringBefore("\n\nAlso in this folder")
        // Dropping /Work would trash the hidden item inside it: refused.
        val dropping = call("edit", "path" to "/", "old_text" to shown, "new_text" to "* Only this now", "delete_nodes" to true)
        assertTrue(dropping.isError && "cannot delete" in dropping.text, dropping.text)
        val rewritten = call("edit", "path" to "/", "old_text" to shown, "new_text" to "* Only this now\n* Work  <!-- /Work -->")
        assertFalse(rewritten.isError, rewritten.text)
        assertEquals(
            "- Secret #private\n- Only this now\n- Health #Private [↳](<Health/_node.md>)\n- Work [↳](<Work/_node.md>)\n",
            fs.read(root, "_node.md"),
        )
        assertEquals("- Pills\n", fs.read(root, "Health/_node.md"))
        // Nothing hidden can be changed, nor taken along by a delete.
        assertTrue(call("delete", "path" to "/Work").isError)
        assertTrue(call("move", "path" to "/Health", "to" to "/Work").isError)
        assertTrue(call("create_file", "folder" to "/Health", "name" to "x", "text" to "y").isError)
        assertEquals("- Pills\n", fs.read(root, "Health/_node.md"))

        // The app's own mode plays no part; an unscoped connection sees everything.
        registry.setPrivacyMode("m1")
        val all = t.call("read", JsonObject(mapOf("path" to JsonPrimitive("/"))), allowEdits = true)!!.text
        assertTrue("Secret" in all && "Health" in all, all)
        // A connection whose mode was deleted is off.
        registry.setPrivacyModes(emptyList())
        val off = call("read", "path" to "/")
        assertTrue(off.isError && "turned off" in off.text, off.text)
        // The agent is never told about privacy.
        assertFalse("privacy" in McpServer.INSTRUCTIONS.lowercase())
    }

    /** A workspace that only lists [windows], in one tab. */
    private class FixedWorkspace(val windows: List<AgentWorkspace.Window>) : AgentWorkspace {
        override suspend fun tabs() = listOf(AgentWorkspace.Tab("t1", "Tab", true, windows))
        override suspend fun openWindow(tabId: String?, path: String) = "!unused"
        override suspend fun showInWindow(windowId: String, path: String): String? = null
        override suspend fun closeWindow(windowId: String): String? = null
        override suspend fun newTab(title: String?, path: String?) = "!unused"
        override suspend fun selectTab(tabId: String): String? = null
        override suspend fun renameTab(tabId: String, title: String): String? = null
        override suspend fun closeTab(tabId: String): String? = null
    }

    @Test
    fun list_windows_hides_windows_the_connections_mode_hides() = runTest {
        seed("_node.md", "- Work [↳](<Work/_node.md>)\n- Health #private [↳](<Health #private/_node.md>)\n- Idea #private\n")
        seed("Work/_node.md", "- Budget\n")
        seed("Health #private/_node.md", "- Pills\n")
        val ws = FixedWorkspace(
            listOf(
                AgentWorkspace.Window("w1", "Work", "Home / Work", isFocused = true),
                AgentWorkspace.Window("w2", "Health #private", "Home / Health #private", isFocused = false),
                AgentWorkspace.Window("w3", "", "Home / Idea #private", isFocused = false),
                AgentWorkspace.Window("w4", null, "Home / Secret", isFocused = false),
            ),
        )
        val registry = DocumentRegistry(repo, backgroundScope)
        registry.setPrivacyModes(listOf(PrivacyMode("m1", "Colleagues", listOf("private"))))
        val t = McpTools(registry, ws)
        val scoped = t.call("list_windows", JsonObject(emptyMap()), allowEdits = true, privacyModeId = "m1")!!.text
        assertTrue("w1: Home / Work — /Work (focused)" in scoped, scoped)
        for (w in listOf("w2", "w3", "w4")) assertTrue("$w: (not available)" in scoped, scoped)
        assertFalse("Health" in scoped || "Idea" in scoped || "Secret" in scoped, scoped)
        val whole = t.call("list_windows", JsonObject(emptyMap()), allowEdits = true)!!.text
        assertTrue("w2: Home / Health #private — /Health #private" in whole && "w4: Home / Secret" in whole, whole)
    }

    @Test
    fun a_zoom_path_resolves_to_the_folder_it_lands_in() = runTest {
        seed("_node.md", "- Main [↳](<Main/_node.md>)\n")
        seed("Main/_node.md", "- **Personal** [↳](<Personal/_node.md>)\n")
        seed("Main/Personal/_node.md", "- Leaf\n> Notes\n> [↳](<Notes/_node.md>)\n")
        seed("Main/Personal/Notes/_node.md", "- Child\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        assertEquals("Main/Personal", registry.folderOfZoomPath("", listOf("main", "**Personal**")))
        assertEquals("Main/Personal/Notes", registry.folderOfZoomPath("", listOf("Main", "**Personal**", "Notes")))
        // A leaf, or a title that is gone, stops where it is stored.
        assertEquals("Main/Personal", registry.folderOfZoomPath("", listOf("Main", "**Personal**", "Leaf")))
        assertEquals("Main", registry.folderOfZoomPath("", listOf("Main", "Gone", "Notes")))
        assertEquals("Main", registry.folderOfZoomPath("Main", emptyList()))
    }

    @Test
    fun move_takes_a_node_with_its_folder_to_another_parent() = runTest {
        seed("_node.md", "- Milk\n- Inbox [↳](<Inbox/_node.md>)\n- Projects [↳](<Projects/_node.md>)\n")
        seed("Inbox/_node.md", "- Trip ideas [↳](<Trip ideas/_node.md>)\n- Other\n")
        seed("Inbox/Trip ideas/_node.md", "- Lisbon\n")
        seed("Projects/_node.md", "- Website\n")
        seed("Plan.md", "See [ideas](lunarbor:/Inbox/Trip%20ideas)\n")
        val t = tools()
        val r = t.run("move", "path" to "/Inbox/Trip ideas", "to" to "/Projects", "position" to 0)
        assertFalse(r.isError, r.text)
        assertEquals("- Lisbon\n", fs.read(root, "Projects/Trip ideas/_node.md"))
        assertEquals("- Trip ideas [↳](<Trip ideas/_node.md>)\n- Website\n", fs.read(root, "Projects/_node.md"))
        assertEquals("- Other\n", fs.read(root, "Inbox/_node.md"))
        assertEquals("See [ideas](Projects/Trip%20ideas/_node.md)\n", fs.read(root, "Plan.md"))
        assertTrue(t.run("move", "path" to "/Projects", "to" to "/Projects/Trip ideas").isError)
    }

    @Test
    fun move_renames_and_relocates_files() = runTest {
        seed("_node.md", "- Projects [↳](<Projects/_node.md>)\n")
        seed("Projects/_node.md", "- Website\n")
        seed("scan.txt", "hello")
        val t = tools()
        val r = t.run("move", "path" to "/scan.txt", "to" to "/Projects", "new_name" to "Scan 1.txt")
        assertFalse(r.isError, r.text)
        assertEquals("hello", fs.read(root, "Projects/Scan 1.txt"))
        assertNull(fs.read(root, "scan.txt"))
    }

    @Test
    fun list_folder_and_reading_any_file() = runTest {
        seed("_node.md", "- Projects [↳](<Projects/_node.md>)\n")
        seed("Projects/_node.md", "- Website\n")
        seed("data.csv", "a,b\n1,2\n")
        seed("Projects/brief.md", "# Brief\n")
        fs.writeBinary("$root/photo.png", byteArrayOf(-119, 80, 78, 71, 0, 1, 2))
        val t = tools()
        val list = t.run("list_folder", "path" to "/", "depth" to 2).text
        assertTrue("- Projects/  (node: Projects)" in list, list)
        assertTrue("  - brief.md  (note" in list, list)
        assertTrue("- photo.png  (image, 7 bytes" in list, list)
        assertTrue("a,b\n1,2" in t.run("read", "path" to "/data.csv").text)
        val img = t.run("read", "path" to "/photo.png")
        assertFalse(img.isError, img.text)
        assertEquals("image", img.extra.single()["type"]!!.jsonPrimitive.content)
        assertTrue(t.run("edit", "path" to "/data.csv", "old_text" to "a", "new_text" to "b").isError)
    }

    // -------------------------------------------------------------- timeout

    /** Answers like [inner], except that reads never come back while [stalled]. */
    private class StallingFileSystem(private val inner: InMemoryFileSystem) : FileSystem by inner {
        var stalled = false

        override suspend fun readFileIfExists(path: String): String? {
            if (stalled) awaitCancellation()
            return inner.readFileIfExists(path)
        }

        override suspend fun listDirectoryEntries(path: String): List<VaultDirectoryEntry> {
            if (stalled) awaitCancellation()
            return inner.listDirectoryEntries(path)
        }
    }

    @Test
    fun a_call_stuck_on_file_access_times_out_and_frees_the_tools() = runTest {
        seed("_node.md", "- Milk\n")
        val stalling = StallingFileSystem(fs)
        val t = McpTools(DocumentRegistry(NoteRepository(stalling, root, nowMillis = { 0L }), backgroundScope))
        stalling.stalled = true
        val stuck = t.run("read", "path" to "/")
        assertTrue(stuck.isError)
        assertTrue("in time" in stuck.text, stuck.text)
        stalling.stalled = false
        // The lock was released: the next call runs normally.
        val text = t.run("read", "path" to "/").text
        assertTrue("* Milk" in text, text)
    }

    // ------------------------------------------------------- journal (LBR-23)

    private val monday = CalendarDate(2026, 10, 5)
    private val dayPath = "Journal/2026/Week 41/2026-10-05 Monday"

    /** Tools whose clock says it is [today] (Monday 2026-10-05 by default). */
    private fun TestScope.journalTools(registry: DocumentRegistry = DocumentRegistry(repo, backgroundScope), today: CalendarDate = monday) =
        McpTools(registry, today = { today })

    private suspend fun McpTools.callAs(name: String, allowEdits: Boolean = true, privacy: String? = null, vararg args: Pair<String, Any>) =
        call(name, JsonObject(args.associate { (k, v) -> k to JsonPrimitive(v.toString()) }), allowEdits, privacy)!!

    @Test
    fun today_gives_the_path_of_today_and_of_a_given_date() = runTest {
        seed("_node.md", "- Milk\n- Journal [↳](<Journal/_node.md>)\n")
        seed("Journal/_node.md", "- 2026 [↳](<2026/_node.md>)\n")
        seed("Journal/2026/_node.md", "- Week 41 [↳](<Week 41/_node.md>)\n")
        seed("Journal/2026/Week 41/_node.md", "- 2026-10-06 Tuesday\n- 2026-10-05 Monday [↳](<2026-10-05 Monday/_node.md>)\n")
        seed("$dayPath/_node.md", "- Called the plumber\n")
        val t = journalTools()
        val before = fs.tree(root)

        val now = t.callAs("today")
        assertFalse(now.isError, now.text)
        assertTrue("Path: /$dayPath\n" in now.text && "today" in now.text, now.text)
        // Read-only connections get the path of an existing day too.
        val readOnly = t.callAs("today", allowEdits = false)
        assertTrue("Path: /$dayPath\n" in readOnly.text, readOnly.text)
        assertTrue("* Called the plumber" in t.callAs("read", args = arrayOf("path" to "/$dayPath")).text)

        // A day without children (no folder yet) still has a path that read
        // and append take; the first child makes it a node.
        val tuesday = t.callAs("today", args = arrayOf("date" to "2026-10-06"))
        assertTrue("Path: /Journal/2026/Week 41/2026-10-06 Tuesday\n" in tuesday.text && "no items yet" in tuesday.text, tuesday.text)
        val empty = t.callAs("read", args = arrayOf("path" to "/Journal/2026/Week 41/2026-10-06 Tuesday"))
        assertFalse(empty.isError, empty.text)
        assertTrue("(no items yet)" in empty.text, empty.text)
        assertEquals(before, fs.tree(root))
        val appended = t.callAs("append", args = arrayOf("path" to "/Journal/2026/Week 41/2026-10-06 Tuesday", "text" to "* Dentist"))
        assertFalse(appended.isError, appended.text)
        assertEquals("- Dentist\n", fs.read(root, "Journal/2026/Week 41/2026-10-06 Tuesday/_node.md"))
        assertTrue("* Dentist" in appended.text, appended.text)

        // ISO weeks: 2025-12-29 is in 2026's week 01. A past day is only
        // looked up, never created.
        val past = t.callAs("today", args = arrayOf("date" to "2025-12-29"))
        assertFalse(past.isError, past.text)
        assertTrue("/Journal/2026/Week 01/2025-12-29 Monday" in past.text && "never created" in past.text, past.text)
        assertNull(fs.read(root, "Journal/2026/Week 01/_node.md"))
        assertTrue(t.callAs("today", args = arrayOf("date" to "2026-02-30")).isError)
        assertTrue(t.callAs("today", args = arrayOf("date" to "next week")).isError)
    }

    @Test
    fun today_creates_the_day_only_when_edits_are_on() = runTest {
        seed("_node.md", "- Milk\n")
        val t = journalTools()

        val off = t.callAs("today", allowEdits = false)
        assertFalse(off.isError, off.text)
        assertTrue("has no journal node yet" in off.text && "/$dayPath" in off.text, off.text)
        assertEquals("- Milk\n", fs.read(root, "_node.md"))
        assertNull(fs.read(root, "Journal/_node.md"))

        val on = t.callAs("today")
        assertFalse(on.isError, on.text)
        assertTrue("Created" in on.text && "Path: /$dayPath\n" in on.text, on.text)
        // Journal last at the root; each item newest first below it.
        assertEquals("- Milk\n- Journal [↳](<Journal/_node.md>)\n", fs.read(root, "_node.md"))
        assertEquals("- 2026 [↳](<2026/_node.md>)\n", fs.read(root, "Journal/_node.md"))
        assertEquals("- 2026-10-05 Monday\n", fs.read(root, "Journal/2026/Week 41/_node.md"))
        // Called again: found, not made twice.
        assertTrue("It exists" in t.callAs("today").text)
        assertEquals("- 2026-10-05 Monday\n", fs.read(root, "Journal/2026/Week 41/_node.md"))

        // The agent writes to it at once.
        assertFalse(t.callAs("append", args = arrayOf("path" to "/$dayPath", "text" to "* Called the plumber")).isError)
        assertEquals("- Called the plumber\n", fs.read(root, "$dayPath/_node.md"))

        // A future day may be created; it goes first in its week.
        val wed = t.callAs("today", args = arrayOf("date" to "2026-10-07"))
        assertTrue("Created" in wed.text, wed.text)
        assertEquals(
            "- 2026-10-07 Wednesday\n- 2026-10-05 Monday [↳](<2026-10-05 Monday/_node.md>)\n",
            fs.read(root, "Journal/2026/Week 41/_node.md"),
        )
        // Read-only: a missing future day is not created either.
        assertTrue("not created" in t.callAs("today", allowEdits = false, args = arrayOf("date" to "2026-10-12")).text)
        assertNull(fs.read(root, "Journal/2026/Week 42/_node.md"))
    }

    @Test
    fun a_new_day_gets_the_daily_template() = runTest {
        seed("_node.md", "- Templates [↳](<Templates/_node.md>)\n")
        seed("Templates/_node.md", "- Daily [↳](<Daily/_node.md>)\n")
        seed("Templates/Daily/_node.md", "- Open tasks {{search: #todo is:open in:/Journal}}\n- Meetings [↳](<Meetings/_node.md>)\n")
        seed("Templates/Daily/Meetings/_node.md", "- standup\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        registry.dailyTemplate.set("Templates/Daily")
        val t = journalTools(registry)

        val r = t.callAs("today")
        assertFalse(r.isError, r.text)
        assertTrue("from the daily template" in r.text, r.text)
        assertEquals(
            "- Open tasks {{search: #todo is:open in:/Journal}}\n- Meetings [↳](<Meetings/_node.md>)\n",
            fs.read(root, "$dayPath/_node.md"),
        )
        assertEquals("- standup\n", fs.read(root, "$dayPath/Meetings/_node.md"))
        // The template is untouched.
        assertEquals("- standup\n", fs.read(root, "Templates/Daily/Meetings/_node.md"))
    }

    @Test
    fun a_day_a_pane_prepared_is_committed_in_place() = runTest {
        seed("_node.md", "- Milk\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        assertEquals(PaneBackingViewModel.TodayOutcome.OPENED, pane.navigateToToday(monday))
        runCurrent()
        val linesBefore = pane.stateFlow.value.lines
        val idsBefore = pane.stateFlow.value.documentState!!.lineIds
        assertNull(fs.read(root, "Journal/_node.md"))

        val r = journalTools(registry).callAs("today")
        assertFalse(r.isError, r.text)
        runCurrent()
        // The pane's rows are the real day now: same rows, same ids, saved.
        val s = pane.stateFlow.value
        assertEquals(linesBefore, s.lines)
        assertEquals(idsBefore, s.documentState!!.lineIds)
        val doc = registry.acquire("_node.md")
        assertTrue(s.documentState!!.lineIds.none { doc.isPending(it) })
        registry.release("_node.md")
        assertEquals("- 2026-10-05 Monday\n", fs.read(root, "Journal/2026/Week 41/_node.md"))
        assertEquals(1, linesBefore.count { "2026-10-05 Monday" in it })
    }

    @Test
    fun a_scope_hiding_the_journal_finds_and_creates_nothing() = runTest {
        seed("_node.md", "- Milk\n- Journal #private [↳](<Journal/_node.md>)\n")
        seed("Journal/_node.md", "- 2025 [↳](<2025/_node.md>)\n")
        seed("Journal/2025/_node.md", "- Week 52\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        registry.setPrivacyModes(listOf(PrivacyMode("m1", "Colleagues", listOf("private"))))
        val t = journalTools(registry)

        val r = t.callAs("today", privacy = "m1")
        assertTrue(r.isError && "Nothing at /$dayPath" in r.text, r.text)
        assertEquals("- 2025 [↳](<2025/_node.md>)\n", fs.read(root, "Journal/_node.md"))
        assertNull(fs.read(root, "Journal/2026/_node.md"))
        // Without the scope the same call creates it.
        assertTrue("Created" in t.callAs("today").text)
    }

    // ---------------------------------------------------------------- server

    @Test
    fun server_answers_initialize_and_lists_only_reading_tools_when_edits_are_off() = runTest {
        val server = McpServer(tools())
        val init = Json.parseToJsonElement(
            server.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""", true)!!,
        ).jsonObject["result"]!!.jsonObject
        assertEquals("2025-03-26", init["protocolVersion"]!!.jsonPrimitive.content)
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", true))
        suspend fun names(allow: Boolean) =
            Json.parseToJsonElement(server.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", allow)!!)
                .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        val all = names(true)
        val reading = names(false)
        assertTrue("edit" in all)
        assertFalse("edit" in reading)
        assertTrue("read" in reading)
        // No workspace: no window tools.
        assertFalse("open_window" in all)
    }
}
