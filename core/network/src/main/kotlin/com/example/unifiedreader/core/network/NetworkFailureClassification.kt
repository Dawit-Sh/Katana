package com.example.unifiedreader.core.network

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException

fun Throwable.isRetryableNetworkFailure(): Boolean = generateSequence(this) { error ->
    error.cause
}.any { error ->
    error is IOException ||
        error::class.simpleName in setOf(
            "ConnectTimeoutException",
            "HttpRequestTimeoutException",
            "SocketTimeoutException",
            "UnresolvedAddressException",
        )
}

/**
 * Whether this error (or one of its causes) means the connection to the server was lost or never made:
 * a connect or read timeout, a connection reset, refused or aborted, an unknown host, no route, or a body
 * that broke off part way. These pass when the network does, so a download waits for the server instead
 * of failing.
 *
 * Narrower than [isRetryableNetworkFailure], which takes any [IOException]: a full disk or a missing file
 * is not a lost connection, and neither is an error answer from the server (404, 401), which no error
 * here stands for.
 */
fun Throwable.isConnectionFailure(): Boolean = generateSequence(this) { error ->
    error.cause?.takeIf { cause -> cause !== error }
}.take(MAX_CAUSE_DEPTH).any(Throwable::isConnectionFailureItself)

private fun Throwable.isConnectionFailureItself(): Boolean = when (this) {
    is IncompleteDownloadException,
    // ConnectException (refused, timed out), NoRouteToHostException, "Connection reset",
    // "Software caused connection abort", "Broken pipe".
    is SocketException,
    is UnknownHostException,
    // SocketTimeoutException and OkHttp's other timeouts.
    is InterruptedIOException,
    // The connection closed before the answer's headers or body were all there.
    is EOFException,
    -> true
    // OkHttp's word for a body that ended before its length: the connection dropped.
    is ProtocolException -> message?.contains("unexpected end of stream", ignoreCase = true) == true
    else -> this::class.simpleName in ConnectionFailureClassNames
}

// Ktor's and OkHttp's own types, named so this module needn't reach into their internals.
private val ConnectionFailureClassNames = setOf(
    "ConnectTimeoutException",
    "HttpRequestTimeoutException",
    "SocketTimeoutException",
    "UnresolvedAddressException",
    "StreamResetException",
)

private const val MAX_CAUSE_DEPTH = 16
