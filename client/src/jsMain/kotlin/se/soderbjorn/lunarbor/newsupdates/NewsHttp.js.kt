/*
 * NewsHttp.js.kt (jsMain)
 * Ktor's Js engine: the browser's `fetch` (the Electron renderer). See NewsHttp.kt.
 */

package se.soderbjorn.lunarbor.newsupdates

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

internal actual fun newsHttpClient(): HttpClient = HttpClient(Js) { configureNewsClient(this) }
