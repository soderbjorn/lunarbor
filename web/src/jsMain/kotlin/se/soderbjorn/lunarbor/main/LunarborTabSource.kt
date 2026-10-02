/* LunarborTabSource.kt (jsMain)
 * Adapter that exposes lunarbor's typed [LayoutState] as a
 * lunula [TabSource]. The toolkit's `mountAppShell`
 * subscribes to the push channel and renders whatever tabs / panes
 * lunarbor's current `LayoutState` describes; user gestures
 * (select / close / rename / reorder / pane move/resize/maximize)
 * are forwarded as plain callbacks the AppShell layer wires to its
 * existing mutation methods.
 *
 * This is the per-app glue that proves the abstraction shape works
 * for an app whose tab list is derived from its own typed document
 * model (with legacy migration logic). Lunarbor's AppShell creates a
 * single [LunarborTabSource] at mount, captures the push callback on
 * the first toolkit subscribe, and calls [notify] after every
 * `LayoutState` mutation. */
package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunula.store.LayoutState
import se.soderbjorn.lunula.web.shell.PaneAddMenuItem
import se.soderbjorn.lunula.web.shell.PaneSnapshotEntry
import se.soderbjorn.lunula.web.shell.TabListSnapshot
import se.soderbjorn.lunula.web.shell.TabSnapshotEntry
import se.soderbjorn.lunula.web.shell.TabSource

/**
 * Stateful adapter producing a [TabSource] for lunarbor's
 * [LayoutState]. Lunarbor's `AppShell` constructs one at mount and
 * calls [notify] after every layout-state mutation; the toolkit
 * subscribes once and re-renders on every notification.
 *
 * @param onTabSelected fires when the user activates a tab. Lunarbor
 *   should set its `layoutState.activeTabId` and re-emit.
 * @param onTabAdded fires when the user clicks the trailing `+`.
 * @param onTabClosed fires when the user closes a tab.
 * @param onTabRenamed fires when an inline rename commits.
 * @param onTabReordered fires after a successful drag-reorder.
 * @param onTabHiddenSet fires from the tab menu's "Hide / Show in tab
 *   bar" row. Lunarbor flips `TabState.isHidden` and persists it.
 * @param onTabHiddenFromSidebarSet fires from the tab menu's "Hide /
 *   Show in side bar" row. Lunarbor flips `TabState.isHiddenFromSidebar`
 *   and persists it.
 * @param onPaneSelected fires when the user clicks a pane row in the
 *   default sidebar tree.
 * @param onPaneFocused fires when the user focuses a pane from inside
 *   the pane area (a click in it, or spatial navigation). Lunarbor
 *   records it as the tab's focused pane — the one Starred, Insert Link,
 *   Navigate to and "New window" act on — and pushes a snapshot that
 *   agrees, which the toolkit's focus hold waits for.
 * @param onPaneClosed fires when the user closes a pane.
 * @param onPaneMoved fires when the user picks a tab in the pane `⋮`
 *   menu's "Move to tab" submenu, with the pane's current tab, the pane
 *   and the destination tab.
 * @param onPaneAdded fires on a click on the "+" button itself — the
 *   default of its menu ([paneAddMenuItems]).
 * @param paneAddMenuItems rows of the "+" menu above the toolkit's own
 *   "New tab", for the active tab id (Lunarbor: "New window").
 *
 * Pane geometry callbacks (move/resize/maximize) used to live here but
 * were lifted into the toolkit alongside [LunarborTabSource]'s adapter
 * to [se.soderbjorn.lunula.web.shell.TabSource]. Pane geometry now
 * lives entirely in the toolkit's `PersistKeys.LAYOUT_STATE`, written
 * through [toolkitPersister]; lunarbor doesn't have to mirror it.
 */
class LunarborTabSource(
    private val onTabSelected: (String) -> Unit,
    private val onTabAdded: () -> Unit,
    private val onTabClosed: (String) -> Unit,
    private val onTabRenamed: (String, String) -> Unit,
    private val onTabReordered: (sourceId: String, targetId: String, before: Boolean) -> Unit,
    private val onTabHiddenSet: (id: String, hidden: Boolean) -> Unit,
    private val onTabHiddenFromSidebarSet: (id: String, hidden: Boolean) -> Unit,
    private val onPaneSelected: (tabId: String, paneId: String) -> Unit,
    private val onPaneFocused: (tabId: String, paneId: String) -> Unit,
    private val onPaneClosed: (tabId: String, paneId: String) -> Unit,
    private val onPaneMoved: (tabId: String, paneId: String, targetTabId: String) -> Unit,
    private val onPaneAdded: (tabId: String) -> Unit,
    private val paneAddMenuItems: (tabId: String) -> List<PaneAddMenuItem>,
) {
    private var push: ((TabListSnapshot) -> Unit)? = null
    private var lastSnapshot: TabListSnapshot = TabListSnapshot(emptyList(), null)

    /**
     * The [TabSource] to hand to [se.soderbjorn.lunula.web.shell.AppShellSpec].
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
        onSetHidden = { id, hidden -> onTabHiddenSet(id, hidden) },
        onSetHiddenFromSidebar = { id, hidden -> onTabHiddenFromSidebarSet(id, hidden) },
        onPaneSelect = { tabId, paneId -> onPaneSelected(tabId, paneId) },
        onPaneFocused = { tabId, paneId -> onPaneFocused(tabId, paneId) },
        onPaneClose = { tabId, paneId -> onPaneClosed(tabId, paneId) },
        onPaneMove = { tabId, paneId, targetTabId -> onPaneMoved(tabId, paneId, targetTabId) },
        onPaneAdd = { tabId -> onPaneAdded(tabId) },
        paneAddMenuItems = paneAddMenuItems,
    )

    /**
     * Pushes a fresh snapshot to the toolkit derived from [layoutState].
     * Lunarbor calls this once at mount (before the toolkit subscribes)
     * and again after every layout-state mutation.
     *
     * Every tab is reported, with its `isHidden` / `isHiddenFromSidebar`
     * flags: the toolkit leaves a tab hidden from the strip out of it
     * (listing it under the overflow menu's unlisted tabs so it can be
     * shown again) and one hidden from the sidebar out of the tree. Floating panes
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
        val snapshot = TabListSnapshot(
            tabs = layoutState.tabs
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
                        isHidden = tab.isHidden,
                        isHiddenFromSidebar = tab.isHiddenFromSidebar,
                    )
                },
            activeTabId = layoutState.activeTabId,
        )
        lastSnapshot = snapshot
        push?.invoke(snapshot)
    }
}
