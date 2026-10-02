/*
 * AgentWorkspace.kt (commonMain)
 * ------------------------------
 * The app's windows and tabs as the MCP tools see them: what is open, and
 * the few things an agent may do with them — open a window on a node or
 * note, point a window somewhere else, close it; add, select, rename and
 * close tabs.
 *
 * The windowing system is per-platform (the web shell's tabs and floating
 * panes), so this is an interface: the platform shell implements it
 * (`AppShell.agentWorkspace` on the web) and hands it to [McpTools].
 * commonMain only.
 */

package se.soderbjorn.lunarbor.mcp

/**
 * Window and tab control for agents.
 *
 * ### Callers
 * - [McpTools]' window tools (`list_windows`, `open_window`, …).
 *
 * Paths are vault-relative (no leading slash; `""` the root), naming a
 * node folder or a file, as everywhere in [McpTools]. Methods that can be
 * refused return an error message for the agent, or `null` on success.
 */
interface AgentWorkspace {

    /**
     * One tab and its windows.
     *
     * @property id Stable tab id.
     * @property title The tab's label.
     * @property isActive Whether it is the tab on screen.
     * @property windows Its windows (panes), front-most last.
     */
    data class Tab(val id: String, val title: String, val isActive: Boolean, val windows: List<Window>)

    /**
     * One window (pane).
     *
     * @property id Stable window id.
     * @property location Where it is: the node folder it shows (zoomed
     *   into, or the open outline's) or the file it has open; `null`
     *   while it is still loading.
     * @property title What its header shows.
     * @property isFocused Whether it is its tab's focused window.
     */
    data class Window(val id: String, val location: String?, val title: String, val isFocused: Boolean)

    /** Every tab, in tab-strip order. */
    suspend fun tabs(): List<Tab>

    /**
     * Opens a new window showing [path] in [tabId] (the active tab when
     * `null`) and focuses it.
     *
     * @return The new window's id, or an error message (prefixed `!`).
     */
    suspend fun openWindow(tabId: String?, path: String): String

    /** Points the window [windowId] at [path], as a link click would (Back returns). */
    suspend fun showInWindow(windowId: String, path: String): String?

    /** Closes the window [windowId]; closing a tab's last window closes the tab, unless it is the only one. */
    suspend fun closeWindow(windowId: String): String?

    /**
     * Adds a tab titled [title] with one window on [path] (the root when
     * `null`) and makes it active.
     *
     * @return The new tab's id, or an error message (prefixed `!`).
     */
    suspend fun newTab(title: String?, path: String?): String

    /** Makes [tabId] the tab on screen. */
    suspend fun selectTab(tabId: String): String?

    /** Renames the tab [tabId]. */
    suspend fun renameTab(tabId: String, title: String): String?

    /** Closes the tab [tabId] with its windows; the last tab cannot be closed. */
    suspend fun closeTab(tabId: String): String?
}
