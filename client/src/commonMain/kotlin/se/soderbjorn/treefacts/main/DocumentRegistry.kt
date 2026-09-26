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
import se.soderbjorn.treefacts.data.VaultEntry
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
     * App-scoped outline index used by the Insert Link feature. Bypasses
     * its own cache for files the registry currently holds — always
     * reads from [Document.stateFlow] for those — so the autosave loop
     * does not invalidate anything. Closed-file entries are invalidated
     * here whenever a save tick completes ([refreshVaultListings])
     * or a [Document] is shut down.
     */
    val vaultIndex: VaultIndex = VaultIndex(
        loadFromDisk = repository::loadFile,
        listAllMdFiles = repository::listAllNoteFiles,
        rootFileName = repository.rootFileName,
        openDocuments = ::openDocumentsSnapshot,
    )

    private fun openDocumentsSnapshot(): Map<String, Document> {
        // Snapshot taken without the slots lock — slot mutations would
        // race with VaultIndex lookups otherwise, and we only need a
        // best-effort view (newly-acquired docs can lag one lookup; the
        // next call sees them).
        val result = HashMap<String, Document>(slots.size)
        for ((rel, slot) in slots) result[rel] = slot.document
        return result
    }

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

    init {
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
            onAfterSave = {
                refreshVaultListings()
                // The save may have promoted/demoted bullets inside this
                // file; drop the cache entry so the next non-live lookup
                // (which only happens once this file is closed again)
                // re-reads from disk. Live lookups bypass the cache
                // anyway, so this is purely belt-and-braces for after
                // [release] tears the [Document] down.
                vaultIndex.invalidate(fileRel)
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
        // While the file is open as a [Document], the index reads from
        // it live, so any cached pre-open parse is now misleading. Drop
        // it so closing the doc later re-reads fresh from disk.
        vaultIndex.invalidate(fileRel)
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
        // Document.shutdown flushed one final save; the next non-live
        // lookup of this file will need to re-read from disk to see
        // those changes, so invalidate any cache entry.
        vaultIndex.invalidate(fileRel)
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

    /**
     * Gives a folder picked from the Insert Link modal's folder-stub
     * results its (empty) outline file, `<dir>/.treefacts`, so it becomes
     * a node the link resolver can walk to. No-op when the file already
     * exists. After a write, refreshes [vaultListingsFlow] and drops the
     * [VaultIndex] cache entry so the new node is visible at once.
     *
     * @param fileRel Vault-relative outline path, `<dir>/.treefacts`, as
     *   carried by [se.soderbjorn.treefacts.data.VaultIndex.SearchHit.fileRel].
     * @return `true` when a new file was written.
     */
    suspend fun ensureFolderStub(fileRel: String): Boolean {
        val created = repository.createEmptyFile(fileRel)
        if (!created) return false
        refreshVaultListings()
        vaultIndex.invalidate(fileRel)
        return true
    }

    /**
     * Lists vault images for the `Insert Image` palette. Delegates
     * straight to [NoteRepository.listImageFiles]; not cached because the
     * autosave loop can mutate the on-disk tree without notifying us.
     */
    suspend fun listImageFiles(): List<String> = repository.listImageFiles()

    /**
     * Persists a pasted image and returns its vault-relative path.
     * Delegates to [NoteRepository.saveImageBytes]; exposed on the
     * registry so panes don't have to know about the repository
     * directly.
     */
    suspend fun saveImageBytes(suggestedName: String, bytes: ByteArray): String =
        repository.saveImageBytes(suggestedName, bytes)

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
     * any other program) show up at that refresh.
     */
    suspend fun refreshVaultListings() {
        val keys = _vaultListings.value.keys.toList()
        if (keys.isEmpty()) return
        val updates = HashMap<String, List<VaultEntry>>()
        for (k in keys) {
            updates[k] = repository.listVaultLevel(k)
        }
        _vaultListings.update { it + updates }
    }
}
