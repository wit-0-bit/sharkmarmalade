package be.bendardenne.jellyfin.aaos

import android.app.Application
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.PREF_BITRATE
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SharkMarmaladeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit {
            // One-time cleanup of the retired album behaviour preference.
            remove("album_behaviour")
            // The "Direct stream" bitrate option was removed (it produced the same 256k AAC
            // transcode as picking 256 kbps); migrate any stored value to a valid selection.
            if (prefs.getString(PREF_BITRATE, null) == "Direct stream") {
                putString(PREF_BITRATE, "256000")
            }
        }
    }
}
