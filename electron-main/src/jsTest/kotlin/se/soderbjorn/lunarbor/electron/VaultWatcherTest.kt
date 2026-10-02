/* VaultWatcherTest.kt — tests for [VaultChangeFilter]: this app's own
 * writes, moves and deletes are not outside changes, nor are events that
 * changed nothing (a sync client re-touching files); an outside edit of a
 * known file, a fresh file, a new folder and a deletion are. */
package se.soderbjorn.lunarbor.electron

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultWatcherTest {

    private fun text(t: String, mtimeMs: Double = 0.0) = PathProbe(t, false, t.length.toDouble(), mtimeMs)
    private fun dir(mtimeMs: Double = 0.0) = PathProbe(null, true, 0.0, mtimeMs)
    private fun file(size: Double, mtimeMs: Double) = PathProbe(null, false, size, mtimeMs)

    @Test
    fun a_text_write_is_ours_until_someone_else_changes_the_file() {
        val f = VaultChangeFilter()
        f.recordText("/v/_node.md", "* A\n")
        assertFalse(f.isExternalChange("/v/_node.md", 0.0) { text("* A\n") })
        assertTrue(f.isExternalChange("/v/_node.md", 0.0) { text("* A\n* agent\n") })
    }

    @Test
    fun an_older_own_write_seen_while_a_newer_one_is_in_flight_is_ours() {
        val f = VaultChangeFilter()
        f.recordText("/v/_node.md", "* A\n")
        f.recordText("/v/_node.md", "* AB\n")
        assertFalse(f.isExternalChange("/v/_node.md", 0.0) { text("* A\n") })
        assertFalse(f.isExternalChange("/v/_node.md", 0.0) { text("* AB\n") })
    }

    @Test
    fun re_touching_a_known_file_without_changing_it_is_not_a_change() {
        val f = VaultChangeFilter()
        f.recordRead("/v/a/_node.md", "* x\n")
        repeat(3) { assertFalse(f.isExternalChange("/v/a/_node.md", 1e9) { text("* x\n", mtimeMs = 1e9) }) }
    }

    @Test
    fun an_unknown_file_counts_only_with_a_fresh_mtime() {
        val f = VaultChangeFilter()
        val now = 100_000.0
        assertFalse(f.isExternalChange("/v/old/_node.md", now) { text("* x\n", mtimeMs = now - 175_000) })
        assertTrue(f.isExternalChange("/v/new.md", now) { text("hi", mtimeMs = now - 200) })
        // Once seen, the old file is known: a later real edit counts.
        assertTrue(f.isExternalChange("/v/old/_node.md", now) { text("* y\n", mtimeMs = now - 175_000) })
    }

    @Test
    fun other_files_compare_by_size_and_mtime() {
        val f = VaultChangeFilter()
        val now = 100_000.0
        assertFalse(f.isExternalChange("/v/a.png", now) { file(10.0, 1.0) })
        assertFalse(f.isExternalChange("/v/a.png", now) { file(10.0, 1.0) })
        assertTrue(f.isExternalChange("/v/a.png", now) { file(12.0, 2.0) })
    }

    @Test
    fun a_folder_counts_when_it_appears_or_disappears() {
        val f = VaultChangeFilter()
        val now = 100_000.0
        assertFalse(f.isExternalChange("/v/Recipes", now) { dir(mtimeMs = 1.0) })
        assertFalse(f.isExternalChange("/v/Recipes", now) { dir(mtimeMs = now) })
        assertTrue(f.isExternalChange("/v/Recipes", now) { null })
        assertTrue(f.isExternalChange("/v/Recipes", now) { dir(mtimeMs = 1.0) })
    }

    @Test
    fun touched_paths_and_everything_under_them_are_ours_for_a_while() {
        val f = VaultChangeFilter()
        f.recordTouch("/v/Recipes", 1_000.0)
        assertFalse(f.isExternalChange("/v/Recipes", 1_500.0) { null })
        assertFalse(f.isExternalChange("/v/Recipes/_node.md", 1_500.0) { text("x", 1_400.0) })
        assertTrue(f.isExternalChange("/v/Recipes (2)", 1_500.0) { null })
        assertTrue(f.isExternalChange("/v/Recipes/_node.md", 1_000.0 + SELF_TOUCH_WINDOW_MS + 1) { text("y", 4_000.0) })
    }

    @Test
    fun a_deleted_unknown_path_counts() {
        assertTrue(VaultChangeFilter().isExternalChange("/v/gone", 0.0) { null })
    }
}
