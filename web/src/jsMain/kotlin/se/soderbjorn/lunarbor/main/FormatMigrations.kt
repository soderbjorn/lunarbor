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
 * the app remembers (pane locations, open folds) is mapped onto the new
 * layout.
 *
 * Current migrations:
 * - [TAG_FREE_FOLDER_NAMES]: node folders are named without the item's
 *   `#tags` (`NoteRepository.migrateTagFreeFolderNames`).
 *
 * Platform glue only: the vault work itself lives in commonMain.
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.LunarborLink
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.PathMove
import se.soderbjorn.lunula.core.Persister

/** Persister key of the migrations done, per vault root. */
private const val DONE_KEY: String = "lunarborFormatMigrations"

/** Id of the "node folders without tags" migration. */
private const val TAG_FREE_FOLDER_NAMES: String = "tag-free-folder-names"

/** Persister key of `PaneLocationStore` (its `PERSIST_KEY`). */
private const val PANE_LOCATIONS_KEY: String = PaneLocationStore.PERSIST_KEY

/** Persister key of the fold memory ([AppShell.loadFoldMemory]). */
private const val FOLD_MEMORY_KEY: String = AppShell.FOLD_MEMORY_KEY

/**
 * Runs every format migration [repository]'s vault has not had yet, then
 * records them as done. A migration that throws is logged and not recorded,
 * so the next start tries again; the app starts either way.
 *
 * Called by `Main.start` before `graph.documentRegistry` is first touched,
 * so no document, listing or index reads the vault meanwhile.
 *
 * @param repository The app's repository (the vault to migrate).
 * @param persister Where pane locations, folds and the done list live.
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
    if (TAG_FREE_FOLDER_NAMES in done) return
    try {
        val result = repository.migrateTagFreeFolderNames()
        if (result.moves.isNotEmpty()) {
            println(
                "[migration] $TAG_FREE_FOLDER_NAMES: ${result.moves.size} folders renamed, " +
                    "${result.rewrittenFiles.size} files rewritten, originals in ${result.backupFolder}",
            )
            remapPaneLocations(persister, result.moves)
            remapFoldMemory(persister, vault, result.moves)
        }
        done += TAG_FREE_FOLDER_NAMES
        all[vault] = done.sorted().toTypedArray()
        persister.write(DONE_KEY, JSON.stringify(all))
    } catch (t: Throwable) {
        console.error("[migration] $TAG_FREE_FOLDER_NAMES failed; will retry on next start", t)
    }
}

/**
 * Points each persisted pane location's `file` (`PaneLocationStore`) at
 * the moved path. Zoom title paths name items by title, so they need no
 * change.
 */
private suspend fun remapPaneLocations(persister: Persister, moves: List<PathMove>) {
    val parsed: dynamic = readJson(persister, PANE_LOCATIONS_KEY) ?: return
    var changed = false
    for (paneId in js("Object.keys(parsed)") as Array<String>) {
        val entry: dynamic = parsed[paneId]
        val file = entry?.file as? String ?: continue
        val moved = LunarborLink.remap(file, moves) ?: continue
        entry.file = moved
        changed = true
    }
    if (changed) persister.write(PANE_LOCATIONS_KEY, JSON.stringify(parsed))
}

/**
 * Re-keys the fold memory's folders (`AppShell.loadFoldMemory`'s shape,
 * `{ vault, folders }`) when it was stored for [vault].
 */
private suspend fun remapFoldMemory(persister: Persister, vault: String, moves: List<PathMove>) {
    val parsed: dynamic = readJson(persister, FOLD_MEMORY_KEY) ?: return
    val folders: dynamic = parsed.folders
    if (parsed.vault != vault || !(js("Array.isArray(folders)") as Boolean)) return
    val next = (folders as Array<Any?>).filterIsInstance<String>()
        .map { LunarborLink.remap(it, moves) ?: it }
    parsed.folders = next.distinct().sorted().toTypedArray()
    persister.write(FOLD_MEMORY_KEY, JSON.stringify(parsed))
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
