/*
 * DocumentRegistry.kt
 * -------------------
 * Owns the lifecycle of every [Document] instance the app currently has
 * loaded. Refcounts panes per file: two panes that view the same file
 * share one [Document] instance (so their edits and autosave stay in
 * sync); when the last pane navigates away, the registry flushes one
 * final save and tears the [Document] down.
 *
 * Also owns the shared vault-listings cache — one listing per folder a
 * pane has looked at, read by the folder contents list under the bullets
 * and by the count badges on expanded folder-backed bullets. The cache
 * lives here (not on any one document) because folder structure isn't
 * tied to any particular file: when one save creates or removes a
 * folder, every pane's list wants to see the change.
 *
 * And owns the link machinery (TRF-8): the [VaultIndex] (link-target
 * search and the index of which files link where), the "does this link
 * target exist" cache the views use to strike broken links through
 * ([linkStatusFlow]), and the rewrite that keeps links and Starred entries
 * pointing at folders and files a save renamed or moved
 * ([applyPathMoves]), and the backlinks of the pages panes show
 * ([backlinksFlow], LBR-7).
 *
 * And owns the privacy modes (LBR-10): the vault's `_privacy.config`
 * ([privacyFlow], [setPrivacyModes]), the mode the whole app shows
 * ([setPrivacyMode], one for every window and tab), and which paths it
 * hides ([isPathHidden], [hasHiddenUnder], answered by the [textIndex]).
 * Search, search nodes, link search, wiki links, Insert Image and the
 * listings filter by it here; panes filter their own rows.
 *
 * commonMain only — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.soderbjorn.lunarbor.data.NodeLine
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.PathMove
import se.soderbjorn.lunarbor.data.PrivacyConfig
import se.soderbjorn.lunarbor.data.PrivacyFilter
import se.soderbjorn.lunarbor.data.PrivacyMode
import se.soderbjorn.lunarbor.data.TextIndex
import se.soderbjorn.lunarbor.data.SearchQuery
import se.soderbjorn.lunarbor.data.SubtreeCodec
import se.soderbjorn.lunarbor.data.TextScope
import se.soderbjorn.lunarbor.data.TextSearchResult
import se.soderbjorn.lunarbor.data.VaultEntry
import se.soderbjorn.lunarbor.data.VaultEntryKind
import se.soderbjorn.lunarbor.data.VaultIndex
import se.soderbjorn.lunarbor.data.WikiLink
import kotlin.time.TimeSource

/**
 * App-scoped registry of loaded [Document]s and shared vault state.
 *
 * ### Callers
 * - One instance per app, created by the platform DI graph.
 * - [acquire] / [release] are called by `PaneBackingViewModel` whenever
 *   a pane is created, navigates to a different file, or is torn down.
 * - [vaultListingsFlow] is observed by panes so the folder contents list
 *   and count badges can render from one shared cache.
 *
 * @param repository The single [NoteRepository] used for all disk I/O.
 * @param scope App-scoped coroutine scope passed to each [Document]
 *   for its initial load and autosave loop.
 * @param saveDebounceMillis Forwarded to every [Document] this registry
 *   creates; see [Document.DEFAULT_SAVE_DEBOUNCE_MILLIS].
 * @param maxSaveDelayMillis Forwarded likewise; see
 *   [Document.DEFAULT_MAX_SAVE_DELAY_MILLIS].
 */
