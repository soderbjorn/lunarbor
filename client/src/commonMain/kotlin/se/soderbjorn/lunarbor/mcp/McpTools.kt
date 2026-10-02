/*
 * McpTools.kt (commonMain)
 * ------------------------
 * The tools Lunarbor offers AI agents over MCP (see McpServer.kt): read a
 * node or note, search, list tags, and — when the user allows edits — edit
 * a node or note, append to one, and create a note.
 *
 * The tools work on the live app, not behind its back: reads see unsaved
 * edits (every open document is saved first), and every edit runs through
 * the file's `Document` (`DocumentRegistry.editForAgent`) — the same
 * primitives and save rules as typing: a bullet that gets children becomes
 * a folder, a retitled node renames its folder, a deleted node goes to the
 * trash, links are rewritten, and a pane showing the node updates at once.
 *
 * Agents see nodes as outline text ([AgentOutline]): one node's own items,
 * with `<!-- /path -->` marking the items that are nodes themselves. An
 * edit replaces one exact snippet of that text (like a text editor's
 * search-and-replace) and the result is diffed back onto the document's
 * rows, so untouched items keep their identity and nested nodes their
 * folders.
 *
 * A connection may be scoped to one folder of the vault (App settings →
 * Agent access, one key per connection): every path a tool takes must then
 * be that folder or inside it ([checkScope]), and the defaults that would
 * mean "the whole vault" mean that folder instead. Paths stay vault paths,
 * so `lunarbor:` links read and written by the agent mean the same everywhere.
 *
 * commonMain only. Calls are serialized: one tool runs at a time.
 */

package se.soderbjorn.lunarbor.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import se.soderbjorn.lunarbor.data.FolderName
import se.soderbjorn.lunarbor.data.NodeLine
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.SearchQuery
import se.soderbjorn.lunarbor.data.TextScope
import se.soderbjorn.lunarbor.data.VaultEntryKind
import se.soderbjorn.lunarbor.main.BlockLayout
import se.soderbjorn.lunarbor.main.Document
import se.soderbjorn.lunarbor.main.DocumentLayout
import se.soderbjorn.lunarbor.main.DocumentRegistry
import se.soderbjorn.lunarbor.main.FolderContents
import se.soderbjorn.lunarbor.main.LineId
import se.soderbjorn.lunarbor.main.PaneBackingViewModel

/**
 * The MCP tool table and handlers.
 *
 * ### Callers
 * - [McpServer] lists [toolsFor] and dispatches `tools/call` to [call].
 *
 * @param registry The app's [DocumentRegistry]: every read and write goes
 *   through it, so agents and panes share one view of the vault.
 * @param workspace The app's windows and tabs, or `null` on a platform
 *   without them (the window tools are then not offered).
 */
class McpTools(private val registry: DocumentRegistry, private val workspace: AgentWorkspace? = null) {

