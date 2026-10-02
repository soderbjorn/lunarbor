/* BackupSettings.kt (jsMain)
 *
 * The App settings sidebar's **Backup** section (Electron only): the backup
 * folder with a Choose… / Change… button, "Back up now", an "Automatically
 * back up" interval, and "Last backup" — the newest backup zip the main
 * process finds in the folder. Also [installBackupScheduler], which answers
 * the main process's automatic backups.
 *
 * Everything is owned by the Electron main process (VaultBackup.kt): the
 * settings, the schedule, the zip and the write hold while it is written.
 * The renderer's only part is saving open documents first
 * (`DocumentRegistry.flushAll`), so a backup holds the latest edits.
 *
 * Platform view code only — builds DOM, delegates every action. */
package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Date
import kotlin.js.Promise

/** `noteApi` when it carries the backup bridge (Electron), else `null`. */
internal fun backupBridge(): dynamic {
    val api = js("globalThis.noteApi")
    if (api == null || js("typeof api.getBackup !== 'function'") as Boolean) return null
    return api
}

/** The interval choices, in hours (0 = off), matching `BACKUP_INTERVAL_HOURS` in VaultBackup.kt. */
private val INTERVALS = listOf(
    0 to "Off",
    1 to "Every hour",
    6 to "Every 6 hours",
    12 to "Every 12 hours",
    24 to "Every day",
    168 to "Every week",
)

/**
 * The status the main process reports (`BackupHost.status`).
 *
 * @property folder The backup folder, or `null` before one is chosen.
 * @property intervalHours Automatic backup interval; 0 is off.
 * @property running A backup is being written.
 * @property error Why the last backup or folder pick failed.
 * @property lastBackupMs When this vault's newest backup in the folder was made.
 */
private data class BackupStatus(
    val folder: String?,
    val intervalHours: Int,
    val running: Boolean,
    val error: String?,
    val lastBackupMs: Double?,
) {
    companion object {
        fun of(d: dynamic) = BackupStatus(
            folder = d.folder as String?,
            intervalHours = (d.intervalHours as? Number)?.toInt() ?: 0,
            running = d.running == true,
            error = d.error as String?,
            lastBackupMs = (d.lastBackupMs as? Number)?.toDouble(),
        )
    }
}

/**
 * Saves pending edits, then has the main process zip the vault. Used by
 * "Back up now" and by automatic backups ([installBackupScheduler]).
 *
 * @param flushEdits Saves every open document (`DocumentRegistry.flushAll`).
 * @return The status after the backup.
 */
private suspend fun backUpNow(flushEdits: suspend () -> Unit): dynamic {
    try {
        flushEdits()
    } catch (e: Throwable) {
        console.error("[lunarbor] could not save before backup", e)
    }
    return (backupBridge().backupNow() as Promise<dynamic>).await()
}

/**
 * Answers the main process's automatic backups: when one is due, saves
 * pending edits and backs up. Subscribing also lets the main process check
 * the schedule at startup. No-op outside Electron.
 *
 * Called once by `main` in Main.kt.
 *
 * @param scope Scope the backups run in.
 * @param flushEdits Saves every open document.
 */
fun installBackupScheduler(scope: CoroutineScope, flushEdits: suspend () -> Unit) {
    val bridge = backupBridge() ?: return
    bridge.onBackupDue { scope.launch { backUpNow(flushEdits) } }
}

/**
 * Builds the Backup section. Called by `buildAppSettingsContent` when
 * [backupBridge] is present; fills itself in from `noteApi.getBackup` and
 * follows status pushes while it is on screen.
 *
 * @param scope Scope the bridge calls run in.
 * @param flushEdits Saves every open document before "Back up now".
 */
