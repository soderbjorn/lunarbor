/*
 * TextNormalization.js.kt (jsMain)
 * `actual` for [toNfc] via JavaScript's `String.prototype.normalize`.
 */

package se.soderbjorn.lunarbor.platform

/** See the `expect` in commonMain. */
actual fun String.toNfc(): String = asDynamic().normalize("NFC") as String
