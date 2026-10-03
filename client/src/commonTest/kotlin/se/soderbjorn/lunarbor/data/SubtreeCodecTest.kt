/*
 * SubtreeCodecTest.kt (commonTest)
 * Round-trip and parsing tests for the `_node.md` outline format, and for
 * the composed-outline tree the save path splits into folders.
 */

package se.soderbjorn.lunarbor.data

import se.soderbjorn.lunarbor.main.BlockLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SubtreeCodecTest {

    private val sample = """
        - Buy oat milk
        - Recipes [↳](<Recipes/_node.md>)
        - Trip to **Lisbon**
        > **Packing**: passport, charger, adapter
    """.trimIndent() + "\n"

    private fun roundTrip(items: List<NodeLine>): List<NodeLine> =
        SubtreeCodec.parseNodeFile(SubtreeCodec.formatNodeFile(items))

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
    fun folder_titles_keep_any_markdown_including_links_and_brackets() {
        val folder = NodeLine.Folder("See [the guide](https://example.com) [draft] \\ **c**", "See the guide")
        val text = SubtreeCodec.formatNodeFile(listOf(folder))
        assertEquals("- See [the guide](https://example.com) [draft] \\ **c** [↳](<See the guide/_node.md>)\n", text)
        assertEquals(listOf(folder), SubtreeCodec.parseNodeFile(text))
        // An empty title is just the link.
        assertEquals("- [↳](<Untitled/_node.md>)\n", SubtreeCodec.formatNodeFile(listOf(NodeLine.Folder("", "Untitled"))))
        assertEquals(listOf(NodeLine.Folder("", "Untitled")), roundTrip(listOf(NodeLine.Folder("", "Untitled"))))
    }

    @Test
    fun folder_names_with_spaces_parentheses_emoji_and_percent_round_trip() {
        for (name in listOf("Untitled (2)", "🍕 Work%3A Warp Factor Pizza", "Q3%2FQ4 plan", "50%25 done")) {
            assertEquals(listOf(NodeLine.Folder("T", name)), roundTrip(listOf(NodeLine.Folder("T", name))), name)
        }
        // `%` is written as `%25`, so viewers open the right folder.
        assertEquals(
            "- T [↳](<Q3%252FQ4 plan/_node.md>)\n",
            SubtreeCodec.formatNodeFile(listOf(NodeLine.Folder("T", "Q3%2FQ4 plan"))),
        )
    }

    @Test
    fun child_links_written_by_other_tools_are_understood() {
        assertEquals(
            listOf(NodeLine.Folder("Trip", "Trip to Lisbon")),
            SubtreeCodec.parseNodeFile("* Trip [→](Trip%20to%20Lisbon/_node.md)\n"),
        )
        // A link to anything but a child's outline is just part of the text.
        assertEquals(
            listOf(NodeLine.Leaf("See [x](Notes/plan.md)"), NodeLine.Leaf("Deep [↳](<a/b/_node.md>)")),
            SubtreeCodec.parseNodeFile("- See [x](Notes/plan.md)\n- Deep [↳](<a/b/_node.md>)\n"),
        )
    }

    @Test
    fun bullet_text_that_markdown_would_misread_is_escaped_and_restored() {
        val cases = mapOf(
            "1. Picard — would hold a 1:1" to "- 1\\. Picard — would hold a 1:1",
            "2) second" to "- 2\\) second",
            "- not a sub-list" to "- \\- not a sub-list",
            "* nor this" to "- \\* nor this",
            "+" to "- \\+",
            "---" to "- \\---",
            "\\- a literal backslash" to "- \\\\- a literal backslash",
            "1\\. already escaped" to "- 1\\\\. already escaped",
            "# Heading stays a heading" to "- # Heading stays a heading",
            "> quotes stay quotes" to "- > quotes stay quotes",
            "2026 was a year" to "- 2026 was a year",
            "looks like a child [↳](<X/_node.md>)" to "- looks like a child \\[↳](<X/_node.md>)",
        )
        for ((title, line) in cases) {
            assertEquals(line + "\n", SubtreeCodec.formatNodeFile(listOf(NodeLine.Leaf(title))), title)
            assertEquals(listOf(NodeLine.Leaf(title)), SubtreeCodec.parseNodeFile(line + "\n"), title)
        }
    }

    @Test
    fun trailing_spaces_in_bullet_text_are_kept() {
        for (item in listOf(NodeLine.Leaf("ends with a space "), NodeLine.Folder("folder title  ", "F"))) {
            assertEquals(listOf(item), roundTrip(listOf(item)))
        }
    }

    @Test
    fun empty_bullets_round_trip() {
        assertEquals("-\n", SubtreeCodec.formatNodeFile(listOf(NodeLine.Leaf(""))))
        assertEquals(listOf(NodeLine.Leaf(""), NodeLine.Leaf("")), SubtreeCodec.parseNodeFile("-\n*\n"))
    }

    @Test
    fun blocks_are_blockquotes_holding_any_markdown() {
        val block = NodeLine.Block(listOf("## Packing", "", "* passport", "- charger", "> a real quote", "```", "  code", "```", ":::"))
        val text = SubtreeCodec.formatNodeFile(listOf(NodeLine.Leaf("A"), block, NodeLine.Leaf("B")))
        assertEquals(
            "- A\n> ## Packing\n>\n> * passport\n> - charger\n> > a real quote\n> ```\n>   code\n> ```\n> :::\n- B\n",
            text,
        )
        assertEquals(listOf(NodeLine.Leaf("A"), block, NodeLine.Leaf("B")), SubtreeCodec.parseNodeFile(text))
    }

    @Test
    fun consecutive_blocks_are_separated_by_a_blank_line() {
        val items = listOf(NodeLine.Block(listOf("one")), NodeLine.Block(listOf("two")), NodeLine.Text("loose"))
        val text = SubtreeCodec.formatNodeFile(items)
        assertEquals("> one\n\n> two\n\nloose\n", text)
        assertEquals(items, SubtreeCodec.parseNodeFile(text))
    }

    @Test
    fun a_block_with_children_ends_with_its_child_link() {
        val block = NodeLine.Block(listOf("**Packing** list", "- passport"), "Packing list", "Packing list")
        val text = SubtreeCodec.formatNodeFile(listOf(block))
        assertEquals("> **Packing** list\n> - passport\n> [↳](<Packing list/_node.md>)\n", text)
        assertEquals(listOf(block), SubtreeCodec.parseNodeFile(text))
        // A plain block whose last line merely looks like a child link stays plain.
        val plain = NodeLine.Block(listOf("see", "[↳](<X/_node.md>)"))
        assertEquals("> see\n> \\[↳](<X/_node.md>)\n", SubtreeCodec.formatNodeFile(listOf(plain)))
        assertEquals(listOf(plain), roundTrip(listOf(plain)))
    }

    @Test
    fun an_empty_block_round_trips() {
        assertEquals(">\n", SubtreeCodec.formatNodeFile(listOf(NodeLine.Block(listOf("")))))
        assertEquals(listOf(NodeLine.Block(listOf(""))), SubtreeCodec.parseNodeFile(">\n"))
    }

    @Test
    fun a_file_reformatted_by_another_tool_loads_the_same_tree() {
        val reformatted = """
            * Buy oat milk

            * Recipes [↳](<Recipes/_node.md>)
            + Trip to **Lisbon**

            >**Packing**: passport, charger, adapter
        """.trimIndent() + "\n"
        assertEquals(SubtreeCodec.parseNodeFile(sample), SubtreeCodec.parseNodeFile(reformatted))
    }

    @Test
    fun unknown_lines_survive_as_text() {
        val items = SubtreeCodec.parseNodeFile("hello\n- a\n:::\n+not a bullet\n")
        assertEquals(
            listOf(NodeLine.Text("hello"), NodeLine.Leaf("a"), NodeLine.Text(":::"), NodeLine.Text("+not a bullet")),
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
        assertEquals(">\n", SubtreeCodec.formatNodeFile(listOf(NodeLine.Block(block.content))))
    }

    @Test
    fun block_content_with_fence_lines_round_trips_through_the_editor_form() {
        val text = "- A\n> ## Notes\n> :::\n> - item\n> ::::\n"
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
