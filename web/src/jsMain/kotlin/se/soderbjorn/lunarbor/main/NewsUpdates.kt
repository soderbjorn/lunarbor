/*
 * NewsUpdates.kt (jsMain)
 * -----------------------
 * The desktop's "News & updates" bell and dialog, over the common
 * [NewsUpdatesBackingViewModel] (which owns every rule — the 24 h check,
 * update comparison, dismissals, Restore). Same behaviour and look as
 * Lunamux's:
 *
 *  - The bell is a topbar action ([newsTopbarAction]), always visible and
 *    clickable: muted when there is nothing new, coloured when there is
 *    an update or news, pulsing only for news (no pulse under
 *    `prefers-reduced-motion`). The look is driven by `<body>` attributes
 *    (`data-lunarbor-news`, `data-lunarbor-news-pulse`) so it survives the
 *    toolkit's topbar rebuilds.
 *  - The dialog ([showNewsDialog]): a "New update" box (Download opens the
 *    manifest's URL in the system browser, × dismisses that version), news
 *    cards (date, title, plain-text body, "Learn more", × dismisses), "You're
 *    all caught up" when empty, and a footer with Check now and Restore.
 *
 * Platform glue lives here too: [ElectronNewsStateStore]
 * (`noteApi.getNewsState` / `setNewsState` → `lunarbor-news.json`, owned by
 * the main process's NewsHost.kt); `news.json` is fetched by the common
 * Ktor fetcher (`createNewsFetcher`). [startNewsUpdates] returns `null` without an
 * Electron bridge, so the browser demo has no bell and fetches nothing.
 *
 * View only: no rules here beyond drawing [NewsUpdatesBackingViewModel.State].
 */

package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunarbor.newsupdates.createNewsFetcher
import se.soderbjorn.lunarbor.newsupdates.NewsItem
import se.soderbjorn.lunarbor.newsupdates.NewsPersistedState
import se.soderbjorn.lunarbor.newsupdates.NewsStateStore
import se.soderbjorn.lunarbor.newsupdates.NewsUpdatesBackingViewModel
import se.soderbjorn.lunarbor.newsupdates.UpdatePlatform
import se.soderbjorn.lunula.web.shell.TopbarAction
import kotlin.js.Date
import kotlin.js.Promise

/**
 * Builds and starts the app's news / update checker, and keeps the bell's
 * look in step with it.
 *
 * Called once by `Main.kt` before the shell renders; the result goes to
 * [AppShell] for the bell.
 *
 * @param scope the app scope.
 * @return the started view model, or `null` without the Electron bridge
 *   (browser demo): no bell, no fetch.
 */
fun startNewsUpdates(scope: CoroutineScope): NewsUpdatesBackingViewModel? {
    val noteApi = window.asDynamic().noteApi ?: return null
    if (noteApi.getNewsState == null) return null
    val versionName = (noteApi.appVersionName as? String)?.takeIf { it.isNotBlank() } ?: "0"
    val versionCode = (noteApi.appVersionCode as? String)?.toLongOrNull() ?: 0L
    val viewModel = NewsUpdatesBackingViewModel(
        store = ElectronNewsStateStore(),
        fetcher = createNewsFetcher(),
        platformId = UpdatePlatform.MAC,
        currentVersionCode = versionCode,
        currentVersionName = versionName,
        scope = scope,
        now = { Date.now().toLong() },
    )
    newsScope = scope
    ensureNewsStyles()
    scope.launch { viewModel.stateFlow.collect { refreshNewsBell(it) } }
    viewModel.start()
    return viewModel
}

/**
 * The topbar bell; a click opens [showNewsDialog] with the current state.
 * Added by [AppShell] next to Starred and the palette.
 *
 * @param viewModel the app's checker from [startNewsUpdates].
 */
internal fun newsTopbarAction(viewModel: NewsUpdatesBackingViewModel): TopbarAction = TopbarAction(
    id = "lunarbor-topbar-news",
    iconHtml = ICON_BELL,
    label = "News & updates",
    onActivate = { showNewsDialog(viewModel) },
)

/** Mirrors [state] onto the `<body>` attributes the bell's CSS reads. */
private fun refreshNewsBell(state: NewsUpdatesBackingViewModel.State) {
    val body = document.body ?: return
    if (state.hasContent) body.setAttribute("data-lunarbor-news", "1") else body.removeAttribute("data-lunarbor-news")
    if (state.hasNews) body.setAttribute("data-lunarbor-news-pulse", "1") else body.removeAttribute("data-lunarbor-news-pulse")
}

