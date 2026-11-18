package com.example.unifiedreader.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * The shared client. Its short default timeouts suit the home servers on the LAN; a client for
 * the internet (the GitHub updater) passes longer ones.
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
// lists slowly, so the upstream 2 s / 5 s limits failed there.
private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000L
private const val DEFAULT_SOCKET_TIMEOUT_MILLIS = 60_000L
