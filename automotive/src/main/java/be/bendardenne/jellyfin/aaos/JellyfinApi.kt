package be.bendardenne.jellyfin.aaos

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder

/**
 * True when a failure is transport-level (dead radio, DNS, TLS, timeout) rather than a real
 * answer from the server. The Jellyfin SDK wraps these in its own exception types with the
 * IOException somewhere down the cause chain — walk it.
 */
fun isNetworkFailure(e: Throwable): Boolean {
    var cause: Throwable? = e
    var depth = 0
    while (cause != null && depth < 8) {
        if (cause is java.io.IOException) {
            return true
        }
        cause = cause.cause
        depth++
    }
    return false
}

/**
 * Extension function which applies credentials to a Jellyfin ClientApi and returns
 * the associated headers to use in custom requests.
 */
fun ApiClient.auth(accountManager: JellyfinAccountManager): Map<String, String> {
    update(
        baseUrl = accountManager.server,
        accessToken = accountManager.token
    )

    // Use the SDK's own header builder (the same one it uses for every SDK-mediated call) rather
    // than hand-rolling the string, so the two can't drift and parameter values get encoded.
    val header = AuthorizationHeaderBuilder.buildHeader(
        clientName = clientInfo.name,
        clientVersion = clientInfo.version,
        deviceId = deviceInfo.id,
        deviceName = deviceInfo.name,
        accessToken = accessToken,
    )

    return mapOf("Authorization" to header)
}