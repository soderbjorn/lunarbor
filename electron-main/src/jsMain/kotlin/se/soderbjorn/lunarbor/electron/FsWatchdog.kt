/* FsWatchdog.kt — notices when the main process's file access stops.
 *
 * Two things are probed every [PROBE_INTERVAL_MS] with a `stat` of the
 * temp folder (local, never cloud-synced):
 *
 *  - The file thread (FileThread.kt), which does all of the main
 *    process's file I/O. A probe unanswered after [STUCK_AFTER_MS] means
 *    an operation is blocked inside the OS (e.g. a stalled cloud drive):
 *    the user is asked once to restart, and [isStuck] lets the MCP
 *    endpoint (McpHttpServer.kt) answer at once instead of relaying to a
 *    renderer that can no longer finish. A probe that comes back later
 *    clears the state.
 *  - libuv's shared thread pool, through Node's real `fs/promises`. It
 *    has been seen to wedge for good (queued work, idle threads), which
 *    is why files no longer use it. A wedged pool ([LibuvPool.isStuck]) is only
 *    logged; it still matters in two places: Lunicle's host-name lookups
 *    switch to a resolver that does not use it (LunicleHost.kt), and
 *    quitting ends the process outright ([hardExitIfPoolStuck]), since a
 *    normal exit waits on the pool's threads forever.
 *
 * Main process only; started once by `ElectronMain.main`. */
package se.soderbjorn.lunarbor.electron

import kotlin.js.Promise

/** Pause between probes. */
private const val PROBE_INTERVAL_MS: Int = 10_000

/** How long a probe may stay unanswered before what it probes counts as stuck. */
private const val STUCK_AFTER_MS: Double = 20_000.0

/**
 * One repeating probe: at most one in flight; stuck while it has been
 * unanswered for [STUCK_AFTER_MS].
 *
 * @param name What is probed, for the log.
 * @param stat Starts one probe; its promise settling (either way) answers it.
 */
internal class Probe(private val name: String, private val stat: () -> Promise<dynamic>) {
    /** When the probe in flight was started (`Date.now()`), or `null` when none is. */
    private var startedAt: Double? = null

    /** Whether a stuck spell has been logged (and reported, for the file thread). */
    var reported = false

    val isStuck: Boolean
        get() = startedAt?.let { now() - it >= STUCK_AFTER_MS } ?: false

    /** Starts a probe when none is in flight. */
    fun tick() {
        if (startedAt != null) return
        val started = now()
        startedAt = started
        val clear: (dynamic) -> Unit = {
            if (startedAt == started) {
                if (reported) println("[lunarbor] $name is answering again")
                startedAt = null
                reported = false
            }
        }
        stat().then(clear, clear)
    }

    /** Logs the start of a stuck spell once; `true` the first time. */
    fun reportOnce(): Boolean {
        if (!isStuck || reported) return false
        reported = true
        println("[lunarbor] $name has stopped answering")
        return true
    }
}

/**
 * Whether libuv's shared thread pool is running work, from a probe
 * [FsWatchdog] drives. Kept apart from [FsWatchdog] so code that only
 * asks (LunicleHost.kt, and its tests) does not pull in Electron.
 */
internal object LibuvPool {
    val probe = Probe("libuv's thread pool") { poolFsPromises.stat(osModule.tmpdir()) }

    /** `true` while the pool has stopped running work. */
    val isStuck: Boolean get() = probe.isStuck
}

/**
 * Probes the file thread and libuv's thread pool; offers a restart when
 * the file thread stops answering.
 *
 * ### Callers
 * - `ElectronMain.main` calls [start] once and [hardExitIfPoolStuck] on `quit`.
 * - `McpHost.forward` reads [isStuck] to refuse MCP requests at once.
 */
object FsWatchdog {
    private val fileThread = Probe("file access") { fsPromises.stat(osModule.tmpdir()) }
    private val pool get() = LibuvPool.probe

    private var started = false

    /**
     * `true` while the file thread has not answered for [STUCK_AFTER_MS]:
     * file operations are blocked, so nothing reaches the disk.
     */
    val isStuck: Boolean get() = fileThread.isStuck

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

    /**
     * Ends the process at once when libuv's pool is wedged: Node's normal
     * exit joins the pool's threads, which never wake, so the app would
     * hang half-quit. Called on Electron's `quit` event, after the windows
     * have closed and saved.
     */
    fun hardExitIfPoolStuck() {
        if (!pool.isStuck) return
        println("[lunarbor] libuv's thread pool is stuck; ending the process directly")
        hardExit()
    }

    /** One check: probe both, log a stuck pool, ask once when file access is stuck. */
    private fun tick(window: () -> BrowserWindow?) {
        fileThread.tick()
        pool.tick()
        pool.reportOnce()
        if (!fileThread.isStuck || fileThread.reported) return
        val w = window()
        if (w == null || w.isDestroyed()) return
        fileThread.reportOnce()
        val options: dynamic = js("({})")
        options.type = "warning"
        options.message = "Lunarbor can no longer read or write files"
        options.detail = "File access has stopped responding (the disk or a cloud drive is not answering), " +
            "so changes are not being saved and agent requests cannot be answered. " +
            "Restart Lunarbor to fix it. Edits made since this started cannot be saved."
        options.buttons = arrayOf("Restart Lunarbor", "Later")
        options.defaultId = 0
        options.cancelId = 1
        dialog.showMessageBox(w, options).then { result: dynamic ->
            if (result.response == 0) {
                // Not `quit` (it waits on saves that cannot finish) nor `exit`
                // (it can wait on a blocked thread): `relaunch` has already
                // started the helper that opens the new instance.
                app.relaunch()
                hardExit()
            }
        }
    }

    /** SIGKILL to this process: no exit handlers, nothing to wait on. */
    private fun hardExit() {
        val p: dynamic = process
        p.kill(p.pid, "SIGKILL")
    }
}

private fun now(): Double = js("Date.now()") as Double
