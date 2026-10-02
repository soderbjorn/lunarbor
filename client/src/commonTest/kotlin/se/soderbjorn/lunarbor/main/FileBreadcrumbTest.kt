/*
 * FileBreadcrumbTest.kt (commonTest)
 * ----------------------------------
 * Tests for [fileBreadcrumbOf]: the file part of the pane breadcrumb —
 * vault root, each node folder down to the open file, then the file —
 * that the web pane header puts in front of the zoom ancestors.
 */

package se.soderbjorn.lunarbor.main

import kotlin.test.Test
import kotlin.test.assertEquals

class FileBreadcrumbTest {

    private val rootFile = "_node.md"

    private fun crumbs(fileRel: String) = fileBreadcrumbOf(fileRel, rootFile)

    @Test
    fun rootOutlineIsJustHome() {
        assertEquals(listOf(FileCrumb("Home", rootFile)), crumbs(rootFile))
        assertEquals(listOf(FileCrumb("Home", rootFile)), crumbs(""))
    }

    @Test
    fun nestedOutlineListsEveryFolderDownToItself() {
        assertEquals(
            listOf(
                FileCrumb("Home", rootFile),
                FileCrumb("Recipes", "Recipes/_node.md"),
                FileCrumb("Pasta", "Recipes/Pasta/_node.md"),
            ),
            crumbs("Recipes/Pasta/_node.md"),
        )
    }

    @Test
    fun noteAndImageEndWithThemselves() {
        assertEquals(
            listOf(
                FileCrumb("Home", rootFile),
                FileCrumb("Recipes", "Recipes/_node.md"),
                FileCrumb("Shopping notes", "Recipes/Shopping notes.md"),
            ),
            crumbs("Recipes/Shopping notes.md"),
        )
        assertEquals(
            listOf(FileCrumb("Home", rootFile), FileCrumb("granola.png", "granola.png")),
            crumbs("granola.png"),
        )
    }

    @Test
    fun folderNamesAreDecoded() {
        assertEquals(
            listOf(
                FileCrumb("Home", rootFile),
                FileCrumb("Q3/Q4 plan", "Q3%2FQ4 plan/_node.md"),
            ),
            crumbs("Q3%2FQ4 plan/_node.md"),
        )
    }
}
