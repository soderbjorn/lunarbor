/* LunicleModelsTest.kt (commonTest)
 *
 * Pins the lenient parsing of Lunicle's REST answers (LBR-26): a board in
 * the shape Lunicle's `get_board` builds (with LNL-223's `assignableUsers`,
 * absent optional fields, unknown keys), a full issue, `/me`, and the
 * `{error, message}` error body. */
package se.soderbjorn.lunarbor.lunicle

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LunicleModelsTest {

    private fun parse(text: String) = Json.parseToJsonElement(text)

    private val boardJson = """
        {
          "project": { "id": 2, "name": "Framnafolk", "keyPrefix": "FRA", "brandNew": 1 },
          "statuses": [
            { "name": "New", "requiresResolution": false },
            { "name": "In progress", "requiresResolution": false },
            { "name": "Closed", "requiresResolution": true }
          ],
          "priorities": ["High", "Normal", "Low"],
          "resolutions": ["Done", "Will not fix"],
          "labels": ["manager"],
          "components": [],
          "estimateMode": "none",
          "assignableUsers": [{ "name": "Linus" }, { "name": "Robert" }],
          "someFutureField": { "x": [1, 2, 3] },
          "issues": [
            {
              "id": 774, "key": "FRA-12", "title": "Lönerapporter", "status": "New", "priority": "High",
              "labels": ["manager"], "components": [], "author": "Robert", "assignee": "Linus",
              "isBlocked": true, "blockedBy": ["FRA-3"], "updatedAt": 1791279648030, "canEdit": true,
              "estimate": { "amount": 30, "unit": "minutes" }
            },
            {
              "id": 775, "key": "FRA-13", "title": "Closed one", "status": "Closed", "priority": "Low",
              "resolution": "Done", "labels": [], "components": [], "author": "Linus",
              "updatedAt": 1791279000000, "canEdit": false
            },
            { "key": "FRA-99", "title": "no id: skipped" },
            "not an object"
          ]
        }
    """

    @Test
    fun board_parses_vocabulary_and_issues() {
        val board = LunicleJson.board(parse(boardJson))!!
        assertEquals(LunicleProject(2, "Framnafolk", "FRA"), board.project)
        assertEquals(listOf("New", "In progress", "Closed"), board.statuses.map { it.name })
        assertTrue(board.statuses.last().requiresResolution)
        assertFalse(board.statuses.first().requiresResolution)
        assertEquals(listOf("High", "Normal", "Low"), board.priorities)
        assertEquals(listOf("Done", "Will not fix"), board.resolutions)
        assertEquals(listOf("Linus", "Robert"), board.assignableUsers)
        assertEquals(emptyList(), board.sprints)
        assertNull(board.activeSprint)
        assertEquals(listOf(774L, 775L), board.issues.map { it.id })

        val first = board.issues[0]
        assertEquals("FRA-12", first.key)
        assertEquals("Lönerapporter", first.title)
        assertEquals("Linus", first.assignee)
        assertNull(first.resolution)
        assertTrue(first.isBlocked)
        assertEquals(listOf("FRA-3"), first.blockedBy)
        assertEquals(LunicleEstimate(30, "minutes"), first.estimate)
        assertEquals(1791279648030L, first.updatedAt)
        assertTrue(first.canEdit)

        val second = board.issues[1]
        assertEquals("Done", second.resolution)
        assertNull(second.assignee)
        assertFalse(second.isBlocked)
        assertFalse(second.canEdit)
    }

    @Test
    fun board_without_assignable_users_reads_null() {
        // Absent for a read-only caller, and on servers before LNL-223.
        val board = LunicleJson.board(
            parse("""{ "project": { "id": 1, "name": "P", "keyPrefix": "P" }, "statuses": ["New"], "priorities": [], "issues": [] }"""),
        )!!
        assertNull(board.assignableUsers)
        assertEquals(listOf(LunicleStatus("New")), board.statuses)
    }

    @Test
    fun board_needs_a_project() {
        assertNull(LunicleJson.board(parse("""{ "statuses": [] }""")))
        assertNull(LunicleJson.board(parse("""[]""")))
        assertNull(LunicleJson.board(null))
    }

    @Test
    fun issue_parses_comments_links_and_history() {
        val issue = LunicleJson.issue(
            parse(
                """
                {
                  "id": 774, "key": "FRA-12", "projectId": 2, "title": "Lönerapporter",
                  "description": "Every **month**.", "status": "New", "priority": "High",
                  "labels": [], "components": [], "author": "Robert",
                  "createdAt": 1700000000000, "updatedAt": 1791279648030, "canEdit": true, "canComment": true,
                  "parent": { "id": 700, "key": "FRA-1", "title": "Epic" },
                  "relations": [{ "relationId": 9, "label": "Blocked by", "kind": "Blocked by", "id": 760, "key": "FRA-3", "title": "Setup" }],
                  "comments": [
                    { "id": 1, "body": "First", "author": "Linus", "createdAt": 1791279000000 },
                    { "id": 2, "body": "By agent", "author": "Robert", "agentName": "Claude", "createdAt": 1791279100000 }
                  ],
                  "history": [{ "id": 50, "kind": "STATUS_CHANGED", "value": "New", "author": "Robert", "viaToken": "Lunarbor", "createdAt": 1 }]
                }
                """,
            ),
        )!!
        assertEquals(774L, issue.id)
        assertEquals("FRA-12", issue.key)
        assertEquals(2L, issue.projectId)
        assertEquals("Every **month**.", issue.description)
        assertTrue(issue.canComment)
        assertEquals("FRA-1", issue.parent?.key)
        assertEquals(LunicleIssueRef(760, "FRA-3", "Setup", label = "Blocked by", relationId = 9), issue.relations.single())
        assertEquals(listOf("First", "By agent"), issue.comments.map { it.body })
        assertEquals("Claude", issue.comments[1].agentName)
        assertEquals("Lunarbor", issue.history.single().viaToken)
        assertEquals(emptyList(), issue.children)
    }

    @Test
    fun me_and_created_answers() {
        val me = LunicleJson.me(
            parse("""{ "user": { "id": 3, "name": "Robert" }, "token": { "id": 8, "name": "Lunarbor", "scope": "read" } }"""),
        )!!
        assertEquals("Robert", me.userName)
        assertEquals("Lunarbor", me.tokenName)
        assertTrue(me.isReadOnly)
        assertNull(me.tokenExpiresAt)

        assertEquals(
            LunicleCreated(781, "FRA-14", "Created FRA-14 (issue id 781): Title"),
            LunicleJson.created(parse("""{ "message": "Created FRA-14 (issue id 781): Title", "id": 781, "key": "FRA-14" }""")),
        )
        assertEquals(LunicleCreated(12, null, "Commented on issue 774."),
            LunicleJson.created(parse("""{ "message": "Commented on issue 774.", "id": 12, "issueId": 774 }""")))
    }

    @Test
    fun error_bodies() {
        val readOnly = LunicleJson.error(
            403,
            parse("""{ "error": "insufficient_scope", "message": "This token is read-only. Make a read-write token to change anything." }"""),
        )
        assertTrue(readOnly.isReadOnlyToken)
        assertEquals("This token is read-only. Make a read-write token to change anything.", readOnly.message)

        val notFound = LunicleJson.error(404, parse("""{ "error": "not_found", "message": "No such issue." }"""))
        assertTrue(notFound.isNotFound)
        assertFalse(notFound.isReadOnlyToken)
        assertEquals("not_found", notFound.code)

        val forbidden = LunicleJson.error(403, parse("""{ "error": "forbidden", "message": "You cannot edit this issue." }"""))
        assertFalse(forbidden.isReadOnlyToken)

        // A body that is not Lunicle's (a proxy's HTML page) still gives a sentence.
        val bare = LunicleJson.error(502, null)
        assertEquals("", bare.code)
        assertTrue(bare.message.isNotBlank())
        assertTrue(LunicleJson.error(429, null).isRateLimited)
        assertTrue(LunicleJson.error(401, null).isInvalidToken)
    }
}
