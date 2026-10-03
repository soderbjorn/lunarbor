/*
 * WikiLink.kt (commonMain)
 * ------------------------
 * Wiki-style links, `[[Name]]` (Obsidian's syntax, common in imported
 * notes). Unlike `lunarbor:` links ([LunarborLink]) they name a target instead of
 * addressing it by path, so they are resolved against the vault's link
 * targets ([LinkTarget], the same list the link search uses): a name that
 * matches exactly one target acts as a link to it; no match or several
 * leaves the text as it is.
 *
 * The syntax stays in the text as written — nothing here rewrites a file,
 * and moves never touch it (the name resolves again against the moved
 * target). The text index records each line's names ([namesIn]) only to
 * find backlinks (LBR-7), never to rewrite them.
 *
 * commonMain only — no DOM, Android UI, or UIKit imports. Pure.
 */

package se.soderbjorn.lunarbor.data

import se.soderbjorn.lunarbor.platform.toNfc

/**
 * Parsing and resolution of `[[Name]]` wiki links.
 *
 * ### Callers
 * - [InlineMarkdownTokenizer] marks `[[…]]` runs ([StyledRun.wikiName]) via
 *   [endAt] and [nameOf].
 * - `DocumentRegistry.requestWikiLink` resolves a name with [resolve].
 */
object WikiLink {

    /**
     * If a wiki link `[[…]]` starts at [pos] in [text], the position one
     * past its closing `]]`; otherwise `null`. The inside must hold a
     * non-blank name ([nameOf]) and no `[`, `]` or line break.
     */
    fun endAt(text: String, pos: Int): Int? {
        if (!text.startsWith("[[", pos)) return null
        val close = text.indexOf("]]", pos + 2)
        if (close < 0) return null
        val inner = text.substring(pos + 2, close)
        if (inner.any { it == '[' || it == ']' || it == '\n' || it == '\r' }) return null
        if (nameOf(inner).isEmpty()) return null
        return close + 2
    }

    /**
     * The target name of a wiki link's inside [inner] (the text between
     * `[[` and `]]`): everything before an alias (`|`) or heading (`#`)
     * suffix, trimmed. `[[Note|shown]]` and `[[Note#Part]]` both name
     * `Note`.
     */
    fun nameOf(inner: String): String {
        val cut = inner.indexOfFirst { it == '|' || it == '#' }
        return (if (cut < 0) inner else inner.substring(0, cut)).trim()
    }

    /**
     * The part of a whole wiki link [link] (`[[…]]`, as [endAt] found it)
     * that stays visible when the link is drawn collapsed: the alias after
     * `|`, otherwise everything between the brackets (`Note#Part` keeps its
     * heading). The rest — `[[`, `Note|`, `]]` — is syntax the editor hides
     * on rows without the caret.
     *
     * Called by the web paint loop (`buildStyledTextRegion`) for resolved
     * wiki links.
     *
     * @param link The link's full text, brackets included.
     * @return The visible range as `start until end` offsets into [link];
     *   never empty for a link [endAt] accepts with a non-blank alias, and
     *   falling back to the whole inside when the alias is blank.
     */
    fun shownRangeOf(link: String): IntRange {
        val innerStart = 2
        val innerEnd = link.length - 2
        val bar = link.indexOf('|', innerStart)
        if (bar in innerStart until innerEnd && link.substring(bar + 1, innerEnd).isNotBlank()) {
            return (bar + 1) until innerEnd
        }
        return innerStart until innerEnd
    }

    /**
     * The target names ([nameOf]) of every wiki link in [text], in order —
     * `[[Note|shown]]` and `[[Note#Part]]` both give `Note`. Called by the
     * text index for every line, to find backlinks.
     */
    fun namesIn(text: String): List<String> {
        if (!text.contains("[[")) return emptyList()
        val out = ArrayList<String>()
        var from = 0
        while (true) {
            val at = text.indexOf("[[", from)
            if (at < 0) break
            val end = endAt(text, at)
            if (end == null) {
                from = at + 1
                continue
            }
            out += nameOf(text.substring(at + 2, end - 2))
            from = end
        }
        return out
    }

    /**
     * The key [resolve] matches names by: Unicode NFC (macOS file names
     * are decomposed), lowercase, inner whitespace collapsed.
     */
    fun keyOf(name: String): String =
        name.toNfc().trim().lowercase().replace(WHITESPACE, " ")

    /**
     * The one target [name] refers to, or `null` when none or several do.
     *
     * A target matches when its title ([LinkTarget.title]: a node's bullet
     * text, a folder's name, a note's name without `.md`, another file's
     * full name) equals [name] ignoring case — or, for a file, when its
     * file name with the extension does (`[[Budget.md]]`). The vault root
     * never matches.
     *
     * @param name A link's target name ([nameOf]).
     * @param targets Every link target in the vault (`VaultIndex.targets`).
     * @return The matching target's vault-relative path.
     */
    fun resolve(name: String, targets: List<LinkTarget>): String? {
        val key = keyOf(name)
        if (key.isEmpty()) return null
        var found: String? = null
        for (t in targets) {
            if (t.pathRel.isEmpty()) continue
            val fileName = if (t.kind == VaultEntryKind.FOLDER) null else t.pathRel.substringAfterLast('/')
            if (keyOf(t.title) != key && (fileName == null || keyOf(fileName) != key)) continue
            if (found != null && found != t.pathRel) return null
            found = t.pathRel
        }
        return found
    }

    private val WHITESPACE = Regex("\\s+")
}
