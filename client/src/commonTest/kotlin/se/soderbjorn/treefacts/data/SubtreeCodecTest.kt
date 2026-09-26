/*
 * SubtreeCodecTest.kt (commonTest)
 * Round-trip and parsing tests for the `.treefacts` outline format and for
 * the composed-outline tree the save path splits into folders.
 */

package se.soderbjorn.treefacts.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SubtreeCodecTest {

    private val sample = """
        * Buy oat milk
        + [Recipes](Recipes)
        * Trip to **Lisbon**
        :::
        **Packing**: passport, charger, adapter
        :::
    """.trimIndent() + "\n"

    @Test
    fun parses_the_ticket_example() {
        val items = SubtreeCodec.parseNodeFile(sample)
        assertEquals(
            listOf(
                NodeLine.Leaf("Buy oat milk"),
                NodeLine.Folder("Recipes", "Recipes"),
                NodeLine.Leaf("Trip to **Lisbon**"),
                NodeLine.Block(listOf("**Packing**: passport, charger, adapter")),
            ),
            items,
        )
        assertEquals(sample, SubtreeCodec.formatNodeFile(items))
    }

    @Test
    fun folder_titles_keep_formatting_and_escape_brackets() {
        val line = SubtreeCodec.formatFolderLine("a [b] \\ **c**", "a %5Bb%5D")
        assertEquals("+ [a \\[b\\] \\\\ **c**](a %5Bb%5D)", line)
        assertEquals(listOf(NodeLine.Folder("a [b] \\ **c**", "a %5Bb%5D")), SubtreeCodec.parseNodeFile(line))
    }

    @Test
    fun folder_names_with_parentheses_round_trip() {
        val line = SubtreeCodec.formatFolderLine("Untitled", "Untitled (2)")
        assertEquals(listOf(NodeLine.Folder("Untitled", "Untitled (2)")), SubtreeCodec.parseNodeFile(line))
    }

    @Test
    fun block_containing_a_fence_gets_a_longer_fence() {
        val block = NodeLine.Block(listOf("before", ":::", "after", "::::"))
        val text = SubtreeCodec.formatNodeFile(listOf(block))
        assertEquals(":::::\nbefore\n:::\nafter\n::::\n:::::\n", text)
        assertEquals(listOf(block), SubtreeCodec.parseNodeFile(text))
    }

    @Test
    fun blank_lines_are_dropped_outside_blocks_and_kept_inside() {
        val items = SubtreeCodec.parseNodeFile("* a\n\n* b\n:::\nx\n\ny\n:::\n")
        assertEquals(
            listOf(NodeLine.Leaf("a"), NodeLine.Leaf("b"), NodeLine.Block(listOf("x", "", "y"))),
            items,
        )
    }

    @Test
    fun unknown_lines_and_unclosed_fences_survive_as_text() {
        val items = SubtreeCodec.parseNodeFile("hello\n:::\n* a\n+ not a link\n")
        assertEquals(
            listOf(NodeLine.Text("hello"), NodeLine.Text(":::"), NodeLine.Leaf("a"), NodeLine.Text("+ not a link")),
            items,
        )
    }

    @Test
    fun compose_indents_and_records_folders() {
        val composed = SubtreeCodec.composeNodeLines(SubtreeCodec.parseNodeFile(sample), indent = 2)
        assertEquals(
            listOf("  * Buy oat milk", "  * Recipes", "  * Trip to **Lisbon**", "  :::", "  **Packing**: passport, charger, adapter", "  :::"),
            composed.lines,
        )
        assertEquals(mapOf(1 to "Recipes"), composed.folderByRow)
    }

    @Test
    fun parse_composed_builds_the_tree_with_blocks_owned_by_their_parent() {
        val lines = listOf(
            "* A",
            "  * B",
            "  :::",
            "  * not a bullet",
            "  :::",
            "* C",
        )
        val items = SubtreeCodec.parseComposed(lines)
        assertEquals(2, items.size)
        val a = assertIs<ComposedItem.Bullet>(items[0])
        assertEquals(4, a.endRow)
        assertEquals(2, a.children.size)
        val block = assertIs<ComposedItem.Block>(a.children[1])
        assertEquals(listOf("* not a bullet"), block.content)
        assertEquals(4, SubtreeCodec.composedSubtreeEnd(lines, 0))
        assertEquals(1, SubtreeCodec.composedSubtreeEnd(lines, 1))
        assertEquals(5, SubtreeCodec.composedSubtreeEnd(lines, 5))
    }

    @Test
    fun trailing_empty_bullets_are_not_content() {
        val items = SubtreeCodec.parseComposed(listOf("* A", "  * ", "  *  "))
        val a = assertIs<ComposedItem.Bullet>(items[0])
        assertEquals(false, SubtreeCodec.hasContent(a.children))
        val withText = SubtreeCodec.parseComposed(listOf("* A", "  * ", "  * x"))
        assertEquals(true, SubtreeCodec.hasContent(assertIs<ComposedItem.Bullet>(withText[0]).children))
    }

    @Test
    fun title_and_link_bullet_helpers() {
        assertEquals("Recipes", SubtreeCodec.titleOf("  * Recipes"))
        assertEquals("", SubtreeCodec.titleOf("  * "))
        val link = SubtreeCodec.parseAnyLinkBullet("* [Pasta (fresh)](<Recipes/Pasta.md#r=3>)")!!
        assertEquals("Recipes/Pasta.md#r=3", link.url)
        assertEquals("* Pasta (fresh)", link.bulletText)
        assertEquals(
            "* [Pasta \\(fresh\\)](<Recipes/My Pasta.md>)",
            SubtreeCodec.formatPlainLinkBullet(0, "Pasta (fresh)", "Recipes/My Pasta.md"),
        )
    }
}
