/*
 * FoldTransition.kt (jsMain)
 * --------------------------
 * The animation a pane plays when a subtree unfolds or folds in place.
 *
 * Unfolding, the rows below the parent glide down and the children are
 * uncovered top-down just behind them; folding, the rows below glide up
 * and cover the children bottom-up while they fade. The edge between the
 * two always moves with the gliding rows, so text never overlaps and
 * nothing jumps. It is deliberately smaller and quicker than the zoom
 * morph (NavigationTransition): folding happens often and in place.
 *
 * Mechanics (FLIP): [capture] records, before the repaint, where each
 * row sits and keeps its element — the paint loop rebuilds every row, so
 * the old elements survive detached. [play], right after the repaint and
 * before the browser paints, measures the new rows and animates from the
 * old layout: rows present on both sides translate back from where they
 * were; new rows are revealed by a `clip-path` edge; removed rows stay as
 * ghosts of their old elements in a layer on `document.body` (outside the
 * editor, so it can never take the caret), clipped by the same edge. All
 * of it shares one easing curve, sampled into keyframes, so the edge and
 * the glide stay in lockstep.
 *
 * View-only, ephemeral UI state: no business rules, no view-model calls.
 * Honours `prefers-reduced-motion` (no animation).
 */

package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * The rows on screen before a fold repaint, captured by
 * [FoldTransition.capture].
 *
 * @property rows Line id → the row's element and its viewport rect
 *   (left, top, width, height) at capture time.
 */
internal class FoldSnapshot(val rows: Map<LineId, Pair<HTMLElement, DoubleArray>>)

/**
 * Plays the fold / unfold animation. One instance per `MainScreen`.
 *
 * ### Callers
 * The `MainScreen` state collector: [capture] before a repaint while a
 * fold is in progress, [play] right after it.
 */
internal class FoldTransition {

    /**
     * The row animations of the fold in progress: when it started
     * (document timeline ms, the animations' own clock) and each row's keyframes by line id.
     * Kept so [resume] can put them back on the rows a repaint rebuilds
     * mid-fold (a folder listing or link check arriving), which would
     * otherwise snap them to their end state.
     */
    private var active: Pair<Double, Map<LineId, Array<dynamic>>>? = null

    /**
     * Records every painted row of [editor] by line id.
     *
     * @param lineIds The line ids of the state the editor currently shows
     *   (each row's `data-row` indexes into it).
     */
    fun capture(editor: HTMLElement, lineIds: List<LineId>): FoldSnapshot {
        val rows = HashMap<LineId, Pair<HTMLElement, DoubleArray>>()
        val els = editor.querySelectorAll("[data-row]")
        for (i in 0 until els.length) {
            val el = els.item(i) as? HTMLElement ?: continue
            val row = el.getAttribute("data-row")?.toIntOrNull() ?: continue
            val id = lineIds.getOrNull(row) ?: continue
            val r = el.getBoundingClientRect()
            rows[id] = el to doubleArrayOf(r.left, r.top, r.width, r.height)
        }
        return FoldSnapshot(rows)
    }

