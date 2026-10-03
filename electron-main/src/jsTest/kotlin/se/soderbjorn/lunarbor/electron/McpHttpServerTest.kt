/* McpHttpServerTest.kt (electron-main, jsTest)
 *
 * Pins the MCP endpoint's gate: only requests carrying the key
 * ([isAuthorized]) from this machine and not from a web page
 * ([isLocalRequest]) get through, and the key picks the connection — with
 * its privacy scope — the request runs as ([connectionFor]). */
package se.soderbjorn.lunarbor.electron

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpHttpServerTest {

    private val key = "tf_0123456789abcdef"

    @Test
    fun only_the_exact_key_as_a_bearer_token_is_accepted() {
        assertTrue(isAuthorized("Bearer $key", key))
        assertFalse(isAuthorized(null, key))
        assertFalse(isAuthorized(key, key))
        assertFalse(isAuthorized("Bearer ${key}x", key))
        assertFalse(isAuthorized("Bearer tf_0123456789abcdeX", key))
        assertFalse(isAuthorized("Bearer ", ""))
    }

    @Test
    fun agents_on_this_machine_pass_web_pages_and_rebinding_do_not() {
        assertTrue(isLocalRequest(null, "127.0.0.1:47321"))
        assertTrue(isLocalRequest(null, "localhost:47321"))
        assertTrue(isLocalRequest("http://localhost:3000", "127.0.0.1:47321"))
        assertFalse(isLocalRequest("https://evil.example", "127.0.0.1:47321"))
        assertFalse(isLocalRequest("null", "127.0.0.1:47321"))
        assertFalse(isLocalRequest(null, "evil.example:47321"))
        assertFalse(isLocalRequest(null, null))
    }

    @Test
    fun the_key_picks_the_connection_and_its_privacy_scope() {
        val all = McpConnection("a", "All", "tf_aaaa")
        val work = McpConnection("w", "Work", "tf_wwww", privacy = "colleagues", allowEdits = false)
        val both = listOf(all, work)
        assertEquals(work, connectionFor("Bearer tf_wwww", both))
        assertEquals(all, connectionFor("Bearer tf_aaaa", both))
        assertNull(connectionFor("Bearer tf_xxxx", both))
        assertNull(connectionFor(null, both))
        assertNull(connectionFor("Bearer tf_aaaa", emptyList()))
    }
}
