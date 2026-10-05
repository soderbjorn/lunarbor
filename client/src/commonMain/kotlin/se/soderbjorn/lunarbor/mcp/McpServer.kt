/*
 * McpServer.kt (commonMain)
 * -------------------------
 * The Model Context Protocol server behind App settings → Agent access:
 * JSON-RPC 2.0 messages in, responses out — `initialize`, `ping`,
 * `tools/list`, `tools/call` — with the tools in [McpTools].
 *
 * Transport-free on purpose. On desktop the Electron main process owns the
 * HTTP endpoint (`McpHttpServer.kt`: localhost only, a secret key on every
 * request) and hands each request body to the renderer, where this class
 * answers it against the live `DocumentRegistry` (`web/.../McpBridge.kt`).
 * Hand-rolled with kotlinx.serialization's JSON tree, like lunamux's and
 * lunicle's servers — no MCP SDK.
 *
 * commonMain only.
 */

package se.soderbjorn.lunarbor.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Answers MCP JSON-RPC messages.
 *
 * ### Callers
 * - The platform bridge (`McpBridge` on the web) calls [handle] once per
 *   HTTP request body.
 *
 * @param tools The tool table and handlers.
 */
class McpServer(private val tools: McpTools) {

    /**
     * Answers one request body: a JSON-RPC message or a batch of them.
     *
     * @param allowEdits Whether the user lets agents change the vault
     *   (App settings); without it the write tools are neither listed nor run.
     * @param privacyModeId The request's connection's privacy scope: the id
     *   of the privacy mode whose hidden content the tools leave out, or
     *   `null` for "No privacy" ([McpTools.call]). The agent is not told.
     * @return The response JSON, or `null` when nothing is to be sent back
     *   (only notifications or client responses) — the transport answers
     *   `202 Accepted` then.
     */
    suspend fun handle(body: String, allowEdits: Boolean, privacyModeId: String? = null): String? {
        val parsed = try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            return error(JsonNull, PARSE_ERROR, "Parse error").toString()
        }
        if (parsed is JsonArray) {
            if (parsed.isEmpty()) return error(JsonNull, INVALID_REQUEST, "Empty batch").toString()
            val answers = parsed.mapNotNull { handleOne(it, allowEdits, privacyModeId) }
            return if (answers.isEmpty()) null else JsonArray(answers).toString()
        }
        return handleOne(parsed, allowEdits, privacyModeId)?.toString()
    }

    /** One message → its response, or `null` for a notification or a client response. */
    private suspend fun handleOne(message: JsonElement, allowEdits: Boolean, privacyModeId: String?): JsonElement? {
        val obj = message as? JsonObject ?: return error(JsonNull, INVALID_REQUEST, "Invalid request")
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull ?: return null
        val id = obj["id"]
        if (id == null || id is JsonNull) return null
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(id, initializeResult(params))
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject {
                putJsonArray("tools") {
                    for (t in tools.toolsFor(allowEdits)) add(buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("inputSchema", t.inputSchema)
                    })
                }
            })
            "tools/call" -> callTool(id, params, allowEdits, privacyModeId)
            else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun initializeResult(params: JsonObject): JsonObject {
        val asked = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", if (asked in PROTOCOL_VERSIONS) asked else PROTOCOL_VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", "lunarbor")
                put("version", SERVER_VERSION)
            }
            put("instructions", INSTRUCTIONS)
        }
    }

    private suspend fun callTool(id: JsonElement, params: JsonObject, allowEdits: Boolean, privacyModeId: String?): JsonElement {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
            ?: return error(id, INVALID_PARAMS, "tools/call needs a tool name")
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val outcome = tools.call(name, args, allowEdits, privacyModeId) ?: return error(id, INVALID_PARAMS, "Unknown tool: $name")
        return result(id, buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", outcome.text)
                })
                for (block in outcome.extra) add(block)
            }
            put("isError", outcome.isError)
        })
    }

    private fun result(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    companion object {
        /** Protocol versions this server speaks, newest first. */
        val PROTOCOL_VERSIONS: List<String> = listOf("2025-06-18", "2025-03-26", "2024-11-05")

        /** Reported in `serverInfo`. */
        const val SERVER_VERSION: String = "1.0.0"

        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602

        /**
         * Orientation for the agent, sent on `initialize` (clients put it in
         * the agent's system prompt). Cross-cutting rules live here; each
         * tool's mechanics in its own description.
         */
        val INSTRUCTIONS: String = """
            Lunarbor is the user's outliner. Their notes are a tree of nodes: every node is a bullet, and a bullet with
            children is stored as a folder of its own. Paths name those folders and files from the vault's root, "/",
            e.g. "/Projects/Website". These tools work on the running app: the user sees your changes at once, in any
            window showing that node.

            How to work:
            - Orient with read "/" (the root node) and search. read shows one node's own items; an item that is a node
              itself ends in <!-- /its/path -->, which you read to go inside.
            - read heads a node with its Created and Updated times (UTC). Updated is the last change to the node's own
              items or title, not to nodes inside it; "unknown" means it has not changed since Lunarbor began keeping
              these times.
            - Prefer nodes. Write notes, lists, plans, steps and facts as bullets, one idea per bullet, with detail as
              child bullets nested under it — also for lists. Use a ::: block only for continuous text that should stay
              whole (a transcript, an email, a document, code). Create a separate .md file (create_file) only as a last
              resort, for a standalone document the user wants as a file.
            - Bullet text is inline Markdown (**bold**, *italic*, `code`, [links](https://…)). #tags make things
              findable (search "#tag"); a tag on a parent covers everything under it.
            - An item whose whole title is struck through (~~Buy oat milk~~ #todo) is done, and so is everything under
              it. search accepts is:done and is:open (not done), e.g. "#todo is:open"; done lines end in [done].
            - Link to another node or file with [text](/path), its vault path percent-encoded (spaces as %20), e.g.
              [soups](/Recipes/Soups). Lunarbor stores links relative to the node they are in (a node named by its
              _node.md: [soups](../Recipes/Soups/_node.md)), which is how you will read them back, and keeps them
              working when things move or are renamed.
            - list_folder shows everything on disk in a folder (files, sizes, dates); read also reads any file — text
              as text, images as images, other files as base64.
            - Change things with edit (replace exact text from read), append, create_node, create_file, move and delete. A
              <!-- /path --> is a node's identity: keep it on its line, even when you retitle the node. Deleted things go
              to the vault's trash, from where the user can restore them, but still ask the user before deleting
              anything they did not ask you to remove.
            - The window tools (list_windows, open_window, …) arrange what the user sees; use them to show the user your
              work when that helps, and do not close windows you did not open unless asked.
            - If an edit tool says edits are turned off, the user allowed reading only; tell them what you would change.
        """.trimIndent()
    }
}
