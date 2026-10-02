/* VaultBackup.kt — vault backups, in the Electron main process.
 *
 * App settings → Backup picks a folder; "Back up now" (or the automatic
 * schedule) zips the whole vault into it as `<vault name> backup <date>.zip`
 * (ZipWriter.kt). While the zip is written every vault write the renderer
 * asks for waits ([VaultWriteGate]): the file-op IPC handlers in
 * ElectronMain.kt run through [VaultWriteGate.write], the backup through
 * [VaultWriteGate.exclusive], which first lets writes already under way
 * finish. The archive is written as `….zip.partial` and renamed when
 * complete, so a half-written backup is never taken for a real one.
 *
 * "Last backup" is not stored: it is the newest date among this vault's
 * backup files in the folder ([backupTimeOf]), so it stays true when backups
 * are deleted, copied in or made on another machine.
 *
 * Automatic backups: a check every minute, and one at startup as soon as
 * the window's renderer is ready (`lunarbor:backupReady`, so a backup
 * that fell due while the app was closed runs right away); when the newest backup is older
 * than the chosen interval ([isBackupDue]) it asks the window to back up
 * (`lunarbor:backupDue`) so the renderer first saves unsaved edits, then
 * calls `lunarbor:backupNow` like the button does. Without a window that
 * answers, it backs up directly. A failed automatic backup is retried after
 * [BACKUP_RETRY_MS].
 *
 * Settings — the folder and the interval — live in their own file,
 * `lunarbor-backup.json`, owned by this process (`lunarbor:getBackup` /
 * `setBackup` / `chooseBackupFolder`). The folder may not lie inside the
 * vault (the backup would contain the earlier backups).
 *
 * Main-process glue only. */
package se.soderbjorn.lunarbor.electron

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise

/** Intervals the Backup section offers, in hours; 0 is off. */
internal val BACKUP_INTERVAL_HOURS: List<Int> = listOf(0, 1, 6, 12, 24, 168)

/** How often the schedule is checked. */
private const val BACKUP_CHECK_MS: Int = 60_000

/** Wait before retrying a failed automatic backup. */
private const val BACKUP_RETRY_MS: Double = 15 * 60_000.0

/** How long an asked-for automatic backup may take to start before it is asked again. */
private const val BACKUP_DUE_GRACE_MS: Double = 5 * 60_000.0

/**
 * The persisted Backup settings.
 *
 * @property folder Absolute folder backups go to, or `null` before one is chosen.
 * @property intervalHours Automatic backup interval, one of [BACKUP_INTERVAL_HOURS]; 0 is off.
 */
internal data class BackupSettings(val folder: String? = null, val intervalHours: Int = 0)

/**
 * The backup file name for [vaultName] at [epochMs] (local time):
 * `Notes backup 2026-10-01 14.30.05.zip`. No colons, so Finder and Windows
 * show it as written.
 */
internal fun backupFileName(vaultName: String, epochMs: Double): String {
    val d: dynamic = js("new Date(epochMs)")
    fun two(n: dynamic) = (n as Int).toString().padStart(2, '0')
    val date = "${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}"
    val time = "${two(d.getHours())}.${two(d.getMinutes())}.${two(d.getSeconds())}"
    return "$vaultName backup $date $time.zip"
}

private val BACKUP_NAME = Regex("""^(.*) backup (\d{4})-(\d{2})-(\d{2}) (\d{2})\.(\d{2})\.(\d{2})\.zip$""")

/**
 * When the backup [fileName] of [vaultName] was made (epoch ms, from the
 * local date in its name), or `null` when it is not one of that vault's
 * backups. The inverse of [backupFileName].
 */
internal fun backupTimeOf(fileName: String, vaultName: String): Double? {
    val m = BACKUP_NAME.matchEntire(fileName) ?: return null
    if (m.groupValues[1] != vaultName) return null
    val (y, mo, d, h, mi, s) = m.groupValues.drop(2).map { it.toInt() }
    val ms = js("new Date(y, mo - 1, d, h, mi, s).getTime()") as Double
    return if (ms.isNaN()) null else ms
}

private operator fun <T> List<T>.component6(): T = this[5]

/**
 * `true` when an automatic backup is due: automatic backups are on
 * ([intervalHours] > 0) and the newest backup ([lastMs], `null` for none) is
 * at least that old at [nowMs].
 */
internal fun isBackupDue(intervalHours: Int, lastMs: Double?, nowMs: Double): Boolean {
    if (intervalHours <= 0) return false
    return lastMs == null || nowMs - lastMs >= intervalHours * 3_600_000.0
}

/**
 * `true` when [path] is [folder] or lies inside it. Both must be absolute
 * and normalized; [caseInsensitive] for macOS / Windows file systems.
 */
