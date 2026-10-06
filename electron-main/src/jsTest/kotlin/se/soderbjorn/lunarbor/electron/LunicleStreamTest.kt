/* LunicleStreamTest.kt (electron-main, jsTest)
 *
 * Pins the pure parts of the change streams `LunicleHost` holds (LBR-27):
 * the `text/event-stream` parser (events, comments, multi-line data, any
 * chunk split, `\r\n`, the last event id to resume from), the reconnect
 * backoff, and the stream's path with its projects and origin. */
package se.soderbjorn.lunarbor.electron

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LunicleStreamTest {

    private class Heard {
        val events = ArrayList<Triple<String?, String, String>>()
        var comments = 0
        val parser = SseParser({ id, event, data -> events += Triple(id, event, data) }, { comments++ })
    }

    @Test
    fun parses_lunicles_events_and_pings() {
        val h = Heard()
        h.parser.feed("retry: 3000\n: connected\n\n")
        h.parser.feed("id: 17\nevent: issue.updated\ndata: {\"projectId\":2,\"issueId\":774}\n\n: ping\n\n")
        assertEquals(listOf(Triple<String?, String, String>("17", "issue.updated", "{\"projectId\":2,\"issueId\":774}")), h.events)
        assertEquals(2, h.comments)
        assertEquals("17", h.parser.lastEventId)
    }

    @Test
    fun any_split_and_crlf_read_the_same() {
        val text = "id: 1\r\nevent: reset\r\ndata: {}\r\n\r\nevent: comment.added\ndata: a\ndata: b\n\n"
        val whole = Heard().also { it.parser.feed(text) }.events
        val bytes = Heard().also { h -> text.forEach { h.parser.feed(it.toString()) } }.events
        assertEquals(whole, bytes)
        assertEquals(listOf<Triple<String?, String, String>>(Triple("1", "reset", "{}"), Triple(null, "comment.added", "a\nb")), whole)
    }

    @Test
    fun an_event_without_data_is_not_dispatched_and_the_name_defaults() {
        val h = Heard()
        h.parser.feed("event: lonely\n\ndata:x\n\n")
        assertEquals(listOf(Triple<String?, String, String>(null, "message", "x")), h.events)
    }

    @Test
    fun backoff_and_path() {
        assertEquals(listOf(5_000, 15_000, 60_000, 300_000, 300_000), (0..4).map { lunicleStreamBackoffMs(it) })
        assertEquals("/api/v1/events?projects=2,5,9&origin=abc", lunicleStreamPath(listOf(9L, 2, 5), "abc"))
        assertTrue(Regex("^[0-9a-f]{24}$").matches(LUNICLE_ORIGIN))
    }
}