    /**
     * One tool as `tools/list` describes it.
     *
     * @property name Tool name.
     * @property description What it does, for the agent.
     * @property inputSchema JSON Schema of its arguments.
     * @property writes `true` for a tool that changes the vault; offered
     *   only when edits are allowed.
     * @property windows `true` for a window / tab tool; offered only with
     *   a [workspace].
     */
    class Tool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject,
        val writes: Boolean = false,
        val windows: Boolean = false,
    )

    /**
     * What a call returns: text for the agent, flagged as an error when
     * the call was refused or failed.
     *
     * @property extra Further MCP content blocks after the text: an
     *   `image` block (`read` on an image) or an embedded `resource` with a
     *   base64 `blob` (`read` on another binary file).
     */
    data class Result(val text: String, val isError: Boolean = false, val extra: List<JsonObject> = emptyList())

    /** A refusal the agent can act on; its message is shown as the result. */
    private class Refusal(message: String) : Exception(message)

    /** One tool at a time: edits never interleave with each other or with reads. */
    private val lock = Mutex()

    /** Content blocks the running tool adds to its result ([Result.extra]); used under [lock]. */
    private val extra = ArrayList<JsonObject>()

    /**
     * The folder the running call's connection is limited to (vault-relative,
     * `""` for the whole vault); set by [call] under [lock].
     */
    private var scope = ""

    /** Every tool, in the order `tools/list` shows them. */
    val tools: List<Tool> = listOf(
        Tool(
            name = "read",
            description = "Read a node (its outline items and the other files in its folder) or any file. " +
                "Start with path \"/\", the vault's root node. Items that are nodes themselves end in " +
                "<!-- /path -->; read that path to go inside. Use the text exactly as shown for edit's old_text. " +
                "Markdown notes and other text files come back as text, images as images, and other files (PDFs, " +
                "archives, …) as base64 data, up to 10 MB.",
            inputSchema = schema(
                "path" to stringProp("Vault path of a node folder or a file, e.g. \"/\", \"/Recipes/Soups\", \"/Recipes/Plan.md\", \"/Recipes/photo.jpg\". lunarbor:/ links work too."),
                "depth" to intProp("How many levels of nested nodes to include (1-5, default 1). Edits only ever match the node's own items (depth 1)."),
                required = listOf("path"),
            ),
        ),
        Tool(
            name = "search",
            description = "Find lines anywhere in the vault: bullet titles, block lines and note lines. Words and \"phrases\" " +
                "match parts of a line, #tag a whole tag and #tag* a tag prefix (a tag on a parent counts for everything " +
                "under it); a space or AND joins, OR alternates, NOT or -word negates, parentheses group. Results are grouped " +
                "by the node or note they are in, with its path.",
            inputSchema = schema(
                "query" to stringProp("The search expression, e.g. \"#todo -#done\" or \"budget OR invoice\"."),
                "path" to stringProp("Only search this node's tree, or this one note (default: the whole vault)."),
                "limit" to intProp("Most lines to return (default 50, at most 300)."),
                required = listOf("query"),
            ),
        ),
        Tool(
            name = "list_folder",
            description = "List what is in a folder on disk: its subfolders (saying which are nodes) and files, with " +
                "sizes and dates, optionally a few levels deep. Unlike read it lists everything, items' folders included.",
            inputSchema = schema(
                "path" to stringProp("The folder (default \"/\", the vault root)."),
                "depth" to intProp("How many levels of subfolders to list (1-5, default 1)."),
            ),
        ),
        Tool(
            name = "list_tags",
            description = "List the #tags used in a tree, most used first, with how many lines carry each.",
            inputSchema = schema(
                "path" to stringProp("Node whose tree to look in (default: the whole vault)."),
            ),
        ),
        Tool(
            name = "edit",
            description = "Change a node's own items, or a note's text, by replacing one exact snippet of what read shows. " +
                "old_text must match exactly one place. In a node, new_text is outline text: \"* \" bullets, nested 2 spaces " +
                "deeper to make children (a bullet with children becomes a node), and ::: fenced blocks for longer Markdown. " +
                "Keep each <!-- /path --> on its line to keep that node (you may retitle or move it); removing such a line " +
                "deletes the node and everything in it, which needs delete_nodes: true. To change what is inside a nested " +
                "node, edit that node's own path. Returns the node as it is after the edit.",
            inputSchema = schema(
                "path" to stringProp("The node or .md note to edit."),
                "old_text" to stringProp("Exact text to replace, copied from read (whole lines are safest)."),
                "new_text" to stringProp("The replacement (empty to remove the lines)."),
                "delete_nodes" to boolProp("Set to true to confirm that items with <!-- /path --> may be deleted with all their content."),
                required = listOf("path", "old_text", "new_text"),
            ),
            writes = true,
        ),
        Tool(
            name = "append",
            description = "Add items at the end of a node (outline text, as for edit, without <!-- --> paths), or text at the " +
                "end of a note. To add children to an existing bullet, use edit and indent the new lines under it.",
            inputSchema = schema(
                "path" to stringProp("The node or .md note to add to."),
                "text" to stringProp("Outline text for a node (\"* \" bullets, indented children, ::: blocks), plain text for a note."),
                required = listOf("path", "text"),
            ),
            writes = true,
        ),
        Tool(
            name = "create_node",
            description = "Add a new node: a bullet titled title at the end of parent, with items (outline text) as its " +
                "children. A node with children is stored as a folder of its own (named after the title), so this is also " +
                "how you make a new folder; a node without children is just a bullet. Returns the parent with the new " +
                "node's path. Put structured content here — lists, steps, notes, facts — one idea per bullet, nested.",
            inputSchema = schema(
                "parent" to stringProp("The node to add it to, e.g. \"/\" or \"/Projects\"."),
                "title" to stringProp("The new node's title (inline Markdown and #tags allowed)."),
                "items" to stringProp("Its children as outline text: \"* \" bullets, indented sub-items, ::: blocks. Optional."),
                required = listOf("parent", "title"),
            ),
            writes = true,
        ),
        Tool(
            name = "create_file",
            description = "Create a text file in a node's folder: a Markdown note (name without an extension, or ending " +
                "in .md) or another text file (.txt, .csv, .json, …). Use it only for standalone documents or data an " +
                "outline cannot hold; prefer create_node for anything that is notes, lists or structure.",
            inputSchema = schema(
                "folder" to stringProp("The node folder to put it in, e.g. \"/Recipes\" (\"/\" for the vault root)."),
                "name" to stringProp("The file name; .md is added when there is no extension. A name that is taken gets \" (2)\"."),
                "text" to stringProp("The file's contents."),
                required = listOf("folder", "name", "text"),
            ),
            writes = true,
        ),
        Tool(
            name = "move",
            description = "Move a node (with everything in it), a file or a folder to another node or folder, or put a " +
                "node at another position among its parent's items. A node keeps its children and attachments; links to it " +
                "are updated. To move a bullet that has no path (no children), use edit: remove its line in one node and " +
                "add it in the other.",
            inputSchema = schema(
                "path" to stringProp("What to move, e.g. \"/Inbox/Trip ideas\" or \"/Inbox/scan.pdf\"."),
                "to" to stringProp("The node or folder to move it into, e.g. \"/Projects\" (\"/\" for the root). Its own parent to only reorder or rename."),
                "position" to intProp("For a node: its place among the new parent's items, 0 for first (default: last)."),
                "new_name" to stringProp("A new title for a node, or a new file / folder name. Optional."),
                required = listOf("path", "to"),
            ),
            writes = true,
        ),
        Tool(
            name = "delete",
            description = "Delete a node (with everything in it), a note, a file or a folder, by path. It goes to the " +
                "vault's .trash folder, from where the user can restore it. A node's files that are not Lunarbor's own " +
                "stay where they are. To delete a bullet that has no path (no children), use edit and remove its line.",
            inputSchema = schema(
                "path" to stringProp("What to delete, e.g. \"/Projects/Old plan\" or \"/Recipes/notes.md\"."),
                required = listOf("path"),
            ),
            writes = true,
        ),
        Tool(
            name = "list_windows",
            description = "List the open tabs and their windows, with what each window shows. Ids are for the other window tools.",
            inputSchema = schema(),
            windows = true,
        ),
        Tool(
            name = "open_window",
            description = "Open a new window on a node or note, e.g. to show the user what you changed. Returns its id.",
            inputSchema = schema(
                "path" to stringProp("The node or file to show."),
                "tab_id" to stringProp("The tab to open it in (default: the active tab)."),
                required = listOf("path"),
            ),
            windows = true,
        ),
        Tool(
            name = "show_in_window",
            description = "Point an open window at another node or note, as clicking a link there would (the user can go Back).",
            inputSchema = schema(
                "window_id" to stringProp("The window, from list_windows."),
                "path" to stringProp("The node or file to show."),
                required = listOf("window_id", "path"),
            ),
            windows = true,
        ),
        Tool(
            name = "close_window",
            description = "Close a window. Nothing is deleted; closing a tab's last window closes the tab.",
            inputSchema = schema("window_id" to stringProp("The window, from list_windows."), required = listOf("window_id")),
            windows = true,
        ),
        Tool(
            name = "new_tab",
            description = "Add a tab with one window and switch to it.",
            inputSchema = schema(
                "title" to stringProp("The tab's label (default \"Untitled\")."),
                "path" to stringProp("What its window shows (default: the root node)."),
            ),
            windows = true,
        ),
        Tool(
            name = "select_tab",
            description = "Switch to a tab.",
            inputSchema = schema("tab_id" to stringProp("The tab, from list_windows."), required = listOf("tab_id")),
            windows = true,
        ),
        Tool(
            name = "rename_tab",
            description = "Rename a tab.",
            inputSchema = schema(
                "tab_id" to stringProp("The tab, from list_windows."),
                "title" to stringProp("The new label."),
                required = listOf("tab_id", "title"),
            ),
            windows = true,
        ),
        Tool(
            name = "close_tab",
            description = "Close a tab and its windows (nothing is deleted). The last tab cannot be closed.",
            inputSchema = schema("tab_id" to stringProp("The tab, from list_windows."), required = listOf("tab_id")),
            windows = true,
        ),
    )

    /**
     * The tools offered: all of them, less the ones that change the vault
     * when [allowEdits] is off, and less the window tools without a
     * [workspace].
     */
    fun toolsFor(allowEdits: Boolean): List<Tool> =
        tools.filter { (allowEdits || !it.writes) && (workspace != null || !it.windows) }

    /**
     * Runs the tool [name] with [args]. Never throws: refusals and
     * failures come back as error results the agent can read.
     *
     * Called by [McpServer] for `tools/call`.
     *
     * @param allowEdits Whether the user lets agents change the vault;
     *   write tools are refused when not.
     * @param folder The folder the connection is limited to (vault path,
     *   `""` or `"/"` for the whole vault); paths outside it are refused.
     * @return `null` when no tool has that name.
     */
    suspend fun call(name: String, args: JsonObject, allowEdits: Boolean, folder: String = ""): Result? {
        val tool = tools.firstOrNull { it.name == name && (workspace != null || !it.windows) } ?: return null
        if (tool.writes && !allowEdits) {
            return Result("Edits are turned off in Lunarbor's settings (Agent access). Only reading and searching are allowed.", true)
        }
        return lock.withLock {
            extra.clear()
            try {
                scope = AgentOutline.normalizePath(folder)
                val text = cap(run(name, args))
                Result(text, extra = extra.toList())
            } catch (e: Refusal) {
                Result(e.message ?: "Refused", true)
            } catch (e: AgentOutline.ParseException) {
                Result(e.message ?: "Could not read the text", true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                println("[lunarbor] MCP tool $name failed: $e")
                Result("That failed inside Lunarbor: ${e.message ?: e.toString()}", true)
            }
        }
    }

    private suspend fun run(name: String, args: JsonObject): String = when (name) {
        "read" -> read(path(args, "path"), (args.int("depth") ?: 1).coerceIn(1, MAX_DEPTH))
        "search" -> search(args.string("query").orEmpty(), args.string("path"), (args.int("limit") ?: 50).coerceIn(1, 300))
        "list_folder" -> listFolder(optionalPath(args, "path"), (args.int("depth") ?: 1).coerceIn(1, MAX_DEPTH))
        "move" -> move(path(args, "path"), path(args, "to"), args.int("position"), args.string("new_name"))
        "list_tags" -> listTags(optionalPath(args, "path"))
        "edit" -> edit(path(args, "path"), required(args, "old_text"), required(args, "new_text"), args.bool("delete_nodes") == true)
        "append" -> append(path(args, "path"), required(args, "text"))
        "create_node" -> createNode(path(args, "parent"), required(args, "title"), args.string("items").orEmpty())
        "create_file" -> createFile(path(args, "folder"), required(args, "name"), args.string("text").orEmpty())
        "delete" -> delete(path(args, "path"))
        "list_windows" -> listWindows()
        "open_window" -> windowResult(ws().openWindow(args.string("tab_id"), existing(path(args, "path"))), "Opened window")
        "show_in_window" -> done(ws().showInWindow(required(args, "window_id"), existing(path(args, "path"))))
        "close_window" -> done(ws().closeWindow(required(args, "window_id")))
        "new_tab" -> windowResult(
            ws().newTab(args.string("title"), (args.string("path")?.let { checkScope(AgentOutline.normalizePath(it)) } ?: scope.ifEmpty { null })?.let { existing(it) }),
            "Opened tab",
        )
        "select_tab" -> done(ws().selectTab(required(args, "tab_id")))
        "rename_tab" -> done(ws().renameTab(required(args, "tab_id"), required(args, "title")))
        "close_tab" -> done(ws().closeTab(required(args, "tab_id")))
        else -> throw Refusal("No such tool: $name")
    }

    // ------------------------------------------------------------------ read

    private suspend fun read(rel: String, depth: Int): String {
        registry.flushAll()
        return when (val target = targetOf(rel)) {
            is Target.Node -> {
                val items = diskItems(target.folder, depth)
                buildString {
                    append(nodeHeader(target.folder))
                    append(if (items.isEmpty()) "(no items yet)" else AgentOutline.format(items))
                    append(folderContents(target.folder))
                }
            }
            is Target.Note -> {
                val text = registry.readNoteText(target.fileRel).orEmpty()
                "Note ${display(target.fileRel)} (Markdown)\n\n" + text.ifEmpty { "(empty)" }
            }
            is Target.File -> readFile(target.fileRel)
        }
    }

    /**
     * Reads any file that is not an editable note: text as text, images as
     * an `image` block, anything else as a base64 `resource` block (see
     * [Result.extra]). Files over [MAX_FILE_BYTES] are described, not read.
     */
    private suspend fun readFile(rel: String): String {
        val size = registry.fileSizeOf(rel) ?: throw Refusal("${display(rel)} could not be read.")
        val name = rel.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        val header = "File ${display(rel)} (${formatSize(size)})"
        if (size > MAX_FILE_BYTES) return "$header is larger than ${formatSize(MAX_FILE_BYTES)}, too large to read here."
        if (ext in TEXT_EXTENSIONS || ext == "svg") {
            return "$header\n\n" + registry.readTextFile(rel).orEmpty()
        }
        val bytes = registry.readFileBytes(rel) ?: throw Refusal("${display(rel)} could not be read.")
        if (looksLikeText(bytes)) return "$header\n\n" + bytes.decodeToString()
        val mime = MIME_TYPES[ext] ?: "application/octet-stream"
        val data = kotlin.io.encoding.Base64.Default.encode(bytes)
        if (mime.startsWith("image/")) {
            extra += buildJsonObject {
                put("type", "image")
                put("data", data)
                put("mimeType", mime)
            }
            return "$header, $mime — the image follows."
        }
        extra += buildJsonObject {
            put("type", "resource")
            putJsonObject("resource") {
                put("uri", "lunarbor:${display(rel)}")
                put("mimeType", mime)
                put("blob", data)
            }
        }
        return "$header, $mime — its bytes follow as base64."
    }

    /** `true` for bytes that are UTF-8 text: no NUL bytes in the first 8 KB, and they decode cleanly. */
    private fun looksLikeText(bytes: ByteArray): Boolean {
        val head = if (bytes.size > 8192) bytes.copyOf(8192) else bytes
        if (head.any { it == 0.toByte() }) return false
        return try { bytes.decodeToString(throwOnInvalidSequence = true); true } catch (_: Exception) { false }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes bytes"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> "${((bytes * 10) / (1024 * 1024)) / 10.0} MB"
    }

    private suspend fun listFolder(folder: String, depth: Int): String {
        if (folder.isNotEmpty() && registry.kindOf(folder) != VaultEntryKind.FOLDER) throw Refusal("There is no folder at ${display(folder)}.")
        registry.flushAll()
        val sb = StringBuilder("Folder ${display(folder)}\n")
        var count = 0
        suspend fun walk(dir: String, level: Int) {
            val entries = registry.listFolder(dir)
                .filter { !it.pathRel.substringAfterLast('/').startsWith(".") }
                .sortedWith(compareByDescending<se.soderbjorn.lunarbor.data.VaultEntry> { it.isDirectory }.then { a, b -> FolderContents.naturalCompare(a.name, b.name) })
            val pad = "  ".repeat(level)
            for (e in entries) {
                if (++count > MAX_LIST_ENTRIES) return
                val fileName = e.pathRel.substringAfterLast('/')
                sb.append(pad).append("- ")
                if (e.isDirectory) {
                    sb.append(fileName).append("/  ")
                    sb.append(if (e.isReferenced) "(node: ${nodeTitle(e.pathRel)})" else "(folder)")
                    sb.append('\n')
                    if (level + 1 < depth) walk(e.pathRel, level + 1)
                } else {
                    sb.append(fileName)
                    // App files (outlines, Starred.md) are not listed.
                    val what = e.kind.name.lowercase().replace("markdown", "note")
                    sb.append("  (").append(what)
                    registry.fileSizeOf(e.pathRel)?.let { sb.append(", ").append(formatSize(it)) }
                    if (e.lastEditedMs > 0) sb.append(", changed ").append(isoDate(e.lastEditedMs))
                    sb.append(")\n")
                }
            }
        }
        walk(folder, 0)
        if (count == 0) sb.append("(empty)")
        if (count > MAX_LIST_ENTRIES) sb.append("\n[Stopped after $MAX_LIST_ENTRIES entries; list a subfolder or use a smaller depth.]")
        return sb.toString().trimEnd()
    }

    /** `YYYY-MM-DD HH:MM` (UTC) for [ms]. */
    private fun isoDate(ms: Long): String =
        kotlin.time.Instant.fromEpochMilliseconds(ms).toString().take(16).replace('T', ' ')

    /** `# Title` and `Path: /…` heading a node's text. */
    private suspend fun nodeHeader(folder: String): String = "# ${nodeTitle(folder)}\nPath: ${display(folder)}\n\n"

    /** The node's items as on disk, nested nodes read [depth] − 1 levels down. */
    private suspend fun diskItems(folder: String, depth: Int): List<AgentOutline.Item> =
        registry.nodeItemsOf(folder).map { line ->
            when (line) {
                is NodeLine.Leaf -> AgentOutline.Item(AgentOutline.Kind.BULLET, line.title)
                is NodeLine.Folder -> {
                    val child = join(folder, line.folder)
                    AgentOutline.Item(
                        AgentOutline.Kind.BULLET, line.title, node = child,
                        children = if (depth > 1) diskItems(child, depth - 1) else emptyList(),
                    )
                }
                is NodeLine.Block -> {
                    val child = line.folder?.let { join(folder, it) }
                    AgentOutline.Item(
                        AgentOutline.Kind.BLOCK, content = line.content, node = child,
                        children = if (child != null && depth > 1) diskItems(child, depth - 1) else emptyList(),
                    )
                }
                is NodeLine.Text -> AgentOutline.Item(AgentOutline.Kind.TEXT, line.raw)
            }
        }

    /** The title of the node at [folder]: its item's text in the parent's outline. */
    private suspend fun nodeTitle(folder: String): String {
        if (folder.isEmpty()) return NoteRepository.ROOT_DISPLAY_NAME
        val parent = folder.substringBeforeLast('/', "")
        val name = folder.substringAfterLast('/')
        for (line in registry.nodeItemsOf(parent)) {
            when {
                line is NodeLine.Folder && line.folder.equals(name, ignoreCase = true) -> return line.title
                line is NodeLine.Block && line.folder.equals(name, ignoreCase = true) -> return line.title
            }
        }
        return FolderName.decode(name)
    }

    /** The "Also in this folder" list: files and folders that are not items. */
    private suspend fun folderContents(folder: String): String {
        val entries = FolderContents.visible(registry.listFolder(folder))
        if (entries.isEmpty()) return ""
        return buildString {
            append("\n\nAlso in this folder (files, not items):")
            for (e in entries) {
                val what = when (e.kind) {
                    VaultEntryKind.FOLDER -> "folder"
                    VaultEntryKind.MARKDOWN -> "note"
                    VaultEntryKind.IMAGE -> "image"
                    VaultEntryKind.DRAWING -> "Excalidraw drawing"
                    VaultEntryKind.HTML -> "web page"
                    VaultEntryKind.FILE -> "file"
                }
                append("\n- ").append(display(e.pathRel)).append(" (").append(what).append(')')
            }
        }
    }

    // ---------------------------------------------------------------- search

    private suspend fun search(query: String, path: String?, limit: Int): String {
        val parsed = SearchQuery.parse(query)
        if (parsed.isEmpty) throw Refusal("The query has no words or tags to search for.")
        val scopeRel = (parsed.scopePath ?: path)?.let { checkScope(AgentOutline.normalizePath(it)) } ?: scope
        val scope = if (scopeRel.endsWith(NoteRepository.NOTE_EXTENSION)) TextScope.File(scopeRel) else TextScope.Tree(scopeRel)
        val result = registry.searchText(scope, parsed.expr, parsed.reversed, limit)
        if (result.total == 0) return "No lines match in ${display(scopeRel)}."
        return buildString {
            append(result.total).append(if (result.total == 1) " matching line" else " matching lines")
            if (result.total > result.hits.size) append(" (showing ").append(result.hits.size).append(')')
            append(":\n")
            var lastFile: String? = null
            for (hit in result.hits) {
                if (hit.fileRel != lastFile) {
                    lastFile = hit.fileRel
                    append('\n').append(placeOf(hit.fileRel)).append('\n')
                }
                append("  - ").append(hit.text).append('\n')
            }
        }.trimEnd()
    }

    /** Where a file's lines live: the node (`/path — Home › A › B`) or the note. */
    private fun placeOf(fileRel: String): String {
        if (!NoteRepository.isOutlineFile(fileRel)) return "${display(fileRel)} (note)"
        val folder = NoteRepository.folderOfOutline(fileRel)
        val trail = listOf(NoteRepository.ROOT_DISPLAY_NAME) +
            folder.split('/').filter { it.isNotEmpty() }.map(FolderName::decode)
        return "${display(folder)} (${trail.joinToString(" › ")})"
    }

    private suspend fun listTags(folder: String): String {
        registry.flushAll()
        registry.textIndex.ensureBuilt()
        val scope = if (folder.endsWith(NoteRepository.NOTE_EXTENSION)) TextScope.File(folder) else TextScope.Tree(folder)
        val tags = registry.textIndex.tags(scope, "", max = 300)
        if (tags.isEmpty()) return "No tags in ${display(folder)}."
        return tags.joinToString("\n") { "#${it.tag} (${it.count})" }
    }

    // ----------------------------------------------------------------- edits

    private suspend fun edit(rel: String, oldText: String, newText: String, deleteNodes: Boolean): String {
        val old = oldText.replace("\r\n", "\n")
        if (old.isEmpty()) throw Refusal("old_text is empty. Use append to add at the end.")
        return when (val target = targetOf(rel)) {
            is Target.Node -> editNode(target.folder) { current ->
                AgentOutline.parse(replaceOnce(current, old, newText.replace("\r\n", "\n"), target.folder)) to deleteNodes
            }
            is Target.Note -> editNote(target.fileRel) { current -> replaceOnce(current, old, newText.replace("\r\n", "\n"), target.fileRel) }
            is Target.File -> throw notEditable(target.fileRel)
        }
    }

    private suspend fun append(rel: String, text: String): String {
        val added = text.replace("\r\n", "\n").trim('\n')
        if (added.isBlank()) throw Refusal("text is empty.")
        return when (val target = targetOf(rel)) {
            is Target.Node -> {
                val items = AgentOutline.parse(added)
                if (hasNode(items)) throw Refusal("New items cannot carry <!-- /path --> annotations: those name existing nodes.")
                editNode(target.folder) { current -> (AgentOutline.parse(current) + items) to false }
            }
            is Target.Note -> editNote(target.fileRel) { current ->
                val body = current.trimEnd('\n')
                (if (body.isEmpty()) added else "$body\n$added") + if (current.endsWith("\n")) "\n" else ""
            }
            is Target.File -> throw notEditable(target.fileRel)
        }
    }

    private suspend fun createNode(parent: String, title: String, itemsText: String): String {
        if (title.isBlank()) throw Refusal("title is empty.")
        if (title.contains('\n')) throw Refusal("title must be one line; put further lines in items.")
        val children = AgentOutline.parse(itemsText)
        if (hasNode(children)) throw Refusal("New items cannot carry <!-- /path --> annotations: those name existing nodes.")
        val node = AgentOutline.Item(AgentOutline.Kind.BULLET, title.trim(), children = children)
        val folder = (targetOf(parent) as? Target.Node)?.folder ?: throw Refusal("${display(parent)} is a note; nodes go in nodes.")
        return editNode(folder) { current -> (AgentOutline.parse(current) + node) to false }
    }

    private suspend fun createFile(folder: String, name: String, text: String): String {
        val n = name.trim()
        if (n.isEmpty()) throw Refusal("name is empty.")
        if ('/' in n || '\\' in n) throw Refusal("name must be a file name, not a path; give the folder separately.")
        if (folder.isNotEmpty() && registry.kindOf(folder) != VaultEntryKind.FOLDER) {
            throw Refusal(
                "There is no folder at ${display(folder)}. Files go in a node's folder; a bullet without children has " +
                    "none — create_node with items makes one, or put the file in its parent.",
            )
        }
        val ext = n.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext !in TEXT_EXTENSIONS && n.contains('.')) {
            throw Refusal("Only text files can be created (${TEXT_EXTENSIONS.joinToString { ".$it" }}).")
        }
        val stem = if (ext.isEmpty()) n else n.substring(0, n.length - ext.length - 1)
        val fileName = if (ext.isEmpty()) "$n${NoteRepository.NOTE_EXTENSION}" else n
        if (NoteRepository.isAppFile(join(folder, fileName))) throw Refusal("$fileName is a name Lunarbor keeps for itself.")
        val rel = registry.createFile(folder, stem, if (ext.isEmpty()) "md" else ext, text)
        return "Created ${display(rel)}."
    }

    private suspend fun delete(rel: String): String {
        if (rel.isEmpty()) throw Refusal("The vault's root cannot be deleted.")
        val folder = if (NoteRepository.isOutlineFile(rel)) NoteRepository.folderOfOutline(rel) else rel
        if (folder.isEmpty()) throw Refusal("The vault's root cannot be deleted.")
        if (folder.equals(scope, ignoreCase = true)) throw Refusal("${display(folder)} is the folder this connection works in; it cannot be deleted.")
        when (registry.kindOf(folder)) {
            null -> throw Refusal("Nothing at ${display(folder)}.")
            VaultEntryKind.FOLDER -> {
                registry.flushAll()
                val parent = folder.substringBeforeLast('/', "")
                if (!isItemOfParent(folder)) {
                    registry.trashFolder(folder)?.let { throw Refusal("${display(folder)} was not deleted: $it") }
                    return "Moved the folder ${display(folder)} to the trash."
                }
                val title = nodeTitle(folder)
                editNode(parent) { current ->
                    AgentOutline.parse(current).filterNot { it.node.equals(folder, ignoreCase = true) } to true
                }
                return "Deleted the node \"$title\" (${display(folder)}); it is in the vault's trash."
            }
            else -> {
                if (NoteRepository.isAppFile(folder)) throw Refusal("${display(folder)} is kept by Lunarbor itself.")
                registry.trashNote(folder, null)?.let { throw Refusal("${display(folder)} was not deleted: $it Close the window showing it first (close_window).") }
                return "Moved ${display(folder)} to the trash."
            }
        }
    }

    private suspend fun move(rel: String, to: String, position: Int?, newName: String?): String {
        val src = if (NoteRepository.isOutlineFile(rel)) NoteRepository.folderOfOutline(rel) else rel
        if (src.isEmpty()) throw Refusal("The vault's root cannot move.")
        if (src.equals(scope, ignoreCase = true)) throw Refusal("${display(src)} is the folder this connection works in; it cannot move.")
        val kind = registry.kindOf(src) ?: throw Refusal("Nothing at ${display(src)}.")
        if (NoteRepository.isAppFile(src) && kind != VaultEntryKind.FOLDER) throw Refusal("${display(src)} is kept by Lunarbor itself.")
        if (to.isNotEmpty() && registry.kindOf(to) != VaultEntryKind.FOLDER) {
            throw Refusal("There is no node or folder at ${display(to)}. A bullet without children has no folder; give it a child first.")
        }
        if (kind == VaultEntryKind.FOLDER && (to == src || to.startsWith("$src/"))) throw Refusal("Something cannot move into itself.")
        if (newName != null && ('/' in newName || '\n' in newName)) throw Refusal("new_name is a name, not a path.")
        registry.flushAll()
        val isNode = kind == VaultEntryKind.FOLDER && isItemOfParent(src)
        val dst = try {
            registry.moveForAgent(src, to, isNode, position, newName)
        } catch (e: IllegalStateException) {
            throw Refusal("${display(src)} was not moved: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw Refusal("${display(src)} was not moved: ${e.message}")
        }
        return if (isNode) "Moved the node to ${display(dst)}.\n\n" + read(to, 1) else "Moved to ${display(dst)}."
    }

    /** `true` when the folder [folder] backs an item of its parent node. */
    private suspend fun isItemOfParent(folder: String): Boolean {
        val parent = folder.substringBeforeLast('/', "")
        val name = folder.substringAfterLast('/')
        return registry.nodeItemsOf(parent).any {
            (it is NodeLine.Folder && it.folder.equals(name, true)) || (it is NodeLine.Block && it.folder.equals(name, true))
        }
    }

    /**
     * The `list_windows` text: every tab with its windows. A window whose
     * location lies outside the connection's folder (or is not known yet,
     * on a scoped connection) shows neither its title nor its path — the
     * title is a breadcrumb of node names the agent may not see.
     */
    private suspend fun listWindows(): String {
        val tabs = ws().tabs()
        return buildString {
            for (tab in tabs) {
                append("Tab ").append(tab.id).append(" \"").append(tab.title).append('"')
                if (tab.isActive) append(" (active)")
                append('\n')
                if (tab.windows.isEmpty()) append("  (no windows)\n")
                for (w in tab.windows) {
                    append("  - window ").append(w.id).append(": ")
                    val location = w.location
                    when {
                        location != null && inScope(location) -> append(w.title).append(" — ").append(display(location))
                        location == null && scope.isEmpty() -> append(w.title)
                        else -> append("(outside your folder)")
                    }
                    if (w.isFocused) append(" (focused)")
                    append('\n')
                }
            }
        }.trimEnd().ifEmpty { "No tabs are open." }
    }

    /** The workspace, or a refusal when this platform has none. */
    private fun ws(): AgentWorkspace = workspace ?: throw Refusal("This Lunarbor has no windows to control.")

    /**
     * [rel] when a window can show it: the root, a node folder, a note or
     * an image. Refuses otherwise.
     */
    private suspend fun existing(rel: String): String {
        if (rel.isEmpty()) return rel
        when (registry.kindOf(rel)) {
            null -> throw Refusal("Nothing at ${display(rel)}.")
            VaultEntryKind.FILE -> throw Refusal("${display(rel)} opens in its own app; windows show nodes, notes, images, drawings and web pages.")
            else -> {}
        }
        return rel
    }

    /** A workspace result: `!`-prefixed errors refuse, ids are reported. */
    private fun windowResult(result: String, what: String): String =
        if (result.startsWith("!")) throw Refusal(result.substring(1)) else "$what $result."

    private fun done(error: String?): String = error?.let { throw Refusal(it) } ?: "Done."

    /**
     * Edits the node [folder]'s own items: renders them, lets [change]
     * produce the new items (and whether nodes may be deleted), diffs those
     * onto the document and saves.
     *
     * @return The node as it is after the save.
     */
    private suspend fun editNode(folder: String, change: (String) -> Pair<List<AgentOutline.Item>, Boolean>): String {
        val fileRel = NoteRepository.outlineFileOf(folder)
        var notice = ""
        val after = registry.editForAgent(
            fileRel,
            edit = { doc ->
                val top = topItems(doc)
                val (items, deleteNodes) = change(AgentOutline.format(top.map { it.item }))
                notice = applyItems(doc, top, items, deleteNodes)
                ""
            },
            after = { doc, _ -> AgentOutline.format(topItems(doc).map { it.item }) },
        )
        return buildString {
            append("Saved.").append(notice).append("\n\n")
            append(nodeHeader(folder))
            append(after.ifEmpty { "(no items)" })
        }
    }

    /** Edits a note's whole text with [change] and saves. */
    private suspend fun editNote(fileRel: String, change: (String) -> String): String {
        registry.editForAgent(fileRel, edit = { doc ->
            val s = doc.stateFlow.value
            val newLines = change(s.lines.joinToString("\n")).split('\n')
            val match = Document.matchIds(s.lines.map { "T:$it" }, newLines.map { "T:$it" })
            doc.rewriteRows(newLines, match.map { i -> i?.let { s.lineIds[it] } })
        })
        return "Saved ${display(fileRel)}."
    }

    /**
     * One top-level item of a node's document.
     *
     * @property item The item as agents see it (children not included).
     * @property id The [LineId] of its first row.
     * @property ownLastRow Last row of the item's own text.
     * @property endRow Last row of its subtree (children a pane has
     *   expanded are in the document).
     */
    private class DocItem(val item: AgentOutline.Item, val id: LineId, val row: Int, val ownLastRow: Int, val endRow: Int)

    /**
     * The node's own items in [doc] — rows at column 0, empty leaf
     * bullets left out (an empty outline holds one).
     */
    private fun topItems(doc: Document): List<DocItem> {
        val s = doc.stateFlow.value
        val lines = s.lines
        val promoted = doc.promotedByRow()
        val out = ArrayList<DocItem>()
        var r = 0
        while (r < lines.size) {
            val line = lines[r]
            val col = DocumentLayout.itemColumn(lines, r)
            when {
                col == 0 -> {
                    val own = DocumentLayout.itemLastRow(lines, r)
                    val end = DocumentLayout.subtreeEnd(lines, r, 0)
                    val node = promoted[r]?.folderRel
                    val item = if (DocumentLayout.bulletAsteriskColumn(line) == 0) {
                        AgentOutline.Item(AgentOutline.Kind.BULLET, line.substring(2), node = node)
                    } else {
                        val content = BlockLayout.diskContentOf((r..own).map { BlockLayout.contentOf(lines[it]) })
                        AgentOutline.Item(AgentOutline.Kind.BLOCK, content = content, node = node)
                    }
                    val emptyLeaf = item.kind == AgentOutline.Kind.BULLET && node == null && end == r && item.text.isBlank()
                    if (!emptyLeaf) out += DocItem(item, s.lineIds[r], r, own, end)
                    r = end + 1
                }
                line.isBlank() -> r++
                DocumentLayout.indentOf(line) == 0 -> {
                    out += DocItem(AgentOutline.Item(AgentOutline.Kind.TEXT, line), s.lineIds[r], r, r, r)
                    r++
                }
                else -> r++
            }
        }
        return out
    }

    /**
     * Writes [new] (top-level items with any new children) over the
     * document's [old] items. Items are paired as on reload
     * ([Document.matchIds]): a node by its folder wherever it moved, any
     * other item by its unchanged text. A paired item keeps its row id —
     * and so its folder, fold state and spliced children — while
     * everything else gets new rows.
     *
     * @param deleteNodes Whether old nodes missing from [new] may go (to
     *   the trash, on the save).
     * @return A notice for the agent about deleted nodes, or `""`.
     * @throws Refusal for an annotation that names no item here, a node
     *   that would be deleted without [deleteNodes], or children added to a
     *   node from its parent.
     */
    private fun applyItems(doc: Document, old: List<DocItem>, new: List<AgentOutline.Item>, deleteNodes: Boolean): String {
        fun key(item: AgentOutline.Item) = item.node?.let { "F:${it.lowercase()}" } ?: "T:${AgentOutline.formatOwn(item)}"
        val match = Document.matchIds(old.map { key(it.item) }, new.map { key(it) })
        val nested = new.flatMap { it.children }
        if (hasNode(nested)) throw Refusal("Only the node's own items can carry <!-- /path -->; nested lines are new items.")
        val seen = HashSet<String>()
        for ((j, item) in new.withIndex()) {
            val node = item.node ?: continue
            if (!seen.add(node.lowercase())) throw Refusal("${display(node)} appears twice. Each node can be in one place only.")
            if (match[j] == null) throw Refusal("<!-- ${display(node)} --> is no item of this node. Keep each annotation on the line it came with.")
            val was = old[match[j]!!].item
            if (was.kind != item.kind) throw Refusal("${display(node)} has children, so it cannot change between bullet and block.")
            if (item.children.isNotEmpty()) {
                throw Refusal("${display(node)} is a node of its own: add items to it with path ${display(node)}, not from here.")
            }
        }
        val kept = match.filterNotNull().toHashSet()
        val dropped = old.indices.filter { it !in kept && old[it].item.node != null }.map { old[it].item.node!! }
        if (dropped.isNotEmpty() && !deleteNodes) {
            throw Refusal(
                "This edit would delete ${dropped.joinToString { display(it) }} with everything in it (moved to the " +
                    "vault's trash). To keep a node, leave its <!-- /path --> at the end of its line (you may change the " +
                    "title before it). If you do mean to delete, call edit again with delete_nodes: true.",
            )
        }
        val s = doc.stateFlow.value
        val lines = ArrayList<String>()
        val ids = ArrayList<LineId?>()
        for ((j, item) in new.withIndex()) {
            val m = match[j]
            if (m == null) {
                val rows = AgentOutline.rowsOf(item, 0)
                lines += rows
                repeat(rows.size) { ids += null }
                continue
            }
            val was = old[m]
            AgentOutline.ownRowsOf(item, 0).forEachIndexed { k, row ->
                lines += row
                ids += if (k == 0) was.id else null
            }
            for (r in was.ownLastRow + 1..was.endRow) {
                lines += s.lines[r]
                ids += s.lineIds[r]
            }
            for (child in item.children) {
                val rows = AgentOutline.rowsOf(child, PaneBackingViewModel.TAB_SIZE)
                lines += rows
                repeat(rows.size) { ids += null }
            }
        }
        if (lines.isEmpty()) {
            lines += NoteRepository.EMPTY_OUTLINE_LINE
            ids += null
        }
        doc.rewriteRows(lines, ids)
        return if (dropped.isEmpty()) "" else " Moved to the trash: ${dropped.joinToString { display(it) }}."
    }

    // ---------------------------------------------------------------- helpers

    /** What a path names: a node (its folder) or a `.md` note. */
    private sealed class Target {
        data class Node(val folder: String) : Target()
        data class Note(val fileRel: String) : Target()

        /** Any other file: readable, not editable here. */
        data class File(val fileRel: String) : Target()
    }

    /**
     * Resolves [rel] to a node or note.
     *
     * @throws Refusal when nothing is there, or it is another kind of file.
     */
    private suspend fun targetOf(rel: String): Target {
        if (rel.isEmpty()) return Target.Node("")
        if (NoteRepository.isOutlineFile(rel)) return Target.Node(NoteRepository.folderOfOutline(rel))
        return when (registry.kindOf(rel)) {
            VaultEntryKind.FOLDER -> Target.Node(rel)
            VaultEntryKind.MARKDOWN -> if (NoteRepository.isAppFile(rel)) Target.File(rel) else Target.Note(rel)
            VaultEntryKind.IMAGE, VaultEntryKind.DRAWING, VaultEntryKind.HTML, VaultEntryKind.FILE -> Target.File(rel)
            null -> throw Refusal(
                "Nothing at ${display(rel)}. Paths name node folders and files; a bullet without children has no " +
                    "path — it is an item of its parent node. Use search or read \"/\" to find paths.",
            )
        }
    }

    /** [current] with [old] replaced by [new]; [old] must occur exactly once. */
    private fun replaceOnce(current: String, old: String, new: String, rel: String): String {
        val first = current.indexOf(old)
        if (first < 0) {
            throw Refusal(
                "old_text was not found in ${display(rel)}. Read it again and copy the text exactly. In a node, edit " +
                    "matches only the node's own items, not those of nested nodes (edit those by their own path).",
            )
        }
        if (current.indexOf(old, first + 1) >= 0) throw Refusal("old_text matches more than one place in ${display(rel)}; include more of the text around it.")
        return current.substring(0, first) + new + current.substring(first + old.length)
    }

    private fun notEditable(rel: String) = Refusal(
        "${display(rel)} cannot be edited here: edit and append change nodes and .md notes. You can read it, move it or delete it.",
    )

    private fun hasNode(items: List<AgentOutline.Item>): Boolean = items.any { it.node != null || hasNode(it.children) }

    private fun path(args: JsonObject, name: String): String = checkScope(AgentOutline.normalizePath(required(args, name)))

    /** The optional path [name] (checked like [path]), or the connection's folder when it is not given. */
    private fun optionalPath(args: JsonObject, name: String): String =
        args.string(name)?.let { checkScope(AgentOutline.normalizePath(it)) } ?: scope

    /** `true` when the vault path [rel] is the connection's folder or inside it (always, unscoped). */
    private fun inScope(rel: String): Boolean = isInside(rel, scope)

    /**
     * [rel] when it lies in the connection's folder ([inScope]); refuses it
     * otherwise. Every path a tool takes passes through here.
     */
    private fun checkScope(rel: String): String {
        if (!inScope(rel)) {
            throw Refusal("${display(rel)} is outside the folder this connection may use (${display(scope)}). Use paths inside it.")
        }
        return rel
    }

    private fun required(args: JsonObject, name: String): String = args.string(name) ?: throw Refusal("$name is missing.")

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

    /** Long results are cut, so one call never floods the agent's context. */
    private fun cap(text: String): String =
        if (text.length <= MAX_RESULT_CHARS) text
        else text.substring(0, MAX_RESULT_CHARS) + "\n\n[Cut off at $MAX_RESULT_CHARS characters. Read nested nodes by their own path, or use a smaller depth.]"

    companion object {
        /** Deepest `read` depth. */
        const val MAX_DEPTH: Int = 5

        /** Longest result text. */
        const val MAX_RESULT_CHARS: Int = 60_000

        /** Largest file `read` returns. */
        const val MAX_FILE_BYTES: Long = 10L * 1024 * 1024

        /** Most entries `list_folder` lists. */
        const val MAX_LIST_ENTRIES: Int = 1_000

        /** Media types of common binary files, by extension. */
        val MIME_TYPES: Map<String, String> = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
            "webp" to "image/webp", "pdf" to "application/pdf", "zip" to "application/zip",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "mp3" to "audio/mpeg", "wav" to "audio/wav", "m4a" to "audio/mp4", "mp4" to "video/mp4", "mov" to "video/quicktime",
            "heic" to "image/heic",
        )

        /** Extensions `create_file` writes: text formats only. */
        val TEXT_EXTENSIONS: Set<String> = setOf("md", "txt", "csv", "tsv", "json", "yaml", "yml", "xml", "html", "css", "js", "ts", "kt", "py", "sh", "toml", "ini", "log")

        /** [rel] as agents see paths: with a leading slash, `/` for the root. */
        fun display(rel: String): String = "/$rel"

        /**
         * `true` when the vault path [rel] is [folder] or lies inside it,
         * ignoring case (macOS file names are case-insensitive); always
         * `true` for [folder] `""`, the whole vault.
         */
        fun isInside(rel: String, folder: String): Boolean =
            folder.isEmpty() || rel.equals(folder, ignoreCase = true) ||
                rel.lowercase().startsWith(folder.lowercase() + "/")

        private fun join(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"

        private fun stringProp(description: String): JsonElement = buildJsonObject {
            put("type", "string")
            put("description", description)
        }

        private fun intProp(description: String): JsonElement = buildJsonObject {
            put("type", "integer")
            put("description", description)
        }

        private fun boolProp(description: String): JsonElement = buildJsonObject {
            put("type", "boolean")
            put("description", description)
        }

        private fun schema(vararg props: Pair<String, JsonElement>, required: List<String> = emptyList()): JsonObject =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { for ((k, v) in props) put(k, v) }
                if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                put("additionalProperties", false)
            }
    }
}
