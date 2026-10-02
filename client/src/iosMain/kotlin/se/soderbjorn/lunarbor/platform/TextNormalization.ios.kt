/*
 * TextNormalization.ios.kt (iosMain)
 * `actual` for [toNfc] via Foundation's canonical precomposition.
 */

package se.soderbjorn.lunarbor.platform

import platform.Foundation.NSString
import platform.Foundation.precomposedStringWithCanonicalMapping

/** See the `expect` in commonMain. */
@Suppress("CAST_NEVER_SUCCEEDS")
actual fun String.toNfc(): String = (this as NSString).precomposedStringWithCanonicalMapping
