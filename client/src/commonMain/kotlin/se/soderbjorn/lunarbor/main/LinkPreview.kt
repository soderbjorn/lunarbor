/*
 * LinkPreview.kt (commonMain)
 * ---------------------------
 * Link bullets to nodes. A leaf bullet whose text holds exactly one
 * `lunarbor:` link to a node is a mirror (`Document`, see its class doc):
 * it folds open onto that node's own, editable items. This file holds
 * the pure rules: which lines can mirror ([linkPreviewPathOf]) and what a
 * node's listing holds ([linkPreviewItemsOf]) — read and cached by
 * `DocumentRegistry` (`requestLinkPreview`) for 3D mode's pages and maps.
 *
 * commonMain only — pure functions, no state.
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.InlineMarkdownTokenizer
import se.soderbjorn.lunarbor.data.LineMarkdownPrefix
import se.soderbjorn.lunarbor.data.NodeLine
import se.soderbjorn.lunarbor.data.SubtreeCodec
import se.soderbjorn.lunarbor.data.LunarborLink
import se.soderbjorn.lunarbor.data.TextIndex

/**
 * One bullet of a linked node, as its preview shows it.
 *
 * @property title The bullet's plain text (inline Markdown and a heading
 *   or quote prefix removed); a block item shows its title.
 * @property pathRel The bullet's own folder (vault-relative) when it is
 *   folder-backed — opening it goes there — or `null` for a leaf.
 * @property tagKeys The item's normalized `#tags`, so a pane can leave out
 *   the items its privacy mode hides.
 */
data class LinkPreviewItem(val title: String, val pathRel: String?, val tagKeys: Set<String> = emptySet())

/**
 * A bullet's open-able preview: the linked node and its bullets.
 *
 * @property pathRel The linked node's folder, vault-relative.
 * @property items The node's direct children, in order; never empty.
 */
data class LinkPreview(val pathRel: String, val items: List<LinkPreviewItem>)

/**
 * The link target a bullet [line] would preview: the path of its only
 * `lunarbor:` link, or `null` when the line is not a bullet or holds no link or
 * more than one. Whether the target is a node with bullets is decided
 * later, from what the registry reads there.
 */
internal fun linkPreviewPathOf(line: String): String? {
    if (DocumentLayout.bulletAsteriskColumn(line) < 0) return null
    val links = LunarborLink.findLinks(line)
    return links.singleOrNull()?.pathRel
}

/**
 * The preview items for the node at [folderRel] whose outline lists
 * [nodeLines]: one per bullet and block item, text lines skipped.
 *
 * Called by `DocumentRegistry.requestLinkPreview`.
 */
internal fun linkPreviewItemsOf(folderRel: String, nodeLines: List<NodeLine>): List<LinkPreviewItem> {
    fun join(name: String) = if (folderRel.isEmpty()) name else "$folderRel/$name"
    fun plain(title: String): String {
        val prefix = LineMarkdownPrefix.detect(title, 0)
        return InlineMarkdownTokenizer.tokenize(title.substring(prefix.markerEnd)).displayText.trim()
    }
    fun tags(text: String) = text.split('\n').flatMapTo(HashSet()) { TextIndex.tagKeysOfRow("* $it") }
    return nodeLines.mapNotNull { line ->
        when (line) {
            is NodeLine.Leaf -> LinkPreviewItem(plain(line.title), null, tags(line.title))
            is NodeLine.Folder -> LinkPreviewItem(plain(line.title), join(line.folder), tags(line.title))
            is NodeLine.Block -> LinkPreviewItem(
                plain(SubtreeCodec.blockTitleOf(line.content)), line.folder?.let(::join), tags(line.content.joinToString("\n")),
            )
            is NodeLine.Text -> null
        }
    }.filter { it.title.isNotEmpty() || it.pathRel != null }
}
