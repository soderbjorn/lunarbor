/*
 * NavigationTransition.kt (jsMain)
 * --------------------------------
 * The animation a pane plays when it navigates: zooming into or out of a
 * bullet, walking Back / Forward, opening another file.
 *
 * The idea is that zooming is spatial. Zooming in, the clicked bullet's
 * text lifts out of its row and grows into the page title while the
 * old page falls away and the children rise into place; zooming out,
 * the title shrinks back down into its row. Everything else is a quick
 * fade-through: a short sideways drift for Back / Forward and breadcrumb
 * jumps within one outline (right when going deeper, left when going
 * shallower), no motion at all for a switch to another file.
 *
 * Mechanics: [capture] runs while the outgoing view is still in the DOM.
 * It clones the pane into a fixed-position overlay on `document.body`
 * (which survives the toolkit's chrome rebuild) and measures whatever
 * the morph needs from the old view. The caller then repaints the new
 * view underneath and calls [play], which on the next animation frame
 * measures the new view, flies a "flyer" copy of the morphing text
 * between the two text boxes, fades the overlay out and the new content
 * in, and cleans everything up. The flyer is always rendered at the
 * title's size and scaled down (never up), so the text stays crisp.
 *
 * View-only, ephemeral UI state: no business rules, no view-model calls.
 * Honours `prefers-reduced-motion` (a short plain fade).
 */

package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.DOMRect
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node

/**
 * The kind of navigation a pane just made, which decides the animation.
 */
internal enum class NavigationKind {
    /** Same outline, one level (or more) deeper, target visible before: morph row → title. */
    ZOOM_IN,

    /** Same outline, shallower, old target visible after: morph title → row. */
    ZOOM_OUT,

    /** Same outline, deeper, but nothing to morph from (Back / Forward, link). */
    DEEPER,

    /** Same outline, shallower or sideways, nothing to morph into. */
    SHALLOWER,

    /** Another file (node, note, image): plain fade-through. */
    OTHER_FILE,
}

/**
 * The outgoing view, captured by [NavigationTransition.capture] before the
 * pane repaints.
 *
 * @property overlay fixed-position clone of the pane content on `document.body`.
 * @property content wrapper inside [overlay] holding the clones; scaled
 *   separately so the overlay's own box never grows past the pane.
 * @property kind the planned animation; [NavigationKind.ZOOM_OUT] may
 *   still fall back to [NavigationKind.SHALLOWER] when the row it would
 *   land in is not painted.
 * @property morphRow `data-row` of the morph's row: in the old view for
 *   [NavigationKind.ZOOM_IN] (the clicked bullet), in the new view for
 *   [NavigationKind.ZOOM_OUT] (the bullet we just left).
 * @property sourceText the text box the flyer starts from — the clicked
 *   row's text (zoom in) or the old title's text (zoom out).
 * @property sourceFontPx font size at [sourceText].
 * @property titleFlyer for zoom out: a detached copy of the old title,
 *   styled with its computed font, which becomes the flyer.
 */
internal class OutgoingView(
    val overlay: HTMLElement,
    val content: HTMLElement,
    val kind: NavigationKind,
    val morphRow: Int?,
    val sourceText: DOMRect?,
    val sourceFontPx: Double,
    val titleFlyer: HTMLElement?,
)

/**
 * Plays the pane's navigation animation. One instance per `MainScreen`.
 *
 * ### Callers
 * - `MainScreen.prepareNavigationCrossfade` / the state collector call
 *   [capture] when the pane's `(file, zoom)` changes, before repainting.
 * - The state collector calls [play] once the new view is painted (for a
 *   cross-file switch, after the new file has loaded).
 */
internal class NavigationTransition {

