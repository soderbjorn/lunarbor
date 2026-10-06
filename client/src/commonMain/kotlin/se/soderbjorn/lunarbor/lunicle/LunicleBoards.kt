/*
 * LunicleBoards.kt (commonMain)
 * -----------------------------
 * The app-scoped board cache behind board nodes (LBR-27): one
 * [LunicleBoardState] per `{{lunicle: <connection>/<KEY>}}` a pane has
 * shown, in [boardsFlow]. Shaped like `DocumentRegistry.requestSearchNode`
 * / `searchNodeResultsFlow`: panes [request] a board while painting, this
 * class fetches it, and the flow carries the result to every pane.
 * Owned by `DocumentRegistry` (`DocumentRegistry.lunicleBoards`).
 *
 * Keeping boards current:
 *
 *  - **Interest.** Each pane reports which boards it shows unfolded and
 *    which of their issues it has unfolded ([setInterest]); a board no pane
 *    shows is not refreshed.
 *  - **Change stream first** (LNL-224): for every connection with a shown,
 *    resolved board, the main process holds one SSE stream covering those
 *    projects ([LunicleEventSource.watch]). Events are coalesced
 *    ([COALESCE_MS]) and each burst re-reads the board once; events this
 *    app's own writes caused (`self`) are ignored; `board.changed` re-reads
 *    the board, `reset` every board of that connection. While a stream is
 *    connected the indicator says "Live" and polling pauses for its boards.
 *  - **Polling as the fallback**: every [POLL_MS] while some pane shows a
 *    board whose connection has no live stream (a server without one, a
 *    stream reconnecting), plus [refreshShown] on window focus.
 *  - **Issue details**: `GET /issues/{id}` only for issues a pane has
 *    unfolded — on unfolding, and after a re-read for the unfolded ones
 *    that changed ([LunicleBoardDiff.toRefetch]).
 *  - **Remote-change notice**: a re-read that changed something shows
 *    [LunicleBoardDiff.remoteMessage] for [NOTICE_MS], then the indicator
 *    goes back to "Synced …" / "Live".
 *  - **Errors** keep the last good board ([LunicleBoardState.board]) under
 *    [LunicleBoardState.error].
 *
 * Board rows are never written anywhere: not into `Document.lines`, the
 * vault, the indexes, or anything agents see.
 *
 * Confined to [scope]'s dispatcher (the app's is single-threaded on the
 * web): the bookkeeping maps are not locked.
 *
 * commonMain only — no platform imports, no DI annotations.
 */

package se.soderbjorn.lunarbor.lunicle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Every board node's board, fetched and kept current (see the file header).
 *
 * ### Callers
 * - `PaneBackingViewModel.lunicleBoardOf` ([request]) for every board node
 *   it paints, and `PaneBackingViewModel.reportShownBoards` ([setInterest]
 *   / [clearInterest]) after every paint and when the pane closes.
 * - `AppShell`'s window-focus handler ([refreshShown]).
 * - The change stream's listener ([onStreamMessage], wired in `init`).
 *
 * @param service Connections, clients and `<connection>/<KEY>` resolution.
 * @param events The change stream, or `null` (polling only).
 * @param scope Where fetches, the poll loop and the burst timer run.
 * @param now The clock (epoch ms); tests pass a virtual one.
 * @param pollMillis Pause between polls ([POLL_MS]).
 * @param coalesceMillis How long a burst of events gathers ([COALESCE_MS]).
 * @param noticeMillis How long a remote-change notice shows ([NOTICE_MS]).
 */
