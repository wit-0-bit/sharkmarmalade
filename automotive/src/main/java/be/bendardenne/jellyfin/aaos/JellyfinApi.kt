package be.bendardenne.jellyfin.aaos

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder

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