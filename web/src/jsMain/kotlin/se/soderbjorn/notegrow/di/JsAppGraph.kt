package se.soderbjorn.notegrow.di

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import se.soderbjorn.notegrow.data.NoteRepository
import se.soderbjorn.notegrow.main.DocumentBackingViewModel
import se.soderbjorn.notegrow.main.DocumentViewBackingViewModel
import se.soderbjorn.notegrow.main.MainViewModel
import se.soderbjorn.notegrow.platform.FileSystem

object AppScope

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
@SingleIn(AppScope::class)
@DependencyGraph
interface JsAppGraph {
    val mainViewModel: MainViewModel
    val coroutineScope: CoroutineScope
    /**
     * Singleton holding the live document state. Every notegrow pane
     * binds its own [DocumentViewBackingViewModel] (with its own zoom
     * navigation + selection) to this same backing VM so all panes
     * mutate one shared document but maintain independent views.
     */
    val documentBackingViewModel: DocumentBackingViewModel

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
    fun provideDocumentBackingViewModel(
        repository: NoteRepository,
        scope: CoroutineScope
    ): DocumentBackingViewModel = DocumentBackingViewModel(repository, scope)

    @SingleIn(AppScope::class)
    @Provides
    fun provideDocumentViewBackingViewModel(
        documentBackingViewModel: DocumentBackingViewModel,
        scope: CoroutineScope
    ): DocumentViewBackingViewModel =
        DocumentViewBackingViewModel(documentBackingViewModel, scope)

    @SingleIn(AppScope::class)
    @Provides
    fun provideMainViewModel(
        backing: DocumentViewBackingViewModel,
        scope: CoroutineScope
    ): MainViewModel = MainViewModel(scope, backing)
}

fun createJsAppGraph(): JsAppGraph = createGraph<JsAppGraph>()
