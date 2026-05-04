package se.soderbjorn.notegrow.di

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import se.soderbjorn.notegrow.data.NoteRepository
import se.soderbjorn.notegrow.main.DocumentRegistry
import se.soderbjorn.notegrow.platform.FileSystem

object AppScope

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
@SingleIn(AppScope::class)
@DependencyGraph
interface JsAppGraph {
    val coroutineScope: CoroutineScope

    /**
     * Singleton [DocumentRegistry] shared across every pane. The
     * registry hands out [se.soderbjorn.notegrow.main.Document]
     * instances by `fileRel`, refcounted: two panes pointed at the
     * same file get the same Document (so concurrent edits stay in
     * sync); when the last pane releases a file, the Document is
     * flushed and torn down. Per-pane state (cursor, zoom, history)
     * lives on each pane's `PaneBackingViewModel`, created in
     * `AppShell.ensurePaneViewModel`.
     */
    val documentRegistry: DocumentRegistry

    @SingleIn(AppScope::class)
    @Provides
    fun provideCoroutineScope(): CoroutineScope = GlobalScope

    @SingleIn(AppScope::class)
    @Provides
    fun provideFileSystem(): FileSystem = FileSystem()

    @SingleIn(AppScope::class)
    @Provides
    fun provideNoteRepository(fileSystem: FileSystem): NoteRepository =
        NoteRepository(fileSystem)

    @SingleIn(AppScope::class)
    @Provides
    fun provideDocumentRegistry(
        repository: NoteRepository,
        scope: CoroutineScope,
    ): DocumentRegistry = DocumentRegistry(repository, scope)
}

fun createJsAppGraph(): JsAppGraph = createGraph<JsAppGraph>()