/**
 * Opens the News & updates dialog (one at a time). Closed by ×, the
 * backdrop or Escape. A snapshot of the state: Check now and Restore
 * rebuild it from the fresh state.
 *
 * @param viewModel the app's checker.
 */
internal fun showNewsDialog(viewModel: NewsUpdatesBackingViewModel) {
    if (document.querySelector(".lunarbor-news-backdrop") != null) return
    ensureNewsStyles()
    val state = viewModel.stateFlow.value
    val backdrop = el("div", "lunarbor-news-backdrop")
    val panel = el("div", "lunarbor-news-dialog")
    panel.setAttribute("role", "dialog")
    panel.setAttribute("aria-label", "News & updates")
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
    fun reopen() {
        close()
        showNewsDialog(viewModel)
    }

    val header = el("div", "lunarbor-news-head")
    val title = el("h2", "lunarbor-news-title", "News & updates")
    header.appendChild(title)
    val closeButton = button("×", "lunarbor-news-close") { close() }
    closeButton.title = "Close"
    header.appendChild(closeButton)
    panel.appendChild(header)

    val list = el("div", "lunarbor-news-list")
    panel.appendChild(list)

    val updateUrl = state.infoUrl
    if (state.updateAvailable && updateUrl != null) {
        val section = el("div", "lunarbor-news-section")
        section.appendChild(el("h3", "lunarbor-news-section-head", "New update"))
        val card = el("div", "lunarbor-news-card is-update")
        card.appendChild(
            el(
                "div", "lunarbor-news-card-body",
                state.latestVersionName?.let { "Lunarbor $it is available." } ?: "A new version is available.",
            ),
        )
        card.appendChild(button("Download", "lunarbor-news-button is-primary") { openExternalUrl(updateUrl) })
        card.appendChild(
            dismissButton("Dismiss this update") {
                collapse(card) {
                    viewModel.dismissUpdate()
                    section.remove()
                    refreshEmpty(list)
                }
            },
        )
        section.appendChild(card)
        list.appendChild(section)
    }

    if (state.newsItems.isNotEmpty()) {
        val section = el("div", "lunarbor-news-section")
        section.appendChild(el("h3", "lunarbor-news-section-head", "News"))
        for (item in state.newsItems) section.appendChild(newsCard(viewModel, list, section, item))
        list.appendChild(section)
    }
    refreshEmpty(list)

    val footer = el("div", "lunarbor-news-footer")
    if (state.checkNowAvailable) {
        val checkNow = button(if (state.checkInProgress) "Checking…" else "Check now", "lunarbor-news-button") {}
        checkNow.disabled = state.checkInProgress
        checkNow.addEventListener("click", { _: Event ->
            if (checkNow.disabled) return@addEventListener
            checkNow.disabled = true
            checkNow.textContent = "Checking…"
            newsScope?.launch {
                viewModel.checkNow()
                if (backdrop.isConnected) reopen()
            }
        })
        footer.appendChild(checkNow)
    }
    footer.appendChild(
        button("Restore dismissed", "lunarbor-news-button lunarbor-news-restore") {
            viewModel.restoreAll()
            reopen()
        },
    )
    panel.appendChild(footer)

    document.addEventListener("keydown", keyHandler, true)
    backdrop.addEventListener("mousedown", { e -> if (e.target === backdrop) close() })
    document.body?.appendChild(backdrop)
}

/** One news card: date, title, plain-text body, optional "Learn more", ×. */
private fun newsCard(
    viewModel: NewsUpdatesBackingViewModel,
    list: HTMLElement,
    section: HTMLElement,
    item: NewsItem,
): HTMLElement {
    val card = el("div", "lunarbor-news-card")
    item.date?.let { card.appendChild(el("div", "lunarbor-news-card-date", it)) }
    card.appendChild(el("h4", "lunarbor-news-card-title", item.title))
    // textContent: the body is plain text from the web, never HTML.
    card.appendChild(el("div", "lunarbor-news-card-body", item.body))
    item.url?.let { url -> card.appendChild(button("Learn more", "lunarbor-news-button") { openExternalUrl(url) }) }
    card.appendChild(
        dismissButton("Dismiss") {
            collapse(card) {
                viewModel.dismissNews(item.id)
                if (section.querySelector(".lunarbor-news-card") == null) section.remove()
                refreshEmpty(list)
            }
        },
    )
    return card
}

