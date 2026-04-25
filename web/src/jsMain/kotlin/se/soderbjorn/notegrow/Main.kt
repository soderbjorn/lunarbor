package se.soderbjorn.notegrow

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.notegrow.di.createJsAppGraph
import se.soderbjorn.notegrow.main.AppShell

/**
 * Web entry point. Builds the DI graph and mounts the [AppShell] into the
 * `<div id="app">` host element.
 *
 * The shell wraps the existing note editor inside the toolkit's windowing
 * system and applies the active darkness theme to `document.documentElement`
 * before any content is painted.
 */
fun main() {
    window.onload = {
        val app = document.getElementById("app") as HTMLElement
        val graph = createJsAppGraph()
        AppShell(graph.mainViewModel, graph.coroutineScope).render(app)
    }
}
