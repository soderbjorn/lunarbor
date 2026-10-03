/*
 * NewsFeed.kt (commonMain)
 * ------------------------
 * The one file the desktop app checks for news and updates,
 * `https://lunarbor.dev/news.json` (hosted in `lunarbor-www`), and its
 * parser. It holds both halves:
 *
 *  - `platforms`: per platform, the latest published build
 *    (`latestVersionCode`, `latestVersionName`, `url`).
 *  - `items`: short announcements, each with an opaque never-reused `id`
 *    and an `active` flag.
 *
 * Both keep Lunamux's shapes (its `versions.json` and `news.json`), merged
 * into one file so a check is one request and a release edits one file.
 *
 * Parsing is lenient ([NewsFeedParser]: comments, trailing commas and
 * unknown keys are fine, so an item can be commented out); a malformed
 * platform entry or item is skipped rather than failing the file. A
 * `schemaVersion` newer than this build understands is read as "nothing"
 * by [NewsUpdatesBackingViewModel].
 *
 * commonMain only — no platform imports.
 */

package se.soderbjorn.lunarbor.newsupdates

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Keys under [NewsFeed.platforms]; the desktop app reads [MAC]. */
object UpdatePlatform {
    /** The macOS Electron app. */
    const val MAC: String = "mac"
}

/**
 * The shape of `news.json`.
 *
 * @property schemaVersion format version; one newer than
 *   [SUPPORTED_SCHEMA_VERSION] means "no update, no news".
 * @property platforms an [UpdatePlatform] key → that platform's latest build.
 * @property items the announcements, shown in this order.
 */
data class NewsFeed(
    val schemaVersion: Int = 1,
    val platforms: Map<String, PlatformVersionInfo> = emptyMap(),
    val items: List<NewsItem> = emptyList(),
) {
    companion object {
        /** The highest [schemaVersion] this build knows how to read. */
        const val SUPPORTED_SCHEMA_VERSION: Int = 1
    }
}

/**
 * The latest published build for one platform.
 *
 * @property latestVersionCode monotonic build number (`CFBundleVersion` on
 *   the Mac), compared against the running build's.
 * @property latestVersionName the version shown to the user, e.g. `0.2.0`.
 * @property url the download page opened by the update box's Download.
 */
data class PlatformVersionInfo(
    val latestVersionCode: Long,
    val latestVersionName: String,
    val url: String,
)

/**
 * One announcement.
 *
 * @property id opaque, never reused (date-prefixed by convention); the key
 *   of the persisted dismissed set.
 * @property active shown only when `true` (the default is `false`, so a
 *   malformed entry stays hidden); set `false` to retire it for everyone.
 * @property date optional `YYYY-MM-DD`, display only.
 * @property title the card's headline.
 * @property body plain text, shown as written (never as HTML).
 * @property url optional "Learn more" link, opened in the system browser.
 */
data class NewsItem(
    val id: String,
    val active: Boolean = false,
    val date: String? = null,
    val title: String,
    val body: String,
    val url: String? = null,
)

/**
 * Lenient parser for `news.json`.
 *
 * Called by [NewsUpdatesBackingViewModel] after each fetch, and by tests.
 */
object NewsFeedParser {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        allowComments = true
        allowTrailingComma = true
        isLenient = true
    }

    /**
     * Parses `news.json`.
     *
     * @param text the file's text.
     * @return the feed, or `null` when the text is not a JSON object.
     *   Platform entries missing a field, and items without an `id`,
     *   `title` or `body`, are left out; a missing `platforms` or `items`
     *   reads as empty.
     */
    fun parse(text: String): NewsFeed? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val platforms = (root["platforms"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
            val entry = value as? JsonObject ?: return@mapNotNull null
            val code = entry.long("latestVersionCode") ?: return@mapNotNull null
            val name = entry.string("latestVersionName") ?: return@mapNotNull null
            val url = entry.string("url") ?: return@mapNotNull null
            key to PlatformVersionInfo(code, name, url)
        }.toMap()
        val items = (root["items"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            NewsItem(
                id = item.string("id") ?: return@mapNotNull null,
                active = (item["active"] as? JsonPrimitive)?.booleanOrNull ?: false,
                date = item.string("date"),
                title = item.string("title") ?: return@mapNotNull null,
                body = item.string("body") ?: return@mapNotNull null,
                url = item.string("url")?.takeIf { it.isNotBlank() },
            )
        }
        return NewsFeed(schemaVersion = root.int("schemaVersion") ?: 1, platforms = platforms, items = items)
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private fun JsonObject.string(key: String): String? =
        primitive(key)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.long(key: String): Long? = primitive(key)?.longOrNull

    private fun JsonObject.int(key: String): Int? = primitive(key)?.intOrNull

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
