/* LunarborDefaultLook.kt (jsMain)
 *
 * What Lunarbor looks like to someone who has never picked a theme: the
 * toolkit's "Lunarbor Dark" / "Lunarbor Light" pair, drawn from the app
 * icon's glowing red tree, instead of the toolkit's own "Lunamux" defaults.
 *
 * The defaults are applied on the way *out* of the persister
 * ([LunarborDefaultLookPersister.read]) and never written on the user's
 * behalf, so they sit strictly beneath any choice the user makes, and a
 * later change here reaches everybody who has not chosen. Same arrangement
 * as LunaPin's `DefaultLook.kt` and Lunicle's `ThemePersister`.
 *
 * Wired in `JsAppGraph.providePersister` around the desktop and browser
 * persisters. The browser demo is left out: its `demo/state.json` picks
 * its own look.
 */
package se.soderbjorn.lunarbor.di

import se.soderbjorn.lunula.core.DEFAULT_DARK_THEME
import se.soderbjorn.lunula.core.DEFAULT_LIGHT_THEME
import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.core.ThemeSnapshotV2

/** The dark slot's theme until the user picks another: the icon's red glow on charcoal. */
const val LUNARBOR_DEFAULT_DARK_THEME: String = "Lunarbor Dark"

/** The light slot's companion: the same red as ink on warm paper. */
const val LUNARBOR_DEFAULT_LIGHT_THEME: String = "Lunarbor Light"

/**
 * [stored] — the raw `THEME_V2_SELECTION` blob — with Lunarbor's default
 * slot themes applied beneath whatever it holds.
 *
 * - Nothing stored: both Lunarbor themes, the toolkit's default appearance
 *   (follow the system).
 * - Something stored: a slot still holding the toolkit's built-in default
 *   name moves to Lunarbor's; any other name and the appearance are kept.
 *   A slot holding the toolkit default is one the toolkit filled in, not one
 *   anybody chose (the sun/moon toggle persists a whole blob with those names
 *   in it). The cost: explicitly picking "Lunamux Dark" reads as unchosen.
 *
 * Called by [LunarborDefaultLookPersister.read].
 *
 * @param stored the persisted selection JSON, or null when there is none.
 * @return the selection JSON the toolkit should mount from; never null.
 */
fun defaultedThemeSelectionJson(stored: String?): String {
    val snapshot = ThemeSnapshotV2.fromStrings(selectionJson = stored, customThemesJson = null)
    return ThemeSnapshotV2(
        darkThemeName = snapshot.darkThemeName
            .takeUnless { it == DEFAULT_DARK_THEME } ?: LUNARBOR_DEFAULT_DARK_THEME,
        lightThemeName = snapshot.lightThemeName
            .takeUnless { it == DEFAULT_LIGHT_THEME } ?: LUNARBOR_DEFAULT_LIGHT_THEME,
        appearance = snapshot.appearance,
    ).selectionJson()
}

/**
 * A [Persister] that passes everything through to [delegate] except reads of
 * [PersistKeys.THEME_V2_SELECTION], which get Lunarbor's default themes
 * applied by [defaultedThemeSelectionJson]. Writes are untouched, so nothing
 * is stored that the user did not choose.
 *
 * Constructed by `JsAppGraph.providePersister`.
 *
 * @property delegate the real backend (Electron IPC or `localStorage`).
 */
class LunarborDefaultLookPersister(private val delegate: Persister) : Persister {
    override suspend fun read(key: String): String? {
        val value = delegate.read(key)
        return if (key == PersistKeys.THEME_V2_SELECTION) defaultedThemeSelectionJson(value) else value
    }

    override suspend fun write(key: String, value: String) = delegate.write(key, value)
}
