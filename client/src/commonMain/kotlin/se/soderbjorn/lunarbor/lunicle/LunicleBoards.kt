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
 *  - **Writes** (LBR-29): [renameIssue] and [createIssue] are optimistic —
 *    the new title, or the new issue's row, shows at once
 *    ([LunicleBoardState.titleEdits] / [LunicleBoardState.creating]) while
 *    the indicator says "Saving to Lunicle…"; a success says "Synced just
 *    now" and re-reads the board, a failure takes the change back and shows
 *    the error in red ([LunicleBoardState.alert]). Every write carries the
 *    main process's `X-Lunicle-Origin`, so the stream marks its echo `self`;
 *    and the issues this app changed are left out of the next read's
 *    remote-change notice, so an own edit never reads as someone else's.
 *  - **Properties** (LBR-30): [setProperty] moves an issue
 *    (`POST /issues/{id}/move`) or changes its priority or assignee
 *    (`PATCH /issues/{id}`) the same optimistic way
 *    ([LunicleBoardState.propertyEdits]).
 *  - **Drags** ([dragIssue]): a dragged issue is moved to its new column
 *    (`POST /issues/{id}/move`) and then put in its place within the
 *    column, in its new priority (`PUT /issues/{id}/order`, `reorder_issue`)
 *    — optimistic too ([LunicleBoardState.orderEdits]). A Lunicle without
 *    the order route answers 404: the priority is then set with `PATCH`
 *    and the indicator says reordering needs a newer Lunicle.
 *  - **Description and comments** (LBR-31): [setDescription]
 *    (`PATCH /issues/{id}` `{description}`) and [addComment]
 *    (`POST /issues/{id}/comments` `{body}`) are optimistic too
 *    ([LunicleBoardState.descriptionEdits] / [LunicleBoardState.postingComments]);
 *    a failed comment hands its text back to the pane that wrote it. The
 *    ids of comments this app posted are kept
 *    ([LunicleBoardState.ownComments]), so panes never highlight them as
 *    arrivals.
 *  - **Deleting** ([deleteIssue], `DELETE /issues/{id}`): not optimistic —
 *    the issue stays, with "Saving to Lunicle…", until Lunicle answers; it
 *    then leaves the board, or the error shows in red. Permanent.
 *  - **Read-only**: a board's first good read asks whether the token is
 *    read-only ([LunicleService.isReadOnly]); a write answered 403
 *    `insufficient_scope` says so too. [explain] shows why something
 *    cannot be edited, once per board and reason.
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

    /** Last local id handed out ([newLocalId]); counts down from 0. */
    private var localSeq = 0L

    /** Board → issue ids this app changed that no read has seen since (left out of notices). */
    private val ownChanges = HashMap<LunicleBoardKey, MutableSet<Long>>()

    /** Board → issue ids a write of this app is changing right now. */
    private val writing = HashMap<LunicleBoardKey, MutableSet<Long>>()

    /** `<board id>|<reason>` pairs [explain] has shown. */
    private val explained = HashSet<String>()

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

    /** The cache's clock (epoch ms): panes stamp comment arrivals with it (LBR-31). */
    fun clock(): Long = now()

    /**
     * A new local id for a draft (LBR-29): negative, never reused, unique
     * app-wide — so a draft anchored after another pane's new issue, or a
     * created draft's row, can be named before Lunicle gives it an id.
     * Called by `PaneBackingViewModel` when it starts a draft.
     */
    fun newLocalId(): Long = --localSeq

    /**
     * Sets issue [issueId]'s title on board [key] to [title]
     * (`PATCH /issues/{id}` `{title}`), optimistically: the board shows
     * [title] at once and "Saving to Lunicle…"; on success "Synced just
     * now" and a re-read, on failure the old title and the error in red.
     * A board not resolved yet ignores it.
     *
     * Called by `PaneBackingViewModel` when the caret leaves an edited
     * title, or on Enter.
     */
    fun renameIssue(key: LunicleBoardKey, issueId: Long, title: String) {
        val target = stateOf(key).target ?: return
        writing.getOrPut(key) { HashSet() } += issueId
        put(key) { it.copy(titleEdits = it.titleEdits + (issueId to title), writes = it.writes + 1) }
        scope.launch {
            val r = service.client(target.connection.id).updateIssue(issueId, LunicleIssueChanges(title = LunicleField.Set(title)))
            writing[key]?.remove(issueId)
            fun withoutEdit(s: LunicleBoardState) =
                if (s.titleEdits[issueId] == title) s.titleEdits - issueId else s.titleEdits
            when (r) {
                is LunicleResult.Ok -> {
                    ownChanges.getOrPut(key) { HashSet() } += issueId
                    val t = now()
                    put(key) { s ->
                        s.copy(
                            board = s.board?.let { b -> b.copy(issues = b.issues.map { if (it.id == issueId) it.copy(title = title) else it }) },
                            details = s.details[issueId]?.let { d -> s.details + (issueId to d.copy(summary = d.summary.copy(title = title))) } ?: s.details,
                            titleEdits = withoutEdit(s),
                            writes = (s.writes - 1).coerceAtLeast(0),
                            savedAt = t,
                        )
                    }
                    afterSave(key, t)
                }
                is LunicleResult.Failure -> failed(key, target, r.error) { s -> s.copy(titleEdits = withoutEdit(s)) }
            }
        }
    }

    /**
     * Deletes issue [issueId] of board [key] for good (`DELETE
     * /issues/{id}`, its comments with it). Not optimistic: the indicator
     * says "Saving to Lunicle…" until the answer; on success the issue
     * leaves the board at once ("Synced just now", then a re-read), on
     * failure (no right to delete, a read-only token) it stays and the
     * error shows in red. A board not resolved yet ignores it.
     *
     * Called by `PaneBackingViewModel.deleteLunicleIssue`, after the
     * palette's "Delete Lunicle issue…" was confirmed.
     */
    fun deleteIssue(key: LunicleBoardKey, issueId: Long) {
        val target = stateOf(key).target ?: return
        writing.getOrPut(key) { HashSet() } += issueId
        put(key) { it.copy(writes = it.writes + 1) }
        scope.launch {
            val r = service.client(target.connection.id).deleteIssue(issueId)
            writing[key]?.remove(issueId)
            when (r) {
                is LunicleResult.Ok -> {
                    ownChanges.getOrPut(key) { HashSet() } += issueId
                    val t = now()
                    put(key) { s ->
                        s.copy(
                            board = s.board?.let { b -> b.copy(issues = b.issues.filter { it.id != issueId }) },
                            details = s.details - issueId,
                            writes = (s.writes - 1).coerceAtLeast(0),
                            savedAt = t,
                        )
                    }
                    afterSave(key, t)
                }
                is LunicleResult.Failure -> failed(key, target, r.error) { it }
            }
        }
    }

    /**
     * Files a new issue on board [key] from a draft (`POST
     * /projects/{id}/issues` with `title`, `status`, `priority`),
     * optimistically: its row ([LunicleCreatingIssue]) shows at once at
     * [anchor] with "Saving to Lunicle…", gets its key from the answer and
     * gives way to the real issue once a re-read lists it. On failure the
     * row goes and the error shows in red.
     *
     * Called by `PaneBackingViewModel` when the caret leaves a draft with a
     * title, on Enter in one, and for the column's "New issue" line.
     *
     * @param localId The draft's [newLocalId].
     * @param priority `null` for the project's default.
     */
    fun createIssue(key: LunicleBoardKey, localId: Long, status: String, priority: String?, title: String, anchor: LunicleDraftAnchor) {
        val target = stateOf(key).target ?: return
        val entry = LunicleCreatingIssue(localId, status, priority, title, anchor)
        put(key) { it.copy(creating = it.creating + entry, writes = it.writes + 1) }
        scope.launch {
            val r = service.client(target.connection.id)
                .createIssue(target.project.id, LunicleNewIssue(title = title, status = status, priority = priority))
            when (r) {
                is LunicleResult.Ok -> {
                    val created = r.value
                    ownChanges.getOrPut(key) { HashSet() } += created.id
                    val t = now()
                    put(key) { s ->
                        val onBoard = s.board?.issues?.any { it.id == created.id } == true
                        s.copy(
                            creating = if (onBoard) s.creating.filter { it.localId != localId }
                            else s.creating.map { if (it.localId == localId) it.copy(createdId = created.id, createdKey = created.key) else it },
                            createdIds = s.createdIds + (localId to created.id),
                            writes = (s.writes - 1).coerceAtLeast(0),
                            savedAt = t,
                        )
                    }
                    afterSave(key, t)
                }
                is LunicleResult.Failure -> failed(key, target, r.error) { s -> s.copy(creating = s.creating.filter { it.localId != localId }) }
            }
        }
    }

    /** Last [LunicleIssueEdit.seq] handed out. */
    private var editSeq = 0L

    /**
     * Changes issue [issueId]'s status, priority or assignee on board [key]
     * (LBR-30), optimistically: the board shows [value] at once
     * ([LunicleBoardState.propertyEdits]) with "Saving to Lunicle…" — so a
     * moved issue is already under its new column — then "Synced just now"
     * and a re-read; on failure the old value comes back and the error
     * (Lunicle's own sentence, e.g. an ambiguous assignee name) shows in red.
     *
     *  - [LuniclePill.Field.STATUS]: `POST /issues/{id}/move` `{status}`,
     *    plus [resolution] for a column that requires one.
     *  - [LuniclePill.Field.PRIORITY]: `PATCH /issues/{id}` `{priority}`.
     *  - [LuniclePill.Field.ASSIGNEE]: `PATCH /issues/{id}` `{assignee}` by
     *    display name; [value] `null` sends `assignee: null` (nobody).
     *
     * A board not resolved yet ignores it. Called by
     * `PaneBackingViewModel.pickLunicleOption` / `chooseLunicleResolution`.
     */
    fun setProperty(key: LunicleBoardKey, issueId: Long, field: LuniclePill.Field, value: String?, resolution: String? = null) {
        val target = stateOf(key).target ?: return
        if (field != LuniclePill.Field.ASSIGNEE && value == null) return
        val edit = LunicleIssueEdit(++editSeq, issueId, field, value, resolution)
        writing.getOrPut(key) { HashSet() } += issueId
        put(key) { it.copy(propertyEdits = it.propertyEdits + edit, writes = it.writes + 1) }
        scope.launch {
            val client = service.client(target.connection.id)
            val r = when (field) {
                LuniclePill.Field.STATUS -> client.moveIssue(issueId, value!!, resolution)
                LuniclePill.Field.PRIORITY -> client.updateIssue(issueId, LunicleIssueChanges(priority = LunicleField.Set(value!!)))
                LuniclePill.Field.ASSIGNEE -> client.updateIssue(issueId, LunicleIssueChanges(assignee = LunicleField.Set(value)))
            }
            writing[key]?.remove(issueId)
            fun withoutEdit(s: LunicleBoardState) = s.propertyEdits.filter { it.seq != edit.seq }
            when (r) {
                is LunicleResult.Ok -> {
                    ownChanges.getOrPut(key) { HashSet() } += issueId
                    val t = now()
                    put(key) { s ->
                        s.copy(
                            board = s.board?.let { b -> b.copy(issues = b.issues.map { if (it.id == issueId) edit.applyTo(it) else it }) },
                            details = s.details[issueId]?.let { d -> s.details + (issueId to d.copy(summary = edit.applyTo(d.summary))) } ?: s.details,
                            propertyEdits = withoutEdit(s),
                            writes = (s.writes - 1).coerceAtLeast(0),
                            savedAt = t,
                        )
                    }
                    afterSave(key, t)
                }
                is LunicleResult.Failure -> failed(key, target, r.error) { s -> s.copy(propertyEdits = withoutEdit(s)) }
            }
        }
    }

    /**
     * Drops issue [issueId] of board [key] at [edit]'s place (its column,
     * priority and neighbour), optimistically: the board shows it there at
     * once ([LunicleBoardState.orderEdits]) with "Saving to Lunicle…", then
     * "Synced just now" and a re-read; on failure it goes back (a re-read
     * follows, since the move may have landed before the reorder failed)
     * and the error shows in red.
     *
     * Writes, in order:
     *  - a new column: `POST /issues/{id}/move` `{status}` (plus
     *    [LunicleOrderEdit.resolution] for a closing column — whose issues are
     *    grouped by resolution in Lunicle, so nothing more is sent);
     *  - otherwise `PUT /issues/{id}/order` with the neighbour and, when it
     *    changed, the priority. A Lunicle without that route (404) gets the
     *    priority by `PATCH` instead, and [REORDER_UNSUPPORTED_TEXT] shows.
     *
     * A board not resolved yet ignores it. Called by
     * `PaneBackingViewModel.dropLunicleIssue` / `chooseLunicleDropResolution`.
     *
     * @param from The issue as the board showed it before the drop (its
     *   status and priority decide which writes are needed).
     */
    fun dragIssue(key: LunicleBoardKey, from: LunicleBoardIssue, edit: LunicleOrderEdit) {
        val target = stateOf(key).target ?: return
        val issueId = from.id
        val entry = edit.copy(seq = ++editSeq, issueId = issueId)
        writing.getOrPut(key) { HashSet() } += issueId
        put(key) { it.copy(orderEdits = it.orderEdits + entry, writes = it.writes + 1) }
        scope.launch {
            val client = service.client(target.connection.id)
            var unsupported = false
            var r: LunicleResult<String> = LunicleResult.Ok("")
            if (entry.status != from.status) r = client.moveIssue(issueId, entry.status, entry.resolution)
            if (r is LunicleResult.Ok && entry.resolution == null) {
                val priority = entry.priority.takeIf { it != from.priority }
                r = client.reorderIssue(issueId, entry.beforeId, entry.afterId, priority)
                if ((r as? LunicleResult.Failure)?.error.let { it is LunicleError.Http && it.isNotFound }) {
                    // A Lunicle before `reorder_issue`: keep what can be kept.
                    unsupported = true
                    r = if (priority != null) client.updateIssue(issueId, LunicleIssueChanges(priority = LunicleField.Set(priority)))
                    else LunicleResult.Ok("")
                }
            }
            writing[key]?.remove(issueId)
            fun withoutEdit(s: LunicleBoardState) = s.orderEdits.filter { it.seq != entry.seq }
            when (val result = r) {
                is LunicleResult.Ok -> {
                    ownChanges.getOrPut(key) { HashSet() } += issueId
                    val t = now()
                    put(key) { s ->
                        s.copy(
                            board = s.board?.let { b -> b.copy(issues = entry.applyTo(b.issues)) },
                            orderEdits = withoutEdit(s),
                            writes = (s.writes - 1).coerceAtLeast(0),
                            savedAt = t,
                        )
                    }
                    if (unsupported) showAlert(key, REORDER_UNSUPPORTED_TEXT)
                    afterSave(key, t)
                }
                is LunicleResult.Failure -> {
                    failed(key, target, result.error) { s -> s.copy(orderEdits = withoutEdit(s)) }
                    refresh(key)
                }
            }
        }
    }

    /**
     * Sets issue [issueId]'s description on board [key] to [text]
     * (`PATCH /issues/{id}` `{description}`, LBR-31), optimistically: the
     * board shows [text] at once and "Saving to Lunicle…"; on success
     * "Synced just now" and a re-read, on failure the old description and
     * the error in red. A board not resolved yet ignores it.
     *
     * Called by `PaneBackingViewModel.leaveLunicleRow` when the caret
     * leaves an edited description.
     */
    fun setDescription(key: LunicleBoardKey, issueId: Long, text: String) {
        val target = stateOf(key).target ?: return
        writing.getOrPut(key) { HashSet() } += issueId
        put(key) { it.copy(descriptionEdits = it.descriptionEdits + (issueId to text), writes = it.writes + 1) }
        scope.launch {
            val r = service.client(target.connection.id).updateIssue(issueId, LunicleIssueChanges(description = LunicleField.Set(text)))
            writing[key]?.remove(issueId)
            fun withoutEdit(s: LunicleBoardState) =
                if (s.descriptionEdits[issueId] == text) s.descriptionEdits - issueId else s.descriptionEdits
            when (r) {
                is LunicleResult.Ok -> {
                    ownChanges.getOrPut(key) { HashSet() } += issueId
                    val t = now()
                    put(key) { s ->
                        s.copy(
                            details = s.details[issueId]?.let { d -> s.details + (issueId to d.copy(description = text)) } ?: s.details,
                            descriptionEdits = withoutEdit(s),
                            writes = (s.writes - 1).coerceAtLeast(0),
                            savedAt = t,
                        )
                    }
                    afterSave(key, t)
                }
                is LunicleResult.Failure -> failed(key, target, r.error) { s -> s.copy(descriptionEdits = withoutEdit(s)) }
            }
        }
    }

    /**
     * Posts [body] as a comment on issue [issueId] of board [key]
     * (`POST /issues/{id}/comments` `{body}`, LBR-31), optimistically: the
     * comment shows at once after the issue's comments, as the token's
     * owner, with "Saving to Lunicle…"; on success its id is remembered as
     * this app's own and the issue is read again, which replaces the
     * stand-in; on failure it goes, the error shows in red and [onFailed]
     * gets [body] back (the pane puts it into the "Comment…" input again).
     * A board not resolved yet calls [onFailed] at once.
     *
     * Called by `PaneBackingViewModel.lunicleEnter` on the "Comment…" row.
     */
    fun addComment(key: LunicleBoardKey, issueId: Long, body: String, onFailed: (String) -> Unit) {
        val target = stateOf(key).target
        if (target == null) {
            onFailed(body)
            return
        }
        val entry = LunicleCreatingComment(newLocalId(), issueId, body, stateOf(key).userName ?: YOU, now())
        writing.getOrPut(key) { HashSet() } += issueId
        put(key) { it.copy(postingComments = it.postingComments + entry, writes = it.writes + 1) }
        scope.launch {
            val r = service.client(target.connection.id).addComment(issueId, body)
            writing[key]?.remove(issueId)
            when (r) {
                is LunicleResult.Ok -> {
                    val id = r.value.id
                    ownChanges.getOrPut(key) { HashSet() } += issueId
                    val t = now()
                    put(key) { s ->
                        s.copy(
                            postingComments = s.postingComments.map { if (it.localId == entry.localId) it.copy(createdId = id) else it },
                            ownComments = s.ownComments + id,
                            writes = (s.writes - 1).coerceAtLeast(0),
                            savedAt = t,
                        )
                    }
                    fetchDetails(key, setOf(issueId))
                    afterSave(key, t)
                }
                is LunicleResult.Failure -> {
                    failed(key, target, r.error) { s -> s.copy(postingComments = s.postingComments.filter { it.localId != entry.localId }) }
                    onFailed(body)
                }
            }
        }
    }

    /**
     * Shows [text] in red on board [key]'s indicator, once per board and
     * [reason] this session: why a title cannot be edited or no issue can
     * be filed (a read-only token, `canEdit: false`, a viewer). Called by
     * `PaneBackingViewModel` when a key would edit what cannot be edited.
     */
    fun explain(key: LunicleBoardKey, reason: String, text: String) {
        if (!explained.add("${key.id}|$reason")) return
        showAlert(key, text)
    }

    /** "Synced just now" goes back to the usual line after [LunicleBoardLayout.SAVED_MS]; the board is re-read. */
    private fun afterSave(key: LunicleBoardKey, at: Long) {
        scope.launch {
            delay(LunicleBoardLayout.SAVED_MS)
            put(key) { s -> if (s.savedAt == at) s.copy(savedAt = null) else s }
        }
        refresh(key)
    }

    /**
     * A write of board [key] failed: [revert] takes the optimistic change
     * back, the error shows in red, and a 403 `insufficient_scope` marks
     * every board of the connection read-only.
     */
    private suspend fun failed(key: LunicleBoardKey, target: LunicleBoardTarget, error: LunicleError, revert: (LunicleBoardState) -> LunicleBoardState) {
        put(key) { s -> revert(s).copy(writes = (s.writes - 1).coerceAtLeast(0)) }
        if (error is LunicleError.Http && error.isReadOnlyToken) {
            service.markReadOnly(target.connection.id)
            _boards.update { all ->
                all.mapValues { (_, s) -> if (s.target?.connection?.id == target.connection.id) s.copy(readOnlyToken = true) else s }
            }
        }
        showAlert(key, LunicleBoardLayout.writeErrorText(error))
    }

    /** Puts [text] on board [key]'s indicator, in red, for [ALERT_MS]. */
    private fun showAlert(key: LunicleBoardKey, text: String) {
        val until = now() + ALERT_MS
        put(key) { it.copy(alert = text, alertUntil = until) }
        scope.launch {
            delay(ALERT_MS)
            put(key) { s -> if (s.alert != null && now() >= s.alertUntil) s.copy(alert = null) else s }
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
        val readOnly = service.isReadOnly(target.connection.id)
        val userName = service.userName(target.connection.id)
        // Issues this app changed are not news: left out of the notice.
        val own = ownChanges.remove(key).orEmpty() + writing[key].orEmpty()
        val before = stateOf(key)
        val changes = LunicleBoardDiff.diff(before.board, board)
        val news = LunicleChanges(changes.changed - own, changes.added - own, changes.removed - own)
        val hinted = hints.mapNotNullTo(HashSet()) { it.issueId }
        val onBoard = board.issues.mapTo(HashSet()) { it.id }
        val unfolded = unfoldedOf(key)
        val refetch = LunicleBoardDiff.toRefetch(changes, hinted, unfolded, before.details.keys, onBoard)
        // Details of issues that changed but are not re-read are stale: drop them.
        val details = LinkedHashMap(before.details - changes.removed - ((changes.changed + hinted) - refetch))
        for (id in refetch) client.issue(id).valueOrNull()?.let { details[id] = it }
        val message = before.board?.let { old ->
            if (news.isEmpty && hinted.isEmpty()) null
            else LunicleBoardDiff.remoteMessage(news, old, board, hints, before.details, details)
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
                // A created issue the board now lists needs its stand-in row no more.
                creating = it.creating.filter { c -> c.createdId == null || c.createdId !in onBoard },
                readOnlyToken = it.readOnlyToken || readOnly,
                userName = userName ?: it.userName,
                postingComments = withoutListed(it.postingComments, details),
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
            put(key) {
                val details = it.details + got
                it.copy(details = details, loadingIssues = it.loadingIssues - ids, postingComments = withoutListed(it.postingComments, details))
            }
        }
    }

    /** [posting] without the comments Lunicle filed that [details] now list (LBR-31). */
    private fun withoutListed(posting: List<LunicleCreatingComment>, details: Map<Long, LunicleIssue>): List<LunicleCreatingComment> =
        posting.filter { c -> c.createdId == null || details[c.issueId]?.comments?.none { it.id == c.createdId } ?: true }

    companion object {
        /** Pause between polls of a shown board without a live stream. */
        const val POLL_MS: Long = 15_000

        /** How long a burst of stream events gathers before the board is re-read once. */
        const val COALESCE_MS: Long = 250

        /** How long the remote-change notice shows on the node line. */
        const val NOTICE_MS: Long = 6_000

        /** The author shown on a comment being posted when the token owner's name is not known (LBR-31). */
        const val YOU: String = "You"

        /** Shown when a drag's place could not be kept: the Lunicle has no `reorder_issue` route yet. */
        const val REORDER_UNSUPPORTED_TEXT: String = "Moved, but this Lunicle can't reorder issues yet; update it to keep the order."

        /** How long a write's error, or why a board cannot be edited, shows in red (LBR-29). */
        const val ALERT_MS: Long = 8_000
    }
}