    /**
     * Snapshots the outgoing view of [root] and decides the animation.
     *
     * @param root the pane's content root (title + scroll wrapper + banner).
     * @param title the live title element (`.lunarbor-title`).
     * @param editor the live editor (`.lunarbor-editor`), still showing
     *   the old view.
     * @param kind the planned animation (see [NavigationKind]).
     * @param morphRow for [NavigationKind.ZOOM_IN], the `data-row` of the
     *   new zoom target in the *old* view; for [NavigationKind.ZOOM_OUT],
     *   its row in the *new* view. `null` otherwise.
     * @return the captured view, already attached as an opaque overlay.
     */
    fun capture(
        root: HTMLElement,
        title: HTMLElement,
        editor: HTMLElement,
        kind: NavigationKind,
        morphRow: Int?,
    ): OutgoingView {
        var planned = kind
        var sourceText: DOMRect? = null
        var sourceFontPx = 0.0
        var titleFlyer: HTMLElement? = null
        when (kind) {
            NavigationKind.ZOOM_IN -> {
                val text = morphRow?.let { rowText(editor, it) }
                val box = text?.let { firstTextBox(it) }
                if (text == null || box == null) {
                    planned = NavigationKind.DEEPER
                } else {
                    sourceText = box
                    sourceFontPx = fontPx(text)
                }
            }
            NavigationKind.ZOOM_OUT -> {
                val box = firstTextBox(title)
                if (box == null) {
                    planned = NavigationKind.SHALLOWER
                } else {
                    sourceText = box
                    sourceFontPx = fontPx(title)
                    titleFlyer = flyerLike(title)
                }
            }
            else -> Unit
        }
        val (overlay, content) = cloneIntoOverlay(root)
        // The morphing text is carried by the flyer; hide its copy in the
        // overlay so it does not show twice.
        when (planned) {
            NavigationKind.ZOOM_IN ->
                (content.querySelector(".lunarbor-editor [data-row='$morphRow'] .lunarbor-text") as? HTMLElement)
                    ?.style?.visibility = "hidden"
            NavigationKind.ZOOM_OUT ->
                (content.querySelector(".lunarbor-title") as? HTMLElement)?.style?.visibility = "hidden"
            else -> Unit
        }
        return OutgoingView(overlay, content, planned, morphRow, sourceText, sourceFontPx, titleFlyer)
    }

    /**
     * Animates from [outgoing] to the view now painted in the pane, then
     * removes the overlay and flyer. Measures on the next animation frame
     * so the new view (possibly re-parented by a chrome rebuild in the
     * meantime) has been laid out; the opaque overlay covers the pane
     * until then, so no half-state is ever visible.
     *
     * @param outgoing what [capture] returned for this navigation.
     * @param title the live title element, now showing the new title.
     * @param scroll the live scroll wrapper (editor + folder contents).
     * @param editor the live editor, now showing the new view.
     */
    fun play(outgoing: OutgoingView, title: HTMLElement, scroll: HTMLElement, editor: HTMLElement) {
        window.requestAnimationFrame {
            if (prefersReducedMotion()) {
                fadeOut(outgoing.overlay, REDUCED_MS, 0)
                return@requestAnimationFrame
            }
            val played = when (outgoing.kind) {
                NavigationKind.ZOOM_IN -> playZoomIn(outgoing, title, scroll)
                NavigationKind.ZOOM_OUT -> playZoomOut(outgoing, title, scroll, editor)
                else -> false
            }
            if (!played) playFade(outgoing, title, scroll)
        }
    }

    /**
     * Row → title. The flyer is a copy of the new title, placed on it and
     * transformed back onto the clicked row's text; it then settles into
     * the title while the old page falls away (fading, growing slightly
     * around the row) and the children rise in beneath.
     *
     * @return `false` when the new title has no text box to land on.
     */
    private fun playZoomIn(outgoing: OutgoingView, title: HTMLElement, scroll: HTMLElement): Boolean {
        val src = outgoing.sourceText ?: return false
        val flyer = flyerLike(title)
        val dst = placeFlyer(flyer, title) ?: return false
        val scale = outgoing.sourceFontPx / fontPx(title)
        title.style.opacity = "0"
        val flight = animate(
            flyer,
            arrayOf(
                keyframe("transform" to "translate(${src.left - dst.left}px, ${src.top - dst.top}px) scale($scale)"),
                keyframe("transform" to "none"),
            ),
            MORPH_MS, 0, EASE_EMPHASIZED, holdEnd = true,
        )
        val originX = src.left - outgoing.overlay.getBoundingClientRect().left
        val originY = src.top - outgoing.overlay.getBoundingClientRect().top
        outgoing.content.style.setProperty("transform-origin", "${originX}px ${originY}px")
        animate(
            outgoing.content,
            arrayOf(keyframe("transform" to "scale(1)"), keyframe("transform" to "scale(1.04)")),
            MORPH_MS, 0, EASE_EMPHASIZED, holdEnd = true,
        )
        fadeOut(outgoing.overlay, OUT_MS, 0)
        animate(
            scroll,
            arrayOf(
                keyframe("opacity" to "0", "transform" to "translateY(12px)"),
                keyframe("opacity" to "1", "transform" to "none"),
            ),
            IN_MS, CONTENT_DELAY_MS, EASE_DECELERATE,
        )
        whenDone(flight) {
            title.style.removeProperty("opacity")
            flyer.parentElement?.removeChild(flyer)
        }
        return true
    }