internal fun isSameOrInside(path: String, folder: String, caseInsensitive: Boolean): Boolean {
    val p = (if (caseInsensitive) path.lowercase() else path).trimEnd('/', '\\')
    val f = (if (caseInsensitive) folder.lowercase() else folder).trimEnd('/', '\\')
    return p == f || p.startsWith("$f/") || p.startsWith("$f\\")
}

/**
 * Holds vault writes back while a backup reads the vault. Single-threaded
 * (the main process's event loop), so plain fields are enough.
 */
internal object VaultWriteGate {
    private var closed: CompletableDeferred<Unit>? = null
    private var inFlight = 0
    private var drained: CompletableDeferred<Unit>? = null

    /**
     * Runs one vault write, after any backup under way. Every file-op IPC
     * handler that changes the vault goes through here.
     */
    suspend fun <T> write(block: suspend () -> T): T {
        while (true) closed?.await() ?: break
        inFlight++
        try {
            return block()
        } finally {
            inFlight--
            if (inFlight == 0) drained?.complete(Unit)
        }
    }

    /**
     * Runs [block] with vault writes held: waits for writes already under
     * way, blocks new ones until [block] returns. Used by `BackupHost.runBackup`.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T {
        while (true) closed?.await() ?: break
        val gate = CompletableDeferred<Unit>()
        closed = gate
        try {
            if (inFlight > 0) {
                val d = CompletableDeferred<Unit>()
                drained = d
                d.await()
            }
            return block()
        } finally {
            drained = null
            closed = null
            gate.complete(Unit)
        }
    }
}

/** Owner of the backup settings, the schedule and the backups themselves. */
internal object BackupHost {
    private var settingsPath: () -> String = { "" }
    private var vaultDir: () -> String = { "" }
    private var window: () -> BrowserWindow? = { null }

    private var settings = BackupSettings()
    private var running = false
    private var lastError: String? = null
    private var retryAfterMs = 0.0
    private var dueSentAtMs = 0.0

    /** WebContents id of the window whose renderer answers `lunarbor:backupDue`. */
    private var readyContentsId: Int? = null

    /**
     * Loads the settings, registers the `lunarbor:*Backup*` IPC handlers and
     * starts the schedule. Called once by `main`, before the first window.
     *
     * @param settingsFile Path of `lunarbor-backup.json`.
     * @param currentVault The vault root of this run (changes on a vault switch).
     * @param currentWindow The app's window, if any.
     */
    fun install(settingsFile: () -> String, currentVault: () -> String, currentWindow: () -> BrowserWindow?) {
        settingsPath = settingsFile
        vaultDir = currentVault
        window = currentWindow
        settings = readSettings()
        registerIpc()
        js("setInterval")({ tick() }, BACKUP_CHECK_MS)
    }

    /** Called when a new window is created: its renderer must say it is ready again. */
    fun onWindowCreated() {
        readyContentsId = null
    }

    private fun registerIpc() {
        ipcMain.handle("lunarbor:getBackup") { _, _ -> GlobalScope.promise { status() } }
        ipcMain.handle("lunarbor:setBackup") { _, patch ->
            GlobalScope.promise {
                (patch.intervalHours as? Number)?.toInt()?.let {
                    if (it in BACKUP_INTERVAL_HOURS) {
                        settings = settings.copy(intervalHours = it)
                        retryAfterMs = 0.0
                    }
                }
                writeSettings(settings)
                status()
            }
        }
        ipcMain.handle("lunarbor:chooseBackupFolder") { _, _ ->
            GlobalScope.promise {
                val w = window()
                if (w != null) {
                    val options: dynamic = js("({})")
                    options.title = "Choose backup folder"
                    options.buttonLabel = "Back up here"
                    options.defaultPath = settings.folder ?: osModule.homedir()
                    options.properties = arrayOf("openDirectory", "createDirectory")
                    val result = dialog.showOpenDialog(w, options).await()
                    val picked = (result.filePaths as Array<String>).firstOrNull()
                    if (result.canceled != true && picked != null) {
                        val abs = pathModule.resolve(picked)
                        if (isInsideVault(abs)) {
                            lastError = "The backup folder can't be inside the vault."
                        } else {
                            settings = settings.copy(folder = abs)
                            lastError = null
                            retryAfterMs = 0.0
                            writeSettings(settings)
                        }
                    }
                }
                status()
            }
        }
        ipcMain.handle("lunarbor:backupNow") { _, _ ->
            GlobalScope.promise {
                runBackup()
                status()
            }
        }
        ipcMain.handle("lunarbor:backupReady") { event, _ ->
            readyContentsId = event.sender.id as Int
            // On startup (and after a vault switch) an overdue backup runs
            // now rather than at the next minute check.
            tick()
            Unit
        }
    }

