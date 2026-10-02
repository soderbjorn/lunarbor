/* PaneLocationStore.kt (jsMain)
 *
 * Remembers where every pane (window) is — its file and zoom
 * ([PaneBackingViewModel.FileHistoryEntry]), plus the text of its open
 * search, if any — across restarts and vault changes. The toolkit's `LAYOUT` / `LAYOUT_STATE` keys only carry pane
 * identity and geometry, so without this every pane reopened at the root.
 *
 * Stored through the app's [Persister] under [PERSIST_KEY] as
 * `{ "<paneId>": { "file": "<vault-relative file>", "zoom": ["title", …], "search": "…", "searchReversed": true } }`
 * (`search` only while the pane's search field is open, `searchReversed`
 * only while its results are reversed), plus `"scroll": <px>`, the
 * current page's scroll offset, and `"caret": {"row", "col", "text"}`, its
 * caret (`PaneBackingViewModel.Caret`)
 * (in Electron that lands in the per-app `lunarbor.json`). Paths are
 * vault-relative, so a vault change rebases them first
 * (`AppShell.switchVault`, [VaultRelocation]).
 *
 * Platform glue only: [AppShell] decides when a location changes and when a
 * pane closes; this class keeps the map and writes it (debounced). */
package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunarbor.main.PaneBackingViewModel.FileHistoryEntry

/**
 * Persisted pane → location map.
 *
 * ### Callers
 * Owned by [AppShell]: [load] at boot (before the first pane renders),
 * [get] when a pane's view model is created, [record] on every navigation,
 * [forget] when a pane closes, and [replaceAll] when the vault changes;
 * [search] / [recordSearch] for the pane's open search.
 *
 * @param persister Where the map is stored (Electron IPC or localStorage).
 * @param scope Scope for the debounced writes.
 * @param livePaneIds Ids of every pane that currently exists; entries for
 *   other ids are dropped on each write so closed tabs don't linger.
 */
