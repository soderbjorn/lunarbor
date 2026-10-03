/*
 * NewsUpdatesBackingViewModelTest.kt (commonTest)
 * -----------------------------------------------
 * The "News & updates" rules: the 24 h startup gate, update comparison
 * and dismissal, news filtering, Restore, a total failure changing
 * nothing, newer schema versions, and lenient manifest parsing.
 */

package se.soderbjorn.lunarbor.newsupdates

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsUpdatesBackingViewModelTest {

    private class MemoryStore(var state: NewsPersistedState = NewsPersistedState()) : NewsStateStore {
        override suspend fun load(): NewsPersistedState = state
        override suspend fun save(state: NewsPersistedState) {
            this.state = state
        }
    }

    private class FakeFetcher(var versions: String?, var news: String?) : NewsFetcher {
        var calls = 0
        override suspend fun fetchText(url: String): String? {
            calls++
            return if (url == DEFAULT_VERSIONS_URL) versions else news
        }
    }

    private fun versionsJson(code: Long, schema: Int = 1) = """
        {
          "schemaVersion": $schema,
          // the Mac build
          "platforms": { "mac": { "latestVersionCode": $code, "latestVersionName": "0.$code.0", "url": "https://lunarbor.dev/#download", } },
        }
    """.trimIndent()

    private val newsJson = """
        { "schemaVersion": 1, "items": [
          { "id": "a", "active": true, "date": "2026-10-01", "title": "A", "body": "Body A", "url": "https://lunarbor.dev/a" },
          { "id": "b", "active": false, "title": "B", "body": "Body B" },
          { "id": "c", "title": "C", "body": "no active flag" },
          { "id": "d", "active": true, "title": "D", "body": "Body D", "extra": 1 },
          /* { "id": "e", "active": true, "title": "E", "body": "commented out" }, */
        ] }
    """.trimIndent()

    private var clock = 1_000_000_000L

    private fun TestScope.vm(
        store: NewsStateStore,
        fetcher: NewsFetcher,
        currentCode: Long = 5,
        scope: CoroutineScope = backgroundScope,
    ) = NewsUpdatesBackingViewModel(
        store = store,
        fetcher = fetcher,
        platformId = UpdatePlatform.MAC,
        currentVersionCode = currentCode,
        currentVersionName = "0.5.0",
        scope = scope,
        now = { clock },
    )

    @Test
    fun parses_leniently_and_keeps_only_active_items() = runTest {
        val store = MemoryStore()
        val vm = vm(store, FakeFetcher(versionsJson(7), newsJson))
        vm.checkNow()
        val s = vm.stateFlow.value
        assertEquals(listOf("a", "d"), s.newsItems.map { it.id })
        assertTrue(s.updateAvailable)
        assertEquals("0.7.0", s.latestVersionName)
        assertEquals("https://lunarbor.dev/#download", s.infoUrl)
        assertTrue(s.hasContent)
        assertTrue(s.hasNews)
        assertEquals(clock, store.state.lastCheckEpochMillis)
    }

    @Test
    fun no_update_when_running_build_is_current() = runTest {
        val vm = vm(MemoryStore(), FakeFetcher(versionsJson(5), """{ "items": [] }"""))
        vm.checkNow()
        val s = vm.stateFlow.value
        assertFalse(s.updateAvailable)
        assertNull(s.infoUrl)
        assertFalse(s.hasContent)
    }

    @Test
    fun dismissed_update_stays_hidden_until_a_newer_build() = runTest {
        val store = MemoryStore()
        val fetcher = FakeFetcher(versionsJson(7), null)
        val vm = vm(store, fetcher)
        vm.checkNow()
        vm.dismissUpdate()
        runCurrent()
        assertFalse(vm.stateFlow.value.updateAvailable)
        assertEquals(7L, store.state.dismissedUpdateVersionCode)

        vm.checkNow()
        assertFalse(vm.stateFlow.value.updateAvailable)

        fetcher.versions = versionsJson(8)
        vm.checkNow()
        assertTrue(vm.stateFlow.value.updateAvailable)
        assertEquals("0.8.0", vm.stateFlow.value.latestVersionName)
    }

    @Test
    fun dismissed_news_is_persisted_and_restore_brings_everything_back() = runTest {
        val store = MemoryStore()
        val vm = vm(store, FakeFetcher(versionsJson(7), newsJson))
        vm.checkNow()
        vm.dismissNews("a")
        vm.dismissUpdate()
        runCurrent()
        assertEquals(listOf("d"), vm.stateFlow.value.newsItems.map { it.id })
        assertEquals(setOf("a"), store.state.dismissedNewsIds)

        vm.restoreAll()
        runCurrent()
        assertEquals(listOf("a", "d"), vm.stateFlow.value.newsItems.map { it.id })
        assertTrue(vm.stateFlow.value.updateAvailable)
        assertEquals(emptySet(), store.state.dismissedNewsIds)
        assertNull(store.state.dismissedUpdateVersionCode)
    }

    @Test
    fun dismissals_survive_a_restart() = runTest {
        val store = MemoryStore(NewsPersistedState(dismissedNewsIds = setOf("a"), dismissedUpdateVersionCode = 7))
        val vm = vm(store, FakeFetcher(versionsJson(7), newsJson))
        vm.start()
        runCurrent()
        val s = vm.stateFlow.value
        assertEquals(listOf("d"), s.newsItems.map { it.id })
        assertFalse(s.updateAvailable)
    }

    @Test
    fun total_failure_changes_nothing() = runTest {
        val store = MemoryStore(NewsPersistedState(lastCheckEpochMillis = 42))
        val fetcher = FakeFetcher(versionsJson(7), newsJson)
        val vm = vm(store, fetcher)
        vm.checkNow()
        val before = vm.stateFlow.value
        clock += 1000
        fetcher.versions = null
        fetcher.news = "not json"
        vm.checkNow()
        assertEquals(before, vm.stateFlow.value)
        assertEquals(before.lastCheckEpochMillis, store.state.lastCheckEpochMillis)
    }

    @Test
    fun newer_schema_versions_read_as_nothing() = runTest {
        val vm = vm(
            MemoryStore(),
            FakeFetcher(versionsJson(7, schema = 2), """{ "schemaVersion": 2, "items": [ { "id": "a", "active": true, "title": "A", "body": "B" } ] }"""),
        )
        vm.checkNow()
        val s = vm.stateFlow.value
        assertFalse(s.updateAvailable)
        assertTrue(s.newsItems.isEmpty())
        assertFalse(s.hasContent)
    }

    @Test
    fun startup_checks_at_once_after_24h_and_otherwise_waits() = runTest {
        // Last check 23 h ago: the first check waits the remaining hour.
        val recent = FakeFetcher(versionsJson(7), newsJson)
        val vm = vm(MemoryStore(NewsPersistedState(lastCheckEpochMillis = clock - 23 * HOUR)), recent)
        vm.start()
        runCurrent()
        assertEquals(0, recent.calls)
        advanceTimeBy(HOUR + 1)
        assertEquals(2, recent.calls)
        // Then every 24 h.
        advanceTimeBy(24 * HOUR)
        assertEquals(4, recent.calls)

        // Last check 25 h ago: checks at once.
        val stale = FakeFetcher(versionsJson(7), newsJson)
        vm(MemoryStore(NewsPersistedState(lastCheckEpochMillis = clock - 25 * HOUR)), stale).start()
        runCurrent()
        assertEquals(2, stale.calls)

        // Never checked: checks at once.
        val fresh = FakeFetcher(versionsJson(7), newsJson)
        vm(MemoryStore(), fresh).start()
        runCurrent()
        assertEquals(2, fresh.calls)
    }

    @Test
    fun start_is_idempotent() = runTest {
        val fetcher = FakeFetcher(versionsJson(7), newsJson)
        val vm = vm(MemoryStore(), fetcher)
        vm.start()
        vm.start()
        runCurrent()
        assertEquals(2, fetcher.calls)
    }

    private companion object {
        const val HOUR = 60L * 60L * 1000L
    }
}
