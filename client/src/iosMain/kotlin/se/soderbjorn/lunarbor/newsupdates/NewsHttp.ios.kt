/*
 * NewsHttp.ios.kt (iosMain)
 * Ktor's Darwin engine (NSURLSession). See NewsHttp.kt.
 */

package se.soderbjorn.lunarbor.newsupdates

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

internal actual fun newsHttpClient(): HttpClient = HttpClient(Darwin) { configureNewsClient(this) }
