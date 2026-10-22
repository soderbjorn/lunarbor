/*
 * AboutDialog.kt (jsMain)
 *
 * The "About Lunarbor" dialog behind the topbar's info button, right of the
 * News & updates bell (as in Lunamux). Two tabs:
 *  - About: copyright, the app version (Electron only — the browser demo has
 *    none), and links to lunarbor.dev and the GitHub repository.
 *  - Licensing: the third-party libraries and bundled fonts, and a link to
 *    the NOTICE file with every license text.
 *
 * View-only glue: no state beyond the open dialog, no view model. Keep the
 * lists in step with NOTICE — it is the canonical attribution.
 */
package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunula.web.shell.TopbarAction

private const val COPYRIGHT = "Copyright © 2026 Robert Söderbjörn"
private const val WEBSITE_URL = "https://lunarbor.dev/"
private const val GITHUB_URL = "https://github.com/soderbjorn/lunarbor"
private const val NOTICE_URL = "$GITHUB_URL/blob/main/NOTICE"

/**
 * Third-party libraries shipped in the app, without versions (they drift and
 * mean little to users). Full attribution per library is in NOTICE.
 */
private val LIBRARIES = listOf(
    "Kotlin",
    "kotlinx.coroutines",
    "kotlinx.serialization",
    "Ktor",
    "Metro",
    "Lunula",
    "Excalidraw",
    "three.js",
    "Electron",
    "Chromium",
)

/**
 * A bundled font's credit line.
 *
 * @property name the family's display name; the row is set in it.
 * @property holder short copyright holder.
 * @property license the license's short name.
 */
private data class FontCredit(val name: String, val holder: String, val license: String)

/** The fonts in `web/fonts`, as NOTICE → "LUNARBOR FONTS" lists them. */
private val BUNDLED_FONTS = listOf(
    FontCredit("Instrument Sans", "The Instrument Sans Project Authors", "OFL 1.1"),
    FontCredit("Unbounded", "The Unbounded Project Authors", "OFL 1.1"),
    FontCredit("JetBrains Mono", "JetBrains", "OFL 1.1"),
    FontCredit("Fira Code", "Nikita Prokopov", "OFL 1.1"),
    FontCredit("Cascadia Code", "Microsoft", "OFL 1.1"),
    FontCredit("IBM Plex Mono", "IBM Corp.", "OFL 1.1"),
    FontCredit("Geist Mono", "Vercel × basement.studio", "OFL 1.1"),
    FontCredit("Source Code Pro", "Adobe", "OFL 1.1"),
)

/**
 * The topbar info button; a click opens [showAboutDialog]. Joins
 * `extraTopbarTrailing` in [AppShell], after the bell.
 */
internal fun aboutTopbarAction(): TopbarAction = TopbarAction(
    id = "lunarbor-topbar-about",
    iconHtml = ICON_ABOUT,
    label = "About Lunarbor",
    onActivate = { showAboutDialog() },
)

/**
 * Opens the About dialog (one at a time), on its About tab. Closed by ×,
 * the backdrop or Escape (taken in the capture phase, so no editor or
 * toolkit hotkey sees it). Called by [aboutTopbarAction].
 */
internal fun showAboutDialog() {
    if (document.querySelector(".lunarbor-about-backdrop") != null) return
    ensureAboutStyles()
    val backdrop = el("div", "lunarbor-about-backdrop")
    val panel = el("div", "lunarbor-about-dialog")
    panel.setAttribute("role", "dialog")
    panel.setAttribute("aria-label", "About Lunarbor")
    backdrop.appendChild(panel)

    lateinit var close: () -> Unit
    val keyHandler: (Event) -> Unit = { e ->
        if ((e as KeyboardEvent).key == "Escape") {
            e.preventDefault()
            e.stopPropagation()
            close()
        }
    }
    close = {
        document.removeEventListener("keydown", keyHandler, true)
        backdrop.remove()
    }

    val header = el("div", "lunarbor-about-head")
    header.appendChild(el("h2", "lunarbor-about-title", "Lunarbor"))
    val closeButton = button("×", "lunarbor-about-close") { close() }
    closeButton.title = "Close"
    header.appendChild(closeButton)
    panel.appendChild(header)

    // Both panels are built up front; the tabs only toggle which one shows.
    val tabs = el("div", "lunarbor-about-tabs")
    val body = el("div", "lunarbor-about-body")
    val panels = listOf("About" to buildMainTab(), "Licensing" to buildLicensingTab())
    val tabButtons = panels.map { (label, _) -> button(label, "lunarbor-about-tab") {} }
    fun select(index: Int) {
        panels.forEachIndexed { i, (_, p) -> p.style.display = if (i == index) "" else "none" }
        tabButtons.forEachIndexed { i, b -> b.classList.toggle("is-active", i == index) }
    }
    tabButtons.forEachIndexed { i, b ->
        b.addEventListener("click", { _: Event -> select(i) })
        tabs.appendChild(b)
    }
    panels.forEach { (_, p) -> body.appendChild(p) }
    select(0)
    panel.appendChild(tabs)
    panel.appendChild(body)

    document.addEventListener("keydown", keyHandler, true)
    backdrop.addEventListener("mousedown", { e -> if (e.target === backdrop) close() })
    document.body?.appendChild(backdrop)
}

