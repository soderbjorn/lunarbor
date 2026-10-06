/* LunicleBoardModelTest.kt (commonTest)
 *
 * Pins the pure rules of board nodes (LBR-27): parsing and stripping
 * `{{lunicle: …}}` ([se.soderbjorn.lunarbor.data.LunicleNode]); column
 * order (closed columns last, folded by default) and issue order (by
 * priority, the board's order within one); pills; the sync indicator and
 * error sentences; the issue link; poll diffing (changed, new and removed
 * issues, and which issues re-fetch); the remote-change message; and
 * parsing the change stream's relayed messages. */
package se.soderbjorn.lunarbor.lunicle

import kotlinx.serialization.json.Json
import se.soderbjorn.lunarbor.data.DoneState
import se.soderbjorn.lunarbor.data.FolderName
import se.soderbjorn.lunarbor.data.InlineMarkdownTokenizer
import se.soderbjorn.lunarbor.data.LunicleNode
import se.soderbjorn.lunarbor.data.LunicleNodeRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A board issue with only what these tests look at. */
internal fun issue(id: Long, status: String, priority: String = "Normal", title: String = "Issue $id", updatedAt: Long = 1, key: String = "FRA-$id") =
    LunicleBoardIssue(id = id, key = key, title = title, status = status, priority = priority, updatedAt = updatedAt)

/** A board of project FRA with the default vocabulary. */
internal fun board(vararg issues: LunicleBoardIssue) = LunicleBoard(
    project = LunicleProject(2, "Framnafolk", "FRA"),
    statuses = listOf(LunicleStatus("New"), LunicleStatus("Closed", requiresResolution = true), LunicleStatus("In progress")),
    priorities = listOf("Very high", "High", "Normal", "Low"),
    issues = issues.toList(),
)

class LunicleBoardModelTest {

    @Test
    fun parses_the_reference() {
        assertEquals(LunicleNodeRef("work", "FRA", "work/FRA"), LunicleNode.refOf("Lunicle board {{lunicle: work/FRA}}"))
        assertEquals(LunicleNodeRef(null, "FRA", "FRA"), LunicleNode.refOf("Sprint {{lunicle:FRA}}"))
        assertEquals(LunicleNodeRef("my-lunicle_2", "lbr", "my-lunicle_2 / lbr"), LunicleNode.refOf("{{lunicle:  my-lunicle_2 / lbr }}"))
        assertNull(LunicleNode.refOf("Plain bullet {{search: #x}}"))
        // Malformed: still a board node, with an empty key.
        val bad = LunicleNode.refOf("Oops {{lunicle: a/b/c}}")!!
        assertFalse(bad.isValid)
        assertEquals("a/b/c", bad.raw)
        assertEquals(LunicleBoardKey("work", "FRA"), LunicleBoardKey.of(LunicleNode.refOf("{{lunicle: Work/fra}}")!!))
        assertEquals("{{lunicle: work/FRA}}", LunicleNode.format("work", "FRA"))
    }

    @Test
    fun the_reference_is_no_part_of_the_title() {
        assertEquals("Lunicle board", LunicleNode.stripQuery("Lunicle board {{lunicle: work/FRA}}"))
        assertEquals("Board and more", LunicleNode.stripQuery("Board {{lunicle: FRA}} and more"))
        assertEquals("Tasks", LunicleNode.stripQueries("Tasks {{search: #todo}} {{lunicle: FRA}}"))
        // Folder names, titles, breadcrumbs and the index all go through these.
        assertEquals("Lunicle board", FolderName.plainTextOf("Lunicle board {{lunicle: work/FRA}}"))
        assertEquals("Lunicle board", FolderName.nameTextOf("**Lunicle** board {{lunicle: work/FRA}} #work"))
        // Toggle done strikes the title only, leaving the reference outside.
        assertEquals("~~Lunicle board~~ {{lunicle: work/FRA}}", DoneState.withDoneText("Lunicle board {{lunicle: work/FRA}}", true))
        assertTrue(DoneState.isDoneText("~~Lunicle board~~ {{lunicle: work/FRA}}"))
    }

    @Test
    fun the_tokenizer_marks_the_reference_as_one_run() {
        val runs = InlineMarkdownTokenizer.tokenize("Board {{lunicle: work/FRA}}").runs
        val ref = runs.single { it.isLunicleQuery }
        assertEquals("{{lunicle: work/FRA}}", ref.text)
        // Every character stays visible, so the caret maps one to one.
        assertEquals("Board {{lunicle: work/FRA}}", InlineMarkdownTokenizer.tokenize("Board {{lunicle: work/FRA}}").displayText)
    }

    @Test
    fun columns_in_board_order_with_closed_ones_last_and_folded() {
        val cols = LunicleBoardLayout.columns(board(issue(1, "New"), issue(2, "Closed"), issue(3, "In progress"), issue(4, "Gone")))
        assertEquals(listOf("New", "In progress", "Closed"), cols.map { it.status.name })
        assertEquals(listOf(false, false, true), cols.map { it.foldedByDefault })
        assertEquals(listOf(1, 1, 1), cols.map { it.count })
    }