/** "You're all caught up" exactly when [list] holds no card. */
private fun refreshEmpty(list: HTMLElement) {
    val hasCards = list.querySelector(".lunarbor-news-card") != null
    val existing = list.querySelector(".lunarbor-news-empty")
    if (hasCards) {
        existing?.remove()
    } else if (existing == null) {
        list.appendChild(el("div", "lunarbor-news-empty", "You're all caught up"))
    }
}

/**
 * Plays the card's collapse transition, then runs [done] once. Without
 * transitions (reduced motion) [done] runs at once.
 */
private fun collapse(card: HTMLElement, done: () -> Unit) {
    if (card.classList.contains("is-dismissing")) return
    val reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches
    if (reduced) {
        card.remove()
        done()
        return
    }
    card.style.maxHeight = "${card.offsetHeight}px"
    card.getBoundingClientRect()
    card.classList.add("is-dismissing")
    var finished = false
    val finish = {
        if (!finished) {
            finished = true
            card.remove()
            done()
        }
    }
    card.addEventListener("transitionend", { e -> if (e.target === card) finish() })
    // A fallback in case no transitionend arrives (e.g. hidden window).
    window.setTimeout({ finish() }, 400)
}

/** Opens [url] in the system browser through the main process (https only). */
private fun openExternalUrl(url: String) {
    val noteApi = window.asDynamic().noteApi
    if (noteApi?.openExternalUrl != null) noteApi.openExternalUrl(url) else window.open(url, "_blank")
}

/** The app scope given to [startNewsUpdates]; the dialog's Check now runs on it. */
private var newsScope: CoroutineScope? = null

/**
 * [NewsStateStore] over the Electron bridge: `lunarbor-news.json`, owned
 * by the main process (NewsHost.kt). Unreadable state reads as fresh.
 */
