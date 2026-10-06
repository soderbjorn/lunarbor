/* LunicleBoardDetailsTest.kt (commonTest)
 *
 * Pins an unfolded board issue's description and comments (LBR-31): the
 * description's rows round-trip with its Markdown text; Enter, Backspace,
 * Delete, a multi-line paste and ↑ / ↓ between its lines
 * ([LunicleDescription.apply]); leaving an edited description sends it
 * (`PATCH /issues/{id}` `{description}`) optimistically, and a failure
 * takes it back; a remote change during an edit neither overwrites it nor
 * stops it from winning, and the indicator says so; a large description
 * shows a preview and expands when the caret goes past it; comments come
 * oldest first with relative times (the clock a parameter), arrivals since
 * the pane last saw the issue are marked and its own posts are not;
 * "Comment…" posts (`POST /issues/{id}/comments` `{body}`) optimistically,
 * and a failed post's text goes back into the row; a read-only token or
 * `canComment: false` leaves the description uneditable and hides
 * "Comment…". Through a real pane, board cache and fake Lunicle API. */
package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.lunicle.FakeLunicleApi
import se.soderbjorn.lunarbor.lunicle.LunicleBoardKey
import se.soderbjorn.lunarbor.lunicle.LunicleBoardLayout
import se.soderbjorn.lunarbor.lunicle.LunicleBoards
import se.soderbjorn.lunarbor.lunicle.LunicleConnection
import se.soderbjorn.lunarbor.lunicle.LunicleConnectionStore
import se.soderbjorn.lunarbor.lunicle.LunicleConnectionsSnapshot
import se.soderbjorn.lunarbor.lunicle.LunicleMethod
import se.soderbjorn.lunarbor.lunicle.LunicleService
import se.soderbjorn.lunarbor.lunicle.LunicleSyncKind
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LunicleBoardDetailsTest {

    // ------------------------------------------------------------ pure rules

    @Test
    fun description_rows_round_trip_with_the_markdown_text() {
        for (text in listOf("", "One line", "Two\nlines", "Ends with a newline\n", "\n\nBlank lines\n\nkept\n", "```\ncode\n```", "  - indented item")) {
            assertEquals(text, LunicleDescription.textOf(LunicleDescription.linesOf(text)))
        }
        assertEquals(listOf(""), LunicleDescription.linesOf(""))
        assertEquals(listOf("a", "", "b"), LunicleDescription.linesOf("a\n\nb"))
        // Other line ends read as \n (and are written back as \n).
        assertEquals(listOf("a", "b", "c"), LunicleDescription.linesOf("a\r\nb\rc"))
    }

    private fun caret(step: LunicleDescriptionStep) = (step as LunicleDescriptionStep.Caret).caret

    @Test
    fun enter_backspace_delete_and_paste_work_on_lines_as_inside_a_block() {
        val lines = listOf("First", "Second", "Third")
        // Enter splits the line at the caret (the selection goes).
        val (split, s1) = LunicleDescription.apply(lines, 1, LunicleDescriptionAction.ENTER, "Second", 3, 4)
        assertEquals(listOf("First", "Sec", "nd", "Third"), split)
        assertEquals(LunicleDescriptionCaret(2, 0, "nd"), caret(s1))
        // Backspace at a line's start joins it onto the line above.
        val (joined, s2) = LunicleDescription.apply(split, 2, LunicleDescriptionAction.BACKSPACE, "nd", 0, 0)
        assertEquals(listOf("First", "Secnd", "Third"), joined)
        assertEquals(LunicleDescriptionCaret(1, 3, "Secnd"), caret(s2))
        // …but not on the first line, nor elsewhere in the line.
        assertEquals(LunicleDescriptionStep.Ignore, LunicleDescription.apply(lines, 0, LunicleDescriptionAction.BACKSPACE, "First", 0, 0).second)
        assertEquals(LunicleDescriptionStep.Ignore, LunicleDescription.apply(lines, 1, LunicleDescriptionAction.BACKSPACE, "Second", 2, 2).second)
        // Delete at a line's end joins the line below onto it.
        val (deleted, s3) = LunicleDescription.apply(lines, 0, LunicleDescriptionAction.DELETE, "First", 5, 5)
        assertEquals(listOf("FirstSecond", "Third"), deleted)
        assertEquals(LunicleDescriptionCaret(0, 5, "FirstSecond"), caret(s3))
        assertEquals(LunicleDescriptionStep.Ignore, LunicleDescription.apply(lines, 2, LunicleDescriptionAction.DELETE, "Third", 5, 5).second)
        // A paste goes in verbatim, one row per line, the caret after it.
        val (pasted, s4) = LunicleDescription.apply(lines, 1, LunicleDescriptionAction.PASTE, "Second", 3, 3, "A\n\n  * b\nC")
        assertEquals(listOf("First", "SecA", "", "  * b", "Cond", "Third"), pasted)
        assertEquals(LunicleDescriptionCaret(4, 1, "Cond"), caret(s4))
        // The live text of the caret's line is kept in place on every key.
        val (typed, _) = LunicleDescription.apply(lines, 1, LunicleDescriptionAction.DOWN, "Second!", 2, 2)
        assertEquals(listOf("First", "Second!", "Third"), typed)
    }

    @Test
    fun enter_continues_and_ends_lists() {
        val (items, s1) = LunicleDescription.apply(listOf("  - milk"), 0, LunicleDescriptionAction.ENTER, "  - milk", 8, 8)
        assertEquals(listOf("  - milk", "  - "), items)
        assertEquals(LunicleDescriptionCaret(1, 4, "  - "), caret(s1))
        val (numbered, _) = LunicleDescription.apply(listOf("1. one"), 0, LunicleDescriptionAction.ENTER, "1. one", 6, 6)
        assertEquals(listOf("1. one", "2. "), numbered)
        // Enter on an empty item ends the list: the marker goes.
        val (ended, s2) = LunicleDescription.apply(items, 1, LunicleDescriptionAction.ENTER, "  - ", 4, 4)
        assertEquals(listOf("  - milk", ""), ended)
        assertEquals(LunicleDescriptionCaret(1, 0, ""), caret(s2))
    }

    @Test
    fun arrows_move_between_lines_and_leave_at_the_ends() {
        val lines = listOf("Long first line", "Short")
        assertEquals(LunicleDescriptionCaret(1, 5, "Short"), caret(LunicleDescription.apply(lines, 0, LunicleDescriptionAction.DOWN, lines[0], 9, 9).second))
        assertEquals(LunicleDescriptionCaret(0, 3, "Long first line"), caret(LunicleDescription.apply(lines, 1, LunicleDescriptionAction.UP, lines[1], 3, 3).second))
        assertEquals(LunicleDescriptionStep.LeaveUp, LunicleDescription.apply(lines, 0, LunicleDescriptionAction.UP, lines[0], 0, 0).second)
        assertEquals(LunicleDescriptionStep.LeaveDown, LunicleDescription.apply(lines, 1, LunicleDescriptionAction.DOWN, lines[1], 0, 0).second)
    }

    @Test
    fun comment_times_are_relative_to_the_clock_given() {
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        // 2026-10-06 is a Tuesday; noon UTC.
        val now = 1_791_244_800_000L + 12 * hour
        assertEquals("just now", LunicleComments.whenText(now - 30_000, now))
        assertEquals("5m", LunicleComments.whenText(now - 5 * minute, now))
        assertEquals("3h", LunicleComments.whenText(now - 3 * hour, now))
        assertEquals("1d", LunicleComments.whenText(now - day, now))
        assertEquals("2d", LunicleComments.whenText(now - 2 * day, now))
        assertEquals("Fri", LunicleComments.whenText(now - 4 * day, now))
        assertEquals("Wed", LunicleComments.whenText(now - 6 * day, now))
        assertEquals("29 Sep", LunicleComments.whenText(now - 7 * day, now))
        assertEquals("6 Oct 2025", LunicleComments.whenText(now - 365 * day, now))
        // Days count in the user's time zone: at 00:30 UTC, 23:00 UTC two
        // days before is "2d" in UTC but "1d" two hours west of it.
        val early = 1_791_244_800_000L + 30 * minute
        assertEquals("2d", LunicleComments.whenText(early - 25 * hour - 30 * minute, early))
        assertEquals("1d", LunicleComments.whenText(early - 25 * hour - 30 * minute, early, utcOffsetMinutes = -120))
        assertEquals("Linus · 5m", LunicleComments.metaText("Linus", null, now - 5 * minute, now))
        assertEquals("Ada (Claude) · just now", LunicleComments.metaText("Ada", "Claude", now, now))
    }

    @Test
    fun comment_rows_are_one_inline_row_oldest_first() {
        assertEquals("First line **bold** last", LunicleComments.inlineBody("First line\n\n  **bold**\r\nlast\n"))
        data class C(val id: Int, val at: Long)
        assertEquals(listOf(2, 1, 3), LunicleComments.ordered(listOf(C(1, 5), C(2, 1), C(3, 5))) { it.at }.map { it.id })
        // Arrivals: nothing on the first look, new ids after, never own ones.
        assertEquals(emptySet(), LunicleComments.arrivals(null, listOf(1, 2), emptySet()))
        assertEquals(setOf(3L), LunicleComments.arrivals(setOf(1, 2), listOf(1, 2, 3, 4), setOf(4)))
        assertEquals("Fixed it typed later", LunicleComments.mergeDraft("Fixed it", "typed later"))
        assertEquals("Fixed it", LunicleComments.mergeDraft("Fixed it", " "))
    }

    // ------------------------------------------------------------ through a pane

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private val store = object : LunicleConnectionStore {
        val work = LunicleConnection("c1", "work", "https://issues.lunicle.dev", hasToken = true)
        override suspend fun list() = LunicleConnectionsSnapshot(listOf(work))
        override suspend fun add(name: String?, baseUrl: String?, token: String?) = list()
        override suspend fun update(id: String, name: String?, baseUrl: String?, token: String?) = list()
        override suspend fun remove(id: String) = list()
    }

    private val boardPath = "/api/v1/projects/2/board"
    private val issuePath = "/api/v1/issues/1"
    private val commentsPath = "/api/v1/issues/1/comments"

    private fun boardJson(updatedAt: Int = 1, canEdit: Boolean = true) =
        """{"project":{"id":2,"name":"Framnafolk","keyPrefix":"FRA"},
           "statuses":[{"name":"New"},{"name":"Closed","requiresResolution":true}],
           "priorities":["High","Normal"],
           "issues":[{"id":1,"key":"FRA-1","title":"Lönerapporter","status":"New","priority":"High","updatedAt":$updatedAt,"canEdit":$canEdit}]}"""

    private fun issueJson(description: String, comments: String, canComment: Boolean = true, updatedAt: Int = 1) =
        """{"id":1,"key":"FRA-1","title":"Lönerapporter","status":"New","priority":"High","updatedAt":$updatedAt,"canEdit":true,
           "projectId":2,"canComment":$canComment,"description":${JsonPrimitive(description)},"comments":[$comments]}"""

    private val twoComments = """
        {"id":5,"body":"Underlaget ligger\ni delade mappen.","author":"Hendrik","createdAt":0},
        {"id":4,"body":"Earlier one","author":"Ada","createdAt":-60000}"""

    private lateinit var api: FakeLunicleApi
    private lateinit var boards: LunicleBoards
    private val key = LunicleBoardKey("work", "FRA")

    private suspend fun TestScope.pane(
        scope: String = "write",
        description: String = "Godkänn löneunderlag\nför september.",
        canComment: Boolean = true,
    ): PaneBackingViewModel {
        api = FakeLunicleApi().apply {
            answer(LunicleMethod.GET, "/api/v1/projects", 200, """[{"id":2,"name":"Framnafolk","keyPrefix":"FRA","yourRole":"contributor"}]""")
            answer(LunicleMethod.GET, "/api/v1/me", 200, """{"user":{"id":1,"name":"Ada"},"token":{"name":"t","scope":"$scope"}}""")
            answer(LunicleMethod.GET, boardPath, 200, boardJson())
            answer(LunicleMethod.GET, issuePath, 200, issueJson(description, twoComments, canComment))
            answer(LunicleMethod.PATCH, issuePath, 200, """{"message":"Updated FRA-1."}""")
            answer(LunicleMethod.POST, commentsPath, 201, """{"message":"Commented on FRA-1.","id":9,"issueId":1}""")
        }
        fs.writeFile("$root/_node.md", "- Lunicle board {{lunicle: work/FRA}}\n- Other\n")
        boards = LunicleBoards(LunicleService(api, store), null, backgroundScope, now = { testScheduler.currentTime })
        val registry = DocumentRegistry(repo, backgroundScope, lunicleBoards = boards)
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        pane.lunicleBoardOf(pane.stateFlow.value, 0, 0)
        runCurrent()
        // Unfold the issue and let its details arrive, as a paint would report it.
        pane.toggleLunicleIssue(issue(pane))
        pane.reportShownBoards(mapOf(key to setOf(1L)))
        runCurrent()
        return pane
    }

    private fun TestScope.now() = testScheduler.currentTime

    private fun TestScope.view(p: PaneBackingViewModel) = p.lunicleBoardOf(p.stateFlow.value, 0, now())!!

    private fun TestScope.issue(p: PaneBackingViewModel) = view(p).columns.flatMap { it.issues }.first { it.issue.id == 1L }

    private fun TestScope.keys(p: PaneBackingViewModel) = LunicleBoardRows.of(view(p)).map { it.ref.key }

    private fun TestScope.ref(p: PaneBackingViewModel, k: String) = LunicleBoardRows.of(view(p)).first { it.ref.key == k }.ref

    private fun writes(method: LunicleMethod) = api.sent.map { it.second }.filter { it.method == method }

    private fun body(vararg fields: Pair<String, String>) = JsonObject(fields.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun the_description_is_the_first_child_row_one_line_per_row() = runTest {
        val p = pane()
        assertEquals(listOf("c:New", "i:1", "d:1", "m:1:4", "m:1:5", "a:1", "e:New", "c:Closed"), keys(p))
        val d = assertNotNull(issue(p).description)
        assertEquals(listOf("Godkänn löneunderlag", "för september."), d.lines)
        assertTrue(d.editable)
        assertNull(d.caretLine)
        assertTrue(LunicleBoardRows.of(view(p)).first { it.ref.key == "d:1" }.editable)
    }

    @Test
    fun an_edited_description_is_sent_on_leaving_and_shows_at_once() = runTest {
        val p = pane()
        val d = ref(p, "d:1")
        assertEquals(LunicleDescriptionCaret(0, 0, "Godkänn löneunderlag"), p.beginLunicleDescription(0, d, 0, atEnd = false, now = now()))
        assertEquals(0, issue(p).description!!.caretLine)
        // Enter at the end of the first line, type on the new one, leave.
        val step = p.editLunicleDescription(key, 1, LunicleDescriptionAction.ENTER, "Godkänn löneunderlag", 20, 20)
        assertEquals(LunicleDescriptionCaret(1, 0, ""), (step as LunicleDescriptionStep.Caret).caret)
        assertEquals(listOf("Godkänn löneunderlag", "", "för september."), issue(p).description!!.lines)
        p.leaveLunicleRow(key, d, "Senast fredag.")
        assertNull(p.stateFlow.value.lunicleDescriptionEdit)
        // Optimistic: the new text and "Saving to Lunicle…" before Lunicle answers.
        val sent = "Godkänn löneunderlag\nSenast fredag.\nför september."
        assertEquals(LunicleDescription.linesOf(sent), issue(p).description!!.lines)
        assertEquals(LunicleSyncKind.SAVING, view(p).sync.kind)
        api.answer(LunicleMethod.GET, issuePath, 200, issueJson(sent, twoComments, updatedAt = 2))
        api.answer(LunicleMethod.GET, boardPath, 200, boardJson(updatedAt = 2))
        runCurrent()
        assertEquals(body("description" to sent), writes(LunicleMethod.PATCH).single().body)
        assertEquals("Synced just now", view(p).sync.text)
        assertEquals(LunicleDescription.linesOf(sent), issue(p).description!!.lines)
    }

    @Test
    fun an_unchanged_description_is_not_sent() = runTest {
        val p = pane()
        val d = ref(p, "d:1")
        p.beginLunicleDescription(0, d, -1, atEnd = true, now = now())
        assertEquals(1, p.stateFlow.value.lunicleDescriptionEdit!!.line)
        p.leaveLunicleRow(key, d, "för september.")
        runCurrent()
        assertTrue(writes(LunicleMethod.PATCH).isEmpty())
    }

    @Test
    fun a_failed_description_write_takes_the_text_back() = runTest {
        val p = pane()
        api.answer(LunicleMethod.PATCH, issuePath, 500, """{"error":"internal","message":"Failing on purpose."}""")
        val d = ref(p, "d:1")
        p.beginLunicleDescription(0, d, 0, atEnd = false, now = now())
        p.leaveLunicleRow(key, d, "Changed")
        runCurrent()
        assertEquals(listOf("Godkänn löneunderlag", "för september."), issue(p).description!!.lines)
        assertEquals(LunicleSyncKind.ERROR, view(p).sync.kind)
        assertEquals("Not saved: Failing on purpose.", view(p).sync.text)
    }

    @Test
    fun a_remote_change_during_an_edit_neither_overwrites_it_nor_wins() = runTest {
        val p = pane()
        val d = ref(p, "d:1")
        p.beginLunicleDescription(0, d, 1, atEnd = true, now = now())
        p.editLunicleDescription(key, 1, LunicleDescriptionAction.UP, "för oktober.", 5, 5)
        // Linus changes the description in Lunicle; a poll brings it.
        api.answer(LunicleMethod.GET, boardPath, 200, boardJson(updatedAt = 5))
        api.answer(LunicleMethod.GET, issuePath, 200, issueJson("Linus skrev om allt.", twoComments, updatedAt = 5))
        testScheduler.advanceTimeBy(LunicleBoards.POLL_MS + 1)
        runCurrent()
        assertEquals("Linus skrev om allt.", boards.boardsFlow.value[key]!!.details[1]!!.description)
        // The edit is kept, line and all.
        val shown = issue(p).description!!
        assertEquals(listOf("Godkänn löneunderlag", "för oktober."), shown.lines)
        assertEquals(0, shown.caretLine)
        // The indicator says it changed remotely, for as long as the edit runs.
        testScheduler.advanceTimeBy(LunicleBoards.NOTICE_MS + 1)
        runCurrent()
        assertEquals(LunicleSyncKind.REMOTE, view(p).sync.kind)
        assertEquals(LunicleBoardLayout.descriptionChangedText("Lönerapporter"), view(p).sync.text)
        // Leaving sends the user's text: the commit wins.
        p.leaveLunicleRow(key, d, "Godkänn löneunderlag")
        runCurrent()
        assertEquals(body("description" to "Godkänn löneunderlag\nför oktober."), writes(LunicleMethod.PATCH).single().body)
        assertFalse(view(p).sync.text == LunicleBoardLayout.descriptionChangedText("Lönerapporter"))
    }

    @Test
    fun a_large_description_shows_a_preview_until_the_caret_goes_past_it() = runTest {
        val long = (1..20).joinToString("\n") { "Line $it" }
        val p = pane(description = long)
        val d = issue(p).description!!
        assertTrue(d.large)
        assertEquals(12, d.hiddenRows)
        assertEquals(8, d.shownLines.size)
        // Walking down past the preview expands it.
        p.beginLunicleDescription(0, ref(p, "d:1"), 7, atEnd = true, now = now())
        assertEquals(12, issue(p).description!!.hiddenRows)
        p.editLunicleDescription(key, 1, LunicleDescriptionAction.DOWN, "Line 8", 6, 6)
        assertEquals(0, issue(p).description!!.hiddenRows)
        // The control cuts it back.
        p.leaveLunicleRow(key, ref(p, "d:1"), "Line 9")
        p.toggleLunicleDescription(issue(p))
        assertEquals(12, issue(p).description!!.hiddenRows)
        // Entering from below (its last line) expands it at once.
        p.beginLunicleDescription(0, ref(p, "d:1"), -1, atEnd = true, now = now())
        assertEquals(0, issue(p).description!!.hiddenRows)
    }

    @Test
    fun comments_come_oldest_first_and_arrivals_are_marked() = runTest {
        val p = pane()
        val first = issue(p).comments
        assertEquals(listOf(4L, 5L), first.map { it.id })
        assertEquals("Underlaget ligger i delade mappen.", first[1].body)
        // Seen once: nothing is marked.
        assertTrue(first.all { it.arrivedAt == null })
        // A new comment comes by polling: marked, briefly.
        testScheduler.advanceTimeBy(1_000)
        api.answer(LunicleMethod.GET, boardPath, 200, boardJson(updatedAt = 3))
        api.answer(
            LunicleMethod.GET, issuePath, 200,
            issueJson("x", "$twoComments, {\"id\":7,\"body\":\"Skickade rättad fil\",\"author\":\"Linus\",\"createdAt\":1000}", updatedAt = 3),
        )
        boards.refreshShown()
        runCurrent()
        val after = issue(p).comments
        assertEquals(listOf(4L, 5L, 7L), after.map { it.id })
        assertNotNull(after.last().arrivedAt)
        assertTrue(after.dropLast(1).all { it.arrivedAt == null })
        assertEquals("Linus commented on Lönerapporter", view(p).sync.text)
    }

    @Test
    fun a_comment_is_posted_optimistically_and_not_marked_as_an_arrival() = runTest {
        val p = pane()
        val add = ref(p, "a:1")
        assertEquals("", p.beginLunicleEdit(0, add, now()))
        // Enter with nothing typed does nothing.
        assertNull(p.lunicleEnter(0, add, "   ", now()))
        // Enter with text: posted, shown at once, the row stays (the field empties).
        assertEquals(add.key, p.lunicleEnter(0, add, " Klart! ", now())?.key)
        val pending = issue(p).comments.last()
        assertTrue(pending.pending)
        assertEquals("Klart!", pending.body)
        assertEquals(LunicleSyncKind.SAVING, view(p).sync.kind)
        api.answer(
            LunicleMethod.GET, issuePath, 200,
            issueJson("x", "$twoComments, {\"id\":9,\"body\":\"Klart!\",\"author\":\"Ada\",\"createdAt\":0}", updatedAt = 2),
        )
        runCurrent()
        assertEquals(body("body" to "Klart!"), writes(LunicleMethod.POST).single { it.path == commentsPath }.body)
        val comments = issue(p).comments
        assertEquals(listOf(4L, 5L, 9L), comments.map { it.id })
        assertFalse(comments.last().pending)
        // Our own comment is no arrival, and no remote notice.
        assertNull(comments.last().arrivedAt)
        assertFalse(view(p).sync.kind == LunicleSyncKind.REMOTE)
    }

    @Test
    fun a_failed_comment_goes_back_into_the_input() = runTest {
        val p = pane()
        api.answer(LunicleMethod.POST, commentsPath, 500, """{"error":"internal","message":"Failing on purpose."}""")
        val add = ref(p, "a:1")
        p.beginLunicleEdit(0, add, now())
        p.lunicleEnter(0, add, "Klart!", now())
        assertTrue(issue(p).comments.last().pending)
        runCurrent()
        // The comment is gone, the error shows, and its text is back with the row.
        assertEquals(listOf(4L, 5L), issue(p).comments.map { it.id })
        assertEquals("Not saved: Failing on purpose.", view(p).sync.text)
        assertEquals("Klart!", issue(p).commentDraft)
        // The open field takes it (merged with what it holds since).
        assertEquals("Klart!", p.takeLunicleCommentDraft(key, 1))
        assertEquals("", issue(p).commentDraft)
        // Left unposted, text stays with the row and comes back to the field.
        p.leaveLunicleRow(key, add, "Halvskrivet")
        assertEquals("Halvskrivet", issue(p).commentDraft)
        assertEquals("Halvskrivet", p.beginLunicleEdit(0, add, now()))
        assertEquals("", issue(p).commentDraft)
    }

    @Test
    fun a_read_only_token_edits_no_description_and_hides_comment() = runTest {
        val p = pane(scope = "read")
        assertFalse("a:1" in keys(p))
        assertFalse(issue(p).description!!.editable)
        assertFalse(LunicleBoardRows.of(view(p)).first { it.ref.key == "d:1" }.editable)
        assertNull(p.beginLunicleDescription(0, ref(p, "d:1"), 0, atEnd = false, now = now()))
        // Typing there says why, once.
        p.explainLunicleRow(0, ref(p, "d:1"), now())
        assertEquals(LunicleBoardLayout.READ_ONLY_TEXT, view(p).sync.text)
    }

    @Test
    fun can_comment_false_edits_no_description_and_hides_comment() = runTest {
        val p = pane(canComment = false)
        assertFalse("a:1" in keys(p))
        assertFalse(issue(p).description!!.editable)
        assertNull(p.beginLunicleDescription(0, ref(p, "d:1"), 0, atEnd = false, now = now()))
        assertNull(p.lunicleEnter(0, LunicleRowRef(LunicleRowKind.ADD_COMMENT, "New", 1), "Hi", now()))
        assertTrue(writes(LunicleMethod.POST).isEmpty())
    }
}
