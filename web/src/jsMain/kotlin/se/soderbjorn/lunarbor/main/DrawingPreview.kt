/*
 * DrawingPreview.kt (jsMain)
 * --------------------------
 * Read-only pictures of `.excalidraw` drawings, for a drawing embedded like
 * an image (`![](flow.excalidraw)`, inserted from the Insert Image palette)
 * and for the palette's thumbnails. Excalidraw's `exportToSvg` turns the
 * scene into an SVG, drawn into a host element.
 *
 * View layer only. The drawing's text is read through the
 * `lunarbor-asset://` protocol (the same URL an inline image loads), so
 * no view-model call is needed. Rendering is asynchronous: a host first
 * shows the last picture rendered for that path (so a repaint never
 * flickers), and a drawing is re-read at most every [RECHECK_MS] — a change
 * re-renders every host on screen showing it.
 *
 * Clicking an embedded drawing opens it in the drawing editor; that is
 * `MainScreen.handleImageMouseDown`, as for images.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import kotlin.js.Promise

/** How often, at most, a drawing on screen is re-read for changes. */
private const val RECHECK_MS = 2000.0

/**
 * The last picture rendered for one drawing.
 *
 * @property text The file text it was rendered from (`null`: unreadable).
 * @property theme The theme it was rendered in (`"dark"` / `"light"`).
 * @property markup The SVG's markup, or `null` for an empty or unreadable drawing.
 * @property widthPx The SVG's own width, used when the embed sets none.
 * @property checkedAt When the file was last read (`Date.now()`).
 */
private class DrawingPicture(
    val text: String?,
    val theme: String,
    val markup: String?,
    val widthPx: Int,
    var checkedAt: Double,
)

private val pictures = mutableMapOf<String, DrawingPicture>()
private val inFlight = mutableSetOf<String>()
private val previewScope = MainScope()

/** Attribute naming the drawing a preview host shows; [refreshHosts] finds hosts by it. */
private const val DRAWING_ATTR = "data-drawing-rel"

/**
 * Makes [host] show the drawing [vaultRel]: at once from the last picture
 * rendered for it (or a "Drawing…" placeholder), then re-rendered when the
 * file or the theme changed.
 *
 * Called by `createImageRunElement` for an embedded drawing and by the
 * Insert Image palette for a thumbnail.
 *
 * @param host The element the SVG goes into; its children are replaced.
 * @param vaultRel Vault-relative path of the `.excalidraw` file.
 * @param widthPx Fixed width for the picture (the embed's `|width`), or
 *   `null` for the drawing's own size; `0` to leave sizing to CSS (thumbnails).
 */
internal fun renderDrawingPreview(host: HTMLElement, vaultRel: String, widthPx: Int?) {
    host.setAttribute(DRAWING_ATTR, vaultRel)
    host.setAttribute("data-drawing-width", widthPx?.toString() ?: "")
    val theme = excalidrawTheme()
    val picture = pictures[vaultRel]
    if (picture != null) paint(host, picture) else paintPlaceholder(host, "Drawing…")
    val stale = picture == null || picture.theme != theme ||
        kotlin.js.Date.now() - picture.checkedAt > RECHECK_MS
    if (stale) refresh(vaultRel, theme)
}

/** Re-reads [vaultRel] and, when it or [theme] changed, renders it again for every host showing it. */
private fun refresh(vaultRel: String, theme: String) {
    if (!inFlight.add(vaultRel)) return
    previewScope.launch {
        try {
            val text = readDrawing(vaultRel)
            val old = pictures[vaultRel]
            if (old != null && old.text == text && old.theme == theme) {
                old.checkedAt = kotlin.js.Date.now()
                return@launch
            }
            pictures[vaultRel] = render(text, theme)
            refreshHosts(vaultRel)
        } catch (t: Throwable) {
            console.warn("[lunarbor] drawing preview failed: $vaultRel", t)
        } finally {
            inFlight.remove(vaultRel)
        }
    }
}

/** The text of [vaultRel] through the asset protocol, or `null` when it cannot be read. */
private suspend fun readDrawing(vaultRel: String): String? {
    val response: dynamic = window.fetch(lunarborAssetUrl(vaultRel)).await()
    if (response.ok != true) return null
    return (response.text() as Promise<String>).await()
}

/** Renders [text] (an `.excalidraw` file) with Excalidraw's `exportToSvg`. */
private suspend fun render(text: String?, theme: String): DrawingPicture {
    val now = kotlin.js.Date.now()
    val scene = parseScene(text)
    val elements: dynamic = scene?.elements
    val live = if (elements == null || elements == undefined) 0
    else (elements.filter { e: dynamic -> e.isDeleted != true }.length as Int)
    if (scene == null || live == 0) return DrawingPicture(text, theme, null, 0, now)
    // Kept in a local first: indexing a dynamic suspend result inline
    // reads the index off the suspension marker.
    val modules: dynamic = loadExcalidrawModules()
    val excalidraw: dynamic = modules[2]
    val appState: dynamic = js("({})")
    val saved: dynamic = scene.appState
    if (saved != null && saved != undefined) js("Object").assign(appState, saved)
    appState.exportBackground = true
    appState.exportWithDarkMode = theme == "dark"
    // At the drawing's own size; a saved export scale (2× for crisp PNGs)
    // would double the picture.
    appState.exportScale = 1
    val opts: dynamic = js("({})")
    opts.elements = elements
    opts.appState = appState
    opts.files = scene.files ?: js("({})")
    opts.exportPadding = 16
    val svg: dynamic = (excalidraw.exportToSvg(opts) as Promise<dynamic>).await()
    val width = (svg.getAttribute("width") as? String)?.toDoubleOrNull()?.toInt() ?: 0
    return DrawingPicture(text, theme, svg.outerHTML as String, width, now)
}

/** Repaints every connected host showing [vaultRel] from its newest picture. */
private fun refreshHosts(vaultRel: String) {
    val picture = pictures[vaultRel] ?: return
    for (node in document.querySelectorAll("[$DRAWING_ATTR]").asList()) {
        val host = node as? HTMLElement ?: continue
        if (host.getAttribute(DRAWING_ATTR) == vaultRel) paint(host, picture)
    }
}

private fun paint(host: HTMLElement, picture: DrawingPicture) {
    val markup = picture.markup ?: return paintPlaceholder(host, if (picture.text == null) "Missing drawing" else "Empty drawing")
    host.innerHTML = markup
    host.classList.remove("is-placeholder")
    val fixed = host.getAttribute("data-drawing-width")?.toIntOrNull()
    when {
        fixed == 0 -> host.style.removeProperty("width")
        fixed != null -> host.style.width = "${fixed}px"
        picture.widthPx > 0 -> host.style.width = "${picture.widthPx}px"
    }
}

private fun paintPlaceholder(host: HTMLElement, label: String) {
    host.innerHTML = ""
    host.classList.add("is-placeholder")
    host.style.removeProperty("width")
    host.textContent = label
}
