/* McpBridge.kt (jsMain)
 *
 * Connects the MCP server (commonMain `McpServer`) to the Electron main
 * process's HTTP endpoint (`electron-main/.../McpHttpServer.kt`): each
 * request body the endpoint receives arrives here through
 * `noteApi.serveMcp`, is answered against the live app, and goes back as
 * the HTTP response. The endpoint does the authentication; by the time a
 * body arrives here it carried one connection's key, and comes with that
 * connection's edits switch and privacy scope (a privacy mode's id, or
 * none), which the tools enforce.
 *
 * Platform glue only — no protocol or tool logic. A plain browser (no
 * `noteApi.serveMcp`) has no endpoint, so nothing is installed. */
package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.promise
import se.soderbjorn.lunarbor.mcp.McpServer

/**
 * Starts answering MCP requests with [server] on [scope]. Called once by
 * `Main.kt` after the shell is mounted (the window tools need it).
 */
fun installMcpBridge(server: McpServer, scope: CoroutineScope) {
    val api = js("globalThis.noteApi")
    if (api == null || js("typeof api.serveMcp !== 'function'") as Boolean) return
    api.serveMcp { body: String, allowEdits: Boolean, privacyModeId: String? ->
        scope.promise { server.handle(body, allowEdits, privacyModeId?.ifEmpty { null }) }
    }
}