class LunicleBoards(
    private val service: LunicleService,
    private val events: LunicleEventSource?,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val pollMillis: Long = POLL_MS,
    private val coalesceMillis: Long = COALESCE_MS,
    private val noticeMillis: Long = NOTICE_MS,
) {
    private val _boards = MutableStateFlow<Map<LunicleBoardKey, LunicleBoardState>>(emptyMap())

    /** Every board a pane has requested this session, by key. Panes mirror it. */
    val boardsFlow: StateFlow<Map<LunicleBoardKey, LunicleBoardState>> = _boards.asStateFlow()

    private val requested = LinkedHashSet<LunicleBoardKey>()

    /** Pane (any identity object) → boards it shows unfolded → issue ids it has unfolded. */
    private val interests = HashMap<Any, Map<LunicleBoardKey, Set<Long>>>()

    /** Connection id → its change stream's state (absent: never connected). */
    private val streams = HashMap<String, LunicleStreamMessage.StreamState>()

    /** Connection id → the projects its stream was last asked to cover. */
    private val watched = HashMap<String, Set<Long>>()

    private val refreshing = HashSet<LunicleBoardKey>()
    private val refreshAgain = HashMap<LunicleBoardKey, List<LunicleChangeHint>>()
    private val burst = LinkedHashMap<LunicleBoardKey, MutableList<LunicleChangeHint>>()
    private var burstJob: Job? = null
    private var poller: Job? = null

    init {
        events?.setListener(::onStreamMessage)
        // A connection added, renamed, re-pointed or removed: a board whose
        // connection changed, or that could not be resolved, resolves again
        // (its name may now mean another connection). Boards still on their
        // first read pick the new list up themselves.
        scope.launch {
            service.connectionsFlow.drop(1).collect { connections ->
                val stale = _boards.value.values.filter { s ->
                    val target = s.target
                    if (target == null) s.error != null else target.connection !in connections
                }.map { it.key }
                if (stale.isEmpty()) return@collect
                _boards.update { all -> all.mapValues { (k, s) -> if (k in stale) s.copy(target = null) else s } }
                for (key in stale) refresh(key)
            }
        }
    }

    /**
     * The state of board [key], or `null` before anything is known — then
     * its first read starts and lands in [boardsFlow]. Cheap; called on
     * every paint.
     */
    fun request(key: LunicleBoardKey): LunicleBoardState? {
        if (requested.add(key)) {
            _boards.update { it + (key to (it[key] ?: LunicleBoardState(key))) }
            refresh(key)
        }
        return _boards.value[key]
    }

    /**
     * What pane [owner] shows: the boards it has on screen unfolded, each
     * with the ids of the issues it has unfolded. Newly unfolded issues are
     * read in full; a board that comes into view after more than a poll
     * interval is re-read at once; the streams and the poll loop follow.
     *
     * @param owner Any object identifying the pane (stable for its life).
     */
    fun setInterest(owner: Any, interest: Map<LunicleBoardKey, Set<Long>>) {
        val before = interests[owner].orEmpty()
        if (before == interest) return
        val shownBefore = shownKeys()
        if (interest.isEmpty()) interests.remove(owner) else interests[owner] = interest
        val t = now()
        for ((key, issues) in interest) {
            request(key)
            val state = _boards.value[key] ?: continue
            if (key !in shownBefore) {
                val at = state.syncedAt
                if (at != null && t - at >= pollMillis) refresh(key)
            }
            val missing = issues - state.details.keys - state.loadingIssues - before[key].orEmpty()
            if (missing.isNotEmpty() && state.target != null) fetchDetails(key, missing)
        }
        updateWatches()
        ensurePoller()
    }

    /** Forgets pane [owner]'s interest (it closed). */
    fun clearInterest(owner: Any) = setInterest(owner, emptyMap())

    /** Re-reads every board some pane shows. Called on window focus. */
    fun refreshShown() {
        for (key in shownKeys()) refresh(key)
    }

    /**
     * One message from a change stream: a status change flips the boards'
     * "Live" flag (and catches up once on connect); an event joins the
     * current burst, re-read [coalesceMillis] after the burst's first event.
     * Events with `self` and `notification.changed` are ignored.
     */
    fun onStreamMessage(message: LunicleStreamMessage) {
        when (message) {
            is LunicleStreamMessage.Status -> {
                streams[message.connectionId] = message.state
                val live = message.state == LunicleStreamMessage.StreamState.CONNECTED
                _boards.update { all ->
                    all.mapValues { (_, s) -> if (s.target?.connection?.id == message.connectionId) s.copy(live = live) else s }
                }
                // Changes between the last read and the stream's start are not replayed.
                if (live) for (key in shownKeys()) if (connectionOf(key) == message.connectionId) refresh(key)
                ensurePoller()
            }
            is LunicleStreamMessage.Event -> {
                if (message.self || message.kind == "notification.changed") return
                val keys = _boards.value.values.filter { s ->
                    val target = s.target ?: return@filter false
                    target.connection.id == message.connectionId &&
                        (message.kind == "reset" || message.projectId == target.project.id)
                }.map { it.key }
                if (keys.isEmpty()) return
                val hint = if (message.kind == "reset" || message.kind == "board.changed") null
                else LunicleChangeHint(message.kind, message.issueId, message.actor)
                for (key in keys) {
                    val list = burst.getOrPut(key) { ArrayList() }
                    if (hint != null) list += hint
                }
                scheduleBurst()
            }
        }
    }

    /** Boards some pane shows unfolded. */
    private fun shownKeys(): Set<LunicleBoardKey> = interests.values.flatMapTo(LinkedHashSet()) { it.keys }

    /** Issue ids some pane has unfolded on board [key]. */
    private fun unfoldedOf(key: LunicleBoardKey): Set<Long> = interests.values.flatMapTo(HashSet()) { it[key].orEmpty() }

    private fun connectionOf(key: LunicleBoardKey): String? = _boards.value[key]?.target?.connection?.id

    private fun isLive(key: LunicleBoardKey): Boolean =
        connectionOf(key)?.let { streams[it] == LunicleStreamMessage.StreamState.CONNECTED } == true

    private fun scheduleBurst() {
        if (burstJob?.isActive == true) return
        burstJob = scope.launch {
            delay(coalesceMillis)
            val taken = burst.toMap()
            burst.clear()
            for ((key, hints) in taken) refresh(key, hints)
        }
    }

    /** Polls the shown boards without a live stream every [pollMillis]; stops when nothing is shown. */
    private fun ensurePoller() {
        if (poller?.isActive == true || shownKeys().isEmpty()) return
        poller = scope.launch {
            while (true) {
                delay(pollMillis)
                val keys = shownKeys()
                if (keys.isEmpty()) break
                for (key in keys) if (!isLive(key)) refresh(key)
            }
        }
    }

    /** Asks each connection's stream to cover exactly the projects of its shown, resolved boards. */
    private fun updateWatches() {
        val source = events ?: return
        val wanted = HashMap<String, MutableSet<Long>>()
        for (key in shownKeys()) {
            val target = _boards.value[key]?.target ?: continue
            wanted.getOrPut(target.connection.id) { LinkedHashSet() } += target.project.id
        }
        for (connection in (wanted.keys + watched.keys).toSet()) {
            val projects = wanted[connection].orEmpty()
            if (watched[connection].orEmpty() == projects) continue
            if (projects.isEmpty()) watched.remove(connection) else watched[connection] = projects
            source.watch(connection, projects)
        }
    }

    /** Re-reads board [key] (one read at a time per board; a request meanwhile runs once more after). */
    private fun refresh(key: LunicleBoardKey, hints: List<LunicleChangeHint> = emptyList()) {
        if (key in refreshing) {
            refreshAgain[key] = refreshAgain[key].orEmpty() + hints
            return
        }
        refreshing += key
        scope.launch {
            try {
                read(key, hints)
            } finally {
                refreshing -= key
                refreshAgain.remove(key)?.let { refresh(key, it) }
            }
        }
    }

    private fun stateOf(key: LunicleBoardKey): LunicleBoardState = _boards.value[key] ?: LunicleBoardState(key)

    private fun put(key: LunicleBoardKey, transform: (LunicleBoardState) -> LunicleBoardState) {
        _boards.update { it + (key to transform(it[key] ?: LunicleBoardState(key))) }
    }

    /** One read of board [key]: resolve, `GET /board`, diff, re-fetch unfolded changed issues, notice. */
    private suspend fun read(key: LunicleBoardKey, hints: List<LunicleChangeHint>) {
        val target = stateOf(key).target ?: when (val r = service.resolveProject(key.connection, key.key)) {
            is LunicleResult.Ok -> r.value
            is LunicleResult.Failure -> {
                put(key) { it.copy(error = r.error) }
                return
            }
        }
        val client = service.client(target.connection.id)
        val board = when (val r = client.board(target.project.id)) {
            is LunicleResult.Ok -> r.value
            is LunicleResult.Failure -> {
                // A 404 may mean the project went away: resolve again next time.
                val lost = (r.error as? LunicleError.Http)?.isNotFound == true
                put(key) { it.copy(target = if (lost) null else target, error = r.error) }
                return
            }
        }
        val before = stateOf(key)
        val changes = LunicleBoardDiff.diff(before.board, board)
        val hinted = hints.mapNotNullTo(HashSet()) { it.issueId }
        val onBoard = board.issues.mapTo(HashSet()) { it.id }
        val unfolded = unfoldedOf(key)
        val refetch = LunicleBoardDiff.toRefetch(changes, hinted, unfolded, before.details.keys, onBoard)
        // Details of issues that changed but are not re-read are stale: drop them.
        val details = LinkedHashMap(before.details - changes.removed - ((changes.changed + hinted) - refetch))
        for (id in refetch) client.issue(id).valueOrNull()?.let { details[id] = it }
        val message = before.board?.let { old ->
            if (changes.isEmpty && hinted.isEmpty()) null
            else LunicleBoardDiff.remoteMessage(changes, old, board, hints, before.details, details)
        }
        val t = now()
        put(key) {
            it.copy(
                target = target,
                board = board,
                details = details,
                syncedAt = t,
                live = streams[target.connection.id] == LunicleStreamMessage.StreamState.CONNECTED,
                error = null,
                notice = message ?: it.notice,
                noticeUntil = if (message != null) t + noticeMillis else it.noticeUntil,
            )
        }
        if (message != null) {
            scope.launch {
                delay(noticeMillis)
                put(key) { s -> if (s.notice != null && now() >= s.noticeUntil) s.copy(notice = null) else s }
            }
        }
        updateWatches()
        ensurePoller()
    }

    /** Reads issues [ids] of board [key] in full (an unfold). */
    private fun fetchDetails(key: LunicleBoardKey, ids: Set<Long>) {
        val target = stateOf(key).target ?: return
        put(key) { it.copy(loadingIssues = it.loadingIssues + ids) }
        scope.launch {
            val client = service.client(target.connection.id)
            val got = HashMap<Long, LunicleIssue>()
            for (id in ids) client.issue(id).valueOrNull()?.let { got[id] = it }
            put(key) { it.copy(details = it.details + got, loadingIssues = it.loadingIssues - ids) }
        }
    }

    companion object {
        /** Pause between polls of a shown board without a live stream. */
        const val POLL_MS: Long = 15_000

        /** How long a burst of stream events gathers before the board is re-read once. */
        const val COALESCE_MS: Long = 250

        /** How long the remote-change notice shows on the node line. */
        const val NOTICE_MS: Long = 6_000
    }
}