    @Test
    fun issues_by_priority_keeping_board_order_within_one() {
        val b = board(
            issue(1, "New", "Low"), issue(2, "New", "High"), issue(3, "New", "Normal"),
            issue(4, "New", "High"), issue(5, "New", "Unheard of"), issue(6, "New", "Very high"),
        )
        assertEquals(listOf(6L, 2, 4, 3, 1, 5), LunicleBoardLayout.columns(b).first().issues.map { it.id })
    }

    @Test
    fun pills_folded_and_unfolded() {
        val priorities = listOf("Very high", "High", "Normal")
        val top = issue(1, "In progress", "Very high")
        assertEquals(listOf("#very-high"), LunicleBoardLayout.pills(top, priorities, unfolded = false).map { it.text })
        assertEquals(emptyList(), LunicleBoardLayout.pills(issue(2, "New", "High"), priorities, unfolded = false))
        assertEquals(
            listOf("#in-progress", "#very-high", "@nobody"),
            LunicleBoardLayout.pills(top, priorities, unfolded = true).map { it.text },
        )
        assertEquals("@linus", LunicleBoardLayout.pills(top.copy(assignee = "Linus"), priorities, true).last().text)
        assertEquals(null, LunicleBoardLayout.commentsLabel(0))
        assertEquals("1 comment", LunicleBoardLayout.commentsLabel(1))
        assertEquals("3 comments", LunicleBoardLayout.commentsLabel(3))
    }

    @Test
    fun the_sync_indicator() {
        val key = LunicleBoardKey("work", "FRA")
        assertEquals(LunicleSyncKind.LOADING, LunicleBoardLayout.syncLine(LunicleBoardState(key), 0).kind)
        val synced = LunicleBoardState(key, syncedAt = 100_000)
        assertEquals(LunicleSyncLine(LunicleSyncKind.SYNCED, "Synced just now", 100_000), LunicleBoardLayout.syncLine(synced, 102_000))
        assertEquals("Synced 12s ago", LunicleBoardLayout.syncLine(synced, 112_000).text)
        assertEquals("Synced 3 min ago", LunicleBoardLayout.syncedText(0, 200_000))
        assertEquals(LunicleSyncKind.LIVE, LunicleBoardLayout.syncLine(synced.copy(live = true), 200_000).kind)
        val notice = synced.copy(notice = "Linus commented on X", noticeUntil = 106_000)
        assertEquals(LunicleSyncLine(LunicleSyncKind.REMOTE, "Linus commented on X"), LunicleBoardLayout.syncLine(notice, 105_000))
        assertEquals(LunicleSyncKind.SYNCED, LunicleBoardLayout.syncLine(notice, 106_000).kind)
        assertEquals(LunicleSyncKind.SAVING, LunicleBoardLayout.syncLine(synced.copy(saving = true), 0).kind)
        val failed = synced.copy(error = LunicleError.Http(401, "invalid_token", "Bad token."))
        assertEquals(LunicleSyncLine(LunicleSyncKind.ERROR, "Check the token in App settings → Lunicle."), LunicleBoardLayout.syncLine(failed, 0))
    }

    @Test
    fun error_sentences() {
        assertEquals("No Lunicle connection is called “x” (App settings → Lunicle).", LunicleBoardLayout.errorText(LunicleError.NoConnection("x", 2)))
        assertEquals("No project with the key ZZZ is visible through “work”.", LunicleBoardLayout.errorText(LunicleError.NoProject("ZZZ", "work")))
        assertEquals("Offline: Could not reach it.", LunicleBoardLayout.errorText(LunicleError.Transport("Could not reach it.")))
    }

    @Test
    fun the_issue_link_and_times() {
        assertEquals("https://issues.lunicle.dev/?issue=FRA-12", LunicleBoardLayout.issueUrl("https://issues.lunicle.dev/", "FRA-12"))
        assertNull(LunicleBoardLayout.issueUrl("http://localhost:8080", "FRA-12"))
        assertEquals("2026-10-04", LunicleBoardLayout.dateText(1_791_072_000_000))
        assertEquals("1970-01-01", LunicleBoardLayout.dateText(0))
        assertEquals("just now", LunicleBoardLayout.whenText(0, 30_000))
        assertEquals("5 min ago", LunicleBoardLayout.whenText(0, 300_000))
        assertEquals("2 h ago", LunicleBoardLayout.whenText(0, 7_200_000))
        assertEquals("3 d ago", LunicleBoardLayout.whenText(0, 3 * 86_400_000L))
    }

