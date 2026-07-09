package be.bendardenne.jellyfin.aaos

import android.accounts.AccountManager
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER

/**
 * Stores the Jellyfin credentials (server URL, username, access token) in app-private
 * SharedPreferences.
 *
 * This used to be backed by Android's AccountManager + an AbstractAccountAuthenticator, which
 * bought nothing (no system "Add account" integration is wanted, no cross-app token sharing) and
 * cost plenty: an authenticator Service + XML, a contract to honour, and a whole class of silent
 * failure modes on production car head units where account mutation can be restricted by the OEM.
 * A file in our own data dir cannot be refused by anyone.
 */
class JellyfinAccountManager(context: Context) {

    companion object {
        // Kept for the one-time migration read; the authenticator itself is gone.
        private const val LEGACY_ACCOUNT_TYPE = BuildConfig.APPLICATION_ID
        private const val LEGACY_TOKEN_TYPE = "$LEGACY_ACCOUNT_TYPE.access_token"
        private const val LEGACY_SERVER_KEY = "$LEGACY_ACCOUNT_TYPE.server"

        // Deliberately NOT the default SharedPreferences: the session callback listens for
        // changes on the default file (bitrate prefs), and credential writes must not fire it.
        private const val PREFS_FILE = "credentials"
        private const val KEY_SERVER = "server"
        private const val KEY_USERNAME = "username"
        private const val KEY_TOKEN = "token"
    }

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    init {
        migrateFromAccountManager(context)
    }

    val server: String?
        get() = prefs.getString(KEY_SERVER, null)

    val username: String?
        get() = prefs.getString(KEY_USERNAME, null)

    val token: String?
        get() = prefs.getString(KEY_TOKEN, null)

    val isAuthenticated: Boolean
        get() = token != null && server != null

    fun storeAccount(server: String, username: String, token: String) {
        prefs.edit {
            putString(KEY_SERVER, server)
            putString(KEY_USERNAME, username)
            putString(KEY_TOKEN, token)
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    /**
     * Best-effort copy of credentials out of the legacy AccountManager account, so an update
     * from an account-backed build keeps its sign-in. The OS purges accounts whose authenticator
     * disappeared, and it may win the race against this read — in that case the user signs in
     * once more, which is the acceptable floor.
     */
    private fun migrateFromAccountManager(context: Context) {
        if (prefs.contains(KEY_TOKEN)) {
            return
        }

        try {
            val accountManager = AccountManager.get(context)
            val account = accountManager.getAccountsByType(LEGACY_ACCOUNT_TYPE).firstOrNull()
                ?: return
            val server = accountManager.getUserData(account, LEGACY_SERVER_KEY)
            val token = accountManager.peekAuthToken(account, LEGACY_TOKEN_TYPE)
            if (server != null && token != null) {
                Log.i(LOG_MARKER, "Migrating credentials from AccountManager")
                storeAccount(server, account.name, token)
            }
        } catch (e: Exception) {
            // Migration is opportunistic; a fresh sign-in recovers from any failure here.
            Log.w(LOG_MARKER, "Legacy account migration failed", e)
        }
    }
}