private class ElectronNewsStateStore : NewsStateStore {
    override suspend fun load(): NewsPersistedState {
        val text = runCatching {
            (window.asDynamic().noteApi.getNewsState() as Promise<String?>).await()
        }.getOrNull() ?: return NewsPersistedState()
        return runCatching {
            val parsed: dynamic = JSON.parse<dynamic>(text)
            val ids = (parsed.dismissedNewsIds as? Array<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
            NewsPersistedState(
                dismissedNewsIds = ids,
                dismissedUpdateVersionCode = (parsed.dismissedUpdateVersionCode as? Number)?.toLong(),
                lastCheckEpochMillis = (parsed.lastCheckEpochMillis as? Number)?.toLong(),
            )
        }.getOrElse { NewsPersistedState() }
    }

    override suspend fun save(state: NewsPersistedState) {
        val obj: dynamic = js("({})")
        obj.dismissedNewsIds = state.dismissedNewsIds.toTypedArray()
        state.dismissedUpdateVersionCode?.let { obj.dismissedUpdateVersionCode = it.toDouble() }
        state.lastCheckEpochMillis?.let { obj.lastCheckEpochMillis = it.toDouble() }
        runCatching { (window.asDynamic().noteApi.setNewsState(JSON.stringify(obj)) as Promise<Any?>).await() }
    }
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

private fun dismissButton(label: String, onClick: () -> Unit): HTMLButtonElement =
    button("×", "lunarbor-news-dismiss", onClick).also {
        it.title = label
        it.setAttribute("aria-label", label)
    }

private fun ensureNewsStyles() {
    if (document.getElementById("lunarbor-news-style") != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = "lunarbor-news-style"
    style.textContent = NEWS_CSS
    document.head?.appendChild(style)
}

/** Lucide-style bell; `.lunarbor-news-bell` carries the muted / coloured / pulsing look. */
private const val ICON_BELL: String =
    """<svg class="lunarbor-news-bell" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>"""

private const val NEWS_CSS = """
.lunarbor-news-bell { color: var(--t-warn, #e0a82e); display: block; }
body:not([data-lunarbor-news="1"]) .lunarbor-news-bell { color: var(--t-chrome-text-dim, var(--t-text-dim, #8e8e93)); opacity: 0.6; }
body[data-lunarbor-news-pulse="1"] .lunarbor-news-bell { animation: lunarbor-news-pulse 2.5s ease-in-out infinite; }
@keyframes lunarbor-news-pulse {
    0%, 100% { color: var(--t-warn, #e0a82e); opacity: 1; }
    50% { color: color-mix(in srgb, var(--t-warn, #e0a82e) 40%, transparent); opacity: 0.55; }
}
@media (prefers-reduced-motion: reduce) {
    body[data-lunarbor-news-pulse="1"] .lunarbor-news-bell { animation: none; }
}
.lunarbor-news-backdrop {
    position: fixed; inset: 0; z-index: 2147483640; background: rgba(0, 0, 0, 0.45);
    display: flex; align-items: flex-start; justify-content: center; padding: 8vh 16px 16px;
}
.lunarbor-news-dialog {
    width: min(620px, 100%); height: min(560px, 84vh); display: flex; flex-direction: column;
    background: var(--t-bg, #1e1e1e); color: var(--t-text, #e6e6e6);
    border: 1px solid var(--t-border, rgba(255,255,255,0.12)); border-radius: 12px;
    box-shadow: 0 28px 72px rgba(0, 0, 0, 0.45), 0 10px 24px rgba(0, 0, 0, 0.30);
    font-size: 13px; line-height: 1.45;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
}
.lunarbor-news-head {
    display: flex; align-items: center; justify-content: space-between; gap: 8px;
    padding: 14px 16px 10px; border-bottom: 1px solid var(--t-border, rgba(255,255,255,0.12));
}
.lunarbor-news-title { margin: 0; font-size: 16px; font-weight: 700; }
.lunarbor-news-close, .lunarbor-news-dismiss {
    flex: 0 0 auto; width: 24px; height: 24px; padding: 0; border: 0; border-radius: 6px;
    background: transparent; color: var(--t-text-dim, #9a9a9a); font-size: 18px; line-height: 1; cursor: pointer;
}
.lunarbor-news-close:hover, .lunarbor-news-dismiss:hover {
    color: var(--t-text, #e6e6e6); background: color-mix(in srgb, var(--t-text, #e6e6e6) 10%, transparent);
}
.lunarbor-news-list { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 14px 16px; display: flex; flex-direction: column; gap: 18px; }
.lunarbor-news-section { display: flex; flex-direction: column; gap: 10px; }
.lunarbor-news-section-head { margin: 0; font-size: 14px; font-weight: 700; color: var(--t-text-bright, var(--t-text, #e6e6e6)); }
.lunarbor-news-card {
    position: relative; display: flex; flex-direction: column; align-items: flex-start; gap: 6px;
    padding: 12px 40px 12px 14px; max-height: 600px; overflow: hidden;
    border: 1px solid var(--t-border, rgba(255,255,255,0.12)); border-radius: 8px;
    background: var(--t-surface, color-mix(in srgb, var(--t-text, #e6e6e6) 3%, transparent));
    transition: opacity 160ms ease, transform 160ms ease, max-height 220ms ease, padding 220ms ease, margin 220ms ease, border-width 220ms ease;
}
.lunarbor-news-card.is-update { border-color: color-mix(in srgb, var(--t-accent) 55%, transparent); }
.lunarbor-news-card.is-dismissing {
    opacity: 0; transform: translateX(16px); max-height: 0 !important;
    padding-top: 0; padding-bottom: 0; border-top-width: 0; border-bottom-width: 0; margin-bottom: -10px;
}
.lunarbor-news-dismiss { position: absolute; top: 8px; right: 8px; }
.lunarbor-news-card-date { font-size: 11px; color: var(--t-text-dim, #9a9a9a); }
.lunarbor-news-card-title { margin: 0; font-size: 15px; font-weight: 700; line-height: 1.3; }
.lunarbor-news-card-body { white-space: pre-wrap; user-select: text; }
.lunarbor-news-empty {
    flex: 1; display: flex; align-items: center; justify-content: center; min-height: 120px;
    color: var(--t-text-dim, #9a9a9a); font-size: 14px;
}
.lunarbor-news-footer {
    display: flex; align-items: center; gap: 8px; padding: 10px 16px 14px;
    border-top: 1px solid var(--t-border, rgba(255,255,255,0.12));
}
.lunarbor-news-restore { margin-left: auto; }
.lunarbor-news-button {
    flex: 0 0 auto; margin-top: 2px; padding: 4px 12px; font: inherit; font-size: 12px; font-weight: 600;
    color: var(--t-text, #e6e6e6); background: transparent;
    border: 1px solid var(--t-border, rgba(255,255,255,0.12)); border-radius: 6px; cursor: pointer;
}
.lunarbor-news-button:hover { background: color-mix(in srgb, var(--t-text, #e6e6e6) 8%, transparent); }
.lunarbor-news-button:disabled { opacity: 0.6; cursor: default; }
.lunarbor-news-button.is-primary { color: var(--t-accent-on, #fff); background: var(--t-accent); border-color: var(--t-accent); }
.lunarbor-news-button.is-primary:hover { background: color-mix(in srgb, var(--t-accent) 85%, #fff); }
"""
