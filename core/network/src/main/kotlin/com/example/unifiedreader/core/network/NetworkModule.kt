package com.example.unifiedreader.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * The shared client. Its short default timeouts suit the home servers on the LAN; a client for
 * the internet (the GitHub updater) passes longer ones.
 *
 * A request has no total time limit: a download or a big answer (a library of thousands of books)
 * takes as long as it needs while bytes keep coming, on a fast network or a patchy one, and gives up
 * only after [socketTimeoutMillis] without a single byte, or [connectTimeoutMillis] without a
 * connection. A request may wait longer for its first byte with `timeout { socketTimeoutMillis }`
 * (Grimmory's all-books list, Kavita's zipped chapters). A download that gives up keeps its bytes and
 * waits for the server ([downloadResumably]), so these limits needn't be long.
 */
fun buildHttpClient(
    connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    socketTimeoutMillis: Long = DEFAULT_SOCKET_TIMEOUT_MILLIS,
): HttpClient =
    HttpClient(OkHttp) {
        install(HttpTimeout) {
            this.connectTimeoutMillis = connectTimeoutMillis
            this.socketTimeoutMillis = socketTimeoutMillis
        }
        install(ContentNegotiation) {
            json(LenientJson)
        }
    }

// Local Fire 7 (2019) build: its Wi-Fi can take seconds to wake and the server answers big
// lists slowly, so the upstream 2 s / 5 s limits failed there. 10 s lets a connection through
// on its fourth try (Android resends a lost SYN after 1, 2 and 4 s); a minute of silence ends a
// stalled answer on the Fire's 2.4 GHz Wi-Fi and a phone's alike.
private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L
private const val DEFAULT_SOCKET_TIMEOUT_MILLIS = 60_000L