    @Test
    fun diff_finds_changed_new_and_removed_issues() {
        val before = board(issue(1, "New", updatedAt = 1), issue(2, "New", updatedAt = 1), issue(3, "New", updatedAt = 1))
        val after = board(issue(1, "New", updatedAt = 1), issue(2, "In progress", updatedAt = 5), issue(4, "New", updatedAt = 5))
        assertEquals(LunicleChanges(setOf(2), setOf(4), setOf(3)), LunicleBoardDiff.diff(before, after))
        // The first read is no change.
        assertTrue(LunicleBoardDiff.diff(null, after).isEmpty)
    }

    @Test
    fun only_unfolded_issues_refetch() {
        val changes = LunicleChanges(changed = setOf(2, 5), added = setOf(4), removed = setOf(3))
        val onBoard = setOf(1L, 2, 4, 5, 6)
        // 2 changed and unfolded; 4 is new but folded; 6 unfolded but unknown; 3 is gone.
        assertEquals(setOf(2L, 6), LunicleBoardDiff.toRefetch(changes, emptySet(), unfolded = setOf(2, 3, 6), known = setOf(2, 3), onBoard = onBoard))
        // A stream hint names an issue whose updatedAt did not move (a comment).
        assertEquals(setOf(1L), LunicleBoardDiff.toRefetch(LunicleChanges(emptySet(), emptySet(), emptySet()), setOf(1), setOf(1), setOf(1), onBoard))
    }

    @Test
    fun the_remote_change_message() {
        val before = board(issue(1, "New", title = "Lönerapporter"), issue(2, "New", title = "Other"))
        val moved = board(issue(1, "Closed", title = "Lönerapporter", updatedAt = 9), issue(2, "New", title = "Other"))
        val one = LunicleBoardDiff.diff(before, moved)
        assertEquals("Lönerapporter moved to Closed", LunicleBoardDiff.remoteMessage(one, before, moved))
        assertEquals(
            "Linus commented on Lönerapporter",
            LunicleBoardDiff.remoteMessage(one, before, moved, listOf(LunicleChangeHint("comment.added", 1, "Linus"))),
        )
        assertEquals(
            "Linus moved Lönerapporter to Closed",
            LunicleBoardDiff.remoteMessage(one, before, moved, listOf(LunicleChangeHint("issue.moved", 1, "Linus"))),
        )
        // From a poll: a new comment on an unfolded issue names its author.
        val detailBefore = LunicleIssue(moved.issues[0], 2, comments = listOf(LunicleComment(1, "a", "Ada")))
        val detailAfter = detailBefore.copy(comments = detailBefore.comments + LunicleComment(2, "b", "Linus"))
        assertEquals(
            "Linus commented on Lönerapporter",
            LunicleBoardDiff.remoteMessage(one, before, moved, emptyList(), mapOf(1L to detailBefore), mapOf(1L to detailAfter)),
        )
        val added = board(issue(1, "New", title = "Lönerapporter"), issue(2, "New", title = "Other"), issue(3, "New", title = "Fresh"))
        assertEquals("Fresh was added", LunicleBoardDiff.remoteMessage(LunicleBoardDiff.diff(before, added), before, added))
        val removed = board(issue(2, "New", title = "Other"))
        assertEquals("Lönerapporter was removed", LunicleBoardDiff.remoteMessage(LunicleBoardDiff.diff(before, removed), before, removed))
        val many = board(issue(1, "New", title = "Lönerapporter", updatedAt = 3), issue(2, "New", title = "Other", updatedAt = 3), issue(7, "New"))
        assertEquals("3 issues changed", LunicleBoardDiff.remoteMessage(LunicleBoardDiff.diff(before, many), before, many))
        assertNull(LunicleBoardDiff.remoteMessage(LunicleBoardDiff.diff(before, before), before, before))
    }

    @Test
    fun parses_relayed_stream_messages() {
        fun parse(text: String) = LunicleStreamMessage.parse(Json.parseToJsonElement(text))
        assertEquals(
            LunicleStreamMessage.Event("c1", "comment.added", projectId = 2, issueId = 774, commentId = 9, actor = "Linus"),
            parse("""{"connectionId":"c1","event":"comment.added","id":"17","data":{"projectId":2,"issueId":774,"commentId":9,"updatedAt":1,"actor":"Linus"}}"""),
        )
        assertEquals(true, (parse("""{"connectionId":"c1","event":"issue.updated","data":{"projectId":2,"issueId":1,"self":true}}""") as LunicleStreamMessage.Event).self)
        assertEquals(LunicleStreamMessage.Event("c1", "reset"), parse("""{"connectionId":"c1","event":"reset","data":{}}"""))
        assertEquals(
            LunicleStreamMessage.Status("c1", LunicleStreamMessage.StreamState.UNSUPPORTED),
            parse("""{"connectionId":"c1","status":"unsupported"}"""),
        )
        assertNull(parse("""{"event":"reset"}"""))
        assertNull(parse("""{"connectionId":"c1","status":"weird"}"""))
    }
}
