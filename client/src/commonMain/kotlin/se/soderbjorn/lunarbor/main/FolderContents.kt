/*
 * FolderContents.kt (commonMain)
 * ------------------------------
 * Pure rules for the folder contents list (TRF-6): the list, drawn under
 * the bullets of the node a pane is showing, of everything in that node's
 * folder that is not already a bullet. Also the count badge an expanded
 * folder-backed bullet carries.
 *
 * What is shown, in which order, and how the badge is worded all live
 * here, so the platform views (web `FolderContentsList.kt`,
 * `OutlinePaintLoop`) only draw, and the rules are covered by commonTest.
 *
 * commonMain only — no DOM, Android UI, or UIKit imports. No state.
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.VaultEntry

/**
 * Filtering, ordering and badge wording for one folder's contents.
 *
 * ### Callers
 * - `PaneBackingViewModel.folderContentsOf` / `folderContentsOfBullet`,
 *   which read the registry's cached listing for a folder and pass it
 *   through [visible].
 * - The web views, for [badgeLabel].
 */
object FolderContents {

    /**
     * The entries of one folder listing that the contents list shows, in
     * display order.
     *
     * - **Hidden:** folders referenced by a child link of the folder's own
     *   outline ([VaultEntry.isReferenced]), anything whose name starts
     *   with a dot, and app files (`NoteRepository.isAppFile`: every
     *   node's `_node.md`, the root's `Starred.md`). The listing
     *   already drops the last two; they are filtered again so the rule
     *   holds for any source.
     * - **Shown:** everything else — `.md` notes, images, other files,
     *   foreign subfolders, and Lunarbor folders no bullet points at.
     * - **Order:** folders first, then files; each group by name with
     *   [naturalCompare].
     *
     * @param entries A raw listing from `NoteRepository.listVaultLevel`.
     */
    fun visible(entries: List<VaultEntry>): List<VaultEntry> =
        entries
            .filter {
                !it.isReferenced && !it.pathRel.substringAfterLast('/').startsWith(".") &&
                    (it.isDirectory || !NoteRepository.isAppFile(it.pathRel))
            }
            .sortedWith(
                compareByDescending<VaultEntry> { it.isDirectory }
                    .then { a, b -> naturalCompare(a.name, b.name) }
            )

    /**
     * Case-insensitive natural order: runs of digits compare by numeric
     * value, so `Note 2` sorts before `Note 10`. Ties (names that differ
     * only in case or leading zeros) fall back to a plain comparison so
     * the order is total and stable.
     *
     * @return Negative, zero or positive, like [Comparator.compare].
     */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val endA = digitRunEnd(a, i)
                val endB = digitRunEnd(b, j)
                val numA = a.substring(i, endA).trimStart('0')
                val numB = b.substring(j, endB).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
                i = endA
                j = endB
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        if (rest != 0) return rest
        return a.compareTo(b)
    }

    private fun digitRunEnd(s: String, from: Int): Int {
        var k = from
        while (k < s.length && s[k].isDigit()) k++
        return k
    }

    /**
     * Wording of the count badge for a folder whose visible contents are
     * [visibleEntries] (the output of [visible]): `3 files`, `1 folder`,
     * `2 folders, 3 files`. `null` when there is nothing to count, in
     * which case no badge is drawn. The numbers always add up to the
     * length of the list shown when zooming into the bullet.
     */
    fun badgeLabel(visibleEntries: List<VaultEntry>): String? {
        if (visibleEntries.isEmpty()) return null
        val folders = visibleEntries.count { it.isDirectory }
        val files = visibleEntries.size - folders
        val parts = ArrayList<String>(2)
        if (folders > 0) parts += if (folders == 1) "1 folder" else "$folders folders"
        if (files > 0) parts += if (files == 1) "1 file" else "$files files"
        return parts.joinToString(", ")
    }
}
