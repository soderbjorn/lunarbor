/* VaultWatcher.kt — watches the vault for changes made outside Lunarbor.
 *
 * A recursive `fs.watch` on the vault root collects changed paths,
 * debounces them ([VAULT_WATCH_DEBOUNCE_MS]) and sends the ones that
 * really changed, and not by this app, to the renderer over
 * `lunarbor:vaultChanged`, where `DocumentRegistry.applyExternalChanges`
 * reloads the open documents they concern — dropping their unsaved
 * edits, so a false report loses typing.
 *
 * Filtering ([VaultChangeFilter]): a watcher event alone means little.
 * Sync clients (Google Drive's File Provider) fire bursts of events for
 * files whose content did not change. So a path counts only when its
 * state differs from what the app last knew — its text for note files,
 * size + mtime for other files, existence for folders. The app knows a
 * path from its own writes and reads (the `lunarbor:*` IPC handlers)
 * and from earlier events. A path it never knew counts only when its
 * mtime is fresh ([FRESH_CHANGE_WINDOW_MS]) or it is gone. Paths the app
 * itself moved, deleted or created are ignored for
 * [SELF_TOUCH_WINDOW_MS], including everything under them.
 *
 * Main process only; the filter itself is pure and tested in
 * VaultWatcherTest. */
package se.soderbjorn.lunarbor.electron

/** Quiet time before a batch of watcher events is checked and sent. */
const val VAULT_WATCH_DEBOUNCE_MS: Int = 300

/** How long a path touched by this app's own file ops counts as ours. */
const val SELF_TOUCH_WINDOW_MS: Double = 3_000.0

/**
 * How recent an mtime must be for a change to a path the app never knew
 * to count. Older ones are sync clients re-touching old files.
 */
const val FRESH_CHANGE_WINDOW_MS: Double = 10_000.0

/** How many of this app's latest writes per file still count as its own. */
private const val OWN_WRITES_KEPT = 4

/**
 * What the watcher sees at a path when it checks an event.
 *
 * @property text The file's text, for note files (`_node.md`,
 *   `.md`); `null` for folders and other files.
 * @property isDirectory `true` for a folder.
 * @property size Size in bytes (0 for folders).
 * @property mtimeMs Last content modification, epoch millis.
 */
data class PathProbe(
    val text: String?,
    val isDirectory: Boolean,
    val size: Double,
    val mtimeMs: Double,
)

/** `true` for files the watcher compares by text rather than by stat. */
fun isWatchedTextFile(path: String): Boolean = path.endsWith(".lunarbor") || path.endsWith(".md")

/**
 * Tells real outside changes from this app's own file ops and from
 * events that changed nothing.
 *
 * ### Callers
 * - The `lunarbor:*` file-op IPC handlers ([recordText], [recordRead],
 *   [recordTouch]).
 * - [installVaultWatcher], which asks [isExternalChange] per changed path.
 */
class VaultChangeFilter {
    /** Last known state per path, as [signatureOf] spells it ([GONE] when absent). */
    private val known = HashMap<String, String>()
    /** This app's latest texts per file, newest last. */
    private val ownTexts = HashMap<String, ArrayDeque<String>>()
    private val touched = HashMap<String, Double>()

    /** Records that this app wrote [content] to the file [path]. */
    fun recordText(path: String, content: String) {
        known[path] = textSignature(content)
        val own = ownTexts.getOrPut(path) { ArrayDeque() }
        own.addLast(content)
        while (own.size > OWN_WRITES_KEPT) own.removeFirst()
    }

    /** Records that this app read [content] from the file [path]. */
    fun recordRead(path: String, content: String) {
        if (isWatchedTextFile(path)) known[path] = textSignature(content)
    }

    /**
     * Records that this app created, moved, deleted or wrote (binary)
     * [path] at [nowMs]; paths under it count as touched too.
     */
    fun recordTouch(path: String, nowMs: Double) {
        known.remove(path)
        touched[path] = nowMs
    }

    /**
     * `true` when the event reported for [path] is a change made outside
     * this app. Remembers [path]'s current state either way, so the next
     * event compares against it.
     *
     * @param probe Reads [path]'s current state; `null` when it is gone.
     */
    fun isExternalChange(path: String, nowMs: Double, probe: (String) -> PathProbe?): Boolean {
        touched.entries.removeAll { nowMs - it.value > SELF_TOUCH_WINDOW_MS }
        val current = probe(path)
        val signature = current?.let(::signatureOf) ?: GONE
        val before = known[path]
        known[path] = signature
        if (touched.keys.any { path == it || path.startsWith("$it/") }) return false
        val text = current?.text
        if (text != null && ownTexts[path]?.contains(text) == true) return false
        return when {
            before != null -> before != signature
            current == null -> true
            else -> nowMs - current.mtimeMs <= FRESH_CHANGE_WINDOW_MS
        }
    }

    private fun textSignature(text: String) = "t:$text"

    private companion object {
        /** Signature of a path that is not there. */
        const val GONE = "-"
    }

    private fun signatureOf(p: PathProbe): String = when {
        p.isDirectory -> "d"
        p.text != null -> textSignature(p.text)
        else -> "f:${p.size}:${p.mtimeMs}"
    }
}

/** The filter shared by the IPC handlers and the watcher. */
internal val vaultChanges = VaultChangeFilter()

/** Reads [path]'s [PathProbe]; `null` when it is gone or unreadable. */
private fun probePath(path: String): PathProbe? = try {
    val st = fsSync.statSync(path)
    val dir = st.isDirectory() as Boolean
    PathProbe(
        text = if (!dir && isWatchedTextFile(path)) fsSync.readFileSync(path, "utf8") else null,
        isDirectory = dir,
        size = if (dir) 0.0 else (st.size as Number).toDouble(),
        mtimeMs = (st.mtimeMs as Number).toDouble(),
    )
} catch (_: Throwable) {
    null
}

private var vaultWatcher: FsWatcher? = null
private var vaultWatcherRoot: String? = null

/**
 * Starts (or keeps) the recursive watcher on [root], sending external
 * change batches to the window returned by [window]. Replaces a watcher
 * on another root (after a vault switch). Watch failures are logged: the
 * app then only picks up outside changes on window focus.
 *
 * Called by `createWindow`.
 */
fun installVaultWatcher(root: String, window: () -> BrowserWindow?) {
    if (vaultWatcher != null && vaultWatcherRoot == root) return
    vaultWatcher?.let { try { it.close() } catch (_: Throwable) {} }
    vaultWatcher = null
    vaultWatcherRoot = root
    val pending = LinkedHashSet<String>()
    var timer: dynamic = null
    val flush = {
        timer = null
        val now = js("Date.now()") as Double
        val batch = pending.toList()
        pending.clear()
        val external = batch.filter { p ->
            vaultChanges.isExternalChange(p, now, ::probePath)
        }
        val w = window()
        if (external.isNotEmpty() && w != null && !w.isDestroyed()) {
            w.webContents.send("lunarbor:vaultChanged", external.toTypedArray())
        }
    }
    try {
        val opts: dynamic = js("({})")
        opts.recursive = true
        vaultWatcher = fsSync.watch(root, opts) { _, filename ->
            if (filename != null && filename.split('/').none { it.startsWith(".") }) {
                pending += pathModule.join(root, filename)
                if (timer != null) js("clearTimeout")(timer)
                timer = js("setTimeout")(flush, VAULT_WATCH_DEBOUNCE_MS)
            }
        }
    } catch (err: Throwable) {
        console.error("Could not watch the vault", root, err.message)
    }
}