internal fun buildBackupSection(scope: CoroutineScope, flushEdits: suspend () -> Unit): HTMLElement {
    ensureBackupStyles()
    val section = el("section", "lunarbor-app-settings-section lunarbor-backup")
    section.appendChild(el("h3", "lunarbor-app-settings-section-title", "Backup"))
    section.appendChild(
        el(
            "p", "lunarbor-backup-intro",
            "Zips the whole vault into a folder you choose, with the date in the file name. " +
                "Saving waits while a backup is written.",
        ),
    )

    // Same look as the Vault section's path row (AppSettingsContent.kt).
    val row = el("div", "lunarbor-vault-row")
    val path = el("div", "lunarbor-vault-path")
    val pathText = el("span", "lunarbor-vault-path-text", "…")
    path.appendChild(pathText)
    row.appendChild(path)
    val choose = button("lunarbor-vault-change", "Choose…")
    row.appendChild(choose)
    section.appendChild(row)

    val now = button("lunarbor-backup-now", "Back up now")
    section.appendChild(now)

    val autoRow = el("label", "lunarbor-backup-auto")
    autoRow.appendChild(el("span", "lunarbor-backup-auto-label", "Automatically back up"))
    val select = document.createElement("select") as HTMLSelectElement
    select.className = "lunarbor-backup-interval"
    for ((hours, label) in INTERVALS) {
        val option = document.createElement("option") as HTMLOptionElement
        option.value = hours.toString()
        option.textContent = label
        select.appendChild(option)
    }
    autoRow.appendChild(select)
    section.appendChild(autoRow)

    val last = el("div", "lunarbor-backup-last")
    section.appendChild(last)
    val error = el("div", "lunarbor-backup-error")
    section.appendChild(error)

    var status: BackupStatus? = null
    var busy = false

    fun render() {
        val s = status ?: return
        val hasFolder = s.folder != null
        pathText.textContent = s.folder ?: "No folder chosen"
        path.title = s.folder ?: ""
        path.classList.toggle("is-empty", !hasFolder)
        choose.textContent = if (hasFolder) "Change…" else "Choose…"
        choose.disabled = busy || s.running
        now.disabled = !hasFolder || busy || s.running
        now.textContent = if (s.running) "Backing up…" else "Back up now"
        select.value = s.intervalHours.toString()
        select.disabled = !hasFolder
        last.hidden = !hasFolder
        last.textContent = when (val ms = s.lastBackupMs) {
            null -> "Last backup: none yet"
            else -> "Last backup: ${Date(ms).toLocaleString()} (${relativeAge(ms)})"
        }
        error.hidden = s.error == null
        error.textContent = s.error ?: ""
    }

    fun run(call: suspend () -> dynamic) {
        busy = true
        render()
        scope.launch {
            try {
                status = BackupStatus.of(call())
            } finally {
                busy = false
                render()
            }
        }
    }

    choose.addEventListener("click", { _: Event ->
        run { (backupBridge().chooseBackupFolder() as Promise<dynamic>).await() }
    })
    now.addEventListener("click", { _: Event -> run { backUpNow(flushEdits) } })
    select.addEventListener("change", { _: Event ->
        val patch: dynamic = js("({})")
        patch.intervalHours = select.value.toInt()
        run { (backupBridge().setBackup(patch) as Promise<dynamic>).await() }
    })

    // Follow automatic backups while the sidebar is open; let go once the
    // section has left the page (the sidebar rebuilds it on every open).
    var unsubscribe: dynamic = null
    unsubscribe = backupBridge().onBackupStatus { d: dynamic ->
        if (!section.isConnected && status != null) {
            if (unsubscribe != null) unsubscribe()
            return@onBackupStatus
        }
        status = BackupStatus.of(d)
        render()
    }

    scope.launch {
        status = BackupStatus.of((backupBridge().getBackup() as Promise<dynamic>).await())
        render()
    }
    return section
}

/** "just now", "5 minutes ago", "3 hours ago", "2 days ago" for [epochMs]. */
private fun relativeAge(epochMs: Double): String {
    val minutes = ((Date.now() - epochMs) / 60_000).toInt()
    fun plural(n: Int, unit: String) = "$n $unit${if (n == 1) "" else "s"} ago"
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> plural(minutes, "minute")
        minutes < 48 * 60 -> plural(minutes / 60, "hour")
        else -> plural(minutes / (24 * 60), "day")
    }
}

private fun button(className: String, label: String): HTMLButtonElement {
    val b = document.createElement("button") as HTMLButtonElement
    b.type = "button"
    b.className = className
    b.textContent = label
    return b
}

private fun el(tag: String, className: String, text: String? = null): HTMLElement =
    (document.createElement(tag) as HTMLElement).also {
        it.className = className
        if (text != null) it.textContent = text
    }

private fun ensureBackupStyles() {
    if (document.getElementById("lunarbor-backup-style") != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = "lunarbor-backup-style"
    style.textContent = BACKUP_CSS
    document.head?.appendChild(style)
}

private const val BACKUP_CSS = """
.lunarbor-backup-intro { margin: 0; font-size: 12px; color: var(--t-text-dim, #9a9a9a); }
.lunarbor-backup .lunarbor-vault-path.is-empty { font-family: inherit; color: var(--t-text-dim, #9a9a9a); }
.lunarbor-backup-now {
    align-self: flex-start; padding: 6px 12px; font: inherit; font-weight: 600;
    color: var(--t-text, #e6e6e6); background: transparent;
    border: 1px solid var(--t-border, rgba(255,255,255,0.12)); border-radius: 8px; cursor: pointer;
}
.lunarbor-backup-now:hover:not(:disabled) {
    background: color-mix(in srgb, var(--t-accent) 14%, var(--t-surface));
    border-color: color-mix(in srgb, var(--t-accent) 40%, var(--t-border, rgba(255,255,255,0.12)));
}
.lunarbor-backup-now:disabled { opacity: 0.45; cursor: default; }
.lunarbor-backup-now:focus-visible, .lunarbor-backup-interval:focus-visible { outline: 2px solid var(--t-accent); outline-offset: 2px; }
.lunarbor-backup-auto { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.lunarbor-backup-auto-label { font-weight: 600; }
.lunarbor-backup-interval {
    padding: 4px 8px; font: inherit; font-size: 12px;
    color: var(--t-text, #e6e6e6); background: var(--t-surface, transparent);
    border: 1px solid var(--t-border, rgba(255,255,255,0.12)); border-radius: 6px;
}
.lunarbor-backup-interval:disabled { opacity: 0.45; }
.lunarbor-backup-last { font-size: 12px; color: var(--t-text-dim, #9a9a9a); }
.lunarbor-backup-error { font-size: 12px; color: var(--t-danger, #e5534b); }
.lunarbor-backup-last[hidden], .lunarbor-backup-error[hidden] { display: none; }
body.appearance-light .lunarbor-backup-now, body.appearance-light .lunarbor-backup-interval { border-color: rgba(0,0,0,0.10); }
"""