    /**
     * Animates from [before] to the rows [editor] shows now.
     *
     * @param lineIds The line ids of the state just painted.
     * @return `true` when rows appeared, disappeared, grew or shrank (the
     *   fold has happened, so the caller stops watching for it); `false`
     *   when nothing did — e.g. an unfold whose children are still loading.
     */
    fun play(before: FoldSnapshot, editor: HTMLElement, lineIds: List<LineId>): Boolean {
        val now = HashMap<LineId, HTMLElement>()
        val els = editor.querySelectorAll("[data-row]")
        for (i in 0 until els.length) {
            val el = els.item(i) as? HTMLElement ?: continue
            val row = el.getAttribute("data-row")?.toIntOrNull() ?: continue
            val id = lineIds.getOrNull(row) ?: continue
            now[id] = el
        }
        val entering = now.keys.filter { it !in before.rows }
        val leaving = before.rows.keys.filter { it !in now }
        // A row that grew in place (a link bullet's preview opening) counts
        // as a fold too.
        val grown = now.filter { (id, el) ->
            val old = before.rows[id]?.second ?: return@filter false
            el.getBoundingClientRect().height - old[3] > 0.5
        }.keys
        val shrunk = now.any { (id, el) ->
            val old = before.rows[id]?.second ?: return@any false
            old[3] - el.getBoundingClientRect().height > 0.5
        }
        if (entering.isEmpty() && leaving.isEmpty() && grown.isEmpty() && !shrunk) return false
        if (prefersReducedMotion()) return true

        val rowFrames = HashMap<LineId, Array<dynamic>>()
        // Rows on both sides glide from their old place; one that grew is
        // uncovered down to its new height by an edge moving with the
        // rows below it.
        for ((id, el) in now) {
            val old = before.rows[id]?.second ?: continue
            val r = el.getBoundingClientRect()
            val dy = old[1] - r.top
            val dh = if (id in grown) r.height - old[3] else 0.0
            if (kotlin.math.abs(dy) < 0.5 && dh == 0.0) continue
            rowFrames[id] = frames { f ->
                if (dh > 0) {
                    keyframe(
                        "transform" to "translateY(${dy * (1 - f)}px)",
                        "clipPath" to "inset(0 0 ${dh * (1 - f)}px 0)",
                    )
                } else {
                    keyframe("transform" to "translateY(${dy * (1 - f)}px)")
                }
            }
        }
        // New rows are uncovered top-down by an edge that runs exactly as
        // far and as fast as the rows below them slide, so the two never
        // overlap.
        if (entering.isNotEmpty()) {
            val rects = entering.map { now.getValue(it).getBoundingClientRect() }
            val top = rects.minOf { it.top }
            val height = rects.maxOf { it.bottom } - top
            for ((i, id) in entering.withIndex()) {
                val r = rects[i]
                rowFrames[id] = frames { f ->
                    val shown = (height * f - (r.top - top)).coerceIn(0.0, r.height)
                    keyframe("clipPath" to "inset(0 0 ${r.height - shown}px 0)", "opacity" to "${0.3 + 0.7 * f}")
                }
            }
        }
        val start = timelineNow()
        active = start to rowFrames
        for ((id, el) in now) rowFrames[id]?.let { animate(el, it) }
        // Removed rows stay where they stood, as ghosts of their old
        // elements, and are covered bottom-up by the rows sliding up.
        if (leaving.isNotEmpty()) {
            val layer = document.createElement("div") as HTMLElement
            layer.className = "lunarbor-fold-ghosts"
            layer.style.apply {
                setProperty("position", "fixed")
                setProperty("inset", "0")
                setProperty("pointer-events", "none")
                setProperty("z-index", "2147483500")
            }
            val editorStyle = window.getComputedStyle(editor)
            var top = Double.MAX_VALUE
            var bottom = 0.0
            for (id in leaving) {
                val (el, r) = before.rows.getValue(id)
                top = minOf(top, r[1])
                bottom = maxOf(bottom, r[1] + r[3])
                el.removeAttribute("data-row")
                el.setAttribute("contenteditable", "false")
                el.style.apply {
                    setProperty("position", "fixed")
                    setProperty("left", "${r[0]}px")
                    setProperty("top", "${r[1]}px")
                    setProperty("width", "${r[2]}px")
                    setProperty("margin", "0")
                    setProperty("font-family", editorStyle.fontFamily)
                    setProperty("font-size", editorStyle.fontSize)
                    setProperty("color", editorStyle.color)
                }
                layer.appendChild(el)
            }
            document.body?.appendChild(layer)
            val viewport = window.innerHeight.toDouble()
            val height = bottom - top
            val fade = animate(layer, frames { f ->
                val edge = bottom - height * f
                keyframe("clipPath" to "inset(${top}px 0 ${viewport - edge}px 0)", "opacity" to "${1 - 0.7 * f}")
            }, holdEnd = true)
            var removed = false
            val remove = { if (!removed) { removed = true; layer.parentElement?.removeChild(layer) } }
            fade.finished.then({ _: dynamic -> remove() }, { _: dynamic -> remove() })
            window.setTimeout({ remove() }, BACKSTOP_MS)
        }
        return true
    }

    /**
     * Puts the fold in progress back on the rows of [editor] after a
     * repaint rebuilt them, fast-forwarded to where it is. A no-op when
     * no fold is running.
     *
     * Called by `MainScreen` after every repaint that is not itself a
     * fold's first ([play]).
     *
     * @param lineIds The line ids of the state just painted.
     */
    fun resume(editor: HTMLElement, lineIds: List<LineId>) {
        val (start, rowFrames) = active ?: return
        val elapsed = timelineNow() - start
        if (elapsed >= DURATION_MS) {
            active = null
            return
        }
        val els = editor.querySelectorAll("[data-row]")
        for (i in 0 until els.length) {
            val el = els.item(i) as? HTMLElement ?: continue
            val id = el.getAttribute("data-row")?.toIntOrNull()?.let { lineIds.getOrNull(it) } ?: continue
            val f = rowFrames[id] ?: continue
            animate(el, f).currentTime = elapsed
        }
    }

    /**
     * Keyframes sampled along one shared easing curve ([ease]) and played
     * linearly, so a glide and the clip edge that must stay flush with it
     * move in lockstep. [at] builds the frame for eased progress `f`.
     */
    private fun frames(at: (f: Double) -> dynamic): Array<dynamic> {
        val out = js("[]")
        for (k in 0..SAMPLES) out.push(at(ease(k.toDouble() / SAMPLES)))
        return out.unsafeCast<Array<dynamic>>()
    }

    /** Ease-out cubic: fast start, gentle landing. */
    private fun ease(t: Double): Double {
        val u = 1 - t
        return 1 - u * u * u
    }

    /** The document timeline's current time: the clock the animations run on. */
    private fun timelineNow(): Double =
        (document.asDynamic().timeline.currentTime as? Double) ?: window.performance.now()

    private fun prefersReducedMotion(): Boolean =
        window.matchMedia("(prefers-reduced-motion: reduce)").matches

    /**
     * Runs [frames] on [el] over [DURATION_MS], linearly (the easing is in
     * the frames). [holdEnd] keeps the last frame (the ghost layer, which
     * is removed afterwards); live rows let go when done.
     */
    private fun animate(el: HTMLElement, frames: Array<dynamic>, holdEnd: Boolean = false): dynamic {
        val options: dynamic = js("({})")
        options.duration = DURATION_MS
        options.easing = "linear"
        options.fill = if (holdEnd) "forwards" else "none"
        return el.asDynamic().animate(frames, options)
    }

    private fun keyframe(vararg props: Pair<String, String>): dynamic {
        val frame: dynamic = js("({})")
        for ((k, v) in props) frame[k] = v
        return frame
    }

    companion object {
        /** One fold: every glide, reveal and cover runs this long. */
        private const val DURATION_MS: Int = 220

        /** Keyframes sampled along the easing curve. */
        private const val SAMPLES: Int = 12

        /** Cleanup deadline should the ghost fade never report finishing. */
        private const val BACKSTOP_MS: Int = 2_000
    }
}
