package be.bendardenne.jellyfin.aaos

import android.accounts.Account
import android.accounts.AccountManager
import android.os.Bundle
import android.util.Log
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER
import be.bendardenne.jellyfin.aaos.auth.Authenticator

class JellyfinAccountManager(private val accountManager: AccountManager) {

    companion object {
        const val ACCOUNT_TYPE = Authenticator.ACCOUNT_TYPE
        const val TOKEN_TYPE = "$ACCOUNT_TYPE.access_token"
        const val USERDATA_SERVER_KEY = "$ACCOUNT_TYPE.server"
    }

    private val account: Account?
        get() = accountManager.getAccountsByType(ACCOUNT_TYPE).firstOrNull()

    val server: String?
        get() = account?.let { accountManager.getUserData(it, USERDATA_SERVER_KEY) }

    val token: String?
        get() = account?.let { accountManager.peekAuthToken(it, TOKEN_TYPE) }

    val isAuthenticated: Boolean
        get() = token != null

    fun storeAccount(server: String, username: String, token: String): Account {
        // Android keys accounts by (name, type), so there is at most one account per username —
        // match on the name alone. Matching on server too used to miss the existing account when
        // the same user re-signed in against a moved server, then addAccountExplicitly returned
        // false (name+type already exists) without applying the new server, leaving a stale URL
        // paired with a fresh token that 401s forever.
        var account = accountManager.getAccountsByType(ACCOUNT_TYPE)
            .firstOrNull { it.name == username }

        if (account == null) {
            account = Account(username, ACCOUNT_TYPE)
            val added = accountManager.addAccountExplicitly(
                account,
                "",     // We don't keep the password, just the auth token.
                Bundle().also { it.putString(USERDATA_SERVER_KEY, server) }
            )
            if (!added) {
                Log.w(LOG_MARKER, "addAccountExplicitly returned false for $username")
            }
        }

        // Always (re)assert the server URL and token so a re-login to a moved server updates the
        // stored URL instead of keeping the stale one.
        accountManager.setUserData(account, USERDATA_SERVER_KEY, server)
        accountManager.setAuthToken(account, TOKEN_TYPE, token)

        return account
    }
}