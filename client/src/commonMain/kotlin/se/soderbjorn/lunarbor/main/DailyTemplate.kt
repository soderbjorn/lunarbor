/*
 * DailyTemplate.kt (commonMain)
 * -----------------------------
 * Which node is the daily template (LBR-21): the node whose items a new
 * journal day starts as a copy of (see `PaneBackingViewModel.navigateToToday`
 * and `DocumentRegistry.dailyTemplateRows`).
 *
 * The template is remembered by its vault folder, like [FoldMemory]
 * remembers open items: stable across loads and kept current through
 * folder moves and renames ([applyMoves]); a template moved into the trash
 * is forgotten. A remembered folder that no longer holds an outline (moved
 * or deleted outside the app) simply means there is no template — the
 * registry checks when it applies it.
 *
 * App-scoped, owned by [DocumentRegistry]. Persisting is platform glue
 * (web `AppShell`, persister key `lunarborDailyTemplate`, per vault): it
 * seeds the folder with [load] at boot and writes it back on [onChanged].
 * It is a setting, but not shown in the settings UI: the palette's
 * "Use as daily template" / "Stop using as daily template" change it.
 *
 * commonMain only — no platform imports.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.PathMove

/**
 * The daily template's vault folder, or none.
 *
 * ### Callers
 * - [PaneBackingViewModel.setDailyTemplate] / [PaneBackingViewModel.isDailyTemplatePage]
 *   (the palette commands and the page title's "Daily template" label),
 *   which mirror [folderFlow] into `State.dailyTemplateFolder`.
 * - [DocumentRegistry.dailyTemplateRows] reads [folder] when a new day is
 *   prepared; [DocumentRegistry.applyPathMoves] calls [applyMoves].
 * - Platform glue (web `AppShell`): [load] at boot, [folder] on [onChanged].
 */
class DailyTemplate {
    private val _folder = MutableStateFlow<String?>(null)

    /** The template's vault folder (never `""`, the root), or `null` for none. */
    val folderFlow: StateFlow<String?> = _folder.asStateFlow()

    /** Current value of [folderFlow]. */
    val folder: String? get() = _folder.value

    /**
     * Called after every change of [folder]; the host persists it. `null`
     * when nothing persists it (tests).
     */
    var onChanged: (() -> Unit)? = null

    /**
     * Makes the node at vault folder [folder] the template, replacing any
     * earlier one; `null` stops using a template. The root (`""`) and
     * folders in the trash are refused (treated as `null`). Notifies
     * [onChanged] only when the value changes.
     */
    fun set(folder: String?) {
        val next = folder?.takeIf { it.isNotEmpty() && !NoteRepository.isInTrash(it) }
        if (_folder.value == next) return
        _folder.value = next
        onChanged?.invoke()
    }

    /** Seeds the stored [folder] at boot; does not notify. */
    fun load(folder: String?) {
        _folder.value = folder?.takeIf { it.isNotEmpty() && !NoteRepository.isInTrash(it) }
    }

    /**
     * Follows folder moves and renames: a template at or under a move's
     * `from` is re-keyed under its `to`. One moved into the trash — the
     * whole folder, or only its outline (a deleted node whose folder stays
     * because it holds files) — is forgotten.
     */
    fun applyMoves(moves: List<PathMove>) {
        var current = _folder.value ?: return
        for (move in moves) {
            val outline = NoteRepository.outlineFileOf(current)
            if (move.from == current || current.startsWith(move.from + "/")) {
                if (move.touchesTrash) {
                    set(null)
                    return
                }
                current = move.to + current.removePrefix(move.from)
            } else if (move.from == outline && move.touchesTrash) {
                set(null)
                return
            }
        }
        set(current)
    }
}
