/*
 * Privacy.kt (commonMain)
 * -----------------------
 * Privacy modes (LBR-10): named lists of tags. While a mode is on, every
 * item whose own line carries one of its tags — and everything under it,
 * at any depth and across files — is left out of everything the app shows:
 * the outline, search, link search, folder contents, previews, agents.
 *
 * A view filter, not security: files on disk are untouched and
 * unencrypted, and switching back to "No privacy" needs no password.
 *
 * This file holds the model only: [PrivacyMode], the vault's
 * `_privacy.config` file ([PrivacyConfig]) and the tag matcher
 * ([PrivacyFilter]). Which rows and paths a filter hides is decided by
 * `PrivacyLayout` (rows of an open document) and `TextIndex.isPathHidden`
 * (paths, across files); `DocumentRegistry` owns the modes and the app's
 * current one.
 *
 * commonMain only — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.lunarbor.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.random.Random

/**
 * One privacy mode.
 *
 * @property id Stable id, kept across renames — what agent connections
 *   store as their privacy scope.
 * @property name What the privacy dialog shows;
 *   unique among the modes (case-insensitive), never empty.
 * @property tags The tags it hides, each without its `#`, as the user
 *   typed them (matching ignores case: [PrivacyConfig.tagKey]).
 */
data class PrivacyMode(val id: String, val name: String, val tags: List<String>) {
    /** The filter this mode applies. */
    val filter: PrivacyFilter get() = PrivacyFilter.of(tags)
}

/**
 * The tags a privacy mode hides, normalized ([PrivacyConfig.tagKey]).
 * [NONE] hides nothing.
 *
 * Matching is on whole tags, case-insensitive: `private` hides `#Private`
 * but not `#privateer`.
 *
 * @property tagKeys Normalized tag names, without `#`.
 */
data class PrivacyFilter(val tagKeys: Set<String>) {
    /** `true` when this filter hides anything at all. */
    val isActive: Boolean get() = tagKeys.isNotEmpty()

    /** `true` when any of the normalized tag names [keys] is one this filter hides. */
    fun hides(keys: Collection<String>): Boolean =
        isActive && keys.any { it in tagKeys }

    companion object {
        /** Hides nothing: "No privacy". */
        val NONE: PrivacyFilter = PrivacyFilter(emptySet())

        /** The filter hiding [tags] (with or without `#`, any case). */
        fun of(tags: Collection<String>): PrivacyFilter =
            PrivacyFilter(tags.map(PrivacyConfig::tagKey).filter { it.isNotEmpty() }.toSet())
    }
}

/**
 * The vault's privacy modes file, `<vault>/[FILE_NAME]`: JSON,
 * `{ "version": 1, "modes": [ { "id", "name", "tags": [ … ] } ] }`.
 *
 * It is an app file (`NoteRepository.isAppFile`): never shown in the
 * folder contents list, search, link search or to agents.
 */
object PrivacyConfig {
    /** Vault-relative name of the file, at the vault root. */
    const val FILE_NAME: String = "_privacy.config"

    private val json = Json { prettyPrint = true }

    /**
     * The modes in [text]; empty for `null`, unreadable text or a file
     * from a newer version. Modes without an id or a name are skipped, and
     * a name already taken (case-insensitive) or an id seen before drops
     * the later mode, so the result always meets [PrivacyMode]'s rules.
     */
    fun parse(text: String?): List<PrivacyMode> {
        if (text.isNullOrBlank()) return emptyList()
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val version = (root["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
        if (version > VERSION) return emptyList()
        val modes = root["modes"] as? JsonArray ?: return emptyList()
        val out = ArrayList<PrivacyMode>()
        val names = HashSet<String>()
        val ids = HashSet<String>()
        for (m in modes) {
            val o = m as? JsonObject ?: continue
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (id.isEmpty() || name.isEmpty() || !ids.add(id) || !names.add(name.lowercase())) continue
            val tags = (o["tags"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::cleanTag) }
                .filter { it.isNotEmpty() }
                .distinctBy(::tagKey)
            out += PrivacyMode(id, name, tags)
        }
        return out
    }

    /** [modes] as the file's text. */
    fun format(modes: List<PrivacyMode>): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("version", VERSION)
            putJsonArray("modes") {
                for (m in modes) add(buildJsonObject {
                    put("id", m.id)
                    put("name", m.name)
                    put("tags", buildJsonArray { m.tags.forEach { add(JsonPrimitive(it)) } })
                })
            }
        },
    ) + "\n"

    /**
     * A tag as the dialog stores it: trimmed, without a leading `#`.
     * Typed with or without the `#`.
     */
    fun cleanTag(tag: String): String = tag.trim().removePrefix("#").trim()

    /** The key a tag is matched by: [cleanTag], then NFC and lower case (as `SearchQuery` matches tags). */
    fun tagKey(tag: String): String = SearchQuery.normalize(cleanTag(tag))

    /** A fresh mode id. */
    fun newId(): String = buildString {
        repeat(12) { append(ID_CHARS[Random.nextInt(ID_CHARS.length)]) }
    }

    /**
     * Why [name] cannot name the mode [id] among [modes], or `null` when
     * it can: it must not be blank or taken by another mode (ignoring
     * case). Used by the dialog's name field.
     */
    fun nameProblem(modes: List<PrivacyMode>, id: String, name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return "A mode needs a name."
        if (modes.any { it.id != id && it.name.equals(n, ignoreCase = true) }) return "Another mode is called $n."
        return null
    }

    /**
     * A name for a new mode: "New mode", or "New mode 2", … when taken.
     */
    fun newModeName(modes: List<PrivacyMode>): String {
        var n = 1
        while (true) {
            val candidate = if (n == 1) "New mode" else "New mode $n"
            if (modes.none { it.name.equals(candidate, ignoreCase = true) }) return candidate
            n++
        }
    }

    /**
     * `true` when the modes can be edited: only under "No privacy"
     * ([currentId] `null`). With a mode on, the dialog would show what it
     * hides, so it offers only the mode picker.
     */
    fun canEditModes(currentId: String?): Boolean = currentId == null

    /** [modes] with the mode [id] renamed to [name] (trimmed), or `null` when [nameProblem] refuses it. */
    fun renamed(modes: List<PrivacyMode>, id: String, name: String): List<PrivacyMode>? {
        if (nameProblem(modes, id, name) != null) return null
        return modes.map { if (it.id == id) it.copy(name = name.trim()) else it }
    }

    /**
     * [modes] with [tag] added to the mode [id] ([cleanTag]); unchanged when
     * it is blank or the mode already has it (ignoring case).
     */
    fun withTag(modes: List<PrivacyMode>, id: String, tag: String): List<PrivacyMode> {
        val clean = cleanTag(tag)
        if (clean.isEmpty()) return modes
        return modes.map { m ->
            if (m.id != id || m.tags.any { tagKey(it) == tagKey(clean) }) m else m.copy(tags = m.tags + clean)
        }
    }

    /** [modes] with [tag] taken off the mode [id] (matched ignoring case). */
    fun withoutTag(modes: List<PrivacyMode>, id: String, tag: String): List<PrivacyMode> =
        modes.map { m -> if (m.id != id) m else m.copy(tags = m.tags.filterNot { tagKey(it) == tagKey(tag) }) }

    /** [modes] plus a new empty mode ([newModeName], [newId]) at the end; returns both. */
    fun withNewMode(modes: List<PrivacyMode>): Pair<List<PrivacyMode>, PrivacyMode> {
        val mode = PrivacyMode(newId(), newModeName(modes), emptyList())
        return (modes + mode) to mode
    }

    private const val VERSION = 1
    private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
}
