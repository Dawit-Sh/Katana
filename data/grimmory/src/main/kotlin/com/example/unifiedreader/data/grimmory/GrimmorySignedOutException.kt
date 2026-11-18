package com.example.unifiedreader.data.grimmory

/**
 * Grimmory turned down the refresh token: it expired (30 days unused), was already used once (each renewal
 * replaces it), or the account signed out everywhere. Only signing in again with the password helps, so
 * the message says so in place of the server's "Invalid credentials".
 */
class GrimmorySignedOutException : RuntimeException(
    "Grimmory signed this device out. Sign in again in Settings › Sources › Grimmory.",
)
