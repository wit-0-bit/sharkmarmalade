package be.bendardenne.jellyfin.aaos.signin

import android.content.ComponentName
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.concurrent.futures.await
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import android.util.Log
import be.bendardenne.jellyfin.aaos.JellyfinMediaLibrarySessionCallback.Companion.LOGIN_COMMAND
import be.bendardenne.jellyfin.aaos.JellyfinMusicService
import be.bendardenne.jellyfin.aaos.R
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch


@AndroidEntryPoint
class SignInActivity : AppCompatActivity() {

    private lateinit var viewModel: SignInActivityViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)

        viewModel = ViewModelProvider(this)[SignInActivityViewModel::class.java]

        viewModel.loggedIn.observe(this) { loggedIn ->
            if (loggedIn == true) {
                notifyServiceOfLogin(finishAfter = true)
            }
        }

        if (savedInstanceState == null) {
            // The media host can cache an auth-error card and fire its resolution intent (this
            // activity) even after a successful sign-in. When that happens, show the actual state
            // instead of a blank server form, and poke the service so the host re-fetches the
            // root and clears its stale error.
            val alreadySignedIn = viewModel.accountManager.isAuthenticated
            if (alreadySignedIn) {
                notifyServiceOfLogin(finishAfter = false)
            }

            supportFragmentManager.beginTransaction()
                .add(
                    R.id.sign_in_container,
                    if (alreadySignedIn) SignedInFragment() else ServerSignInFragment()
                )
                .commit()
        }
    }

    /**
     * Sends LOGIN_COMMAND to the media service, which re-applies credentials and notifies the
     * host to re-fetch the browse root. This is a refresh hint, not a required link: the service
     * also re-syncs credentials from storage on every request it serves.
     */
    private fun notifyServiceOfLogin(finishAfter: Boolean) {
        val service = ComponentName(applicationContext, JellyfinMusicService::class.java)
        val future = MediaController.Builder(
            applicationContext,
            SessionToken(applicationContext, service)
        ).buildAsync()

        lifecycleScope.launch {
            try {
                val controller = future.await()
                try {
                    controller.sendCustomCommand(
                        SessionCommand(LOGIN_COMMAND, Bundle()),
                        Bundle()
                    )
                } finally {
                    // Release the controller so we don't leak a live session
                    // connection for the rest of the process.
                    controller.release()
                }
            } catch (e: Exception) {
                // The account is already stored; the service picks it up on next start.
                Log.e(LOG_MARKER, "Failed to notify service of login", e)
            } finally {
                if (finishAfter) {
                    finish()
                }
            }
        }
    }
}