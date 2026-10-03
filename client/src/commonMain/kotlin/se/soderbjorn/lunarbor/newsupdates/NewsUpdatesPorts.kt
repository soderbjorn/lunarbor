/*
 * NewsUpdatesPorts.kt (commonMain)
 * --------------------------------
 * What [NewsUpdatesBackingViewModel] needs from its platform, as plain
 * interfaces so the view model stays annotation-free and testable:
 *
 *  - [NewsStateStore]: where the bell's state lives between launches
 *    ([NewsPersistedState]). On the desktop that is `lunarbor-news.json`
 *    beside `lunarbor-backup.json`, owned by the Electron main process —
 *    app-scoped, not in the vault, so a vault switch keeps it.
 *  - [NewsFetcher]: one HTTP GET of `news.json`'s text; the app's is
 *    Ktor-based ([createNewsFetcher], NewsHttp.kt).
 *
 * Also the dev toggles ([USE_SAMPLE_DATA], [CHECK_ON_EVERY_STARTUP]; never
 * commit them as `true`) and the shipping flag [CHECK_NOW_BUTTON_ENABLED].
 *
 * commonMain only — no platform imports.
 */

package se.soderbjorn.lunarbor.newsupdates

/**
 * The bell's persisted state.
 *
 * @property dismissedNewsIds [NewsItem.id]s the user closed.
 * @property dismissedUpdateVersionCode the `latestVersionCode` whose update
 *   box the user closed (a higher one shows again), or `null`.
 * @property lastCheckEpochMillis when the last successful check ran, or
 *   `null` before the first; gates the startup check to once per 24 h.
 */
data class NewsPersistedState(
    val dismissedNewsIds: Set<String> = emptySet(),
    val dismissedUpdateVersionCode: Long? = null,
    val lastCheckEpochMillis: Long? = null,
)

/**
 * Loads and saves [NewsPersistedState].
 *
 * Implemented on the web by `ElectronNewsStateStore` (IPC to the main
 * process); tests use an in-memory one.
 */
interface NewsStateStore {
    /** @return the stored state, or the default when nothing is stored or it cannot be read. */
    suspend fun load(): NewsPersistedState

    /** Replaces the stored state. Failures are swallowed by the implementation. */
    suspend fun save(state: NewsPersistedState)
}

/**
 * Fetches the news feed.
 *
 * The app's comes from [createNewsFetcher] (Ktor); tests return canned
 * text.
 */
fun interface NewsFetcher {
    /**
     * @param url an absolute `https:` URL.
     * @return the body of a successful (2xx) response, or `null` on any
     *   network, timeout or HTTP error.
     */
    suspend fun fetchText(url: String): String?
}

/**
 * When `true` the "News & updates" dialog shows a Check now button.
 * Read only through [NewsUpdatesBackingViewModel.State.checkNowAvailable].
 */
const val CHECK_NOW_BUTTON_ENABLED: Boolean = true

/**
 * Dev toggle: skip the network and use [SAMPLE_NEWS_FEED]. Never commit
 * as `true`.
 */
const val USE_SAMPLE_DATA: Boolean = false

/**
 * Dev toggle: check at every startup, ignoring the 24 h gate. Never
 * commit as `true`.
 */
const val CHECK_ON_EVERY_STARTUP: Boolean = false

/** Stand-in for `news.json` under [USE_SAMPLE_DATA]: always an update, two items. */
val SAMPLE_NEWS_FEED: NewsFeed = NewsFeed(
    platforms = mapOf(
        UpdatePlatform.MAC to PlatformVersionInfo(
            latestVersionCode = 999_999L,
            latestVersionName = "99.0.0 (sample)",
            url = "https://lunarbor.dev/",
        ),
    ),
    items = listOf(
        NewsItem(
            id = "sample-welcome",
            active = true,
            date = "2026-10-03",
            title = "Welcome to Lunarbor news",
            body = "Short announcements show up here. Close a card to dismiss it for good.",
            url = "https://lunarbor.dev/",
        ),
        NewsItem(
            id = "sample-depth",
            active = true,
            title = "An undated item",
            body = "Items without a date leave the date line out.\nLine breaks are kept.",
        ),
    ),
)
