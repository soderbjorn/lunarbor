/* LunicleBoardDragGesture.kt (jsMain)
 *
 * Dragging an issue on a board node by its dot, as on Lunicle's own board:
 * a press on an issue's dot that moves more than [DRAG_THRESHOLD_PX] before
 * the release is a drag; one that does not is a click, which folds or
 * unfolds the issue as before. While dragging, the board row under the
 * pointer is asked where the issue would land
 * (`MainViewModel.lunicleDropAt`, rules in commonMain `LunicleBoardDrag`)
 * and that row is marked — a line along its top (`is-drop-before`) or bottom
 * (`is-drop-after`), or a ring round a column's name (`is-drop-into`) — and
 * the dragged row is dimmed (`is-dragging`). Releasing over a drop
 * dispatches [LUNICLE_DROP_EVENT] on the editor, which `MainScreen` hands to
 * `LunicleBoardCursor.drop`; Escape, or releasing anywhere else, cancels.
 *
 * Boards repaint while a drag runs (polls, the change stream, the sync
 * ticker), so the marks are put back on every new box ([decorate], called
 * at the end of `buildLunicleBoard`), like a property change's flash.
 *
 * Ephemeral view state only (the gesture in flight); no rules beyond
 * layout. */
package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * Dispatched (bubbling) on the editor when a board drag ends over a drop,
 * with `detail = { row, drop }` — the board node's document row and the
 * [LunicleDrop]. `MainScreen` hands it to `LunicleBoardCursor.drop`.
 */
internal const val LUNICLE_DROP_EVENT = "lunarbor-lunicle-drop"

/** How far (px) a press on an issue's dot moves before it is a drag rather than a click. */
private const val DRAG_THRESHOLD_PX = 4.0

/** The board drag in flight (see the file header). One at a time, app-wide. */
internal object LunicleBoardDragGesture {

    /**
     * One press on an issue's dot.
     *
     * @property viewModel The pane whose board it is.
     * @property editor The pane's editor, where [LUNICLE_DROP_EVENT] goes.
     * @property nodeRow The board node's document row.
     * @property issueId The pressed issue.
     * @property onClick Folds / unfolds the issue (a press that never moved).
     */
    private class Press(
        val viewModel: MainViewModel,
        val editor: HTMLElement?,
        val nodeRow: Int,
        val issueId: Long,
        val startX: Double,
        val startY: Double,
        val onClick: () -> Unit,
    ) {
        /** Past the threshold: a drag, not a click. */
        var dragging = false

        /** Where it would land now, or `null`. */
        var drop: LunicleDrop? = null
    }

    private var press: Press? = null

    private val onMove: (Event) -> Unit = { ev -> move(ev as MouseEvent) }
    private val onUp: (Event) -> Unit = { ev -> release(ev as MouseEvent) }
    private val onKey: (Event) -> Unit = { ev ->
        if ((ev as KeyboardEvent).key == "Escape" && press?.dragging == true) {
            ev.preventDefault()
            ev.stopPropagation()
            end()
        }
    }

    /**
     * A press on issue [issueId]'s dot (the board's mousedown, left button):
     * starts watching the pointer. A release without moving runs [onClick].
     *
     * Called by `buildLunicleBoard` for every issue row's dot.
     */
    fun press(ev: MouseEvent, viewModel: MainViewModel, nodeRow: Int, issueId: Long, onClick: () -> Unit) {
        end()
        val editor = (ev.target as? HTMLElement)?.closest(".lunarbor-editor") as? HTMLElement
        press = Press(viewModel, editor, nodeRow, issueId, ev.clientX.toDouble(), ev.clientY.toDouble(), onClick)
        window.addEventListener("mousemove", onMove, true)
        window.addEventListener("mouseup", onUp, true)
        window.addEventListener("keydown", onKey, true)
    }

