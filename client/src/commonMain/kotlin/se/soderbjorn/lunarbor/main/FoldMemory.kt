/*
 * FoldMemory.kt (commonMain)
 * --------------------------
 * Which outline items were last left open, so a page shows the same
 * folds when a pane comes back to it — after another file, or after a
 * restart. Every item that can fold has children, and an item with
 * children is folder-backed, so items are remembered by their vault
 * folder: stable across loads (unlike `LineId`s) and kept up to date
 * through folder moves ([applyMoves]).
 *
 * App-scoped, owned by [DocumentRegistry]. Panes record into it
 * ([PaneBackingViewModel] after every fold change and document change)
 * and read it in their default-collapse pass; fold state itself stays
 * pane state — this is only the starting point for an item a pane sees
 * for the first time. Persisting is platform glue: [onChanged] tells the
 * host to write [snapshot], and [load] seeds it at boot.
 *
 * commonMain only — no platform imports.
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.PathMove

/**
 * The set of vault folders whose items are open.
 *
 * ### Callers
 * - [PaneBackingViewModel]: [isExpanded] when an item is first seen,
 *   [setExpanded] whenever its open state may have changed.
 * - [DocumentRegistry.applyPathMoves]: [applyMoves].
 * - Platform glue (web `AppShell`): [load] at boot, [snapshot] on
 *   [onChanged].
 */
class FoldMemory {
    private val folders = HashSet<String>()

    /**
     * Called after every change of the set; the host persists
     * [snapshot] (debounced). `null` when nothing persists it (tests).
     */
    var onChanged: (() -> Unit)? = null

    /** `true` when the item backed by vault folder [folder] was left open. */
    fun isExpanded(folder: String): Boolean = folder in folders

    /**
     * Records the open state of the item backed by [folder]. Notifies
     * [onChanged] only when the set actually changes.
     */
    fun setExpanded(folder: String, expanded: Boolean) {
        val changed = if (expanded) folders.add(folder) else folders.remove(folder)
        if (changed) onChanged?.invoke()
    }

    /**
     * Follows folder moves and renames: a remembered folder at or under a
     * move's `from` is re-keyed under its `to`; one moved into the trash
     * is forgotten.
     */
    fun applyMoves(moves: List<PathMove>) {
        var changed = false
        for (move in moves) {
            val prefix = move.from + "/"
            val hits = folders.filter { it == move.from || it.startsWith(prefix) }
            if (hits.isEmpty()) continue
            folders.removeAll(hits.toSet())
            if (!move.touchesTrash) for (f in hits) folders += move.to + f.removePrefix(move.from)
            changed = true
        }
        if (changed) onChanged?.invoke()
    }

    /** The remembered folders, for persisting. */
    fun snapshot(): Set<String> = folders.toSet()

    /** Replaces the set with [stored] (boot); does not notify. */
    fun load(stored: Collection<String>) {
        folders.clear()
        folders += stored
    }
}
