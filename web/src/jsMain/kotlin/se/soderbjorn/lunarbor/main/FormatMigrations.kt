/*
 * FormatMigrations.kt (jsMain)
 * ----------------------------
 * One-time vault format migrations, run by `Main.kt` at startup, after the
 * DI graph exists but before anything reads the vault (the registry, the
 * panes, the indexes). Each migration runs once per vault: the ones done are
 * remembered under the persister key [DONE_KEY] as
 * `{ "<vault root>": ["<migration id>", …] }`.
 *
 * Rules (CLAUDE.md, "Format changes ship with a migration"): the vault is
 * converted whole, in one pass — never left to individual saves — with the
 * originals kept in `.trash/<timestamp> format migration/`, and every path
 * the app remembers is mapped onto the new layout (none moves here).
 *
 * Current migrations:
 * - [RELATIVE_LINKS]: links are relative Markdown links instead of
 *   `lunarbor:/…` (`NoteRepository.migrateRelativeLinks`).
 *
 * Platform glue only: the vault work itself lives in commonMain.
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunula.core.Persister

/** Persister key of the migrations done, per vault root. */
private const val DONE_KEY: String = "lunarborFormatMigrations"

/** Id of the "relative links" migration. */
private const val RELATIVE_LINKS: String = "relative-links"

/**
 * Runs every format migration [repository]'s vault has not had yet, then
 * records them as done. A migration that throws is logged and not recorded,
 * so the next start tries again; the app starts either way.
 *
 * Called by `Main.start` before `graph.documentRegistry` is first touched,
 * so no document, listing or index reads the vault meanwhile.
 *
 * @param repository The app's repository (the vault to migrate).
 * @param persister Where the done list lives.
 */
suspend fun runFormatMigrations(repository: NoteRepository, persister: Persister) {
    val vault = repository.rootDirectory
    val all: dynamic = readJson(persister, DONE_KEY) ?: js("({})")
    val doneRaw: dynamic = all[vault]
    val done = if (js("Array.isArray(doneRaw)") as Boolean) {
        (doneRaw as Array<Any?>).filterIsInstance<String>().toMutableSet()
    } else {
        mutableSetOf()
    }
    if (RELATIVE_LINKS in done) return
    try {
        val result = repository.migrateRelativeLinks()
        if (result.rewrittenFiles.isNotEmpty()) {
            println(
                "[migration] $RELATIVE_LINKS: ${result.rewrittenFiles.size} files rewritten, " +
                    "originals in ${result.backupFolder}",
            )
        }
        done += RELATIVE_LINKS
        all[vault] = done.sorted().toTypedArray()
        persister.write(DONE_KEY, JSON.stringify(all))
    } catch (t: Throwable) {
        console.error("[migration] $RELATIVE_LINKS failed; will retry on next start", t)
    }
}

/** The JSON value stored under [key], or `null` when absent or unreadable. */
private suspend fun readJson(persister: Persister, key: String): dynamic {
    val raw = persister.read(key) ?: return null
    return try {
        JSON.parse<dynamic>(raw)
    } catch (_: Throwable) {
        null
    }
}