    /**
     * Puts the drag's marks on a freshly painted board [box] of [viewModel]'s
     * node at [nodeRow] (a repaint mid-drag). Called at the end of
     * `buildLunicleBoard`.
     */
    fun decorate(box: HTMLElement, viewModel: MainViewModel, nodeRow: Int) {
        val p = press?.takeIf { it.dragging && it.viewModel === viewModel && it.nodeRow == nodeRow } ?: return
        val rows = box.children
        val draggedKey = LunicleRowRef(LunicleRowKind.ISSUE, "", p.issueId).key
        val drop = p.drop
        for (i in 0 until rows.length) {
            val el = rows.item(i) as? HTMLElement ?: continue
            val key = el.getAttribute(LUNICLE_ROW_KEY_ATTR) ?: continue
            if (key == draggedKey) el.classList.add("is-dragging")
            if (drop != null && key == drop.indicatorKey) {
                el.classList.add(
                    when (drop.indicator) {
                        LunicleDropIndicator.BEFORE -> "is-drop-before"
                        LunicleDropIndicator.AFTER -> "is-drop-after"
                        LunicleDropIndicator.INTO -> "is-drop-into"
                    },
                )
            }
        }
    }

    private fun move(ev: MouseEvent) {
        val p = press ?: return
        if (!p.dragging) {
            val dx = ev.clientX - p.startX
            val dy = ev.clientY - p.startY
            if (dx * dx + dy * dy < DRAG_THRESHOLD_PX * DRAG_THRESHOLD_PX) return
            if (!p.viewModel.canDragLunicleIssue(p.nodeRow, p.issueId)) {
                // Not editable: no drag (the press stays a click until released).
                return
            }
            p.dragging = true
            document.body?.classList?.add("lunarbor-dragging")
        }
        ev.preventDefault()
        p.drop = dropUnder(p, ev)
        redecorate(p)
    }

    /** The drop for the board row under the pointer, on this pane's board only. */
    private fun dropUnder(p: Press, ev: MouseEvent): LunicleDrop? {
        val hit = document.elementFromPoint(ev.clientX.toDouble(), ev.clientY.toDouble()) as? HTMLElement ?: return null
        val row = hit.closest("[$LUNICLE_ROW_KEY_ATTR]") as? HTMLElement ?: return null
        val box = row.parentElement as? HTMLElement ?: return null
        if (box.getAttribute(LUNICLE_BOARD_ROW_ATTR) != p.nodeRow.toString()) return null
        if (p.editor != null && box.closest(".lunarbor-editor") !== p.editor) return null
        val key = row.getAttribute(LUNICLE_ROW_KEY_ATTR) ?: return null
        val rect = row.getBoundingClientRect()
        val lowerHalf = ev.clientY > rect.top + rect.height / 2
        return p.viewModel.lunicleDropAt(p.nodeRow, p.issueId, key, lowerHalf)
    }

    /** Clears the marks on every board box and puts them back for [p]. */
    private fun redecorate(p: Press?) {
        val marked = document.querySelectorAll(".lunarbor-lunicle-row.is-dragging, .lunarbor-lunicle-row.is-drop-before, .lunarbor-lunicle-row.is-drop-after, .lunarbor-lunicle-row.is-drop-into")
        for (i in 0 until marked.length) {
            (marked.item(i) as? HTMLElement)?.classList?.remove("is-dragging", "is-drop-before", "is-drop-after", "is-drop-into")
        }
        if (p == null) return
        val boxes = document.querySelectorAll("[$LUNICLE_BOARD_ROW_ATTR=\"${p.nodeRow}\"]")
        for (i in 0 until boxes.length) {
            val box = boxes.item(i) as? HTMLElement ?: continue
            if (p.editor != null && box.closest(".lunarbor-editor") !== p.editor) continue
            decorate(box, p.viewModel, p.nodeRow)
        }
    }

    private fun release(ev: MouseEvent) {
        val p = press ?: return
        if (!p.dragging) {
            end()
            p.onClick()
            return
        }
        ev.preventDefault()
        ev.stopPropagation()
        val drop = dropUnder(p, ev)
        end()
        if (drop == null) return
        val detail: dynamic = js("({})")
        detail.row = p.nodeRow
        detail.drop = drop
        p.editor?.dispatchEvent(org.w3c.dom.CustomEvent(LUNICLE_DROP_EVENT, org.w3c.dom.CustomEventInit(detail = detail, bubbles = true)))
    }

    /** Stops the gesture: listeners off, marks and the closed hand gone. */
    private fun end() {
        val p = press ?: return
        press = null
        window.removeEventListener("mousemove", onMove, true)
        window.removeEventListener("mouseup", onUp, true)
        window.removeEventListener("keydown", onKey, true)
        if (p.dragging) {
            document.body?.classList?.remove("lunarbor-dragging")
            redecorate(null)
        }
    }
}
