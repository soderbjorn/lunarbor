/* FsWatchdog.kt — notices when the main process's file access stops.
 *
 * Every async `fs` call the file-op IPC handlers make runs on Node's
 * libuv thread pool. That pool has been seen to stop delivering results
 * for good (Electron 32 on macOS, vault on Google Drive): every
 * `fs.promises` call — even a `stat` of the temp folder — then waits
 * forever, so saves stop silently and every MCP call queues behind the
 * first one that got stuck. Only a restart recovers.
 *
 * This file probes the pool every [PROBE_INTERVAL_MS] with a `stat` of
 * the temp folder (local, never cloud-synced, so it measures the pool
 * and not the disk). A probe still unanswered after [STUCK_AFTER_MS]
 * marks file access as stuck: the user is asked once to restart, and
 * [isStuck] lets the MCP endpoint (McpHttpServer.kt) answer at once
 * instead of relaying to a renderer that can no longer finish. A probe
 * that comes back later clears the state.
 *
 * Main process only; started once by `ElectronMain.main`. */
package se.soderbjorn.lunarbor.electron

/** Pause between probes of the thread pool. */
private const val PROBE_INTERVAL_MS: Int = 10_000

/** How long a probe may stay unanswered before file access counts as stuck. */
private const val STUCK_AFTER_MS: Double = 20_000.0

/**
 * Probes the libuv thread pool that every async file operation runs on,
 * and offers a restart when it stops answering.
 *
 * ### Callers
 * - `ElectronMain.main` calls [start] once.
 * - `McpHost.forward` reads [isStuck] to refuse MCP requests at once.
 */
object FsWatchdog {
    /** When the probe in flight was started (`Date.now()`), or `null` when none is. */
    private var probeStartedAt: Double? = null

    /** Whether the user has been asked to restart during this stuck spell. */
    private var asked = false

    private var started = false

    /**
     * `true` while a probe has gone unanswered for [STUCK_AFTER_MS]:
     * async file operations in this process are not completing, so
     * nothing reaches the disk until the app is restarted.
     */
    val isStuck: Boolean
        get() = probeStartedAt?.let { now() - it >= STUCK_AFTER_MS } ?: false

    /**
     * Starts probing. [window] is the window the restart prompt is
     * attached to; without one no prompt is shown (the next check asks
     * again). Idempotent.
     */
    fun start(window: () -> BrowserWindow?) {
        if (started) return
        started = true
        js("setInterval")({ tick(window) }, PROBE_INTERVAL_MS)
    }

    /** One check: start a probe when none is in flight, or ask once a probe is stuck. */
    private fun tick(window: () -> BrowserWindow?) {
        if (probeStartedAt == null) {
            val startedAt = now()
            probeStartedAt = startedAt
            val clear: (dynamic) -> Unit = {
                if (probeStartedAt == startedAt) {
                    if (asked) println("[lunarbor] file access is answering again")
                    probeStartedAt = null
                    asked = false
                }
            }
            fsPromises.stat(osModule.tmpdir()).then(clear, clear)
            return
        }
        if (!isStuck || asked) return
        val w = window()
        if (w == null || w.isDestroyed()) return
        asked = true
        println("[lunarbor] file access has stopped answering; offering a restart")
        val options: dynamic = js("({})")
        options.type = "warning"
        options.message = "Lunarbor can no longer read or write files"
        options.detail = "File access in the app has stopped responding, so changes are not being saved " +
            "and agent requests cannot be answered. Restart Lunarbor to fix it. " +
            "Edits made since this started cannot be saved."
        options.buttons = arrayOf("Restart Lunarbor", "Later")
        options.defaultId = 0
        options.cancelId = 1
        dialog.showMessageBox(w, options).then { result: dynamic ->
            if (result.response == 0) {
                // `exit`, not `quit`: quitting waits on saves that can no longer finish.
                app.relaunch()
                app.exit(0)
            }
        }
    }

    private fun now(): Double = js("Date.now()") as Double
}
