/*
 * DrawingEditor.kt (jsMain)
 * -------------------------
 * The pane's drawing editor: the full Excalidraw UI (`@excalidraw/excalidraw`,
 * a React component) mounted in place of the outline editor while the pane
 * shows a `.excalidraw` file (`PaneBackingViewModel.State.isDrawingView`).
 * `MainScreen` owns the host element and calls [DrawingEditor.show] /
 * [DrawingEditor.hide] from its state collector.
 *
 * View layer only: the drawing's text comes from and goes back through the
 * pane VM (`MainViewModel.loadDrawing` / `onDrawingChanged`); the registry
 * owns the autosave pause and the disk. What lives here is ephemeral UI
 * state — the React root, Excalidraw's imperative API, and the text this
 * editor last loaded or sent, so its own writes coming back as a
 * `drawingRevision` bump are not reloaded.
 *
 * React, ReactDOM and Excalidraw are loaded with a dynamic `import()`, so
 * webpack puts them in their own chunk and panes that never open a drawing
 * never pay for them. Excalidraw's fonts are served from the bundle
 * (`excalidraw-assets/`, copied by `web/build.gradle.kts`) via
 * `window.EXCALIDRAW_ASSET_PATH`; fonts not copied fall back to its CDN.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import kotlin.js.Promise

/**
 * One pane's Excalidraw editor, mounted into [host] on demand.
 *
 * ### Callers
 * - `MainScreen`'s state collector: [show] while the pane shows a drawing,
 *   [hide] otherwise.
 *
 * @param host The element Excalidraw renders into. Must have a definite
 *   height (Excalidraw fills its container); `MainScreen` makes it a flex
 *   child of the pane root.
 * @param viewModel The pane's facade, for loading and saving the drawing.
 * @param scope Scope for the asynchronous module load and file reads.
 */