/** The About tab: copyright, version (when Electron supplies one), links. */
private fun buildMainTab(): HTMLElement {
    val panel = el("div", "lunarbor-about-panel")
    panel.appendChild(el("p", "lunarbor-about-copyright", COPYRIGHT))
    panel.appendChild(el("p", "lunarbor-about-dim", "Released under the MIT License."))
    val noteApi = window.asDynamic().noteApi
    val versionName = (noteApi?.appVersionName as? String)?.takeIf { it.isNotBlank() }
    val versionCode = (noteApi?.appVersionCode as? String)?.takeIf { it.isNotBlank() }
    if (versionName != null) {
        val text = if (versionCode != null) "Version $versionName ($versionCode)" else "Version $versionName"
        panel.appendChild(el("p", "lunarbor-about-version", text))
    }
    val links = el("div", "lunarbor-about-links")
    links.appendChild(link(WEBSITE_URL, "lunarbor.dev"))
    links.appendChild(link(GITHUB_URL, "Source on GitHub"))
    panel.appendChild(links)
    return panel
}

/** The Licensing tab: libraries, bundled fonts, the NOTICE pointer. */
private fun buildLicensingTab(): HTMLElement {
    val panel = el("div", "lunarbor-about-panel")
    panel.appendChild(el("h3", "lunarbor-about-section", "Third-party libraries"))
    val libs = el("ul", "lunarbor-about-list")
    for (name in LIBRARIES) {
        val li = el("li", "")
        li.appendChild(el("span", "lunarbor-about-name", name))
        libs.appendChild(li)
    }
    panel.appendChild(libs)

    panel.appendChild(el("h3", "lunarbor-about-section", "Bundled fonts"))
    panel.appendChild(
        el(
            "p", "lunarbor-about-dim",
            "Offered in App settings → Appearance, each under its upstream license. " +
                "Drawings use Excalidraw's own fonts, listed in NOTICE.",
        ),
    )
    val fonts = el("ul", "lunarbor-about-list")
    for (font in BUNDLED_FONTS) {
        val li = el("li", "")
        // Each credit in its own typeface.
        val name = el("span", "lunarbor-about-name", "${font.name} — ${font.holder}")
        name.style.fontFamily = "'${font.name}', ui-monospace, monospace"
        li.appendChild(name)
        li.appendChild(el("span", "lunarbor-about-tag", font.license))
        fonts.appendChild(li)
    }
    panel.appendChild(fonts)

    val footer = el("p", "lunarbor-about-dim")
    footer.appendChild(document.createTextNode("Full license texts are in the "))
    footer.appendChild(link(NOTICE_URL, "NOTICE"))
    footer.appendChild(document.createTextNode(" file."))
    panel.appendChild(footer)
    return panel
}

/**
 * A real link that opens in the system browser: through the main process in
 * Electron (an in-app navigation would replace the app), a new tab elsewhere.
 */
private fun link(url: String, text: String): HTMLAnchorElement {
    val a = document.createElement("a") as HTMLAnchorElement
    a.className = "lunarbor-about-link"
    a.href = url
    a.target = "_blank"
    a.rel = "noopener noreferrer"
    a.textContent = text
    a.addEventListener("click", { e: Event ->
        e.preventDefault()
        val noteApi = window.asDynamic().noteApi
        if (noteApi?.openExternalUrl != null) noteApi.openExternalUrl(url) else window.open(url, "_blank")
    })
    return a
}