    /**
     * Title → row. The flyer is the old title, starting where it was and
     * shrinking into the row of the bullet we just left, while the old
     * page fades and recedes and the parent level fades in around it.
     *
     * @return `false` when that row is not painted in the new view (it
     *   sits in a folded subtree, or a block), so the caller fades instead.
     */
    private fun playZoomOut(
        outgoing: OutgoingView,
        title: HTMLElement,
        scroll: HTMLElement,
        editor: HTMLElement,
    ): Boolean {
        val src = outgoing.sourceText ?: return false
        val flyer = outgoing.titleFlyer ?: return false
        val rowText = outgoing.morphRow?.let { rowText(editor, it) } ?: return false
        val dst = firstTextBox(rowText) ?: return false
        val start = placeFlyer(flyer, null, src) ?: return false
        val scale = fontPx(rowText) / outgoing.sourceFontPx
        rowText.style.visibility = "hidden"
        val flight = animate(
            flyer,
            arrayOf(
                keyframe("transform" to "none"),
                keyframe("transform" to "translate(${dst.left - start.left}px, ${dst.top - start.top}px) scale($scale)"),
            ),
            MORPH_MS, 0, EASE_EMPHASIZED, holdEnd = true,
        )
        outgoing.content.style.setProperty("transform-origin", "50% 0")
        animate(
            outgoing.content,
            arrayOf(keyframe("transform" to "scale(1)"), keyframe("transform" to "scale(0.97)")),
            MORPH_MS, 0, EASE_EMPHASIZED, holdEnd = true,
        )
        fadeOut(outgoing.overlay, OUT_MS, 0)
        animate(
            title,
            arrayOf(keyframe("opacity" to "0"), keyframe("opacity" to "1")),
            IN_MS, CONTENT_DELAY_MS, EASE_DECELERATE,
        )
        animate(
            scroll,
            arrayOf(
                keyframe("opacity" to "0", "transform" to "scale(1.015)"),
                keyframe("opacity" to "1", "transform" to "none"),
            ),
            IN_MS, CONTENT_DELAY_MS, EASE_DECELERATE,
        )
        whenDone(flight) {
            rowText.style.removeProperty("visibility")
            flyer.parentElement?.removeChild(flyer)
        }
        return true
    }