    /** The minute check: asks for a backup when one is due. */
    private fun tick() {
        GlobalScope.launch {
            val now = js("Date.now()") as Double
            if (running || settings.folder == null || now < retryAfterMs) return@launch
            if (!isBackupDue(settings.intervalHours, newestBackup()?.second, now)) return@launch
            val w = window()
            val answers = w != null && !w.isDestroyed() && readyContentsId != null &&
                w.webContents.asDynamic().id == readyContentsId
            if (answers) {
                // The renderer saves its edits, then calls backupNow.
                if (now - dueSentAtMs < BACKUP_DUE_GRACE_MS) return@launch
                dueSentAtMs = now
                w!!.webContents.send("lunarbor:backupDue")
            } else {
                runBackup()
            }
        }
    }

    /**
     * Zips the vault into the backup folder with writes held. Records any
     * failure in [lastError] (shown by the Backup section) and pushes the
     * status to the window before and after.
     */
    private suspend fun runBackup() {
        if (running) return
        val folder = settings.folder
        if (folder == null) {
            lastError = "Choose a backup folder first."
            return
        }
        running = true
        lastError = null
        broadcast()
        val vault = vaultDir()
        val vaultName = pathModule.basename(vault)
        var partial: String? = null
        try {
            val isDir = try { fsSync.statSync(folder).isDirectory() as Boolean } catch (_: Throwable) { false }
            if (!isDir) throw BackupFailure("The backup folder is missing: $folder")
            if (isInsideVault(folder)) throw BackupFailure("The backup folder can't be inside the vault.")
            VaultWriteGate.exclusive {
                val name = backupFileName(vaultName, js("Date.now()") as Double)
                val target = pathModule.join(folder, name)
                partial = "$target.partial"
                val count = zipFolder(vault, partial!!)
                fsPromises.rename(partial!!, target).await()
                partial = null
                console.log("[lunarbor] backup: $count entries → $target")
            }
            retryAfterMs = 0.0
        } catch (e: Throwable) {
            lastError = (e as? BackupFailure)?.message ?: "Backup failed: ${e.message ?: e}"
            retryAfterMs = (js("Date.now()") as Double) + BACKUP_RETRY_MS
            console.error("[lunarbor] backup failed", e)
            partial?.let { p -> try { fsPromises.unlink(p).await() } catch (_: Throwable) {} }
        } finally {
            running = false
            dueSentAtMs = 0.0
            broadcast()
        }
    }

    private class BackupFailure(message: String) : Exception(message)

    private fun isInsideVault(path: String): Boolean {
        fun real(p: String) = try { fsSync.asDynamic().realpathSync(p) as String } catch (_: Throwable) { p }
        val caseInsensitive = process.platform == "darwin" || process.platform == "win32"
        return isSameOrInside(real(path), real(vaultDir()), caseInsensitive)
    }

    /** This vault's newest backup in the folder: its file name and time, or `null`. */
    private suspend fun newestBackup(): Pair<String, Double>? {
        val folder = settings.folder ?: return null
        val names = try { fsPromises.readdir(folder).await() } catch (_: Throwable) { return null }
        val vaultName = pathModule.basename(vaultDir())
        return names.mapNotNull { n -> backupTimeOf(n, vaultName)?.let { n to it } }.maxByOrNull { it.second }
    }

    /** What the Backup section shows. */
    private suspend fun status(): dynamic {
        val s: dynamic = js("({})")
        s.folder = settings.folder
        s.intervalHours = settings.intervalHours
        s.running = running
        s.error = lastError
        val newest = newestBackup()
        s.lastBackupName = newest?.first
        s.lastBackupMs = newest?.second
        return s
    }

    /** Pushes the status to the window, so an open Backup section follows an automatic backup. */
    private suspend fun broadcast() {
        val s = status()
        val w = window()
        if (w != null && !w.isDestroyed()) w.webContents.send("lunarbor:backupStatus", s)
    }

    private fun readSettings(): BackupSettings {
        val text = try { fsSync.readFileSync(settingsPath(), "utf8") } catch (_: Throwable) { return BackupSettings() }
        val obj: dynamic = try { js("JSON.parse")(text) } catch (_: Throwable) { return BackupSettings() }
        return BackupSettings(
            folder = (obj.folder as? String)?.takeIf { it.isNotBlank() },
            intervalHours = (obj.intervalHours as? Number)?.toInt()?.takeIf { it in BACKUP_INTERVAL_HOURS } ?: 0,
        )
    }

    private fun writeSettings(s: BackupSettings) {
        val obj: dynamic = js("({})")
        obj.folder = s.folder
        obj.intervalHours = s.intervalHours
        val path = settingsPath()
        val tmp = "$path.tmp"
        try {
            fsSync.writeFileSync(tmp, js("JSON.stringify")(obj, null, 2) as String)
            fsSync.asDynamic().renameSync(tmp, path)
        } catch (e: Throwable) {
            console.error("[lunarbor] could not save backup settings", e)
        }
    }
}