private fun el(tag: String, className: String, text: String? = null): HTMLElement =
    (document.createElement(tag) as HTMLElement).also {
        it.className = className
        if (text != null) it.textContent = text
    }

private fun button(label: String, className: String, onClick: () -> Unit): HTMLButtonElement {
    val b = document.createElement("button") as HTMLButtonElement
    b.type = "button"
    b.className = className
    b.textContent = label
    b.addEventListener("click", { _: Event -> onClick() })
    return b
}

private fun ensureAboutStyles() {
    if (document.getElementById("lunarbor-about-style") != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = "lunarbor-about-style"
    style.textContent = ABOUT_CSS
    document.head?.appendChild(style)
}

/** Lucide-style info circle, as Lunamux's About button. */
private const val ICON_ABOUT: String =
    """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>"""

/** The dialog's look, matching the News & updates dialog (NewsUpdates.kt). */
private const val ABOUT_CSS = """
.lunarbor-about-backdrop {
    position: fixed; inset: 0; z-index: 2147483640; background: rgba(0, 0, 0, 0.45);
    display: flex; align-items: flex-start; justify-content: center; padding: 8vh 16px 16px;
}
.lunarbor-about-dialog {
    width: min(480px, 100%); max-height: 84vh; display: flex; flex-direction: column;
    background: var(--t-bg, #1e1e1e); color: var(--t-text, #e6e6e6);
    border: 1px solid var(--t-border, rgba(255,255,255,0.12)); border-radius: 12px;
    box-shadow: 0 28px 72px rgba(0, 0, 0, 0.45), 0 10px 24px rgba(0, 0, 0, 0.30);
    font-size: 13px; line-height: 1.45;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
}
.lunarbor-about-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 14px 16px 6px; }
.lunarbor-about-title { margin: 0; font-size: 16px; font-weight: 700; }
.lunarbor-about-close {
    flex: 0 0 auto; width: 24px; height: 24px; padding: 0; border: 0; border-radius: 6px;
    background: transparent; color: var(--t-text-dim, #9a9a9a); font-size: 18px; line-height: 1; cursor: pointer;
}
.lunarbor-about-close:hover { color: var(--t-text, #e6e6e6); background: color-mix(in srgb, var(--t-text, #e6e6e6) 10%, transparent); }
.lunarbor-about-tabs { display: flex; gap: 4px; padding: 0 16px; border-bottom: 1px solid var(--t-border, rgba(255,255,255,0.12)); }
.lunarbor-about-tab {
    padding: 6px 10px; margin-bottom: -1px; font: inherit; font-weight: 600; cursor: pointer;
    color: var(--t-text-dim, #9a9a9a); background: transparent; border: 0; border-bottom: 2px solid transparent;
}
.lunarbor-about-tab:hover { color: var(--t-text, #e6e6e6); }
.lunarbor-about-tab.is-active { color: var(--t-text, #e6e6e6); border-bottom-color: var(--t-accent, #6ea8fe); }
.lunarbor-about-body { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 14px 16px 16px; user-select: text; }
.lunarbor-about-panel p { margin: 0 0 8px; }
.lunarbor-about-copyright { font-weight: 600; }
.lunarbor-about-dim { color: var(--t-text-dim, #9a9a9a); }
.lunarbor-about-links { display: flex; flex-direction: column; gap: 4px; margin-top: 12px; }
.lunarbor-about-link { color: var(--t-accent, #6ea8fe); text-decoration: none; }
.lunarbor-about-link:hover { text-decoration: underline; }
.lunarbor-about-section { margin: 4px 0 6px; font-size: 13px; font-weight: 700; }
.lunarbor-about-list { list-style: none; margin: 0 0 14px; padding: 0; }
.lunarbor-about-list li {
    display: flex; justify-content: space-between; gap: 12px; padding: 3px 0;
    border-bottom: 1px solid color-mix(in srgb, var(--t-border, rgba(255,255,255,0.12)) 60%, transparent);
}
.lunarbor-about-tag { flex: 0 0 auto; color: var(--t-text-dim, #9a9a9a); font-size: 12px; }
"""
