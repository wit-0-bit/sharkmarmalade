package be.bendardenne.jellyfin.aaos.signin

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.bendardenne.jellyfin.aaos.JellyfinAccountManager
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.QuickConnectDto
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class SignInActivityViewModel @Inject constructor() : ViewModel() {

    @Inject
    lateinit var jellyfin: Jellyfin

    @Inject
    lateinit var accountManager: JellyfinAccountManager

    private var quickConnectSecret: String = ""

    // Guards against a second polling loop being started (e.g. navigating back to the server
    // fragment and forward again) that would clobber quickConnectSecret and double-poll.
    private var quickConnectJob: Job? = null
    private var quickConnectServer: String? = null

    private val _loggedIn = MutableLiveData<Boolean>()
    val loggedIn: LiveData<Boolean> = _loggedIn

    // null means QuickConnect is unavailable (disabled server-side or an error occurred).
    private val _quickConnectCode = MutableLiveData<String?>()
    val quickConnectCode: LiveData<String?> = _quickConnectCode

    suspend fun pingServer(serverUrl: String): Boolean {
        return try {
            Log.i(LOG_MARKER, "Pinging $serverUrl")

            val response = withContext(Dispatchers.IO) {
                jellyfin.createApi(serverUrl).systemApi.getPingSystem()
            }

            response.status == 200
        } catch (e: Exception) {
            Log.w(LOG_MARKER, "Error", e)
            false
        }
    }

    fun startQuickConnect(serverUrl: String) {
        // Already polling this exact server (e.g. the fragment was just recreated): keep the
        // existing loop and its displayed code, don't spawn a second one. But a DIFFERENT server
        // (the user went back and chose another) — or a loop that already finished/failed — must
        // restart, otherwise we'd keep polling and showing the old server's code.
        if (quickConnectJob?.isActive == true && quickConnectServer == serverUrl) {
            return
        }
        quickConnectJob?.cancel()
        quickConnectServer = serverUrl

        Log.i(LOG_MARKER, "Initiate QuickConnect")
        val api = jellyfin.createApi(serverUrl)

        quickConnectJob = viewModelScope.launch {
            try {
                val isEnabled = withContext(Dispatchers.IO) {
                    api.quickConnectApi.getQuickConnectEnabled()
                }

                if (!isEnabled.content) {
                    _quickConnectCode.value = null
                    return@launch
                }

                val response = withContext(Dispatchers.IO) {
                    api.quickConnectApi.initiateQuickConnect()
                }

                quickConnectSecret = response.content.secret
                Log.d(LOG_MARKER, "QuickConnect initiated")
                // The SDK models the code as a String; keep it one end to end.
                _quickConnectCode.value = response.content.code

                while (isActive) {
                    delay(1.seconds)
                    try {
                        // Break the loop once authenticated so we don't re-authenticate every
                        // second forever after the user approves.
                        if (pollQuickConnect(api, serverUrl)) {
                            break
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A transient network blip (or a code that has expired) must not crash
                        // the sign-in screen. Keep polling; a real expiry just never succeeds
                        // until the user retries.
                        Log.w(LOG_MARKER, "QuickConnect poll failed, will retry", e)
                    }
                }
            } catch (e: CancellationException) {
                // The screen is being torn down (e.g. after a successful login); let it cancel.
                throw e
            } catch (e: Exception) {
                Log.w(LOG_MARKER, "QuickConnect unavailable", e)
                _quickConnectCode.value = null
            }
        }
    }

    /** Returns true once QuickConnect has been approved and the account is stored. */
    private suspend fun pollQuickConnect(api: ApiClient, server: String): Boolean {
        val response = withContext(Dispatchers.IO) {
            api.quickConnectApi.getQuickConnectState(quickConnectSecret)
        }

        Log.d(LOG_MARKER, "Checking QuickConnect")

        if (!response.content.authenticated) {
            return false
        }

        val loginResponse = withContext(Dispatchers.IO) {
            api.userApi.authenticateWithQuickConnect(QuickConnectDto(quickConnectSecret))
        }

        loginSuccess(
            server,
            loginResponse.content.user?.name!!,
            loginResponse.content.accessToken!!
        )
        return true
    }

    suspend fun login(server: String, username: String, password: String): Boolean {
        return try {
            val response = withContext(Dispatchers.IO) {
                jellyfin.createApi(server).userApi.authenticateUserByName(username, password)
            }

            if (response.status == 200) {
                loginSuccess(server, username, response.content.accessToken!!)
            }

            response.status == 200
        } catch (e: Exception) {
            Log.e(LOG_MARKER, "Error", e)
            false
        }
    }

    private fun loginSuccess(
        server: String,
        username: String,
        token: String
    ) {
        Log.i(LOG_MARKER, "$username successfully authenticated")
        accountManager.storeAccount(server, username, token)
        _loggedIn.postValue(true)
    }

    companion object {
        internal const val JELLYFIN_SERVER_URL = "jellyfinServer"
    }
}
