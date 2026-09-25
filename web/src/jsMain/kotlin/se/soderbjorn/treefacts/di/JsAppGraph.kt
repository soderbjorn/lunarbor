/* JsAppGraph.kt (jsMain) — Metro DI graph for the web/Electron renderer.
 *
 * Declares the app-scoped infrastructure (coroutine scope, persister,
 * FileSystem, NoteRepository, DocumentRegistry). Per-pane view models are
 * deliberately NOT in the graph — AppShell.ensurePaneViewModel builds them.
 *
 * The vault root comes from the Electron main process (TREEFACTS_VAULT /
 * TREEFACTS_LOCAL_DATA, default ~/treefacts-db) via the preload bridge's
 * `noteApi.vaultRoot`; this file never hardcodes a vault path. */
package se.soderbjorn.treefacts.di

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.web.LocalStoragePersister
import se.soderbjorn.treefacts.data.NoteRepository
import se.soderbjorn.treefacts.main.DocumentRegistry
import se.soderbjorn.treefacts.platform.FileSystem

object AppScope

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
@SingleIn(AppScope::class)
@DependencyGraph
interface JsAppGraph {
    val coroutineScope: CoroutineScope

    /**
     * Singleton [DocumentRegistry] shared across every pane. The
     * registry hands out [se.soderbjorn.treefacts.main.Document]
     * instances by `fileRel`, refcounted: two panes pointed at the
     * same file get the same Document (so concurrent edits stay in
     * sync); when the last pane releases a file, the Document is
     * flushed and torn down. Per-pane state (cursor, zoom, history)
     * lives on each pane's `PaneBackingViewModel`, created in
     * `AppShell.ensurePaneViewModel`.
     */
    val documentRegistry: DocumentRegistry

    /**
     * Durable KV bridge for theme / layout / ui-settings. Backed by
     * [se.soderbjorn.treefacts.di.TreeFactsElectronPersister] when running
     * inside the desktop wrapper (the preload script installs
     * `globalThis.darknessApi`); falls back to namespaced `localStorage`
     * in a plain browser.
     *
     * The Electron backend is treefacts's own persister rather than the
     * toolkit's stock `ElectronIpcPersister` because the latter only
     * routes three keys and drops the theme keys — see
     * [TreeFactsElectronPersister] for why.
     */
    val persister: Persister

    @SingleIn(AppScope::class)
    @Provides
    fun provideCoroutineScope(): CoroutineScope = GlobalScope

    @SingleIn(AppScope::class)
    @Provides
    fun providePersister(): Persister =
        tryTreeFactsElectronPersister() ?: LocalStoragePersister(namespace = "treefacts")

    @SingleIn(AppScope::class)
    @Provides
    fun provideFileSystem(): FileSystem = FileSystem()

    @SingleIn(AppScope::class)
    @Provides
    fun provideNoteRepository(fileSystem: FileSystem): NoteRepository =
        NoteRepository(fileSystem, rootDirectory = bridgeVaultRoot())

    @SingleIn(AppScope::class)
    @Provides
    fun provideDocumentRegistry(
        repository: NoteRepository,
        scope: CoroutineScope,
    ): DocumentRegistry = DocumentRegistry(repository, scope)
}

fun createJsAppGraph(): JsAppGraph = createGraph<JsAppGraph>()

/**
 * Read the vault root the Electron main process resolved for this run,
 * exposed by `electron/preload.js` as `noteApi.vaultRoot`.
 *
 * Called by [JsAppGraph.provideNoteRepository].
 *
 * - Bridge present with a root: returns it.
 * - Bridge present without a root (stale preload): throws rather than
 *   guessing, because a guess could be the maintainer's real vault.
 * - No bridge at all (plain browser run): returns `""`. No file I/O is
 *   possible there anyway — `FileSystem` fails on its first call — so the
 *   value is never used to touch disk.
 *
 * @return The absolute vault root, or `""` outside Electron.
 */
private fun bridgeVaultRoot(): String {
    val bridge = window.asDynamic().noteApi
    if (bridge == null || bridge == undefined) return ""
    val root = bridge.vaultRoot as? String
    check(!root.isNullOrBlank()) {
        "noteApi.vaultRoot missing; the Electron preload must pass --treefacts-vault"
    }
    return root
}
