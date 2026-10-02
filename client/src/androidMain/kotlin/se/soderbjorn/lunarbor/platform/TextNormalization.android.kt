/*
 * TextNormalization.android.kt (androidMain)
 * `actual` for [toNfc] via `java.text.Normalizer`.
 */

package se.soderbjorn.lunarbor.platform

import java.text.Normalizer

/** See the `expect` in commonMain. */
actual fun String.toNfc(): String = Normalizer.normalize(this, Normalizer.Form.NFC)
