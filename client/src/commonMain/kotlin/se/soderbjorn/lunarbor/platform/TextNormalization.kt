/*
 * TextNormalization.kt (commonMain)
 * ---------------------------------
 * Unicode normalization, which Kotlin's common stdlib lacks; each platform
 * source set supplies the `actual` from its own runtime.
 *
 * Needed because macOS hands out file names decomposed (NFD: `o` + U+0308
 * for `ö`) while typed text and file contents are usually precomposed
 * (NFC: U+00F6). The two render identically but never compare equal, so
 * anything that compares a file name with text (a note's title heading,
 * `NoteRepository.displayNameOf`) normalizes first.
 */

package se.soderbjorn.lunarbor.platform

/**
 * This string in Unicode Normalization Form C (canonical composition):
 * `"ö"` becomes `"ö"`. Already-composed text is returned unchanged.
 */
expect fun String.toNfc(): String