class PaneLocationStore(
    private val persister: Persister,
    private val scope: CoroutineScope,
    private val livePaneIds: () -> Set<String>,
) {
    private val locations: MutableMap<String, FileHistoryEntry> = mutableMapOf()

    /** Pane id → text of its open search field (`""` when open and empty). */
    private val searches: MutableMap<String, String> = mutableMapOf()

    /** Panes whose open search lists its results in reverse order. */
    private val reversedSearches: MutableSet<String> = mutableSetOf()

    /** Pane id → scroll offset of its current page. */
    private val scrolls: MutableMap<String, Double> = mutableMapOf()

    /** Pane id → caret on its current page. */
    private val carets: MutableMap<String, PaneBackingViewModel.Caret> = mutableMapOf()
    private var pendingSave: Job? = null

    /**
     * Reads the stored map, keeping only entries whose file still exists
     * (a file deleted in Finder meanwhile must not be recreated by opening
     * it). Called once at boot, before any pane view model is created.
     *
     * @param exists Whether a vault-relative file exists
     *   (`DocumentRegistry.fileExists`).
     */
    suspend fun load(exists: suspend (String) -> Boolean) {
        val raw = persister.read(PERSIST_KEY) ?: return
        val parsed: dynamic = try {
            JSON.parse<dynamic>(raw)
        } catch (_: Throwable) {
            return
        }
        if (parsed == null || jsTypeOf(parsed) != "object") return
        val keys = js("Object.keys(parsed)") as Array<String>
        for (paneId in keys) {
            val entry: dynamic = parsed[paneId]
            // A location remembered before `_node.md` names the old outline file.
            val file = NoteRepository.currentPathOf(entry?.file as? String ?: continue)
            val zoomRaw: dynamic = entry.zoom
            val zoom = if (js("Array.isArray(zoomRaw)") as Boolean) {
                (zoomRaw as Array<Any?>).filterIsInstance<String>()
            } else {
                emptyList()
            }
            if (exists(file)) {
                locations[paneId] = FileHistoryEntry(file, zoom)
                (entry.search as? String)?.let { searches[paneId] = it }
                if (entry.searchReversed == true) reversedSearches += paneId
                (entry.scroll as? Number)?.let { scrolls[paneId] = it.toDouble() }
                val caret: dynamic = entry.caret
                val row = caret?.row as? Number
                val col = caret?.col as? Number
                val text = caret?.text as? String
                if (row != null && col != null && text != null) {
                    carets[paneId] = PaneBackingViewModel.Caret(row.toInt(), col.toInt(), text)
                }
            }
        }
    }

    /** Stored location of [paneId], or `null` when it has none. */
    operator fun get(paneId: String): FileHistoryEntry? = locations[paneId]

    /**
     * Notes that [paneId] is now at [location]; written after a short
     * debounce so a burst of navigation is one write.
     */
    fun record(paneId: String, location: FileHistoryEntry) {
        if (locations[paneId] == location) return
        locations[paneId] = location
        scheduleSave()
    }

    /** Stored search text of [paneId], or `null` when its search was closed. */
    fun search(paneId: String): String? = searches[paneId]

    /** Stored scroll offset of [paneId]'s page, or `null`. */
    fun scroll(paneId: String): Double? = scrolls[paneId]

    /** Stored caret of [paneId]'s page, or `null`. */
    fun caret(paneId: String): PaneBackingViewModel.Caret? = carets[paneId]

    /** Notes [paneId]'s caret; written after the usual debounce. */
    fun recordCaret(paneId: String, caret: PaneBackingViewModel.Caret) {
        if (carets[paneId] == caret) return
        carets[paneId] = caret
        scheduleSave()
    }

    /** Notes [paneId]'s page scroll offset; written after the usual debounce. */
    fun recordScroll(paneId: String, top: Double) {
        if (scrolls[paneId] == top) return
        scrolls[paneId] = top
        scheduleSave()
    }

    /** Whether [paneId]'s stored search lists its results in reverse order. */
    fun isSearchReversed(paneId: String): Boolean = paneId in reversedSearches

    /**
     * Notes [paneId]'s search text — `null` once the search closes;
     * written after the same debounce as [record].
     */
    fun recordSearch(paneId: String, query: String?, reversed: Boolean = false) {
        val isReversed = query != null && reversed
        if (searches[paneId] == query && (paneId in reversedSearches) == isReversed) return
        if (query == null) searches.remove(paneId) else searches[paneId] = query
        if (isReversed) reversedSearches += paneId else reversedSearches -= paneId
        scheduleSave()
    }

    /** Drops [paneId]'s entry (the pane closed). */
    fun forget(paneId: String) {
        reversedSearches -= paneId
        scrolls -= paneId
        carets -= paneId
        val hadSearch = searches.remove(paneId) != null
        if (locations.remove(paneId) != null || hadSearch) scheduleSave()
    }

    /**
     * Replaces the whole map and writes it at once, returning when the
     * write is done. Used by `AppShell.switchVault` right before the window
     * reopens against the new vault.
     */
    suspend fun replaceAll(next: Map<String, FileHistoryEntry>) {
        pendingSave?.cancel()
        locations.clear()
        locations.putAll(next)
        save()
    }

    private fun scheduleSave() {
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            save()
        }
    }

    private suspend fun save() {
        val live = livePaneIds()
        val out: dynamic = js("({})")
        for ((paneId, loc) in locations) {
            if (paneId !in live) continue
            val entry: dynamic = js("({})")
            entry.file = loc.fileRel
            entry.zoom = loc.zoomTitlePath.toTypedArray()
            searches[paneId]?.let { entry.search = it }
            if (paneId in reversedSearches) entry.searchReversed = true
            scrolls[paneId]?.let { entry.scroll = it }
            carets[paneId]?.let { c ->
                val caret: dynamic = js("({})")
                caret.row = c.row
                caret.col = c.col
                caret.text = c.lineText
                entry.caret = caret
            }
            out[paneId] = entry
        }
        persister.write(PERSIST_KEY, JSON.stringify(out))
    }

    companion object {
        /** Persister key of the pane → location map. */
        const val PERSIST_KEY: String = "lunarborPaneLocations"

        /** Debounce between a navigation and the write. */
        private const val SAVE_DEBOUNCE_MS: Long = 400
    }
}
