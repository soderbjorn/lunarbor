/*
 * NewsUpdatesBackingViewModel.kt (commonMain)
 * -------------------------------------------
 * All the logic behind the desktop's "News & updates" bell, ported from
 * Lunamux so both apps behave the same:
 *
 *  - Checks `versions.json` and `news.json` on lunarbor.dev at startup when
 *    24 h have passed since the last successful check (persisted across
 *    launches), then every 24 h while the app runs; "Check now" on demand.
 *  - An update shows when the published `latestVersionCode` is above the
 *    running build's and is not the exact code the user dismissed.
 *  - News shows the items that are `active` and not dismissed.
 *  - A check where both fetches fail changes nothing (the timestamp is not
 *    advanced either).
 *  - Restore brings every dismissed item back from the last fetched
 *    manifests (works offline).
 *
 * One app-scoped instance, built in the web `Main.kt` only when there is an
 * Electron bridge (the browser demo has no bell). The view
 * (`web/.../main/NewsUpdates.kt`) renders [State] and calls the intents.
 * Persistence, fetching and the clock are constructor parameters
 * ([NewsStateStore], [NewsFetcher], `now`).
 *
 * commonMain only — no platform imports.
 */

package se.soderbjorn.lunarbor.newsupdates

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the version manifest is published. */
const val DEFAULT_VERSIONS_URL: String = "https://lunarbor.dev/versions.json"

/** Where the news manifest is published. */
const val DEFAULT_NEWS_URL: String = "https://lunarbor.dev/news.json"

/** How often to check while the app runs, and the startup gate: 24 h. */
const val CHECK_INTERVAL_MILLIS: Long = 24L * 60L * 60L * 1000L

/**
 * Owns the news / update check and exposes its result as [stateFlow].
 *
 * ### Callers
 * - Web `Main.kt` (`startNewsUpdates`): constructs it and calls [start].
 * - Web news dialog: [requestCheckNow] / [checkNow], [dismissNews],
 *   [dismissUpdate], [restoreAll].
 *
 * @param store persistence of [NewsPersistedState].
 * @param fetcher HTTP GET of a manifest's text.
 * @param platformId the [VersionManifest.platforms] key to read
 *   ([UpdatePlatform.MAC]).
 * @param currentVersionCode the running build's version code
 *   (`CFBundleVersion`); `0` when unknown, so any published build is newer.
 * @param currentVersionName the running build's version name, for logs.
 * @param scope where the check loop and saves run (app-scoped).
 * @param now wall-clock time in epoch millis.
 * @param versionsUrl the `versions.json` URL.
 * @param newsUrl the `news.json` URL.
 * @param checkIntervalMillis re-check interval and startup gate.
 */