class DrawingEditor(
    private val host: HTMLElement,
    private val viewModel: MainViewModel,
    private val scope: CoroutineScope,
) {
    /** The drawing currently mounted (or being mounted); `null` when hidden. */
    private var fileRel: String? = null

    /** The `drawingRevision` this editor last caught up with. */
    private var revision: Int = -1

    /** Bumped on every mount / unmount, so a slow load for an older mount is dropped. */
    private var generation: Int = 0

    private var reactRoot: dynamic = null
    private var api: dynamic = null
    private var modules: dynamic = null
    private var theme: String = "dark"

    /** The scene text this editor last loaded or sent: its own echo is not reloaded. */
    private var lastText: String? = null

    /**
     * Cheap fingerprint of the scene last seen in `onChange` (element
     * versions + background + file count). Excalidraw calls `onChange` on
     * every pointer move; only a changed fingerprint is serialized and
     * saved. `null` until the first `onChange` after a (re)load, which only
     * records it — opening a drawing never writes it.
     */
    private var lastSignature: String? = null

    /**
     * Shows the drawing [drawingRel] — mounting Excalidraw for it when it
     * is not the one mounted — and re-reads it when [drawingRevision] moved
     * on since the last call (another pane wrote it, or it changed on disk).
     * A drawing renamed from the page title (`MainViewModel.renamedTo`)
     * stays mounted.
     *
     * @param drawingRel Vault-relative path of the `.excalidraw` file.
     * @param drawingRevision `PaneBackingViewModel.State.drawingRevision`.
     */
    fun show(drawingRel: String, drawingRevision: Int) {
        host.style.display = "block"
        val wantTheme = currentTheme()
        val shown = fileRel
        if (shown != null && drawingRel != shown && viewModel.renamedTo(shown) == drawingRel) {
            // Renamed from the page title: same scene, new file. Keep the
            // canvas; later changes save under the new name.
            fileRel = drawingRel
            revision = drawingRevision
        }
        if (drawingRel != fileRel) {
            unmount()
            fileRel = drawingRel
            revision = drawingRevision
            theme = wantTheme
            mount(drawingRel)
            return
        }
        if (wantTheme != theme) {
            theme = wantTheme
            val appState = js("({})")
            appState.theme = wantTheme
            val update = js("({})")
            update.appState = appState
            api?.updateScene(update)
        }
        if (drawingRevision != revision) {
            revision = drawingRevision
            reload(drawingRel)
        }
    }

    /**
     * Hides the editor and unmounts Excalidraw. The last change has
     * already been handed to the VM by `onChange`; the registry writes it
     * when the pane leaves the drawing.
     */
    fun hide() {
        if (fileRel != null || reactRoot != null) unmount()
        host.style.display = "none"
    }

    private fun unmount() {
        generation++
        reactRoot?.unmount()
        reactRoot = null
        api = null
        fileRel = null
        lastText = null
        lastSignature = null
        host.innerHTML = ""
    }

    private fun mount(drawingRel: String) {
        val gen = generation
        host.innerHTML = ""
        val loading = document.createElement("div") as HTMLElement
        loading.className = "lunarbor-drawing-loading"
        loading.textContent = "Loading drawing…"
        host.appendChild(loading)
        scope.launch {
            val mods = try {
                loadModules()
            } catch (t: Throwable) {
                if (gen == generation) loading.textContent = "Could not load the drawing editor: ${t.message}"
                return@launch
            }
            val text = viewModel.loadDrawing(drawingRel)
            if (gen != generation) return@launch
            val scene = parseScene(text)
            if (scene == null) {
                loading.textContent = "Not a readable Excalidraw file: $drawingRel"
                return@launch
            }
            lastText = text
            lastSignature = null
            host.innerHTML = ""
            val mountPoint = document.createElement("div") as HTMLElement
            mountPoint.className = "lunarbor-drawing-root"
            host.appendChild(mountPoint)
            val react = mods[0]
            val reactDomClient = mods[1]
            val excalidraw = mods[2]
            val initialData = js("({})")
            initialData.elements = scene.elements ?: js("[]")
            initialData.appState = scene.appState ?: js("({})")
            initialData.files = scene.files ?: js("({})")
            initialData.scrollToContent = true
            val props = js("({})")
            props.initialData = initialData
            props.theme = theme
            props.name = drawingRel.substringAfterLast('/').removeSuffix(".excalidraw")
            props.excalidrawAPI = { a: dynamic -> if (gen == generation) api = a }
            props.onChange = { elements: dynamic, appState: dynamic, files: dynamic ->
                if (gen == generation) onSceneChange(fileRel ?: drawingRel, excalidraw, elements, appState, files)
            }
            props.UIOptions = uiOptions()
            val root = reactDomClient.createRoot(mountPoint)
            root.render(react.createElement(excalidraw.Excalidraw, props))
            reactRoot = root
        }
    }

    /**
     * Excalidraw's menu without the actions that would swap the scene for
     * another file, save it elsewhere or fight the app theme — the pane's
     * file is the drawing; export to an image stays.
     */
    private fun uiOptions(): dynamic {
        val canvasActions = js("({})")
        canvasActions.loadScene = false
        canvasActions.saveToActiveFile = false
        canvasActions.toggleTheme = null
        val options = js("({})")
        options.canvasActions = canvasActions
        return options
    }

    private fun onSceneChange(drawingRel: String, excalidraw: dynamic, elements: dynamic, appState: dynamic, files: dynamic) {
        val signature = sceneSignature(elements, appState, files)
        val previous = lastSignature
        lastSignature = signature
        if (previous == null || previous == signature) return
        val text = excalidraw.serializeAsJSON(elements, appState, files, "local") as String
        if (text == lastText) return
        lastText = text
        viewModel.onDrawingChanged(drawingRel, text)
    }

    /** Re-reads [drawingRel] after a revision bump and puts it on the canvas, unless it is our own text. */
    private fun reload(drawingRel: String) {
        val gen = generation
        scope.launch {
            val text = viewModel.loadDrawing(drawingRel) ?: return@launch
            if (gen != generation || text == lastText) return@launch
            val scene = parseScene(text) ?: return@launch
            val a = api ?: return@launch
            val mods = modules ?: return@launch
            lastText = text
            lastSignature = null
            val files = scene.files
            if (files != null && files != undefined) a.addFiles(js("Object").values(files))
            val update = js("({})")
            update.elements = mods[2].restoreElements(scene.elements ?: js("[]"), null)
            val bg = scene.appState?.viewBackgroundColor
            if (bg != null && bg != undefined) {
                val appState = js("({})")
                appState.viewBackgroundColor = bg
                update.appState = appState
            }
            a.updateScene(update)
        }
    }

    private suspend fun loadModules(): dynamic {
        val loaded = loadExcalidrawModules()
        modules = loaded
        return loaded
    }

    private fun currentTheme(): String = excalidrawTheme()
}

