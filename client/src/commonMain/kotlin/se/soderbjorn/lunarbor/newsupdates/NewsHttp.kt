/*
 * NewsHttp.kt (commonMain)
 * ------------------------
 * The production [NewsFetcher]: a Ktor [HttpClient] GET of `news.json`,
 * as Lunamux fetches its manifests. Each target supplies its engine via
 * [newsHttpClient] (the browser's `fetch` on JS / Wasm — the Electron
 * renderer — OkHttp on Android, Darwin on iOS); the timeouts are shared.
 *
 * Ktor stays behind the [NewsFetcher] port: callers get one from
 * [createNewsFetcher], tests pass a fake, and the `:web` module needs no
 * Ktor dependency of its own.
 */

package se.soderbjorn.lunarbor.newsupdates

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * This target's Ktor client for the news check, configured with
 * [configureNewsClient]. Kept for the app's lifetime by [createNewsFetcher].
 */
internal expect fun newsHttpClient(): HttpClient

/**
 * Settings every target's client shares: fail fast (8 s to connect, 15 s
 * for the whole request) instead of hanging the check loop.
 *
 * @param config the target's client builder; changed in place.
 */
internal fun configureNewsClient(config: HttpClientConfig<*>) {
    config.install(HttpTimeout) {
        connectTimeoutMillis = 8_000
        requestTimeoutMillis = 15_000
    }
    config.expectSuccess = false
}

/**
 * The fetcher [NewsUpdatesBackingViewModel] uses in the app. Called once
 * by the web `startNewsUpdates`.
 */
fun createNewsFetcher(): NewsFetcher = KtorNewsFetcher(newsHttpClient())

/** [NewsFetcher] over a Ktor [client]: the body of a 2xx response, else `null`. */
private class KtorNewsFetcher(private val client: HttpClient) : NewsFetcher {
    override suspend fun fetchText(url: String): String? = try {
        val response = client.get(url)
        if (response.status.isSuccess()) {
            response.bodyAsText()
        } else {
            println("NewsUpdates: $url → HTTP ${response.status}")
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        println("NewsUpdates: $url failed: ${t.message ?: t::class.simpleName}")
        null
    }
}
