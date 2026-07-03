/* TreeFactsTabSource.kt (jsMain)
 * Adapter that exposes treefacts's typed [LayoutState] as a
 * darkness-toolkit [TabSource]. The toolkit's `mountAppShell`
 * subscribes to the push channel and renders whatever tabs / panes
 * treefacts's current `LayoutState` describes; user gestures
 * (select / close / rename / reorder / pane move/resize/maximize)
 * are forwarded as plain callbacks the AppShell layer wires to its
 * existing mutation methods.
 *
 * This is the per-app glue that proves the abstraction shape works
 * for an app whose tab list is derived from its own typed document
 * model (with legacy migration logic). TreeFacts's AppShell creates a
 * single [TreeFactsTabSource] at mount, captures the push callback on
 * the first toolkit subscribe, and calls [notify] after every
 * `LayoutState` mutation. */
package se.soderbjorn.treefacts.main

import se.soderbjorn.darkness.store.LayoutState
import se.soderbjorn.darkness.web.shell.PaneSnapshotEntry
import se.soderbjorn.darkness.web.shell.TabListSnapshot
import se.soderbjorn.darkness.web.shell.TabSnapshotEntry
import se.soderbjorn.darkness.web.shell.TabSource
import se.soderbjorn.darkness.web.util.PaneSlotAssigner

/**
 * Process-global pane-slot assigner backing the encircled-digit / letter
 * badge rendered on every pane header and sidebar row. Sticky 1-based
 * indices: a pane keeps the same slot from open until close. Updated by
 * [TreeFactsTabSource.notify] on every layout-state mutation.
 */
internal val treefactsPaneAssigner: PaneSlotAssigner = PaneSlotAssigner()

/**
 * Stateful adapter producing a [TabSource] for treefacts's
 * [LayoutState]. TreeFacts's `AppShell` constructs one at mount and
 * calls [notify] after every layout-state mutation; the toolkit
 * subscribes once and re-renders on every notification.
 *
 * @param onTabSelected fires when the user activates a tab. TreeFacts
 *   should set its `layoutState.activeTabId` and re-emit.
 * @param onTabAdded fires when the user clicks the trailing `+`.
 * @param onTabClosed fires when the user closes a tab.
 * @param onTabRenamed fires when an inline rename commits.
 * @param onTabReordered fires after a successful drag-reorder.
 * @param onPaneSelected fires when the user clicks a pane row in the
 *   default sidebar tree.
 *
 * Pane geometry callbacks (move/resize/maximize) used to live here but
 * were lifted into the toolkit alongside [TreeFactsTabSource]'s adapter
 * to [se.soderbjorn.darkness.web.shell.TabSource]. Pane geometry now
 * lives entirely in the toolkit's `PersistKeys.LAYOUT_STATE`, written
 * through [toolkitPersister]; treefacts doesn't have to mirror it.
 */
class TreeFactsTabSource(
    private val onTabSelected: (String) -> Unit,
    private val onTabAdded: () -> Unit,
    private val onTabClosed: (String) -> Unit,
    private val onTabRenamed: (String, String) -> Unit,
    private val onTabReordered: (sourceId: String, targetId: String, before: Boolean) -> Unit,
    private val onPaneSelected: (tabId: String, paneId: String) -> Unit,
    private val onPaneClosed: (tabId: String, paneId: String) -> Unit,
    private val onPaneAdded: (tabId: String) -> Unit,
) {
    private var push: ((TabListSnapshot) -> Unit)? = null
    private var lastSnapshot: TabListSnapshot = TabListSnapshot(emptyList(), null)

    /**
     * The [TabSource] to hand to [se.soderbjorn.darkness.web.shell.AppShellSpec].
     */
    val tabSource: TabSource = TabSource(
        subscribe = { p ->
            push = p
            // Don't push the empty bootstrap snapshot. The toolkit's
            // sync path wipes any geometry whose tab id isn't in the
            // incoming snapshot — pushing an empty list before
            // [notify] has run with the real LayoutState would
            // delete every persisted pane position the toolkit just
            // hydrated from `PersistKeys.LAYOUT_STATE`. Wait for the
            // real notify() to fire instead.
            if (lastSnapshot.tabs.isNotEmpty()) p(lastSnapshot)
        },
        onSelect = { id -> onTabSelected(id) },
        onAdd = { onTabAdded() },
        onClose = { id -> onTabClosed(id) },
        onRename = { id, label -> onTabRenamed(id, label) },
        onReorder = { sourceId, targetId, before -> onTabReordered(sourceId, targetId, before) },
        onPaneSelect = { tabId, paneId -> onPaneSelected(tabId, paneId) },
        onPaneClose = { tabId, paneId -> onPaneClosed(tabId, paneId) },
        onPaneAdd = { tabId -> onPaneAdded(tabId) },
    )

    /**
     * Pushes a fresh snapshot to the toolkit derived from [layoutState].
     * TreeFacts calls this once at mount (before the toolkit subscribes)
     * and again after every layout-state mutation.
     *
     * Hidden tabs are filtered out of the rendered list. Floating panes
     * are translated to [PaneSnapshotEntry] with full geometry; minimised
     * panes are kept in the model but excluded from the snapshot so the
     * sidebar tree mirrors what the user actually sees.
     *
     * @param activePaneByTab per-tab last-focused pane id, used to
     *   populate [TabSnapshotEntry.activePaneId] so the toolkit lands
     *   the focus ring on whichever pane the host considers active.
     *   Without this, the snapshot reports `activePaneId = null` and
     *   the toolkit falls back to whatever pane was previously active
     *   on its `LayoutController`, which leaves a freshly-spawned pane
     *   un-focused even though the host just moved focus to it.
     */
    fun notify(layoutState: LayoutState, activePaneByTab: Map<String, String> = emptyMap()) {
        // Reconcile the pane-slot assigner with EVERY pane in the model
        // (including hidden tabs and minimized panes), not just the
        // visible subset rendered below. Sticky-slot semantics demand
        // that a pane's number doesn't shift when its tab is hidden or
        // when it's minimized — when it comes back into view the slot
        // it previously held is still there. Order: tabs in tab order,
        // panes in their tab order — the canonical global enumeration.
        val livePaneIds = layoutState.tabs.flatMap { tab ->
            tab.floatingPanes.map { it.id }
        }
        treefactsPaneAssigner.syncTo(livePaneIds)
        val snapshot = TabListSnapshot(
            tabs = layoutState.tabs
                .filterNot { it.isHidden }
                .map { tab ->
                    val visiblePaneIds = tab.floatingPanes
                        .filterNot { it.isMinimized }
                        .map { it.id }
                    val active = activePaneByTab[tab.id]?.takeIf { it in visiblePaneIds }
                    TabSnapshotEntry(
                        id = tab.id,
                        label = tab.title,
                        panes = visiblePaneIds.map { id -> PaneSnapshotEntry(id = id) },
                        activePaneId = active,
                    )
                },
            activeTabId = layoutState.activeTabId,
        )
        lastSnapshot = snapshot
        push?.invoke(snapshot)
    }
}
