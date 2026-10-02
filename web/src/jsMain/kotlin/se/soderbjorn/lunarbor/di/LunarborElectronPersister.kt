/* LunarborElectronPersister.kt (jsMain)
 *
 * Lunarbor's renderer-side [Persister] for the Electron desktop wrapper.
 * It replaces the toolkit's stock [ElectronIpcPersister] because that
 * one only routes three keys (`UI_SETTINGS`, `LAYOUT`, `LAYOUT_STATE`)
 * and silently drops everything else — including the keys the toolkit
 * actually persists the theme under (`THEME_V2_SELECTION`,
 * `THEME_V2_CUSTOM`) plus `SIDEBAR_STATE` / `HOTKEY_BINDINGS`. That gap
 * is why appearance/theme reverted to defaults on every desktop restart.
 *
 * ## How it fixes that
 *
 * The post-revamp toolkit writes each of those settings under its own
 * top-level key. This persister aggregates every such "ui-settings" key
 * into a single flat JSON blob and round-trips it through the Electron
 * main process's already-existing `darkness:writeUiSettings` /
 * `darkness:readUiSettings` channels (see `ElectronMain.kt`). Those
 * channels partition the blob across the cross-app shared `themes.json`
 * (custom theme *definitions*) and the per-app `lunarbor.json`
 * (selections, appearance, fonts, sidebar, hotkeys), merge shared themes
 * with peer Darkness apps, and pack the merged result into the
 * `--darkness-settings=` boot arg so the theme applies on the very first
 * frame (no flash of the default theme).
 *
 * `LAYOUT` and `LAYOUT_STATE` are left to the toolkit's
 * [ElectronIpcPersister], which already routes them correctly to their
 * own per-app files.
 *
 * The philosophy mirrors termtastic's `SettingsPersisterAdapter`: a
 * generic, key-agnostic pass-through that never whitelists keys, so any
 * future toolkit setting persists automatically.
 *
 * jsMain / Electron only. In a plain browser the app uses
 * [se.soderbjorn.lunula.web.LocalStoragePersister] instead (which
 * already stores every key), wired in `JsAppGraph.providePersister`.
 */
package se.soderbjorn.lunarbor.di

import kotlinx.coroutines.suspendCancellableCoroutine
import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.web.ElectronIpcPersister
import kotlin.coroutines.resume

/**
 * Aggregate-blob [Persister] for the Electron renderer. See the file
 * header for the full rationale.
 *
 * ### Callers
 * Constructed by `JsAppGraph.providePersister()` when the Electron IPC
 * bridge (`globalThis.darknessApi`) is present, and handed to both
 * `AppShell` and the toolkit's `mountAppShell`, which read/write theme,
 * sidebar, hotkey and layout state through it.
 *
 * @property layoutDelegate handles the two layout keys, which have their
 *   own dedicated files/channels in the Electron main process. Defaults
 *   to the toolkit's stock [ElectronIpcPersister].
 */
