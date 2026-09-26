/*
 * SubtreeCodecTest.kt (commonTest)
 * Round-trip and parsing tests for the `.treefacts` outline format and for
 * the composed-outline tree the save path splits into folders.
 */

package se.soderbjorn.treefacts.data

import se.soderbjorn.treefacts.main.BlockLayout
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
            listOf(
                "  * Buy oat milk",
                "  * Recipes",
                "  * Trip to **Lisbon**",
                BlockLayout.firstLine(2, "**Packing**: passport, charger, adapter"),
            ),
            composed.lines,
        )
        assertEquals(mapOf(1 to "Recipes"), composed.folderByRow)
    }

    @Test
    fun parse_composed_builds_the_tree_with_blocks_owned_by_their_parent() {
        val lines = listOf(
            "* A",
            "  * B",
            BlockLayout.firstLine(2, "## Heading"),
            BlockLayout.nextLine(2, "* not a bullet"),
            "* C",
        )
        val items = SubtreeCodec.parseComposed(lines)
        assertEquals(2, items.size)
        val a = assertIs<ComposedItem.Bullet>(items[0])
        assertEquals(3, a.endRow)
        assertEquals(2, a.children.size)
        val block = assertIs<ComposedItem.Block>(a.children[1])
        assertEquals(listOf("## Heading", "* not a bullet"), block.content)
        assertEquals(3, SubtreeCodec.composedSubtreeEnd(lines, 0))
        assertEquals(1, SubtreeCodec.composedSubtreeEnd(lines, 1))
        assertEquals(4, SubtreeCodec.composedSubtreeEnd(lines, 4))
    }

    @Test
    fun adjacent_blocks_stay_separate_and_a_stranded_row_opens_its_own_block() {
        val lines = listOf(
            BlockLayout.firstLine(0, "one"),
            BlockLayout.firstLine(0, "two"),
            BlockLayout.nextLine(0, "two b"),
            "* A",
            BlockLayout.nextLine(0, "stranded"),
        )
        val items = SubtreeCodec.parseComposed(lines)
        assertEquals(
            listOf(listOf("one"), listOf("two", "two b"), listOf("stranded")),
            items.filterIsInstance<ComposedItem.Block>().map { it.content },
        )
    }

    @Test
    fun an_empty_block_composes_to_one_empty_row_and_saves_as_one_blank_line() {
        val composed = SubtreeCodec.composeNodeLines(listOf(NodeLine.Block(emptyList())), indent = 0)
        assertEquals(listOf(BlockLayout.firstLine(0)), composed.lines)
        val block = assertIs<ComposedItem.Block>(SubtreeCodec.parseComposed(composed.lines).single())
        assertEquals(":::\n\n:::\n", SubtreeCodec.formatNodeFile(listOf(NodeLine.Block(block.content))))
    }

    @Test
    fun block_content_with_fence_lines_round_trips_through_the_editor_form() {
        val text = "* A\n:::::\n## Notes\n:::\n- item\n::::\n:::::\n"
        val composed = SubtreeCodec.composeNodeLines(SubtreeCodec.parseNodeFile(text), indent = 0)
        assertEquals(4, composed.lines.count { BlockLayout.isBlockLine(it) })
        val items = SubtreeCodec.parseComposed(composed.lines)
        val out = items.map {
            when (it) {
                is ComposedItem.Bullet -> NodeLine.Leaf(it.title)
                is ComposedItem.Block -> NodeLine.Block(it.content)
                is ComposedItem.Text -> NodeLine.Text(it.text)
            }
        }
        assertEquals(text, SubtreeCodec.formatNodeFile(out))
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
