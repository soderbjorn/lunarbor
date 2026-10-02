/*
 * DragDropTest.kt (commonTest)
 * ----------------------------
 * Tests for where a dragged item lands ([PaneBackingViewModel.dropTarget])
 * and for [PaneBackingViewModel.moveLineRange] applying it: the drop row
 * snaps off block interiors and folded subtrees, and the sideways level
 * is clamped between the row below and one level under the item above.
 * Runs the real pane over [DocumentRegistry] + [NoteRepository] on
 * [InMemoryFileSystem].
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.main.PaneBackingViewModel.DropTarget
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DragDropTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    /** A pane on [content] with every subtree expanded. */
    private suspend fun TestScope.pane(content: String): PaneBackingViewModel {
        fs.writeFile("$root/_node.md", content)
        val registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        for (id in pane.stateFlow.value.collapsedIds) pane.toggleCollapse(id)
        return pane
    }

    private val PaneBackingViewModel.lines get() = stateFlow.value.lines
    private fun PaneBackingViewModel.id(row: Int) = stateFlow.value.documentState!!.lineIds[row]

    @Test
    fun sideways_movement_picks_the_level_between_the_row_below_and_the_item_above() = runTest {
        val p = pane("- A\n  * a1\n- B\n- C\n")
        // B dragged onto the lower half of a1: it stays where it is.
        assertEquals(DropTarget(2, 0), p.dropTarget(2, 2, 1, insertAbove = false, levelDelta = 0))
        assertEquals(DropTarget(2, 2), p.dropTarget(2, 2, 1, insertAbove = false, levelDelta = 1))
        assertEquals(DropTarget(2, 4), p.dropTarget(2, 2, 1, insertAbove = false, levelDelta = 2))
        assertEquals(DropTarget(2, 4), p.dropTarget(2, 2, 1, insertAbove = false, levelDelta = 9))
        assertEquals(DropTarget(2, 0), p.dropTarget(2, 2, 1, insertAbove = false, levelDelta = -3))
        // Between A and a1 nothing shallower than a1 fits.
        assertEquals(DropTarget(1, 2), p.dropTarget(3, 3, 1, insertAbove = true, levelDelta = -1))
        // Over the dragged rows themselves: in place.
        assertEquals(DropTarget(0, 0), p.dropTarget(0, 1, 0, insertAbove = false, levelDelta = 0))

        // Dropped in place one level deeper, B becomes a1's sibling.
        p.moveLineRange(2, 2, 2, 2)
        assertEquals(listOf("* A", "  * a1", "  * B", "* C"), p.lines)
        // C dragged under a1, two levels in.
        val t = p.dropTarget(3, 3, 1, insertAbove = false, levelDelta = 2)!!
        assertEquals(DropTarget(2, 4), t)
        p.moveLineRange(3, 3, t.insertBeforeRow, t.indent)
        assertEquals(listOf("* A", "  * a1", "    * C", "  * B"), p.lines)
    }

    @Test
    fun a_drop_never_splits_a_block_or_enters_a_folded_subtree() = runTest {
        val p = pane("- A\n> x\n> y\n- B\n  * b1\n- C\n")
        // Lower half of the block's first row: after the whole block, and
        // one level in makes C the block's child.
        assertEquals(DropTarget(3, 0), p.dropTarget(5, 5, 1, insertAbove = false, levelDelta = 0))
        assertEquals(DropTarget(3, 2), p.dropTarget(5, 5, 1, insertAbove = false, levelDelta = 1))
        // B folded: below it means after b1, at B's level at most.
        p.toggleCollapse(p.id(3))
        assertEquals(DropTarget(5, 0), p.dropTarget(0, 0, 3, insertAbove = false, levelDelta = 3))
    }
}
