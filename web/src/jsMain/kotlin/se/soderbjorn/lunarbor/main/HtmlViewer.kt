/*
 * HtmlViewer.kt (jsMain)
 * ----------------------
 * Shows an HTML page from the vault in the pane, like a browser tab,
 * while the pane's `activeFileRel` points at one
 * (`PaneBackingViewModel.State.isHtmlView`). `MainScreen` hides the
 * scroll wrapper (editor, image view, folder contents list) and shows
 * this viewer's host in its place, filling the pane below the title.
 *
 * The page loads from its `lunarbor-asset://` URL ([lunarborAssetUrl]),
 * so relative links, stylesheets, scripts and images resolve next to the
 * file. It runs in a sandboxed `<iframe>`: scripts and forms work, but
 * without `allow-same-origin` the page has an opaque origin and can reach
 * neither the app's window nor its preload API. `target="_blank"` links
 * open in the system browser (the main process's window-open handler).
 *
 * Read-only: leave with Back, the breadcrumb or another file. Platform
 * view only — no business rules.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunarbor.data.NoteRepository

/**
 * The web page view living in [host] (a sibling of the scroll wrapper,
 * built by `MainScreen`).
 *
 * ### Callers
 * `MainScreen`'s state collector: [show] on every emission while the
 * pane shows an HTML page, [hide] otherwise.
 *
 * @param host Flex child filling the pane below the title; hidden while
 *   no page shows.
 */
internal class HtmlViewer(private val host: HTMLElement) {

    /** Vault-relative path of the page loaded in the frame, or `null`. */
    private var shownRel: String? = null

    /**
     * Shows [fileRel] in the frame. Cheap to call on every emission: the
     * frame is only (re)built when the path changes, so the page keeps
     * its scroll position and script state across repaints.
     */
    fun show(fileRel: String) {
        host.style.display = "block"
        if (fileRel == shownRel) return
        shownRel = fileRel
        host.innerHTML = ""
        val frame = document.createElement("iframe") as HTMLElement
        frame.className = "lunarbor-html-frame"
        frame.setAttribute("sandbox", "allow-scripts allow-forms allow-popups allow-popups-to-escape-sandbox")
        frame.setAttribute("src", lunarborAssetUrl(fileRel))
        frame.setAttribute("title", NoteRepository.displayNameOf(fileRel))
        frame.style.apply {
            position = "absolute"
            setProperty("inset", "0")
            width = "100%"
            height = "100%"
            border = "0"
            // Pages without a background of their own read as on paper,
            // as in a browser — not as dark text on the app's theme.
            background = "#fff"
        }
        host.appendChild(frame)
    }

    /** Hides the view and drops the frame, so a page stops running when left. */
    fun hide() {
        if (shownRel != null) {
            host.innerHTML = ""
            shownRel = null
        }
        host.style.display = "none"
    }
}
