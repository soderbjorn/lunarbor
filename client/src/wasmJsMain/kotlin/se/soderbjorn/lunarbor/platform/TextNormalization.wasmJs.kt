/*
 * TextNormalization.wasmJs.kt (wasmJsMain)
 * `actual` for [toNfc] via JavaScript's `String.prototype.normalize`.
 */

package se.soderbjorn.lunarbor.platform

private fun jsNormalizeNfc(s: String): String = js("s.normalize('NFC')")

/** See the `expect` in commonMain. */
actual fun String.toNfc(): String = jsNormalizeNfc(this)
