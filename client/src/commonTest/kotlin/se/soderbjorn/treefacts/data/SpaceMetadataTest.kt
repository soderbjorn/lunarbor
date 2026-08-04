/*
 * SpaceMetadataTest.kt
 * --------------------
 * Round-trip and edge-case tests for [SpaceMetadata] — the pure parser/
 * rewriter for the `treefacts-space` marker and `treefacts-ai` opt-in
 * keys in a space anchor's frontmatter. Everything here is pure-function
 * testing; the I/O side (NoteRepository.readSpaceMarker /
 * writeSpaceMarker / readSpaceAiAllowed / writeSpaceAiAllowed) is a thin
 * wrapper over these functions plus the frontmatter cache.
 */

package se.soderbjorn.treefacts.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpaceMetadataTest {

    // ------------------------------------------------------------ parsing

    @Test
    fun nullFrontmatterIsNotAllowed() {
        assertFalse(SpaceMetadata.aiAllowedOf(null))
    }

    @Test
    fun frontmatterWithoutKeyIsNotAllowed() {
        assertFalse(SpaceMetadata.aiAllowedOf("---\ntags: [a, b]\n---\n"))
    }

    @Test
    fun allowedValueParses() {
        assertTrue(SpaceMetadata.aiAllowedOf("---\ntreefacts-ai: allowed\n---\n"))
    }

    @Test
    fun otherValueIsNotAllowed() {
        assertFalse(SpaceMetadata.aiAllowedOf("---\ntreefacts-ai: denied\n---\n"))
        assertFalse(SpaceMetadata.aiAllowedOf("---\ntreefacts-ai:\n---\n"))
    }

    @Test
    fun keyParsesAmongOtherKeys() {
        val fm = "---\ntags: [x]\ntreefacts-ai: allowed\naliases: [y]\n---\n"
        assertTrue(SpaceMetadata.aiAllowedOf(fm))
    }

    @Test
    fun whitespaceAroundKeyAndValueTolerated() {
        assertTrue(SpaceMetadata.aiAllowedOf("---\n  treefacts-ai:   allowed  \n---\n"))
    }

    // ----------------------------------------------------------- updating

    @Test
    fun grantOnNullGrowsFreshBlock() {
        assertEquals(
            "---\ntreefacts-ai: allowed\n---\n",
            SpaceMetadata.withAiAllowed(null, allowed = true),
        )
    }

    @Test
    fun grantPreservesUserKeys() {
        val fm = "---\ntags: [x]\naliases: [y]\n---\n"
        assertEquals(
            "---\ntags: [x]\naliases: [y]\ntreefacts-ai: allowed\n---\n",
            SpaceMetadata.withAiAllowed(fm, allowed = true),
        )
    }

    @Test
    fun grantReplacesExistingKeyValue() {
        val fm = "---\ntreefacts-ai: denied\ntags: [x]\n---\n"
        assertEquals(
            "---\ntags: [x]\ntreefacts-ai: allowed\n---\n",
            SpaceMetadata.withAiAllowed(fm, allowed = true),
        )
    }

    @Test
    fun revokeRemovesKeyLeavingUserKeys() {
        val fm = "---\ntags: [x]\ntreefacts-ai: allowed\n---\n"
        assertEquals(
            "---\ntags: [x]\n---\n",
            SpaceMetadata.withAiAllowed(fm, allowed = false),
        )
    }

    @Test
    fun revokeOnKeyOnlyBlockCollapsesToNull() {
        assertNull(SpaceMetadata.withAiAllowed("---\ntreefacts-ai: allowed\n---\n", allowed = false))
    }

    @Test
    fun revokeOnNullStaysNull() {
        assertNull(SpaceMetadata.withAiAllowed(null, allowed = false))
    }

    @Test
    fun grantIsIdempotent() {
        val once = SpaceMetadata.withAiAllowed("---\ntags: [x]\n---\n", allowed = true)
        assertEquals(once, SpaceMetadata.withAiAllowed(once, allowed = true))
    }

    @Test
    fun roundTripThroughParse() {
        val granted = SpaceMetadata.withAiAllowed(null, allowed = true)
        assertTrue(SpaceMetadata.aiAllowedOf(granted))
        val revoked = SpaceMetadata.withAiAllowed(granted, allowed = false)
        assertFalse(SpaceMetadata.aiAllowedOf(revoked))
    }

    // -------------------------------------------------- space marker key

    @Test
    fun nullFrontmatterIsNotASpace() {
        assertFalse(SpaceMetadata.isSpaceOf(null))
    }

    @Test
    fun frontmatterWithoutMarkerIsNotASpace() {
        assertFalse(SpaceMetadata.isSpaceOf("---\ntags: [a]\n---\n"))
    }

    @Test
    fun spaceMarkerParses() {
        assertTrue(SpaceMetadata.isSpaceOf("---\ntreefacts-space: true\n---\n"))
    }

    @Test
    fun otherSpaceValueIsNotASpace() {
        assertFalse(SpaceMetadata.isSpaceOf("---\ntreefacts-space: false\n---\n"))
        assertFalse(SpaceMetadata.isSpaceOf("---\ntreefacts-space:\n---\n"))
    }

    @Test
    fun setSpaceMarkerOnNullGrowsFreshBlock() {
        assertEquals(
            "---\ntreefacts-space: true\n---\n",
            SpaceMetadata.withSpaceMarker(null, isSpace = true),
        )
    }

    @Test
    fun clearSpaceMarkerOnMarkerOnlyBlockCollapsesToNull() {
        assertNull(SpaceMetadata.withSpaceMarker("---\ntreefacts-space: true\n---\n", isSpace = false))
    }

    // --------------------------------------------- two keys coexisting

    @Test
    fun markerAndAiCoexistIndependently() {
        // Mark a space, then grant AI: both keys present, neither clobbers
        // the other, and each parser reads only its own key.
        val marked = SpaceMetadata.withSpaceMarker(null, isSpace = true)
        val both = SpaceMetadata.withAiAllowed(marked, allowed = true)
        assertEquals("---\ntreefacts-space: true\ntreefacts-ai: allowed\n---\n", both)
        assertTrue(SpaceMetadata.isSpaceOf(both))
        assertTrue(SpaceMetadata.aiAllowedOf(both))

        // Revoking AI leaves the space marker standing.
        val aiRevoked = SpaceMetadata.withAiAllowed(both, allowed = false)
        assertEquals("---\ntreefacts-space: true\n---\n", aiRevoked)
        assertTrue(SpaceMetadata.isSpaceOf(aiRevoked))
        assertFalse(SpaceMetadata.aiAllowedOf(aiRevoked))
    }

    @Test
    fun clearingSpaceMarkerPreservesAiAndUserKeys() {
        val fm = "---\ntags: [x]\ntreefacts-space: true\ntreefacts-ai: allowed\n---\n"
        assertEquals(
            "---\ntags: [x]\ntreefacts-ai: allowed\n---\n",
            SpaceMetadata.withSpaceMarker(fm, isSpace = false),
        )
    }
}
