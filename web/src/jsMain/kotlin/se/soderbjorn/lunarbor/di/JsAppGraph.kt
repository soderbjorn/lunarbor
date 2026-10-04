/* JsAppGraph.kt (jsMain) — Metro DI graph for the web/Electron renderer.
 *
 * Declares the app-scoped infrastructure (coroutine scope, persister,
 * FileSystem, NoteRepository, DocumentRegistry). Per-pane view models are
 * deliberately NOT in the graph — AppShell.ensurePaneViewModel builds them.
 *
 * The vault root comes from the Electron main process (LUNARBOR_VAULT /
 * LUNARBOR_LOCAL_DATA, default ~/lunarbor-db) via the preload bridge's
 * `noteApi.vaultRoot`; this file never hardcodes a vault path. In the
 * browser demo (no bridge, see demo/DemoMode.kt) the file system, vault
 * root and persister are the demo's in-memory stand-ins instead. */
package se.soderbjorn.lunarbor.di

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.web.LocalStoragePersister
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.demo.DEMO_VAULT_ROOT
import se.soderbjorn.lunarbor.demo.DemoPersister
import se.soderbjorn.lunarbor.demo.demoFileSystem
import se.soderbjorn.lunarbor.demo.isDemoMode
import se.soderbjorn.lunarbor.main.DocumentRegistry
import se.soderbjorn.lunarbor.platform.FileSystem
import se.soderbjorn.lunarbor.platform.PlatformFileSystem

object AppScope

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
@SingleIn(AppScope::class)
@DependencyGraph
interface JsAppGraph {
    val coroutineScope: CoroutineScope

    /**
     * The app's one [FileSystem]: the in-memory demo vault in the
     * browser demo, the Electron bridge otherwise. Anything outside the
     * registry that reads the vault (the Starred modal's private
     * repository) must use this instance, never a fresh
     * [PlatformFileSystem], or it misses the demo vault.
     */
    val fileSystem: FileSystem

    /**
     * Singleton [DocumentRegistry] shared across every pane. The
     * registry hands out [se.soderbjorn.lunarbor.main.Document]
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
     * [se.soderbjorn.lunarbor.di.LunarborElectronPersister] when running
     * inside the desktop wrapper (the preload script installs
     * `globalThis.darknessApi`); falls back to namespaced `localStorage`
     * in a plain browser. Either is wrapped in
     * [LunarborDefaultLookPersister], so an unchosen theme slot reads as
     * Lunarbor Dark / Lunarbor Light.
     *
     * The Electron backend is lunarbor's own persister rather than the
     * toolkit's stock `ElectronIpcPersister` because the latter only
     * routes three keys and drops the theme keys — see
     * [LunarborElectronPersister] for why.
     */
    val persister: Persister

    @SingleIn(AppScope::class)
    @Provides
    fun provideCoroutineScope(): CoroutineScope = GlobalScope

    @SingleIn(AppScope::class)
    @Provides
    fun providePersister(): Persister =
        if (isDemoMode()) DemoPersister()
        else LunarborDefaultLookPersister(
            tryLunarborElectronPersister() ?: LocalStoragePersister(namespace = "lunarbor"),
        )

    @SingleIn(AppScope::class)
    @Provides
    fun provideFileSystem(): FileSystem = demoFileSystem ?: PlatformFileSystem()

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
 * - No bridge at all (plain browser run): the browser demo's in-memory
 *   vault, [DEMO_VAULT_ROOT].
 *
 * @return The absolute vault root.
 */
private fun bridgeVaultRoot(): String {
    if (isDemoMode()) return DEMO_VAULT_ROOT
    val bridge = window.asDynamic().noteApi
    val root = bridge.vaultRoot as? String
    check(!root.isNullOrBlank()) {
        "noteApi.vaultRoot missing; the Electron preload must pass --lunarbor-vault"
    }
    return root
}