class DocumentRegistry(
    private val repository: NoteRepository,
    private val scope: CoroutineScope,
    private val saveDebounceMillis: Long = Document.DEFAULT_SAVE_DEBOUNCE_MILLIS,
    private val maxSaveDelayMillis: Long = Document.DEFAULT_MAX_SAVE_DELAY_MILLIS,
) {

    /** Vault-relative path of the configured root file. */
    val rootFileName: String = repository.rootFileName

    /** Absolute path of the vault root. Platform glue uses this to
     *  resolve image asset URLs against the same root the repository
     *  uses for `.md` I/O. */
    val rootDirectory: String = repository.rootDirectory

    /**
     * Live ref-count + [Document] handle. The document is shared among
     * panes for the same `fileRel`; releasing brings the count down,
     * and reaching zero triggers shutdown + removal.
     *
     * @property dirtyWatch Collector mirroring the document's
     *   [Document.dirtyFlow] into [unsavedFilesFlow]. Cancelled in
     *   [release] right before the document shuts down.
     */
    private data class Slot(val document: Document, var refCount: Int, val dirtyWatch: Job)

    private val slots: MutableMap<String, Slot> = mutableMapOf()

    /**
     * App-scoped link index: link-target search for the link modals and
     * the index of which files link where. Fed with every note file text
     * the repository reads or writes (wired in `init`); its target list is
     * dropped whenever the listings are refreshed.
     */
    val vaultIndex: VaultIndex = VaultIndex(
        listTargets = repository::listLinkTargets,
        listLinkBearingFiles = repository::listLinkBearingFiles,
        readText = repository::readNoteText,
    )

    /**
     * Which folder-backed items panes last left open ([FoldMemory]), so a
     * page comes back with the same folds. Persisted by platform glue.
     */
    val foldMemory: FoldMemory = FoldMemory()

    /**
     * App-scoped full-text index behind the pane search: every line of
     * every note file. Fed with every note text the repository reads or
     * writes (wired in `init`), built on the first search ([searchText]).
     */
    val textIndex: TextIndex = TextIndex(
        listFiles = repository::listLinkBearingFiles,
        readText = repository::readNoteText,
    )

    /**
     * The lines in [scope] (a tree or one note) meeting [expr]
     * ([TextIndex.search]). Saves the open
     * documents first, so unsaved edits are found, and builds the index on
     * the first call (one read of every note file).
     *
     * Called by `PaneBackingViewModel.setSearchQuery` and the agent tools.
     *
     * @param filter The privacy mode to apply: by default the app's
     *   ([privacyFilter]); an agent connection passes its own scope's.
     */
    suspend fun searchText(
        scope: TextScope,
        expr: SearchQuery.Expr?,
        reversed: Boolean = false,
        max: Int = 300,
        filter: PrivacyFilter = privacyFilter,
    ): TextSearchResult {
        flushAll()
        textIndex.ensureBuilt()
        return textIndex.search(scope, expr, max, reversed, filter)
    }

    /**
     * One search node's query in one tree: the key of its results.
     *
     * @property scope The tree it searches (its own, or its `in:` one).
     * @property query The expression as written (without the braces).
     */
    data class SearchNodeKey(val scope: TextScope, val query: String)

    private val _searchNodeResults = MutableStateFlow<Map<SearchNodeKey, TextSearchResult>>(emptyMap())

    /**
     * Results of every search node a pane has shown this session
     * ([requestSearchNode]), re-run [SEARCH_NODE_REFRESH_MS] after the
     * text index changes (a save, a change from outside). Panes mirror it,
     * so a search node's list follows the vault a few seconds behind.
     */
    val searchNodeResultsFlow: StateFlow<Map<SearchNodeKey, TextSearchResult>> = _searchNodeResults.asStateFlow()

    private val requestedSearchNodes: MutableSet<SearchNodeKey> = LinkedHashSet()
    private var searchNodeRefresh: Job? = null

    /**
     * The results for [key] ([searchNodeResultsFlow]), or `null` while
     * unknown — then the search runs (building the text index first, if
     * needed) and lands in the flow, and [key] is kept fresh from then on.
     *
     * Called by `PaneBackingViewModel.searchNodeOf` for every search node
     * the paint loop draws.
     */
    fun requestSearchNode(key: SearchNodeKey): TextSearchResult? {
        _searchNodeResults.value[key]?.let { return it }
        if (requestedSearchNodes.add(key)) {
            scope.launch {
                textIndex.ensureBuilt()
                runSearchNode(key)
            }
        }
        return null
    }

    private fun runSearchNode(key: SearchNodeKey) {
        val query = SearchQuery.parse(key.query)
        val result = textIndex.search(key.scope, query.expr, SEARCH_NODE_MAX_HITS, query.reversed, privacyFilter)
        _searchNodeResults.update { it + (key to result) }
    }

    /**
     * After an index change: re-runs every requested search node once,
     * [SEARCH_NODE_REFRESH_MS] later — changes in between ride along.
     */
    private fun scheduleSearchNodeRefresh() {
        if (requestedSearchNodes.isEmpty() || searchNodeRefresh?.isActive == true) return
        searchNodeRefresh = scope.launch {
            delay(SEARCH_NODE_REFRESH_MS)
            for (key in requestedSearchNodes.toList()) runSearchNode(key)
        }
    }

    /** Snapshot of the open documents, taken without the slots lock (best effort). */
    private fun openDocuments(): List<Document> = slots.values.map { it.document }

    /**
     * Serializes acquire / release across panes so the refcount, slot
     * map, and any in-flight shutdown stay consistent. Acquire / release
     * are infrequent (only on pane create/navigate/close), so a single
     * mutex is fine.
     */
    private val slotsLock = Mutex()

    private val _vaultListings: MutableStateFlow<Map<String, List<VaultEntry>>> =
        MutableStateFlow(emptyMap())

    /**
     * Observable stream of the shared vault-listings cache. Keys are
     * directory paths relative to the vault root (`""` for the root).
     * Values are the raw direct entries ([NoteRepository.listVaultLevel]);
     * views filter them with [FolderContents.visible]. Missing keys mean
     * "not yet fetched"; the view draws nothing until the pane's call to
     * [ensureVaultListing] populates the entry.
     *
     * Refreshed after every save (via [Document]'s `onAfterSave` hook) so
     * folders created, renamed or trashed by a save surface right away,
     * and by [refreshVaultListings] / [refreshVaultListing] so changes
     * made outside the app (a file added in Finder) show up at the next
     * refresh: window focus and pane navigation.
     */
    val vaultListingsFlow: StateFlow<Map<String, List<VaultEntry>>> =
        _vaultListings.asStateFlow()

    private val _unsavedFiles: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    /**
     * Vault-relative paths of every open [Document] that currently
     * holds unflushed edits (see [Document.dirtyFlow]). Empty ⇔ all
     * changes are on disk. Maintained by a per-slot watcher started in
     * [acquire] and torn down in [release] (a released document's final
     * flush guarantees it is clean, so its entry is removed).
     *
     * App chrome observes this to render a save-state indicator — on
     * the web, the sidebar logo's dot pulses while this set is
     * non-empty and holds a steady light once it drains.
     */
    val unsavedFilesFlow: StateFlow<Set<String>> = _unsavedFiles.asStateFlow()

    private val _linkStatus: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap())

    /**
     * Link target path → whether something exists there, for every `lunarbor:`
     * target a view has asked about ([requestLinkStatus]). Views draw a
     * link whose entry is `false` struck through with a "not found"
     * tooltip; the link text itself is never touched. Re-checked by
     * [refreshVaultListings] — after every save and when the window
     * regains focus — so a target moved or trashed in Finder shows as
     * broken at the next refresh.
     */
    val linkStatusFlow: StateFlow<Map<String, Boolean>> = _linkStatus.asStateFlow()

    /** Paths whose status check is in flight, so a repaint storm checks each once. */
    private val pendingStatus: MutableSet<String> = mutableSetOf()

    private val _wikiLinks: MutableStateFlow<Map<String, String?>> = MutableStateFlow(emptyMap())

    /**
     * Wiki link name key ([WikiLink.keyOf]) → the vault-relative path it
     * resolves to, or `null` when no single target matches, for every
     * `[[Name]]` a view has asked about ([requestWikiLink]). Re-resolved by
     * [refreshVaultListings] — after every save and when the window
     * regains focus — so a note created, renamed or deleted anywhere
     * relinks at the next refresh.
     */
    val wikiLinksFlow: StateFlow<Map<String, String?>> = _wikiLinks.asStateFlow()

    /** Name keys whose resolution is in flight, so a repaint storm resolves each once. */
    private val pendingWikiLinks: MutableSet<String> = mutableSetOf()

    private val _linkPreviews: MutableStateFlow<Map<String, List<LinkPreviewItem>>> = MutableStateFlow(emptyMap())

    /**
     * Linked node folder → its bullets ([LinkPreviewItem]), for every link
     * target a view has asked to preview ([requestLinkPreview]); an empty
     * list when the target is no node with bullets (a file, a missing or
     * empty folder). Read from disk, so it shows the saved outline;
     * re-read by [refreshVaultListings] — after every save and when the
     * window regains focus.
     */
    val linkPreviewsFlow: StateFlow<Map<String, List<LinkPreviewItem>>> = _linkPreviews.asStateFlow()

    private val pendingPreviews: MutableSet<String> = mutableSetOf()

    private val _backlinks: MutableStateFlow<Map<String, List<se.soderbjorn.lunarbor.data.TextHit>>> = MutableStateFlow(emptyMap())

    /**
     * Page path → the lines linking to it ([TextIndex.backlinks]: `lunarbor:`
     * links and resolving `[[wiki]]` links, not from inside the page, less
     * what the app's privacy mode hides), for every page a pane has shown
     * the "Linked from" section of ([requestBacklinks]). Recomputed by
     * [refreshVaultListings] — after every save, external change and window
     * focus — and when the privacy mode changes. LBR-7.
     */
    val backlinksFlow: StateFlow<Map<String, List<se.soderbjorn.lunarbor.data.TextHit>>> = _backlinks.asStateFlow()

    private val pendingBacklinks: MutableSet<String> = mutableSetOf()

    /**
     * The cached backlinks of the page [pathRel] (see [backlinksFlow]), or
     * `null` while unknown — then they are computed (saving open documents
     * and building the text index first, the first time) and land in the
     * flow. Cheap enough to call on every repaint.
     *
     * Called by `PaneBackingViewModel.backlinksOf`.
     */
    fun requestBacklinks(pathRel: String): List<se.soderbjorn.lunarbor.data.TextHit>? {
        _backlinks.value[pathRel]?.let { return it }
        if (!pendingBacklinks.add(pathRel)) return null
        scope.launch {
            try {
                flushAll()
                textIndex.ensureBuilt()
                val hits = backlinksOf(pathRel, backlinkWikiResolver())
                _backlinks.update { it + (pathRel to hits) }
            } finally {
                pendingBacklinks.remove(pathRel)
            }
        }
        return null
    }

    /** [pathRel]'s backlinks, wiki links resolved by [resolveWiki], under the app's privacy mode. */
    private fun backlinksOf(pathRel: String, resolveWiki: ((String) -> String?)?) =
        textIndex.backlinks(pathRel, resolveWiki?.let { r -> { name -> r(name)?.takeUnless { isPathHidden(it) } } }, privacyFilter)

    /**
     * The wiki resolver backlinks need ([VaultIndex.wikiResolver]), or
     * `null` when no indexed line holds a wiki link — then the vault walk
     * for link targets is skipped altogether.
     */
    private suspend fun backlinkWikiResolver(): ((String) -> String?)? =
        if (textIndex.hasWikiLinks()) vaultIndex.wikiResolver() else null

    /** Recomputes every page in [backlinksFlow]. */
    private suspend fun refreshBacklinks() {
        val keys = _backlinks.value.keys.toList()
        if (keys.isEmpty()) return
        val resolve = backlinkWikiResolver()
        _backlinks.update { current -> current + keys.associateWith { backlinksOf(it, resolve) } }
    }

    init {
        repository.noteTextObserver = { file, text ->
            vaultIndex.noteText(file, text)
            textIndex.noteText(file, text)
        }
        textIndex.onChanged = {
            scheduleSearchNodeRefresh()
            schedulePrivacyRevision()
        }
        // Link search never offers what the app's privacy mode hides.
        vaultIndex.isHidden = { path -> isPathHidden(path) }
        // Eagerly populate the vault root listing so the root's contents
        // list paints right after boot.
        scope.launch { ensureVaultListing("") }
        repository.watchExternalChanges { paths -> scope.launch { applyExternalChanges(paths) } }
    }

    /**
     * Increments the refcount for [fileRel] and returns the live
     * [Document]. Creates and starts a fresh [Document] on first
     * acquire. Two panes that acquire the same `fileRel` get the same
     * instance — concurrent edits show up in both.
     */
    suspend fun acquire(fileRel: String): Document = slotsLock.withLock {
        // The registry only owns markdown documents — image and drawing
        // paths are shown by the image view / drawing editor, which bypass the
        // registry entirely (`PaneBackingViewModel.switchActiveFile`
        // skips this call). Fail loudly if a future caller forgets.
        require(!NoteRepository.isFileViewPath(fileRel)) {
            "DocumentRegistry.acquire called with an image or drawing path: $fileRel"
        }
        val existing = slots[fileRel]
        if (existing != null) {
            existing.refCount++
            return@withLock existing.document
        }
        val doc = Document(
            repository = repository,
            scope = scope,
            fileRel = fileRel,
            saveDebounceMillis = saveDebounceMillis,
            maxSaveDelayMillis = maxSaveDelayMillis,
            onAfterSave = { moves ->
                if (moves.isNotEmpty()) applyPathMoves(moves)
                refreshVaultListings()
            },
        )
        // Mirror the document's dirty flag into the aggregate unsaved
        // set for as long as the slot lives. StateFlow.update is a CAS
        // loop, so concurrent watchers on other documents can't lose
        // each other's writes.
        val dirtyWatch = scope.launch {
            doc.dirtyFlow.collect { dirty ->
                // `doc.fileRel`, not `fileRel`: a renamed note keeps its slot.
                _unsavedFiles.update { if (dirty) it + doc.fileRel else it - doc.fileRel }
            }
        }
        slots[fileRel] = Slot(doc, refCount = 1, dirtyWatch = dirtyWatch)
        doc.start()
        doc
    }

    /**
     * Decrements the refcount for [fileRel]. When it hits zero, the
     * [Document] is shut down (final flush + autosave loop cancel) and
     * removed from the map. Safe to call on a `fileRel` that's already
     * been released — the call is a no-op in that case.
     */
    suspend fun release(fileRel: String) {
        val toShutdown = slotsLock.withLock {
            val slot = slots[fileRel] ?: return@withLock null
            slot.refCount--
            if (slot.refCount > 0) return@withLock null
            slots.remove(fileRel)
            slot.dirtyWatch.cancel()
            slot.document
        } ?: return
        toShutdown.shutdown()
        // The dirty watcher was cancelled before the final flush ran, so
        // clear the aggregate entry by hand: shutdown guarantees the
        // document left nothing unsaved.
        _unsavedFiles.update { it - fileRel }
    }

    /**
     * The on-disk text of the note [fileRel], after every open document
     * has been saved (so a note open in another pane reads as edited), or
     * `null` when it does not exist. Called by
     * `PaneBackingViewModel.convertNoteToNode`.
     */
    suspend fun readNoteText(fileRel: String): String? {
        flushAll()
        return repository.readNoteText(fileRel)
    }

    /**
     * Turns everything under the folder [folderRel] into nodes on disk
     * ([NoteRepository.convertFolderTree]) for a recursive "Convert to
     * node" on a folder. Saves every open document first, so their files
     * are current, and afterwards reloads the ones whose outlines were
     * written ([applyExternalChanges], which also refreshes the listings).
     *
     * Called by `PaneBackingViewModel.convertFolderToNode`.
     *
     * @return Each converted `.md` note → its new node's folder; the notes
     *   are still in place (see [trashNote]).
     */
    suspend fun convertFolderTree(folderRel: String): Map<String, String> {
        flushAll()
        val result = repository.convertFolderTree(folderRel)
        applyExternalChanges(result.writtenOutlines)
        return result.convertedNotes
    }

    /**
     * Moves the file [fileRel] to the trash ([NoteRepository.trashFile]) —
     * a note after "Convert to node" copied it into an outline, or any file
     * deleted from the folder contents list — then, when [contentMovedTo]
     * is given (the new node's folder), points every link and Starred entry
     * that named it there, and refreshes the listings. Other links to it
     * are left alone and show as broken.
     *
     * Refused while any pane has the note open: its [Document] would write
     * the file back on its next save. A drawing's unwritten change is
     * written first ([flushDrawings]), so it cannot recreate the file later.
     *
     * Called by `PaneBackingViewModel.trashConvertedNote` /
     * `trashConvertedNotes` / `trashFile`.
     *
     * @return `null` on success, else why the note was kept.
     */
    suspend fun trashNote(fileRel: String, contentMovedTo: String?): String? {
        if (slotsLock.withLock { fileRel in slots }) return "It is open in another window."
        if (NoteRepository.isDrawingPath(fileRel)) flushDrawings(fileRel)
        val dest = repository.trashFile(fileRel) ?: return "It could not be found."
        applyPathMoves(listOf(PathMove(fileRel, dest)))
        if (contentMovedTo != null) applyPathMoves(listOf(PathMove(fileRel, contentMovedTo)))
        refreshVaultListings()
        return null
    }

    /**
     * Whether the vault file [fileRel] exists — see
     * [NoteRepository.fileExists]. Called by the web shell while restoring
     * pane locations at startup.
     */
    suspend fun fileExists(fileRel: String): Boolean = repository.fileExists(fileRel)

    /**
     * Loads the direct entries under `<vaultRoot>/<dirRel>` if they are
     * not already cached, and merges them into [vaultListingsFlow].
     * No-op when the entry is already present.
     */
    suspend fun ensureVaultListing(dirRel: String) {
        if (_vaultListings.value[dirRel] != null) return
        val entries = repository.listVaultLevel(dirRel)
        val current = _vaultListings.value
        if (current[dirRel] != null) return
        _vaultListings.value = current + (dirRel to entries)
    }

    // ------------------------------------------------------------ renames

    /**
     * Listeners told, synchronously and under the slots lock, about every
     * file [renameFile] moved. Each pane registers one so its
     * `activeFileRel` and file history follow the rename before any other
     * acquire / release can run.
     */
    private val renameListeners: MutableList<(PathMove) -> Unit> = mutableListOf()

    /** Every file renamed this session, old path → new path; see [renamedTo]. */
    private val renames: MutableMap<String, String> = mutableMapOf()

    /** Adds [listener] to the listeners [renameFile] calls. Called by each pane on creation. */
    fun addRenameListener(listener: (PathMove) -> Unit) {
        renameListeners += listener
    }

    /** Removes a listener added by [addRenameListener]. Called by a pane on teardown. */
    fun removeRenameListener(listener: (PathMove) -> Unit) {
        renameListeners -= listener
    }

    /**
     * The path the file [fromRel] was last renamed to by [renameFile] in
     * this session, or `null`. The web view uses it to tell a rename (same
     * place, new name: no navigation animation) from a navigation.
     */
    fun renamedTo(fromRel: String): String? = renames[fromRel]

    /**
     * Renames the file [fileRel] — an open `.md` note, an image or a
     * drawing — to [title] (see [NoteRepository.renameTargetOf] for the
     * naming rules):
     *
     *  1. a note is saved and its file moved ([Document.renameTo]), and the
     *     shared [Document] re-keyed so every pane on it keeps it, without a
     *     reload; a drawing's unwritten change is written first
     *     ([flushDrawings]) and its file moved, an image's file just moved;
     *  2. the panes are told ([addRenameListener]), so each pane on the file
     *     follows it;
     *  3. every link and Starred entry pointing at the old path is rewritten
     *     ([applyPathMoves]), and for an image or drawing every embed
     *     showing it (`![](photo.png)`, [rewriteImageEmbeds]); then the
     *     listings are refreshed ([refreshVaultListings]).
     *
     * Called by `PaneBackingViewModel.renameActiveFile` when the user edits
     * the page title of a note, image or drawing.
     *
     * @return The file's new vault-relative path; [fileRel] when nothing
     *   changed; `null` when it was not renamed (blank title, not an open
     *   note, an image or a drawing).
     */
    suspend fun renameFile(fileRel: String, title: String): String? {
        val isFile = NoteRepository.isFileViewPath(fileRel)
        val moved = slotsLock.withLock {
            val slot = slots[fileRel]
            if (slot == null && !isFile) return@withLock null
            val target = repository.renameTargetOf(fileRel, title) ?: return@withLock null
            if (target == fileRel) return@withLock fileRel
            if (slot != null) {
                slot.document.renameTo(target)
                slots.remove(fileRel)
                slots[target] = slot
                _unsavedFiles.update { if (fileRel in it) it - fileRel + target else it }
            } else {
                if (NoteRepository.isDrawingPath(fileRel)) flushDrawings(fileRel)
                runCatching { repository.moveNote(fileRel, target) }
                    .onFailure { println("[lunarbor] rename failed: $fileRel: ${it.message}") }
                    .getOrNull() ?: return@withLock null
            }
            renames[fileRel] = target
            renames.remove(target)
            val move = PathMove(fileRel, target)
            for (listener in renameListeners.toList()) listener(move)
            target
        } ?: return null
        if (moved == fileRel) return fileRel
        applyPathMoves(listOf(PathMove(fileRel, moved)))
        if (isFile) rewriteImageEmbeds(fileRel, moved)
        // Listings and link statuses: the folder's contents list shows the
        // new name, links to the old path are rewritten, to the new one live.
        refreshVaultListings()
        return moved
    }

    /**
     * Points every embed of the renamed image or drawing [from] at [to]:
     * in open documents' lines ([Document.rewriteImageEmbeds], saved with
     * them) and, on disk, in every other note file of the vault
     * ([NoteRepository.rewriteImageEmbedsInFile]). Embeds are not in the
     * link index, so this walks the vault — a rename is rare and
     * user-initiated. Called by [renameFile].
     */
    private suspend fun rewriteImageEmbeds(from: String, to: String) {
        val held = HashSet<String>()
        for (doc in openDocuments()) {
            held += doc.heldFiles()
            doc.rewriteImageEmbeds(from, to)
        }
        val files = listOf(repository.rootFileName) + repository.listAllNoteFiles()
        for (file in files) {
            if (file in held) continue
            runCatching { repository.rewriteImageEmbedsInFile(file, from, to) }
                .onFailure { println("[lunarbor] embed rewrite failed: $file: ${it.message}") }
        }
    }

    // ------------------------------------------------------------- links

    /**
     * Keeps links working after a save renamed or moved folders or files
     * (TRF-8). Called from every document's after-save hook with that
     * save's [moves]:
     *
     *  1. Carries the link index's keys along with moved folders.
     *  2. Rewrites the `lunarbor:` links in every open document's lines
     *     ([Document.rewriteLinks]); those edits save with the document.
     *  3. Rewrites, on disk, every other file the link index says links at
     *     or through a moved path ([NoteRepository.rewriteLinksInFile]) —
     *     `Starred.md` included — except files an open document holds in
     *     memory ([Document.heldFiles]), whose next save writes the
     *     rewritten lines anyway.
     *
     * Moves into or out of the trash only update the index: a link to a
     * deleted node is left as it is and shows as broken until undo brings
     * the folder back.
     */
    suspend fun applyPathMoves(moves: List<PathMove>) {
        vaultIndex.moveKeys(moves)
        textIndex.moveKeys(moves)
        foldMemory.applyMoves(moves)
        val live = moves.filter { !it.touchesTrash }
        if (live.isEmpty()) return
        val held = HashSet<String>()
        for (doc in openDocuments()) {
            held += doc.heldFiles()
            doc.rewriteLinks(live)
        }
        for (file in vaultIndex.filesLinkingInto(live)) {
            if (file in held) continue
            repository.rewriteLinksInFile(file, live)
        }
    }

    /**
     * Returns the cached existence of the link target [pathRel] (see
     * [linkStatusFlow]), or `null` while unknown — in which case a check
     * is started and its result lands in [linkStatusFlow]. Cheap enough to
     * call on every repaint.
     */
    fun requestLinkStatus(pathRel: String): Boolean? {
        _linkStatus.value[pathRel]?.let { return it }
        if (!pendingStatus.add(pathRel)) return null
        scope.launch {
            try {
                val exists = repository.kindOf(pathRel) != null
                _linkStatus.update { it + (pathRel to exists) }
            } finally {
                pendingStatus.remove(pathRel)
            }
        }
        return null
    }

    /**
     * Returns the cached resolution of the wiki link name [name] (see
     * [wikiLinksFlow]): `Result.success(path)`, `Result.success(null)` for
     * no single match, or `null` while unknown — in which case the name is
     * resolved against [VaultIndex.targets] and the result lands in
     * [wikiLinksFlow]. Cheap enough to call on every repaint.
     */
    fun requestWikiLink(name: String): Result<String?>? {
        val key = WikiLink.keyOf(name)
        val cached = _wikiLinks.value
        if (key in cached) return Result.success(cached[key])
        if (!pendingWikiLinks.add(key)) return null
        scope.launch {
            try {
                val path = vaultIndex.wikiResolver()(name)?.takeUnless { isPathHidden(it) }
                _wikiLinks.update { it + (key to path) }
            } finally {
                pendingWikiLinks.remove(key)
            }
        }
        return null
    }

    /** Re-resolves every name in [wikiLinksFlow] against a fresh target list. */
    private suspend fun refreshWikiLinks() {
        val keys = _wikiLinks.value.keys.toList()
        if (keys.isEmpty()) return
        val resolve = vaultIndex.wikiResolver()
        _wikiLinks.update { current ->
            current + keys.associateWith { k -> resolve(k)?.takeUnless { isPathHidden(it) } }
        }
    }

    /**
     * Returns the cached preview of the linked node [folderRel] (see
     * [linkPreviewsFlow]), or `null` while unknown — in which case a read
     * is started and its result lands in [linkPreviewsFlow]. Cheap enough
     * to call on every repaint.
     */
    fun requestLinkPreview(folderRel: String): List<LinkPreviewItem>? {
        _linkPreviews.value[folderRel]?.let { return it }
        if (!pendingPreviews.add(folderRel)) return null
        scope.launch {
            try {
                val items = linkPreviewItemsOf(folderRel, repository.nodeItemsOf(folderRel))
                _linkPreviews.update { it + (folderRel to items) }
            } finally {
                pendingPreviews.remove(folderRel)
            }
        }
        return null
    }

    /** Re-reads every node in [linkPreviewsFlow] from the disk. */
    private suspend fun refreshLinkPreviews() {
        val keys = _linkPreviews.value.keys.toList()
        if (keys.isEmpty()) return
        val fresh = keys.associateWith { linkPreviewItemsOf(it, repository.nodeItemsOf(it)) }
        _linkPreviews.update { it + fresh }
    }

    /** Re-checks every path in [linkStatusFlow] against the disk. */
    suspend fun refreshLinkStatuses() {
        val keys = _linkStatus.value.keys.toList()
        if (keys.isEmpty()) return
        val kinds = repository.kindsOf(keys)
        _linkStatus.update { current -> current + kinds.mapValues { (_, k) -> k != null } }
    }

    /**
     * What is at the link target [pathRel] right now (a folder, or a
     * file's kind), or `null` when nothing is. See [NoteRepository.kindOf].
     */
    suspend fun kindOf(pathRel: String): VaultEntryKind? = repository.kindOf(pathRel)

    /** See [NoteRepository.isBulletFolder]. */
    suspend fun isBulletFolder(folderRel: String): Boolean = repository.isBulletFolder(folderRel)

    /**
     * Saves every open document that has unsaved edits, so a vault walk
     * (the link search) sees bullets promoted or renamed a moment ago.
     */
    suspend fun flushAll() {
        for (doc in openDocuments()) doc.flush()
        flushDrawings()
    }

    // ------------------------------------------------------------ drawings

    /** Drawing path → its newest text not yet written to disk. */
    private val pendingDrawings = mutableMapOf<String, String>()

    /** Drawing path → when its oldest unwritten change came in. */
    private val pendingDrawingSince = mutableMapOf<String, TimeSource.Monotonic.ValueTimeMark>()

    /** Drawing path → the debounced write waiting for it. */
    private val drawingSaveJobs = mutableMapOf<String, Job>()

    /** Serializes drawing writes, so a flush and a debounced write never race. */
    private val drawingLock = Mutex()

    private val _drawingRevisions: MutableStateFlow<Map<String, Int>> = MutableStateFlow(emptyMap())

    /**
     * Drawing path → a counter bumped whenever that drawing's text changed
     * under the panes showing it: written by one pane (so the others
     * showing it can catch up) or changed outside the app. A pane's drawing
     * editor re-reads [drawingText] on a bump and ignores text it sent
     * itself.
     *
     * Observed by `PaneBackingViewModel`, which mirrors the active
     * drawing's counter into `State.drawingRevision`.
     */
    val drawingRevisionsFlow: StateFlow<Map<String, Int>> = _drawingRevisions.asStateFlow()

    private fun bumpDrawingRevision(fileRel: String) {
        _drawingRevisions.update { it + (fileRel to ((it[fileRel] ?: 0) + 1)) }
    }

    /**
     * The newest text of the drawing [fileRel]: an unwritten change sent
     * by a pane ([saveDrawing]) or else the file on disk; `null` when it
     * does not exist.
     *
     * Called by `PaneBackingViewModel.loadDrawing` for the pane's drawing
     * editor.
     */
    suspend fun drawingText(fileRel: String): String? =
        drawingLock.withLock { pendingDrawings[fileRel] } ?: repository.readDrawingText(fileRel)

    /**
     * Takes the drawing [fileRel]'s new [text] (Excalidraw JSON) from a
     * pane's drawing editor and writes it after the usual autosave pause —
     * [saveDebounceMillis] after the last change, at most
     * [maxSaveDelayMillis] after the first unwritten one. Until then the
     * file is in [unsavedFilesFlow].
     *
     * Called by `PaneBackingViewModel.onDrawingChanged`.
     */
    fun saveDrawing(fileRel: String, text: String) {
        scope.launch {
            val waited = drawingLock.withLock {
                pendingDrawings[fileRel] = text
                pendingDrawingSince.getOrPut(fileRel) { TimeSource.Monotonic.markNow() }.elapsedNow().inWholeMilliseconds
            }
            _unsavedFiles.update { it + fileRel }
            drawingSaveJobs.remove(fileRel)?.cancel()
            drawingSaveJobs[fileRel] = scope.launch {
                delay(minOf(saveDebounceMillis, maxOf(0L, maxSaveDelayMillis - waited)))
                writeDrawing(fileRel)
            }
        }
    }

    /** Writes the drawing [fileRel]'s pending text, if any, and tells the other panes. */
    private suspend fun writeDrawing(fileRel: String) {
        val wrote = drawingLock.withLock {
            val text = pendingDrawings.remove(fileRel) ?: return@withLock false
            pendingDrawingSince.remove(fileRel)
            runCatching { repository.writeDrawingText(fileRel, text) }
                .onFailure { println("[lunarbor] drawing save failed: $fileRel: ${it.message}") }
            true
        }
        _unsavedFiles.update { it - fileRel }
        if (wrote) bumpDrawingRevision(fileRel)
    }

    /**
     * Writes every drawing with a pending change now. Called by [flushAll]
     * and by `PaneBackingViewModel` when a pane leaves a drawing or closes.
     *
     * @param fileRel Only this drawing; `null` for all of them.
     */
    suspend fun flushDrawings(fileRel: String? = null) {
        val paths = drawingLock.withLock { pendingDrawings.keys.toList() }.filter { fileRel == null || it == fileRel }
        for (p in paths) {
            drawingSaveJobs.remove(p)?.cancel()
            writeDrawing(p)
        }
    }

    /**
     * Creates `Untitled.excalidraw` (or `Untitled 2.excalidraw`, …) in the
     * folder [dirRel] and refreshes that folder's listing. See
     * [NoteRepository.createDrawingFile].
     *
     * Called by `PaneBackingViewModel.newDrawingFile`.
     *
     * @return The new drawing's vault-relative path.
     */
    suspend fun createDrawingFile(dirRel: String): String {
        val rel = repository.createDrawingFile(dirRel)
        refreshVaultListing(dirRel)
        return rel
    }

    /**
     * Adds a Starred entry for [targetPathRel] labelled [title]. Goes
     * through this registry's repository so the link index sees the new
     * entry and later renames rewrite it. See
     * [NoteRepository.appendStarredEntry].
     */
    suspend fun addStarred(title: String, targetPathRel: String) {
        repository.appendStarredEntry(title, targetPathRel)
    }

    /** Removes every Starred entry for [targetPathRel]. See [NoteRepository.removeStarredEntry]. */
    suspend fun removeStarred(targetPathRel: String) {
        repository.removeStarredEntry(targetPathRel)
    }

    /**
     * Lists vault images and drawings for the `Insert Image` palette. Delegates
     * straight to [NoteRepository.listImageFiles]; not cached because the
     * autosave loop can mutate the on-disk tree without notifying us.
     * Leaves out what the app's privacy mode hides.
     */
    suspend fun listImageFiles(): List<String> = repository.listImageFiles().filterNot { isPathHidden(it) }

    /**
     * Persists a pasted image into the folder [dirRel] and returns its
     * vault-relative path, then refreshes that folder's listing so the
     * image shows in every pane's contents list at once. Delegates to
     * [NoteRepository.saveImageBytes]; exposed on the registry so panes
     * don't have to know about the repository directly.
     *
     * Called by `PaneBackingViewModel.onImagePasted`.
     *
     * @param dirRel Folder of the node (or `.md` note) being edited.
     */
    suspend fun saveImageBytes(dirRel: String, suggestedName: String, bytes: ByteArray): String {
        val rel = repository.saveImageBytes(dirRel, suggestedName, bytes)
        refreshVaultListing(dirRel)
        return rel
    }

    /**
     * Creates `Untitled.md` (or `Untitled 2.md`, …) in the folder
     * [dirRel] and refreshes that folder's listing so the new note shows
     * in every pane's contents list at once. Delegates the naming to
     * [NoteRepository.createMarkdownFile].
     *
     * Called by `PaneBackingViewModel.newMarkdownFile`.
     *
     * @param dirRel An existing folder, vault-relative (`""` = root).
     * @return The new note's vault-relative path.
     */
    suspend fun createMarkdownFile(dirRel: String): String {
        val rel = repository.createMarkdownFile(dirRel)
        refreshVaultListing(dirRel)
        return rel
    }

    // ------------------------------------------------------- agents (MCP)

    /**
     * The items of the node folder [folderRel]'s outline as on disk (empty
     * when it has none). Callers [flushAll] first when unsaved edits
     * matter. See [NoteRepository.nodeItemsOf]. Called by `McpTools`.
     */
    suspend fun nodeItemsOf(folderRel: String): List<NodeLine> = repository.nodeItemsOf(folderRel)

    /**
     * The folder a zoom into [titlePath] under the node folder [folderRel]
     * lands in, read from disk: each title (case-insensitive, as
     * `PaneBackingViewModel.restoreZoom` matches them) steps into its
     * folder-backed item's folder. A leaf, or a title that is not found,
     * stops the walk at the folder reached so far — where that item is
     * stored, as `PaneBackingViewModel.currentLocationPath` reports a leaf
     * zoom.
     *
     * Called by `AppShell.agentWorkspace` for windows whose pane has not
     * loaded yet (another tab's), so `list_windows` scopes them by where
     * they are zoomed, not by their outline file.
     *
     * @param folderRel Vault-relative node folder of the pane's outline.
     * @param titlePath Zoom titles from a top-level item down, as stored in
     *   `PaneBackingViewModel.FileHistoryEntry.zoomTitlePath`.
     * @return The vault-relative folder.
     */
    suspend fun folderOfZoomPath(folderRel: String, titlePath: List<String>): String {
        var folder = folderRel
        for (title in titlePath) {
            val target = title.lowercase()
            val item = repository.nodeItemsOf(folder).firstOrNull {
                when (it) {
                    is NodeLine.Leaf -> it.title.lowercase() == target
                    is NodeLine.Folder -> it.title.lowercase() == target
                    is NodeLine.Block -> it.title.ifEmpty { SubtreeCodec.blockTitleOf(it.content) }.lowercase() == target
                    else -> false
                }
            } ?: break
            val child = when (item) {
                is NodeLine.Folder -> item.folder
                is NodeLine.Block -> item.folder
                else -> null
            } ?: break
            folder = if (folder.isEmpty()) child else "$folder/$child"
        }
        return folder
    }

    /** See [NoteRepository.fileSizeOf]. Called by `McpTools`. */
    suspend fun fileSizeOf(fileRel: String): Long? = repository.fileSizeOf(fileRel)

    /** See [NoteRepository.readFileBytes]. Called by `McpTools`. */
    suspend fun readFileBytes(fileRel: String): ByteArray? = repository.readFileBytes(fileRel)

    /** See [NoteRepository.readTextFile]. Called by `McpTools`. */
    suspend fun readTextFile(fileRel: String): String? = repository.readTextFile(fileRel)

    /** The raw listing of [dirRel] ([NoteRepository.listVaultLevel]). Called by `McpTools`. */
    suspend fun listFolder(dirRel: String): List<VaultEntry> = repository.listVaultLevel(dirRel)

    /**
     * Creates a text file and refreshes its folder's listing; see
     * [NoteRepository.createTextFile]. Called by `McpTools`.
     *
     * @return The new file's vault-relative path.
     */
    suspend fun createFile(dirRel: String, stem: String, extension: String, text: String): String {
        val rel = repository.createTextFile(dirRel, stem, extension, text)
        refreshVaultListing(dirRel)
        return rel
    }

    /**
     * Moves the folder [folderRel] — one no bullet points at — to the trash
     * with everything in it ([NoteRepository.trashFolder]), then refreshes
     * the listings. Refused while a window shows a file inside it.
     * Called by `McpTools` (`delete`).
     *
     * @return An error message, or `null` when it was moved.
     */
    suspend fun trashFolder(folderRel: String): String? {
        val open = slotsLock.withLock { slots.keys.any { it.startsWith("$folderRel/") } }
        if (open) return "A window shows a file inside it; close that window first."
        val dest = repository.trashFolder(folderRel) ?: return "It could not be found."
        applyPathMoves(listOf(PathMove(folderRel, dest)))
        refreshVaultListings()
        return null
    }

    /**
     * Moves a node, file or folder for an agent ([NoteRepository.moveNode]
     * when [isNode], else [NoteRepository.movePath]): saves every open
     * document, moves on disk, rewrites links to the old path
     * ([applyPathMoves]) and reloads the open documents the move concerns
     * ([applyExternalChanges]), so windows on either parent update at once.
     * Refused while a window has a file inside [srcRel] (or [srcRel]
     * itself) open — its document would keep writing to the old path.
     *
     * Called by `McpTools` (`move`).
     *
     * @return The new path.
     * @throws IllegalStateException with a message for the agent when refused.
     */
    suspend fun moveForAgent(srcRel: String, dstParent: String, isNode: Boolean, index: Int?, newName: String?): String {
        val open = slotsLock.withLock { slots.keys.any { it == srcRel || it.startsWith("$srcRel/") } }
        check(!open) { "A window has it (or something inside it) open; close that window first (close_window)." }
        flushAll()
        val srcParent = srcRel.substringBeforeLast('/', "")
        val dst = if (isNode) repository.moveNode(srcRel, dstParent, index, newName) else repository.movePath(srcRel, dstParent, newName)
        if (dst != srcRel) applyPathMoves(listOf(PathMove(srcRel, dst)))
        val touched = listOf(srcRel, dst) +
            if (isNode) listOf(NoteRepository.outlineFileOf(srcParent), NoteRepository.outlineFileOf(dstParent)) else emptyList()
        applyExternalChanges(touched.distinct())
        return dst
    }

    /**
     * Runs one agent edit on the file [fileRel] through its [Document], so
     * the edit follows every save rule (folders made, renamed, trashed;
     * links rewritten) and a pane showing the file sees it at once:
     *
     *  1. saves every open document, so no unsaved edit is lost below;
     *  2. acquires the file's document (shared with any pane on it) and
     *     waits for it to load;
     *  3. runs [edit], then saves;
     *  4. releases the document and reloads every other open document the
     *     file concerns ([applyExternalChanges]) — a pane with this node
     *     expanded inside its parent's outline holds its own copy of it.
     *
     * Called by `McpTools`, which serializes its calls.
     *
     * @param edit Applies the change with `Document` primitives and returns
     *   what the tool reports. May throw to refuse; nothing is changed then.
     * @param after Runs once the edit is saved, with the document still held,
     *   to amend the report with the saved state (new folders, renamed ones).
     */
    suspend fun <T> editForAgent(fileRel: String, edit: (Document) -> T, after: (Document, T) -> T = { _, r -> r }): T {
        flushAll()
        val doc = acquire(fileRel)
        try {
            doc.stateFlow.first { it.isLoaded }
            val result = edit(doc)
            doc.flush()
            return after(doc, result)
        } finally {
            release(fileRel)
            applyExternalChanges(listOf(fileRel))
        }
    }

    /** Serializes [applyExternalChanges] batches. */
    private val externalChangeLock = Mutex()

    /**
     * Brings the app in step with files and folders changed outside it
     * ([NoteRepository.watchExternalChanges]): every open [Document] the
     * change concerns ([Document.isAffectedBy]) is reloaded from disk —
     * dropping its unsaved edits, the disk wins — changed notes are
     * re-read into the link index, and the listings, link statuses and
     * previews are refreshed ([refreshVaultListings]).
     *
     * Paths inside dot-folders (`.trash`) and dotfiles are ignored.
     *
     * @param pathsRel Vault-relative paths of changed files and folders.
     */
    suspend fun applyExternalChanges(pathsRel: List<String>) = externalChangeLock.withLock {
        val relevant = pathsRel.filter { p -> p.isNotEmpty() && p.split('/').none { it.startsWith(".") } }
        if (relevant.isEmpty()) return@withLock
        // The privacy modes file edited outside the app (or synced in).
        if (PrivacyConfig.FILE_NAME in relevant) loadPrivacyModes()
        for (doc in openDocuments()) {
            if (doc.isAffectedBy(relevant)) doc.reloadFromDisk()
        }
        // A drawing changed on disk: the disk wins over an unwritten
        // change, as for documents, and the panes showing it re-read it.
        for (p in relevant.filter { NoteRepository.isDrawingPath(it) }) {
            drawingSaveJobs.remove(p)?.cancel()
            drawingLock.withLock {
                pendingDrawings.remove(p)
                pendingDrawingSince.remove(p)
            }
            _unsavedFiles.update { it - p }
            bumpDrawingRevision(p)
        }
        for (p in relevant) {
            if (NoteRepository.isOutlineFile(p) || p.endsWith(NoteRepository.NOTE_EXTENSION)) repository.readNoteText(p)
        }
        refreshVaultListings()
    }

    /**
     * Re-reads the one folder [dirRel] and replaces (or adds) its cached
     * listing. Called when a pane navigates to a node, so the list it
     * shows is fresh even if nothing was saved since the last read.
     */
    suspend fun refreshVaultListing(dirRel: String) {
        val entries = repository.listVaultLevel(dirRel)
        _vaultListings.update { it + (dirRel to entries) }
    }

    /**
     * Re-fetches every directory currently in [vaultListingsFlow] and
     * replaces each entry with the fresh result. Triggered by
     * [Document]'s `onAfterSave` hook after every save tick so folders
     * created/removed by the save surface at once, and by the web shell
     * whenever the window regains focus so files added in Finder (or by
     * any other program) show up at that refresh. Also drops the cached
     * link-target list and re-checks link targets ([refreshLinkStatuses]),
     * so links broken or fixed on disk repaint accordingly, re-resolves
     * wiki links ([wikiLinksFlow]), and re-reads the linked nodes being
     * previewed ([linkPreviewsFlow]).
     */
    suspend fun refreshVaultListings() {
        vaultIndex.invalidateTargets()
        refreshLinkStatuses()
        refreshLinkPreviews()
        refreshWikiLinks()
        refreshBacklinks()
        val keys = _vaultListings.value.keys.toList()
        if (keys.isEmpty()) return
        val updates = HashMap<String, List<VaultEntry>>()
        for (k in keys) {
            updates[k] = repository.listVaultLevel(k)
        }
        _vaultListings.update { it + updates }
    }

    // ------------------------------------------------------------ privacy

    /**
     * The privacy modes and the app's current one.
     *
     * @property modes The vault's modes, in the dialog's order.
     * @property currentId The mode the whole app shows (every window and
     *   tab), or `null` for "No privacy".
     * @property filter What [currentId] hides ([PrivacyFilter.NONE] for
     *   none) — the tags as they are now, so editing the current mode's
     *   tags applies at once.
     * @property revision Bumped whenever what is hidden may have changed
     *   without [filter] changing (the text index learned of new tags, a
     *   save moved things), so panes re-check their location and repaint.
     */
    data class PrivacyView(
        val modes: List<PrivacyMode> = emptyList(),
        val currentId: String? = null,
        val filter: PrivacyFilter = PrivacyFilter.NONE,
        val revision: Int = 0,
    ) {
        /** The current mode, or `null` for "No privacy". */
        val current: PrivacyMode? get() = modes.firstOrNull { it.id == currentId }
    }

    private val _privacy = MutableStateFlow(PrivacyView())

    /** The privacy modes and the current one; observed by every pane and the web dialog. */
    val privacyFlow: StateFlow<PrivacyView> = _privacy.asStateFlow()

    /** What the app's current privacy mode hides ([PrivacyFilter.NONE] for "No privacy"). */
    val privacyFilter: PrivacyFilter get() = _privacy.value.filter

    private var privacyRevisionJob: Job? = null

    /**
     * After the text index changed while a mode is on: bumps
     * [PrivacyView.revision] once, a moment later (a vault scan reports
     * every file), so panes re-check what they show.
     */
    private fun schedulePrivacyRevision() {
        if (!privacyFilter.isActive || privacyRevisionJob?.isActive == true) return
        privacyRevisionJob = scope.launch {
            delay(PRIVACY_REVISION_DELAY_MS)
            _privacy.update { it.copy(revision = it.revision + 1) }
        }
    }

    /**
     * Reads the vault's modes from `_privacy.config`. The current mode is
     * kept when it still exists, otherwise the app falls back to "No
     * privacy". Called at boot by the platform shell (before panes render,
     * via [setPrivacyMode]) and when the file changes outside the app.
     */
    suspend fun loadPrivacyModes() {
        val modes = PrivacyConfig.parse(repository.readPrivacyConfig())
        applyPrivacy(modes, _privacy.value.currentId)
    }

    /**
     * Replaces the vault's modes with [modes] and writes `_privacy.config`.
     * Changing the tags of the current mode applies at once; removing it
     * falls back to "No privacy".
     *
     * Called by the web privacy dialog on every change (it saves as you go).
     */
    suspend fun setPrivacyModes(modes: List<PrivacyMode>) {
        repository.writePrivacyConfig(PrivacyConfig.format(modes))
        applyPrivacy(modes, _privacy.value.currentId)
    }

    /**
     * Makes [id] the mode the whole app shows (`null`: "No privacy"; an
     * unknown id counts as `null`). With a mode on, the text index is built
     * first, so hidden folders and notes are known before anything is shown
     * under the new mode. Panes on content now hidden move to the nearest
     * visible place themselves.
     *
     * Called by the platform shell at boot (the persisted mode) and by the
     * privacy dialog's mode picker.
     */
    suspend fun setPrivacyMode(id: String?) {
        val mode = _privacy.value.modes.firstOrNull { it.id == id }
        if (mode != null && mode.filter.isActive) {
            flushAll()
            textIndex.ensureBuilt()
        }
        applyPrivacy(_privacy.value.modes, mode?.id)
    }

    /**
     * Sets the modes and current mode, and when what is hidden changed,
     * brings the registry's own caches in line: search nodes re-run, wiki
     * links re-resolve, the link-target list is dropped.
     */
    private suspend fun applyPrivacy(modes: List<PrivacyMode>, currentId: String?) {
        val current = modes.firstOrNull { it.id == currentId }
        if (current != null && current.filter.isActive) textIndex.ensureBuilt()
        val before = _privacy.value
        val filter = current?.filter ?: PrivacyFilter.NONE
        _privacy.value = before.copy(
            modes = modes,
            currentId = current?.id,
            filter = filter,
            revision = before.revision + if (filter != before.filter) 1 else 0,
        )
        if (filter != before.filter) {
            for (key in requestedSearchNodes.toList()) runSearchNode(key)
            vaultIndex.invalidateTargets()
            refreshWikiLinks()
            refreshBacklinks()
        }
    }

    /**
     * The filter an agent connection scoped to the mode [modeId] works
     * under: [PrivacyFilter.NONE] for `null` ("No privacy"), or `null` when
     * no such mode exists any more — the connection is then off until the
     * user picks a scope again. Independent of the app's current mode.
     */
    fun filterForMode(modeId: String?): PrivacyFilter? {
        if (modeId.isNullOrEmpty()) return PrivacyFilter.NONE
        return _privacy.value.modes.firstOrNull { it.id == modeId }?.filter
    }

    /**
     * `true` when [filter] (by default the app's current mode) hides the
     * vault path [pathRel] — a folder, outline, note or other file
     * ([TextIndex.isPathHidden]). Always `false` with no mode on.
     */
    fun isPathHidden(pathRel: String, filter: PrivacyFilter = privacyFilter): Boolean =
        filter.isActive && textIndex.isPathHidden(pathRel, filter)

    /**
     * `true` when [filter] (by default the app's) hides something inside
     * the folder [folderRel] ([TextIndex.hasHiddenUnder]): deleting that
     * folder's node would take hidden content along.
     */
    fun hasHiddenUnder(folderRel: String, filter: PrivacyFilter = privacyFilter): Boolean =
        filter.isActive && textIndex.hasHiddenUnder(folderRel, filter)

    private companion object {
        /** Pause after a text-index change before [PrivacyView.revision] is bumped. */
        const val PRIVACY_REVISION_DELAY_MS: Long = 300

        /** Pause after an index change before search nodes re-run. */
        const val SEARCH_NODE_REFRESH_MS: Long = 3_000

        /** Most results kept for a search node (it lists them a page at a time). */
        const val SEARCH_NODE_MAX_HITS: Int = 2_000
    }
}
