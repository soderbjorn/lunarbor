/* NewsHostTest.kt (electron-main, jsTest)
 *
 * Pins [isOpenableUrl]: `lunarbor:openExternalUrl` opens only https: links
 * from the news manifests in the system browser. */
package se.soderbjorn.lunarbor.electron

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewsHostTest {

    @Test
    fun https_links_open() {
        assertTrue(isOpenableUrl("https://lunarbor.dev/"))
        assertTrue(isOpenableUrl("HTTPS://lunarbor.dev/#download"))
    }

    @Test
    fun other_schemes_are_refused() {
        assertFalse(isOpenableUrl("http://lunarbor.dev/"))
        assertFalse(isOpenableUrl("file:///etc/passwd"))
        assertFalse(isOpenableUrl("javascript:alert(1)"))
        assertFalse(isOpenableUrl("lunarbor-asset://x"))
        assertFalse(isOpenableUrl("https:///nohost"))
        assertFalse(isOpenableUrl(" https://lunarbor.dev/"))
    }
}
