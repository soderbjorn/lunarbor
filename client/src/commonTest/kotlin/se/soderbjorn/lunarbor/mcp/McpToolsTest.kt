/*
 * McpToolsTest.kt (commonTest)
 * ----------------------------
 * Tests for the agent (MCP) surface: the outline text agents read and
 * write ([AgentOutline]), the tools working on a vault through the
 * registry ([McpTools] — reads, edits that follow the folder save rules,
 * refusals that keep nodes from being deleted by accident, create and
 * delete), and the JSON-RPC layer ([McpServer]).
 */

package se.soderbjorn.lunarbor.mcp

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.main.DocumentRegistry
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
        assertEquals("A b", AgentOutline.normalizePath("lunarbor:/A%20b"))
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
    fun a_scoped_connection_reaches_its_folder_and_nothing_outside_it() = runTest {
        seed("_node.md", "- Secret #work\n- Work [↳](<Work/_node.md>)\n- Home [↳](<Home/_node.md>)\n")
        seed("Work/_node.md", "- Budget #work\n- Acme [↳](<Acme/_node.md>)\n")
        seed("Work/Acme/_node.md", "- Call #work\n")
        seed("Home/_node.md", "- Diary #work\n")
        val t = tools()
        suspend fun call(name: String, vararg args: Pair<String, String>) =
            t.call(name, JsonObject(args.associate { (k, v) -> k to JsonPrimitive(v) }), allowEdits = true, folder = "/Work")!!

        // Inside: reads, nested nodes, edits.
        assertFalse(call("read", "path" to "/Work").isError)
        assertFalse(call("read", "path" to "/Work/Acme").isError)
        assertFalse(call("append", "path" to "/Work/Acme", "text" to "* More").isError)
        // Outside: the root, a sibling, lunarbor: links and search scopes are refused.
        for (outside in listOf("/", "/Home", "lunarbor:/Home", "/Workshop")) {
            val r = call("read", "path" to outside)
            assertTrue(r.isError && "outside the folder" in r.text, "$outside: ${r.text}")
        }
        assertTrue(call("search", "query" to "#work in:/").isError)
        assertTrue(call("move", "path" to "/Work/Acme", "to" to "/Home").isError)
        assertTrue(call("delete", "path" to "/Work").isError)
        assertTrue(call("create_file", "folder" to "/Home", "name" to "x", "text" to "y").isError)
        // Defaults cover the folder, not the vault.
        val hits = call("search", "query" to "#work").text
        assertTrue("Budget" in hits && "Call" in hits, hits)
        assertFalse("Secret" in hits || "Diary" in hits, hits)
        val listing = call("list_folder").text
        assertTrue(listing.startsWith("Folder /Work"), listing)
        assertEquals("- Diary #work\n", fs.read(root, "Home/_node.md"))
        // The agent is told where its folder is.
        assertTrue("limited to the folder /Work" in McpServer.instructionsFor("Work"))
        assertEquals(McpServer.INSTRUCTIONS, McpServer.instructionsFor("/"))
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
    fun list_windows_hides_titles_of_windows_outside_the_folder() = runTest {
        val ws = FixedWorkspace(
            listOf(
                AgentWorkspace.Window("w1", "Work/Acme", "Home / Work / Acme", isFocused = true),
                AgentWorkspace.Window("w2", "Home/Diary", "Home / Home / Diary", isFocused = false),
                AgentWorkspace.Window("w3", null, "Home / Secret", isFocused = false),
            ),
        )
        val t = McpTools(DocumentRegistry(repo, backgroundScope), ws)
        val scoped = t.call("list_windows", JsonObject(emptyMap()), allowEdits = true, folder = "/Work")!!.text
        assertTrue("w1: Home / Work / Acme — /Work/Acme (focused)" in scoped, scoped)
        assertTrue("w2: (outside your folder)" in scoped, scoped)
        assertTrue("w3: (outside your folder)" in scoped, scoped)
        assertFalse("Diary" in scoped || "Secret" in scoped, scoped)
        val whole = t.call("list_windows", JsonObject(emptyMap()), allowEdits = true)!!.text
        assertTrue("w2: Home / Home / Diary — /Home/Diary" in whole && "w3: Home / Secret" in whole, whole)
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
        assertEquals("See [ideas](lunarbor:/Projects/Trip%20ideas)\n", fs.read(root, "Plan.md"))
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
