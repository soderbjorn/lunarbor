/*
 * ImagePaths.kt (commonMain)
 * --------------------------
 * How the `src` of an inline image `![alt](src)` maps to a file in the
 * vault (TRF-7).
 *
 * A `src` is resolved like a CommonMark link: relative to the folder of
 * the file the line is stored in. For an outline row that is the folder
 * of the node the row belongs to (`Document.storageFolderOf`); for a
 * `.md` note, the note's folder. So a pasted image is written into that
 * folder and referenced by its bare file name (`![](shot.png)`), and it
 * keeps resolving when the node's folder is renamed or moved.
 *
 * A `src` starting with `/` is vault-rooted (`/Images/logo.png`); that
 * is how images picked from elsewhere in the vault are referenced, so
 * the reference does not depend on where the row lives. A `src` with a
 * URL scheme (`https:`, `data:`) is not a vault path at all.
 *
 * Pure string rules — no I/O. commonMain only.
 */

package se.soderbjorn.treefacts.data

/**
 * Resolution and formatting of image `src` paths.
 *
 * ### Callers
 * - `Document` / `PaneBackingViewModel`, to resolve a row's images for
 *   the view and to decide which images travel with a moved row.
 * - `PaneBackingViewModel.insertImageRef`, to format a vault-rooted
 *   reference for an image picked in the Insert Image palette.
 */
object ImagePaths {

    private val schemeRegex = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /** `true` when [src] carries a URL scheme (`https:`, `data:`, …) and is not a vault path. */
    fun isExternal(src: String): Boolean = schemeRegex.containsMatchIn(src)

    /**
     * The vault-relative path [src] points at, read from a line stored in
     * the folder [baseFolder], or `null` when [src] is external or empty.
     * `.` and `..` segments are folded; `..` above the vault root is
     * dropped, so the result never escapes the vault.
     *
     * @param baseFolder Vault-relative folder of the file holding the
     *   line (`""` = vault root).
     * @param src The image destination as written in the Markdown.
     */
    fun resolve(baseFolder: String, src: String): String? {
        if (src.isEmpty() || isExternal(src)) return null
        val joined = if (src.startsWith("/")) src else if (baseFolder.isEmpty()) src else "$baseFolder/$src"
        val out = ArrayList<String>()
        for (seg in joined.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out += seg
            }
        }
        return if (out.isEmpty()) null else out.joinToString("/")
    }

    /**
     * `true` when [src] names a file directly inside the folder of the
     * line that holds it — a bare file name, as a pasted image is
     * written. Only such images move with a row that moves to another
     * node's folder; anything with a path is shared or elsewhere and is
     * left alone.
     */
    fun isFolderLocal(src: String): Boolean =
        src.isNotEmpty() && !isExternal(src) && '/' !in src && '\\' !in src && src != "." && src != ".."

    /**
     * The vault-rooted `src` for the vault file [vaultRelPath]
     * (`Images/logo.png` → `/Images/logo.png`). Resolves the same from
     * any row, wherever it is moved.
     */
    fun vaultRooted(vaultRelPath: String): String = "/" + vaultRelPath.trimStart('/')
}