class NewsUpdatesBackingViewModel(
    private val store: NewsStateStore,
    private val fetcher: NewsFetcher,
    private val platformId: String,
    private val currentVersionCode: Long,
    private val currentVersionName: String,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val versionsUrl: String = DEFAULT_VERSIONS_URL,
    private val newsUrl: String = DEFAULT_NEWS_URL,
    private val checkIntervalMillis: Long = CHECK_INTERVAL_MILLIS,
) {
    /**
     * What the bell and the dialog show.
     *
     * @property updateAvailable a newer build than the running one is
     *   published and not dismissed.
     * @property latestVersionName that build's name; `null` without an update.
     * @property infoUrl that build's download page; `null` without an update.
     * @property newsItems active, undismissed news, in manifest order.
     * @property lastCheckEpochMillis the last successful check, or `null`.
     * @property checkInProgress a check is running (Check now reads "Checking…").
     */
    data class State(
        val updateAvailable: Boolean = false,
        val latestVersionName: String? = null,
        val infoUrl: String? = null,
        val newsItems: List<NewsItem> = emptyList(),
        val lastCheckEpochMillis: Long? = null,
        val checkInProgress: Boolean = false,
    ) {
        /** The bell is coloured: there is an update or news. */
        val hasContent: Boolean get() = updateAvailable || newsItems.isNotEmpty()

        /** The bell pulses: there is news (an update alone does not pulse). */
        val hasNews: Boolean get() = newsItems.isNotEmpty()

        /** The dialog shows Check now ([CHECK_NOW_BUTTON_ENABLED]). */
        val checkNowAvailable: Boolean get() = CHECK_NOW_BUTTON_ENABLED
    }

    private val _stateFlow = MutableStateFlow(State())

    /** The current state; the bell and the dialog observe it. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private var started = false
    private var persisted = NewsPersistedState()
    private val saveMutex = Mutex()

    /** The advertised update's code, kept so [dismissUpdate] can record it. */
    private var latestUpdateVersionCode: Long? = null

    /** The last fetched manifests, re-applied by [restoreAll] without a fetch. */
    private var lastVersions: VersionManifest? = null
    private var lastNews: NewsManifest? = null

    /**
     * Loads the persisted state and starts the check loop: the first check
     * right away when [checkIntervalMillis] has passed since the last one
     * (or there was none), otherwise when it will have; then one every
     * [checkIntervalMillis]. Idempotent.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            persisted = store.load()
            _stateFlow.update { it.copy(lastCheckEpochMillis = persisted.lastCheckEpochMillis) }
            val initialDelay = if (CHECK_ON_EVERY_STARTUP) 0L else initialDelayMillis(persisted.lastCheckEpochMillis)
            println(
                "NewsUpdates: platform=$platformId code=$currentVersionCode name=$currentVersionName; " +
                    "first check in ${initialDelay}ms, then every ${checkIntervalMillis}ms",
            )
            if (initialDelay > 0L) delay(initialDelay)
            while (true) {
                checkNow()
                delay(checkIntervalMillis)
            }
        }
    }

    /**
     * Fetches both manifests and applies them. Ignored while another check
     * runs; when both fetches fail nothing changes. Never throws (except
     * cancellation).
     *
     * Called by the loop in [start], by [requestCheckNow], and by the
     * dialog's Check now (which awaits it to redraw from the result).
     */
    suspend fun checkNow() {
        if (_stateFlow.value.checkInProgress) return
        _stateFlow.update { it.copy(checkInProgress = true) }
        try {
            val versions = fetchVersions()
            val news = fetchNews()
            if (versions == null && news == null) {
                println("NewsUpdates: both checks failed — state unchanged")
                return
            }
            val time = now()
            persisted = persisted.copy(lastCheckEpochMillis = time)
            _stateFlow.update { it.copy(lastCheckEpochMillis = time) }
            persist()
            if (versions != null) {
                lastVersions = versions
                applyVersions(versions)
            }
            if (news != null) {
                lastNews = news
                applyNews(news)
            }
        } finally {
            _stateFlow.update { it.copy(checkInProgress = false) }
        }
    }

    /** Fire-and-forget [checkNow] on the view model's scope. */
    fun requestCheckNow() {
        scope.launch { checkNow() }
    }

    /**
     * Hides a news item for good: recorded in the persisted set and dropped
     * from [State.newsItems] at once.
     *
     * @param id the [NewsItem.id].
     */
    fun dismissNews(id: String) {
        if (id in persisted.dismissedNewsIds) return
        persisted = persisted.copy(dismissedNewsIds = persisted.dismissedNewsIds + id)
        _stateFlow.update { s -> s.copy(newsItems = s.newsItems.filterNot { it.id == id }) }
        scope.launch { persist() }
    }

    /**
     * Hides the advertised update until a newer build is published. No-op
     * when none is advertised or it is already dismissed.
     */
    fun dismissUpdate() {
        val code = latestUpdateVersionCode ?: return
        if (code == persisted.dismissedUpdateVersionCode) return
        persisted = persisted.copy(dismissedUpdateVersionCode = code)
        _stateFlow.update { it.copy(updateAvailable = false, latestVersionName = null, infoUrl = null) }
        scope.launch { persist() }
    }

    /**
     * Brings back everything dismissed. Re-applies the last fetched
     * manifests at once (offline); with none fetched yet, starts a check.
     */
    fun restoreAll() {
        persisted = persisted.copy(dismissedNewsIds = emptySet(), dismissedUpdateVersionCode = null)
        scope.launch { persist() }
        val versions = lastVersions
        val news = lastNews
        if (versions == null && news == null) {
            requestCheckNow()
            return
        }
        versions?.let { applyVersions(it) }
        news?.let { applyNews(it) }
    }

    private fun applyVersions(manifest: VersionManifest) {
        val info = manifest.platforms[platformId]
            ?.takeIf { manifest.schemaVersion <= VersionManifest.SUPPORTED_SCHEMA_VERSION }
        latestUpdateVersionCode = info?.latestVersionCode
        val available = info != null &&
            info.latestVersionCode > currentVersionCode &&
            info.latestVersionCode != persisted.dismissedUpdateVersionCode
        _stateFlow.update {
            it.copy(
                updateAvailable = available,
                latestVersionName = if (available) info?.latestVersionName else null,
                infoUrl = if (available) info?.url else null,
            )
        }
    }

    private fun applyNews(manifest: NewsManifest) {
        val items = if (manifest.schemaVersion > NewsManifest.SUPPORTED_SCHEMA_VERSION) {
            emptyList()
        } else {
            manifest.items.filter { it.active && it.id !in persisted.dismissedNewsIds }
        }
        _stateFlow.update { it.copy(newsItems = items) }
    }

    private suspend fun fetchVersions(): VersionManifest? {
        if (USE_SAMPLE_DATA) return SAMPLE_VERSION_MANIFEST
        val text = runCatching { fetcher.fetchText(versionsUrl) }.getOrNull() ?: return null
        return NewsManifestParser.parseVersions(text)
    }

    private suspend fun fetchNews(): NewsManifest? {
        if (USE_SAMPLE_DATA) return SAMPLE_NEWS_MANIFEST
        val text = runCatching { fetcher.fetchText(newsUrl) }.getOrNull() ?: return null
        return NewsManifestParser.parseNews(text)
    }

    /** Saves the current [persisted] snapshot; serialized so the last write wins. */
    private suspend fun persist() {
        saveMutex.withLock { store.save(persisted) }
    }

    private fun initialDelayMillis(lastCheck: Long?): Long {
        val since = lastCheck?.let { now() - it } ?: return 0L
        if (since >= checkIntervalMillis || since < 0L) return 0L
        return checkIntervalMillis - since
    }
}
