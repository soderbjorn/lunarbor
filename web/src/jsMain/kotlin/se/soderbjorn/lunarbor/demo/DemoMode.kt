/*
 * DemoMode.kt (jsMain)
 * --------------------
 * The browser demo: Lunarbor running in a plain web page (the website's
 * "try it" bundle), with no Electron shell and no disk. It is on exactly
 * when the page has no `noteApi` bridge — in a plain browser there is no
 * other way the app could reach a vault — so the desktop app can never
 * fall into it, and the website bundle can never look for real files.
 *
 * In demo mode:
 * - the vault is a [DemoFileSystem] in memory, seeded from the bundled
 *   `demo-vault.js` (built from `demo/vault/` by the `:web` Gradle task
 *   `generateDemoVault`); edits work and last until the page reloads;
 * - the persister ([DemoPersister]) keeps only the look (theme and UI
 *   settings) in `localStorage`, so every visit starts at the same place;
 * - desktop-only settings (vault, backup, agent access) hide themselves,
 *   as they already do without the bridge.
 *
 * Main.kt calls [loadDemoVault] before building the DI graph;
 * JsAppGraph reads [demoFileSystem] when it provides the FileSystem.
 */

package se.soderbjorn.lunarbor.demo

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.HTMLScriptElement
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.web.LocalStoragePersister
import kotlin.js.Date
import kotlin.js.Promise

/** Where the demo vault lives in [DemoFileSystem]: a made-up absolute path. */
const val DEMO_VAULT_ROOT: String = "/demo-vault"

/**
 * The bundled vault, served next to `web.js`: a script that sets
 * `window.lunarborDemoVault`. A script rather than JSON to fetch, because a
 * page opened straight from disk (`file://`) may run scripts beside it but
 * may not fetch them — and the website should work opened from Finder too.
 */
private const val DEMO_VAULT_URL: String = "demo-vault.js"

/**
 * `true` when the app runs as the browser demo: no Electron `noteApi`
 * bridge on the page. Read by Main.kt and JsAppGraph at startup.
 *
 * @return Whether demo mode is on.
 */
fun isDemoMode(): Boolean {
    val bridge = window.asDynamic().noteApi
    return bridge == null || bridge == undefined
}

/**
 * The demo's file system once [loadDemoVault] has filled it, else `null`.
 * Read by `JsAppGraph.provideFileSystem` and by `lunarborAssetUrl`.
 */
var demoFileSystem: DemoFileSystem? = null
    private set

/**
 * Loads `demo-vault.js` and unpacks it into a fresh [DemoFileSystem]
 * under [DEMO_VAULT_ROOT], then publishes it as [demoFileSystem]. Called
 * once by Main.kt before the DI graph is built. A missing or broken file
 * leaves an empty vault (the app then starts on an empty root) rather
 * than a blank page.
 *
 * The JSON is `{ "dirs": [rel, …], "files": [{ "path": rel, "text": … |
 * "base64": …, "mtime": ms }, …], "state": { key: value, … } }`, paths
 * relative to the vault root; `state` (from `demo/state.json`) becomes
 * [demoInitialState], the persister's starting values.
 */
suspend fun loadDemoVault() {
    val fs = DemoFileSystem()
    fs.seedDirectory(DEMO_VAULT_ROOT)
    try {
        val json: dynamic = loadVaultScript()
        if (json != null && json != undefined) {
            val now = Date.now().toLong()
            for (dir in (json.dirs as Array<String>)) fs.seedDirectory("$DEMO_VAULT_ROOT/$dir")
            for (file in (json.files as Array<dynamic>)) {
                val path = "$DEMO_VAULT_ROOT/${file.path as String}"
                val text = file.text as? String
                val bytes = text?.encodeToByteArray() ?: decodeBase64(file.base64 as String)
                val mtime = (file.mtime as? Number)?.toLong() ?: now
                fs.seed(path, bytes, mtime)
            }
            val state: dynamic = json.state
            if (state != null && state != undefined) {
                for (key in js("Object.keys")(state) as Array<String>) {
                    demoInitialState[key] = state[key] as String
                }
            }
        } else {
            console.warn("[lunarbor] demo vault not found ($DEMO_VAULT_URL)")
        }
    } catch (e: Throwable) {
        console.warn("[lunarbor] demo vault failed to load: ${e.message}")
    }
    demoFileSystem = fs
}

/**
 * Runs [DEMO_VAULT_URL] as a `<script>` and hands back what it set on
 * `window.lunarborDemoVault` (cleared again, so the packed vault is not
 * kept alive twice), or `null` when the script is missing or broken.
 */
private suspend fun loadVaultScript(): dynamic {
    val script = document.createElement("script") as HTMLScriptElement
    script.src = DEMO_VAULT_URL
    Promise<Unit> { resolve, _ ->
        script.onload = { resolve(Unit) }
        script.onerror = { _, _, _, _, _ -> resolve(Unit); null }
        document.head!!.appendChild(script)
    }.await()
    script.remove()
    val vault: dynamic = window.asDynamic().lunarborDemoVault
    js("delete window.lunarborDemoVault")
    return vault
}

/**
 * Persister values the demo starts with (e.g. `lunarborOpenFolders`, so the
 * tour opens unfolded), filled by [loadDemoVault] and read by [DemoPersister].
 */
private val demoInitialState = HashMap<String, String>()

/** Base64 → bytes, via the browser's `atob`. */
private fun decodeBase64(data: String): ByteArray {
    val binary = window.atob(data)
    return ByteArray(binary.length) { binary[it].code.toByte() }
}

/**
 * The demo's [Persister]: the look (theme selection, custom themes, UI
 * settings) persists per browser in `localStorage`, under its own
 * namespace so it never mixes with anything else; everything about the
 * vault and the window layout (pane locations, open folds, tabs) lives in
 * memory, so every reload starts the tour at the same place — matching a
 * vault that is itself reseeded on every reload. The in-memory values
 * start from `demo/state.json` (see [loadDemoVault]), which also gives
 * the look its default (the demo's own theme) until the visitor picks one.
 *
 * Provided by `JsAppGraph.providePersister` in demo mode.
 */
class DemoPersister : Persister {
    private val durable = LocalStoragePersister(namespace = "lunarbor-demo")
    private val memory = HashMap(demoInitialState)

    init {
        // Authoring aid for demo/state.json: arrange tabs, windows and folds
        // in the running demo, then run `copy(lunarborDemoState())` in the
        // console and keep the keys you want (see demo/README.md).
        window.asDynamic().lunarborDemoState = {
            val out: dynamic = js("({})")
            for ((key, value) in memory) out[key] = JSON.parse<Any?>(value)
            JSON.stringify(out, null, 2)
        }
    }

    private fun isDurable(key: String) =
        key.startsWith("darkness.theme") || key == "darkness.uiSettings"

    override suspend fun read(key: String): String? =
        if (isDurable(key)) durable.read(key) ?: demoInitialState[key] else memory[key]

    override suspend fun write(key: String, value: String) {
        if (isDurable(key)) durable.write(key, value) else memory[key] = value
    }
}