/** The modules [importExcalidraw] resolved, once loaded. */
private var excalidrawModules: dynamic = null

/**
 * React, ReactDOM's client and Excalidraw, loaded once for the whole app
 * (the first drawing editor or inline drawing preview pays for it). Sets
 * `window.EXCALIDRAW_ASSET_PATH` first so fonts come from the bundle.
 *
 * Called by [DrawingEditor] and `renderDrawingPreview`.
 *
 * @return `[react, reactDomClient, excalidraw, css]`.
 */
internal suspend fun loadExcalidrawModules(): dynamic {
    val cached = excalidrawModules
    if (cached != null && cached != undefined) return cached
    if (window.asDynamic().EXCALIDRAW_ASSET_PATH == undefined) {
        window.asDynamic().EXCALIDRAW_ASSET_PATH = js("new URL('excalidraw-assets/', document.baseURI).href")
    }
    val loaded: dynamic = importExcalidraw().await()
    excalidrawModules = loaded
    return loaded
}

/**
 * `"dark"` or `"light"`, from the luminance of the page background (the
 * app theme's `--t-bg`). Called by [DrawingEditor] for Excalidraw's theme
 * and by `renderDrawingPreview` for dark-mode export.
 */
internal fun excalidrawTheme(): String {
    val body = document.body ?: return "dark"
    val bg = window.getComputedStyle(body).backgroundColor
    val parts = Regex("""[\d.]+""").findAll(bg).map { it.value.toDouble() }.toList()
    if (parts.size < 3) return "dark"
    val luminance = (0.2126 * parts[0] + 0.7152 * parts[1] + 0.0722 * parts[2]) / 255.0
    return if (luminance < 0.5) "dark" else "light"
}

/** Parses an `.excalidraw` file's text; `{}` for an empty file, `null` when it is not JSON. */
internal fun parseScene(text: String?): dynamic {
    if (text == null || text.isBlank()) return js("({})")
    return try {
        JSON.parse<dynamic>(text)
    } catch (_: Throwable) {
        null
    }
}

/** See [DrawingEditor.lastSignature]. */
private fun sceneSignature(elements: dynamic, appState: dynamic, files: dynamic): String {
    var versions = 0.0
    val count = elements.length as Int
    for (i in 0 until count) versions += (elements[i].version as Number).toDouble()
    val fileCount = if (files == null || files == undefined) 0 else (js("Object").keys(files).length as Int)
    return "$count:$versions:${appState.viewBackgroundColor}:$fileCount"
}

/**
 * React, ReactDOM's client and Excalidraw (with its stylesheet), as one
 * promise of `[react, reactDomClient, excalidraw, css]`. A dynamic import,
 * so webpack splits them into a chunk of their own.
 */
private fun importExcalidraw(): Promise<dynamic> =
    js("Promise.all([import('react'), import('react-dom/client'), import('@excalidraw/excalidraw'), import('@excalidraw/excalidraw/index.css')])")
