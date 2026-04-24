package se.soderbjorn.notegrow

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.notegrow.di.createJsAppGraph
import se.soderbjorn.notegrow.main.MainScreen

fun main() {
    window.onload = {
        val app = document.getElementById("app") as HTMLElement
        val graph = createJsAppGraph()
        MainScreen(graph.mainViewModel, graph.coroutineScope).render(app)
    }
}
