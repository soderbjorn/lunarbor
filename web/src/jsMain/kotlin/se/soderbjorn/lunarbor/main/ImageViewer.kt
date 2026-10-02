/*
 * ImageViewer.kt (jsMain)
 * -----------------------
 * Renders the editor surface's read-only image view. Active whenever the
 * pane's `activeFileRel` points at an image (extension in
 * `NoteRepository.IMAGE_EXTENSIONS`) — `MainScreen` hides the
 * contenteditable host and shows this viewer in its place. The
 * folder contents list remains visible below so the user can navigate to a sibling.
 *
 * No editing affordances, no resize handle, no popover — image view is
 * deliberately a "look at the file" mode. To leave: click another file in
 * the folder contents list, or use the pane header's Back button or breadcrumb.
 *
 * Image source resolution piggy-backs on `lunarborAssetUrl` from
 * `OutlinePaintLoop.kt`, the same `lunarbor-asset://` URL builder the
 * inline image runs use — single source of truth for vault-asset URLs.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement

/**
 * Repaints [container] to show the image at [vaultRelPath]. Idempotent —
 * clears any prior children and rebuilds a single `<img>`. Cheap enough
 * to run on every state emission (only fires while the pane is in image
 * view) so we don't bother with change-detection.
 *
 * @param container The sibling div allocated by `MainScreen` for the
 *   image viewer. Receives `contenteditable="false"` once at mount; this
 *   function only manipulates its child nodes.
 * @param vaultRelPath Path to the image, relative to the vault root.
 *   Forwarded to [lunarborAssetUrl] which resolves it against the
 *   absolute vault root captured at boot.
 */
fun paintImageViewer(container: HTMLElement, vaultRelPath: String) {
    container.innerHTML = ""
    val img = document.createElement("img") as HTMLImageElement
    img.src = lunarborAssetUrl(vaultRelPath)
    img.alt = vaultRelPath
    img.draggable = false
    img.className = "lunarbor-image-viewer-img"
    // Drop in a "missing image" placeholder when the protocol fails to
    // resolve (file deleted between list paint and click, path typo,
    // unsupported codec). Mirrors the inline image-run error handler in
    // `createImageRunElement` so behavior is consistent across surfaces.
    img.addEventListener("error", { _ ->
        if (container.classList.contains("is-broken")) return@addEventListener
        container.classList.add("is-broken")
        img.remove()
        val broken = document.createElement("div") as HTMLElement
        broken.className = "lunarbor-image-viewer-broken"
        broken.textContent = "Missing image: $vaultRelPath"
        container.appendChild(broken)
    })
    container.appendChild(img)
}
