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
 * ([applyPathMoves]).
 *
 * commonMain only — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.treefacts.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.soderbjorn.treefacts.data.NoteRepository
import se.soderbjorn.treefacts.data.PathMove
import se.soderbjorn.treefacts.data.VaultEntry
import se.soderbjorn.treefacts.data.VaultEntryKind
import se.soderbjorn.treefacts.data.VaultIndex

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
     * Link target path → whether something exists there, for every `tf:`
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

    init {
        repository.noteTextObserver = vaultIndex::noteText
        // Eagerly populate the vault root listing so the root's contents
        // list paints right after boot.
        scope.launch { ensureVaultListing("") }
    }

    /**
     * Increments the refcount for [fileRel] and returns the live
     * [Document]. Creates and starts a fresh [Document] on first
     * acquire. Two panes that acquire the same `fileRel` get the same
     * instance — concurrent edits show up in both.
     */
    suspend fun acquire(fileRel: String): Document = slotsLock.withLock {
        // The registry only owns markdown documents — image paths are
        // viewed via the read-only image view, which bypasses the
        // registry entirely (`PaneBackingViewModel.switchActiveFile`
        // skips this call). Fail loudly if a future caller forgets.
        require(!NoteRepository.isImagePath(fileRel)) {
            "DocumentRegistry.acquire called with an image path: $fileRel"
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
                _unsavedFiles.update { if (dirty) it + fileRel else it - fileRel }
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

    // ------------------------------------------------------------- links

    /**
     * Keeps links working after a save renamed or moved folders or files
     * (TRF-8). Called from every document's after-save hook with that
     * save's [moves]:
     *
     *  1. Carries the link index's keys along with moved folders.
     *  2. Rewrites the `tf:` links in every open document's lines
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
     * Lists vault images for the `Insert Image` palette. Delegates
     * straight to [NoteRepository.listImageFiles]; not cached because the
     * autosave loop can mutate the on-disk tree without notifying us.
     */
    suspend fun listImageFiles(): List<String> = repository.listImageFiles()

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
     * so links broken or fixed on disk repaint accordingly.
     */
    suspend fun refreshVaultListings() {
        vaultIndex.invalidateTargets()
        refreshLinkStatuses()
        val keys = _vaultListings.value.keys.toList()
        if (keys.isEmpty()) return
        val updates = HashMap<String, List<VaultEntry>>()
        for (k in keys) {
            updates[k] = repository.listVaultLevel(k)
        }
        _vaultListings.update { it + updates }
    }
}
