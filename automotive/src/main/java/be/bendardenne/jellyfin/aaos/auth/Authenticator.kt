package be.bendardenne.jellyfin.aaos.auth

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import be.bendardenne.jellyfin.aaos.BuildConfig
import be.bendardenne.jellyfin.aaos.signin.SignInActivity

class Authenticator(val context: Context) : AbstractAccountAuthenticator(context) {
    companion object {
        // Must match the applicationId-derived account_type resource used in authenticator.xml.
        const val ACCOUNT_TYPE = BuildConfig.APPLICATION_ID

        // Must match JellyfinAccountManager.TOKEN_TYPE, which is where the real token is stored
        // (via setAuthToken) and read (via peekAuthToken).
        const val AUTHTOKEN_TYPE = "$ACCOUNT_TYPE.access_token"
    }

    override fun editProperties(p0: AccountAuthenticatorResponse?, p1: String?): Bundle =
        throw UnsupportedOperationException()

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle {
        // Contract: hand back an intent to collect credentials. Launch sign-in so "Add account"
        // from system Settings actually does something instead of silently no-op'ing.
        val intent = Intent(context, SignInActivity::class.java)
        intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        return Bundle().apply { putParcelable(AccountManager.KEY_INTENT, intent) }
    }

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?
    ): Bundle? = null

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        loginOptions: Bundle?
    ): Bundle {
        if (authTokenType != AUTHTOKEN_TYPE) {
            val res = Bundle()
            res.putString(AccountManager.KEY_ERROR_MESSAGE, "invalid auth token type")
            return res
        }

        if (account == null) {
            val res = Bundle()
            res.putString(AccountManager.KEY_ERROR_MESSAGE, "account must not be null")
            return res
        }

        // The real token is stored via setAuthToken (the password field is always blank), so read
        // it back with peekAuthToken rather than returning the empty password.
        val accountManager = AccountManager.get(context)
        val token = accountManager.peekAuthToken(account, authTokenType)
        if (token != null) {
            val res = Bundle()
            res.putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
            res.putString(AccountManager.KEY_ACCOUNT_TYPE, ACCOUNT_TYPE)
            res.putString(AccountManager.KEY_AUTHTOKEN, token)
            return res
        }

        // no stored token, ask for sign-in
        val intent = Intent(context, SignInActivity::class.java)
        intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        val bundle = Bundle()
        bundle.putParcelable(AccountManager.KEY_INTENT, intent)
        return bundle
    }

    override fun getAuthTokenLabel(p0: String?): String? = null

    override fun updateCredentials(
        p0: AccountAuthenticatorResponse?,
        p1: Account?,
        p2: String?,
        p3: Bundle?
    ): Bundle? = null

    override fun hasFeatures(
        p0: AccountAuthenticatorResponse?,
        p1: Account?,
        p2: Array<out String>?
    ): Bundle {
        val result = Bundle()
        result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)
        return result
    }
}