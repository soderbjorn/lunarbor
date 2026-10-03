/*
 * NewsHttp.android.kt (androidMain)
 * Ktor's OkHttp engine. See NewsHttp.kt.
 */

package se.soderbjorn.lunarbor.newsupdates

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun newsHttpClient(): HttpClient = HttpClient(OkHttp) { configureNewsClient(this) }
