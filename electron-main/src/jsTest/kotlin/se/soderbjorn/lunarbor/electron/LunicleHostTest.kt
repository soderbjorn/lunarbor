/* LunicleHostTest.kt (electron-main, jsTest)
 *
 * Pins the checks `LunicleHost` applies at the trust boundary (LBR-26):
 * connection names, base URLs (https only, http for localhost), tokens,
 * and the `/api/v1/` path confinement of `lunarbor:lunicleRequest`. */
package se.soderbjorn.lunarbor.electron

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LunicleHostTest {

    private val existing = listOf(LunicleConnectionRecord("a1", "work", "https://issues.lunicle.dev", "lnl_pat_x"))

    @Test
    fun names_are_slugs_unique_ignoring_case() {
        assertNull(lunicleNameError("home", existing, null))
        assertNull(lunicleNameError("my_board-2", existing, null))
        assertNotNull(lunicleNameError("", existing, null))
        assertNotNull(lunicleNameError("two words", existing, null))
        assertNotNull(lunicleNameError("a/b", existing, null))
        assertNotNull(lunicleNameError("-dash", existing, null))
        assertNotNull(lunicleNameError("x".repeat(33), existing, null))
        assertNotNull(lunicleNameError("WORK", existing, null))
        // Renaming a connection to its own name (another case) is fine.
        assertNull(lunicleNameError("Work", existing, "a1"))
    }

    @Test
    fun base_urls_are_https_or_local_http() {
        assertEquals("https://issues.lunicle.dev", normalizeLunicleBaseUrl(" https://issues.lunicle.dev/ "))
        assertEquals("https://issues.lunicle.dev", normalizeLunicleBaseUrl("HTTPS://Issues.Lunicle.dev"))
        assertEquals("https://example.com:8443/lunicle", normalizeLunicleBaseUrl("https://example.com:8443/lunicle/"))
        assertEquals("http://localhost:8080", normalizeLunicleBaseUrl("http://localhost:8080"))
        assertEquals("http://127.0.0.1:8080", normalizeLunicleBaseUrl("http://127.0.0.1:8080"))
        assertNull(normalizeLunicleBaseUrl("http://issues.lunicle.dev"))
        assertNull(normalizeLunicleBaseUrl("ftp://issues.lunicle.dev"))
        assertNull(normalizeLunicleBaseUrl("issues.lunicle.dev"))
        assertNull(normalizeLunicleBaseUrl("https://user:pw@issues.lunicle.dev"))
        assertNull(normalizeLunicleBaseUrl("https://issues.lunicle.dev/?q=1"))
        assertNull(normalizeLunicleBaseUrl("https://issues.lunicle.dev:99999"))
        assertNull(normalizeLunicleBaseUrl(""))
    }

    @Test
    fun tokens_must_look_like_personal_access_tokens() {
        assertNull(lunicleTokenError("lnl_pat_3f9a1c0123456789"))
        assertNotNull(lunicleTokenError("lnl_pat_"))
        assertNotNull(lunicleTokenError("ghp_abc"))
        assertNotNull(lunicleTokenError("lnl_pat_abc def"))
        assertEquals("lnl_pat_3f9a1c", lunicleTokenHint("lnl_pat_3f9a1c0123456789"))
        assertEquals("", lunicleTokenHint(""))
    }

    @Test
    fun only_api_paths_are_relayed() {
        assertTrue(isAllowedLuniclePath("/api/v1/me"))
        assertTrue(isAllowedLuniclePath("/api/v1/projects/12/board"))
        assertTrue(isAllowedLuniclePath("/api/v1/issues/7/move"))
        assertFalse(isAllowedLuniclePath("/api/v2/me"))
        assertFalse(isAllowedLuniclePath("/api/v1"))
        assertFalse(isAllowedLuniclePath("/login"))
        assertFalse(isAllowedLuniclePath("/api/v1/../admin"))
        assertFalse(isAllowedLuniclePath("/api/v1/%2e%2e/admin"))
        assertFalse(isAllowedLuniclePath("/api/v1/x%2F..%2Fadmin"))
        assertFalse(isAllowedLuniclePath("/api/v1/me?x=1"))
        assertFalse(isAllowedLuniclePath("/api/v1/me#x"))
        assertFalse(isAllowedLuniclePath("/api/v1//me"))
        assertFalse(isAllowedLuniclePath("/api/v1/a\\b"))
        assertFalse(isAllowedLuniclePath("https://evil.example/api/v1/me"))
    }
}
