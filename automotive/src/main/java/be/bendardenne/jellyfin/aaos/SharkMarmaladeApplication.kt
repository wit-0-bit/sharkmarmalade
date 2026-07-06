package be.bendardenne.jellyfin.aaos

import android.app.Application
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SharkMarmaladeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // One-time cleanup of the retired album behaviour preference.
        PreferenceManager.getDefaultSharedPreferences(this).edit { remove("album_behaviour") }
    }
}