class LunarborElectronPersister(
    private val layoutDelegate: Persister = ElectronIpcPersister(),
) : Persister {

    /**
     * In-memory snapshot of every ui-settings key (i.e. everything
     * except [PersistKeys.LAYOUT] / [PersistKeys.LAYOUT_STATE]), kept as
     * a plain JS object `{ key: <parsed JSON value> }`.
     *
     * Lazily seeded from the boot snapshot (`globalThis.__darknessSettings`,
     * the merged blob the main process packed into the launch args) or,
     * failing that, a fresh `readUiSettings` IPC read. Every write mutates
     * this object and re-serialises the whole thing back through IPC, so
     * the on-disk blob is always the complete current state.
     */
    private var cache: dynamic = null

    override suspend fun read(key: String): String? = when (key) {
        PersistKeys.LAYOUT, PersistKeys.LAYOUT_STATE -> layoutDelegate.read(key)
        else -> readUiSettingsKey(key)
    }

    override suspend fun write(key: String, value: String) {
        when (key) {
            PersistKeys.LAYOUT, PersistKeys.LAYOUT_STATE -> layoutDelegate.write(key, value)
            else -> writeUiSettingsKey(key, value)
        }
    }

    /** Extract [key] from the aggregated blob, or null if absent. */
    private suspend fun readUiSettingsKey(key: String): String? {
        val c = ensureCache()
        return elementToString(c[key])
    }

    /**
     * Update [key] in the aggregated blob and push the whole blob to the
     * Electron main process, which partitions + persists it to disk.
     */
    private suspend fun writeUiSettingsKey(key: String, value: String) {
        val c = ensureCache()
        c[key] = parseElementOrString(value)
        writeUiSettingsIpc(js("JSON.stringify(c)") as String)
    }

    /** Seed [cache] once from the boot snapshot, else from a live IPC read. */
    private suspend fun ensureCache(): dynamic {
        val existing = cache
        if (existing != null) return existing
        val raw = readBootSnapshot() ?: readUiSettingsIpc()
        val parsed = parseObjectOrEmpty(raw)
        cache = parsed
        return parsed
    }

    /* --- JSON helpers (JS-native, matching the Electron-glue idiom) --- */

    /**
     * Convert a stored JSON value back to the string the toolkit's
     * [Persister] contract expects: a JS string is returned verbatim
     * (its content), anything else (object/array/number/bool) is
     * re-serialised. Mirrors termtastic's `SettingsPersisterAdapter.read`.
     */
    private fun elementToString(el: dynamic): String? {
        if (el == null || js("el === undefined") as Boolean) return null
        return if (js("typeof el === 'string'") as Boolean) el as String
        else js("JSON.stringify(el)") as String
    }

    /**
     * Parse [value] as JSON so it embeds as a nested element in the blob
     * (keeps `themes.json` / `lunarbor.json` human-readable and matches
     * the canonical nested form other Darkness apps write). Falls back to
     * storing the raw string when [value] isn't valid JSON.
     */
    private fun parseElementOrString(value: String): dynamic = try {
        js("JSON.parse(value)")
    } catch (_: Throwable) {
        value
    }

    private fun parseObjectOrEmpty(raw: String?): dynamic {
        if (raw.isNullOrBlank()) return js("({})")
        return try {
            val parsed: dynamic = js("JSON.parse(raw)")
            if (parsed != null && (js("typeof parsed === 'object'") as Boolean) &&
                !(js("Array.isArray(parsed)") as Boolean)
            ) parsed else js("({})")
        } catch (_: Throwable) {
            js("({})")
        }
    }

    /* --- IPC glue ----------------------------------------------------- */

    private fun readBootSnapshot(): String? =
        js("globalThis.__darknessSettings || null") as? String

    private suspend fun readUiSettingsIpc(): String? {
        val api = js("globalThis.darknessApi") ?: return null
        val fn = js("api && api.readUiSettings")
        if (js("typeof fn !== 'function'") as Boolean) return null
        return invokeAsync(fn, api)
    }

    private suspend fun writeUiSettingsIpc(blob: String) {
        val api = js("globalThis.darknessApi") ?: return
        val fn = js("api && api.writeUiSettings")
        if (js("typeof fn !== 'function'") as Boolean) return
        invokeAsync(fn, api, blob)
    }

    /** Invoke an IPC function that returns a Promise and suspend for it. */
    private suspend fun invokeAsync(fn: dynamic, thisArg: dynamic, arg: String? = null): String? =
        suspendCancellableCoroutine { cont ->
            val promise = if (arg == null) fn.call(thisArg) else fn.call(thisArg, arg)
            promise.then(
                { value: dynamic -> cont.resume(value as? String) },
                { _: dynamic -> cont.resume(null) },
            )
        }
}

/**
 * Returns a [LunarborElectronPersister] when the Electron IPC bridge
 * (`globalThis.darknessApi`) is installed by the desktop wrapper's
 * preload script, or `null` in a plain browser. Mirrors the toolkit's
 * `tryElectronIpcPersister()` so `JsAppGraph.providePersister` can pick
 * the right backend with a single elvis.
 */
fun tryLunarborElectronPersister(): Persister? {
    val present = js("typeof globalThis.darknessApi !== 'undefined'") as Boolean
    return if (present) LunarborElectronPersister() else null
}