    /**
     * Fade-through: the old view fades out quickly, then the new one
     * fades in — never both half-visible at once. Same-outline moves
     * drift sideways by direction; another file does not move at all.
     */
    private fun playFade(outgoing: OutgoingView, title: HTMLElement, scroll: HTMLElement) {
        val dx = when (outgoing.kind) {
            NavigationKind.DEEPER, NavigationKind.ZOOM_IN -> DRIFT_PX
            NavigationKind.SHALLOWER, NavigationKind.ZOOM_OUT -> -DRIFT_PX
            NavigationKind.OTHER_FILE -> 0
        }
        fadeOut(outgoing.overlay, FADE_OUT_MS, 0)
        for (el in listOf(title, scroll)) {
            animate(
                el,
                arrayOf(
                    keyframe("opacity" to "0", "transform" to "translateX(${dx}px)"),
                    keyframe("opacity" to "1", "transform" to "none"),
                ),
                FADE_IN_MS, FADE_OUT_MS - 30, EASE_DECELERATE,
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Clones [root]'s children into an opaque, fixed-position overlay on
     * `document.body`, pinned to [root]'s rect. `position: fixed` escapes
     * the toolkit pane's clipping context, and `display: block` keeps the
     * box at full height (a flex overlay collapsed to its children).
     * Scroll offsets are copied so the snapshot shows what the user saw.
     * Cloned `contenteditable` hosts are made inert so the snapshot can
     * never take the caret.
     */
    private fun cloneIntoOverlay(root: HTMLElement): Pair<HTMLElement, HTMLElement> {
        val rect = root.getBoundingClientRect()
        val rootStyle = window.getComputedStyle(root)
        val overlay = document.createElement("div") as HTMLElement
        overlay.className = "lunarbor-nav-overlay"
        overlay.style.apply {
            setProperty("position", "fixed")
            setProperty("top", "${rect.top}px")
            setProperty("left", "${rect.left}px")
            setProperty("width", "${rect.width}px")
            setProperty("height", "${rect.height}px")
            setProperty("pointer-events", "none")
            setProperty("z-index", "2147483600")
            setProperty("overflow", "hidden")
            setProperty("background", rootStyle.backgroundColor)
            setProperty("color", rootStyle.color)
            setProperty("font-family", rootStyle.fontFamily)
            setProperty("display", "block")
            setProperty("box-sizing", "border-box")
        }
        val content = document.createElement("div") as HTMLElement
        content.style.apply {
            setProperty("display", "flex")
            setProperty("flex-direction", "column")
            setProperty("width", "100%")
            setProperty("height", "100%")
            setProperty("will-change", "transform")
        }
        overlay.appendChild(content)
        val scrollOffsets = mutableListOf<Double>()
        val count = root.children.length
        for (i in 0 until count) {
            val child = root.children.item(i) as? HTMLElement ?: continue
            val clone = (child as Node).cloneNode(true) as HTMLElement
            if (clone.getAttribute("contenteditable") == "true") clone.setAttribute("contenteditable", "false")
            val editable = clone.querySelectorAll("[contenteditable=\"true\"]")
            for (j in 0 until editable.length) {
                (editable.item(j) as? HTMLElement)?.setAttribute("contenteditable", "false")
            }
            content.appendChild(clone)
            scrollOffsets += child.scrollTop
        }
        document.body?.appendChild(overlay)
        for (i in 0 until content.children.length) {
            (content.children.item(i) as? HTMLElement)?.scrollTop = scrollOffsets.getOrElse(i) { 0.0 }
        }
        return overlay to content
    }

    /**
     * A detached copy of [title]'s content wearing its computed font, for
     * use as a flyer: fixed-position, unclipped, one line, click-through.
     */
    private fun flyerLike(title: HTMLElement): HTMLElement {
        val cs = window.getComputedStyle(title)
        val flyer = document.createElement("div") as HTMLElement
        flyer.className = "lunarbor-nav-flyer"
        flyer.innerHTML = title.innerHTML
        flyer.style.apply {
            setProperty("position", "fixed")
            setProperty("left", "0px")
            setProperty("top", "0px")
            setProperty("margin", "0")
            setProperty("padding", "0")
            setProperty("white-space", "nowrap")
            setProperty("pointer-events", "none")
            setProperty("z-index", "2147483601")
            setProperty("font-family", cs.fontFamily)
            setProperty("font-size", cs.fontSize)
            setProperty("font-weight", cs.fontWeight)
            setProperty("font-style", cs.fontStyle)
            setProperty("line-height", cs.lineHeight)
            setProperty("letter-spacing", cs.letterSpacing)
            setProperty("color", cs.color)
            setProperty("will-change", "transform")
        }
        return flyer
    }

    /**
     * Attaches [flyer] so its first text box sits exactly on [target] (or
     * on [title]'s first text box when [target] is `null`), and sets its
     * `transform-origin` to that box's top-left, so a scale transform
     * pivots on the text rather than on the element's line box.
     *
     * @return the text box the flyer now occupies, or `null` when there
     *   is nothing to align to.
     */
    private fun placeFlyer(flyer: HTMLElement, title: HTMLElement?, target: DOMRect? = null): DOMRect? {
        val want = target ?: title?.let { firstTextBox(it) } ?: return null
        document.body?.appendChild(flyer)
        val box = flyer.getBoundingClientRect()
        val text = firstTextBox(flyer) ?: run {
            flyer.parentElement?.removeChild(flyer)
            return null
        }
        val left = want.left - (text.left - box.left)
        val top = want.top - (text.top - box.top)
        flyer.style.left = "${left}px"
        flyer.style.top = "${top}px"
        flyer.style.setProperty("transform-origin", "${text.left - box.left}px ${text.top - box.top}px")
        return want
    }

    /** The editable text span of row [row] in [editor], if painted. Block rows don't morph. */
    private fun rowText(editor: HTMLElement, row: Int): HTMLElement? {
        val rowEl = editor.querySelector("[data-row='$row']") as? HTMLElement ?: return null
        if (rowEl.hasAttribute("data-block-start")) return null
        return rowEl.querySelector(".lunarbor-text") as? HTMLElement
    }

    /** First line box of [el]'s rendered text, or `null` when it has none (empty, hidden). */
    private fun firstTextBox(el: Element): DOMRect? {
        val range = document.createRange()
        range.selectNodeContents(el)
        val rects: dynamic = range.asDynamic().getClientRects()
        val count = rects.length as Int
        for (i in 0 until count) {
            val r = rects[i].unsafeCast<DOMRect>()
            if (r.width > 0 && r.height > 0) return r
        }
        return null
    }

    private fun fontPx(el: Element): Double =
        window.getComputedStyle(el).fontSize.removeSuffix("px").toDoubleOrNull() ?: 16.0

    private fun prefersReducedMotion(): Boolean =
        window.matchMedia("(prefers-reduced-motion: reduce)").matches

    /** Fades [overlay] out after [delayMs] and removes it when done. */
    private fun fadeOut(overlay: HTMLElement, durationMs: Int, delayMs: Int) {
        val fade = animate(
            overlay,
            arrayOf(keyframe("opacity" to "1"), keyframe("opacity" to "0")),
            durationMs, delayMs, EASE_EXIT, holdEnd = true,
        )
        whenDone(fade) { overlay.parentElement?.removeChild(overlay) }
    }

    /**
     * Runs [cleanup] once [animation] has finished or been cancelled —
     * tied to the animation's own clock rather than a timer, so it holds
     * under throttling and slowed playback. A backstop timer covers a
     * browser that never settles the promise.
     */
    private fun whenDone(animation: dynamic, cleanup: () -> Unit) {
        var done = false
        val once = { if (!done) { done = true; cleanup() } }
        animation.finished.then({ _: dynamic -> once() }, { _: dynamic -> once() })
        window.setTimeout({ once() }, BACKSTOP_MS)
    }

    /**
     * Runs a Web Animation on [el], holding the first frame during
     * [delayMs]. Live pane elements pass [holdEnd] `false` so the
     * animation lets go when it ends and never overrides a later style;
     * the overlay and flyers pass `true` so they cannot snap back in the
     * frame before they are removed.
     */
    private fun animate(
        el: HTMLElement,
        frames: Array<dynamic>,
        durationMs: Int,
        delayMs: Int,
        easing: String,
        holdEnd: Boolean = false,
    ): dynamic {
        val options: dynamic = js("({})")
        options.duration = durationMs
        options.delay = delayMs
        options.easing = easing
        options.fill = if (holdEnd) "both" else "backwards"
        return el.asDynamic().animate(frames, options)
    }

    private fun keyframe(vararg props: Pair<String, String>): dynamic {
        val frame: dynamic = js("({})")
        for ((k, v) in props) frame[k] = v
        return frame
    }

    companion object {
        /** The morphing text's flight (row ↔ title). */
        private const val MORPH_MS: Int = 340

        /** The old page's fade under a morph. */
        private const val OUT_MS: Int = 130

        /** The new content's fade / rise under a morph. */
        private const val IN_MS: Int = 260

        /** How long the new content waits for the old page to clear. */
        private const val CONTENT_DELAY_MS: Int = 110

        /** Fade-through: old view out. */
        private const val FADE_OUT_MS: Int = 110

        /** Fade-through: new view in (starts just before the old is gone). */
        private const val FADE_IN_MS: Int = 190

        /** Sideways drift for same-outline moves without a morph. */
        private const val DRIFT_PX: Int = 14

        /** Cleanup deadline should an animation never report finishing. */
        private const val BACKSTOP_MS: Int = 5_000

        /** Reduced motion: a short plain fade of the old view. */
        private const val REDUCED_MS: Int = 100

        /** Fast start, long gentle landing — for things that travel. */
        private const val EASE_EMPHASIZED: String = "cubic-bezier(0.2, 0, 0, 1)"

        /** Arrivals. */
        private const val EASE_DECELERATE: String = "cubic-bezier(0, 0, 0, 1)"

        /**
         * Departures of the old page: gone fast, so it has all but
         * vanished before the new content fades in (no text-on-text).
         */
        private const val EASE_EXIT: String = "cubic-bezier(0, 0, 0.2, 1)"
    }
}
