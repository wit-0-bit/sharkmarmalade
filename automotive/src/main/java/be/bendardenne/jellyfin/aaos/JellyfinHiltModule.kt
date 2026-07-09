package be.bendardenne.jellyfin.aaos

import android.content.Context
import be.bendardenne.jellyfin.aaos.downloads.DownloadStore
import be.bendardenne.jellyfin.aaos.downloads.DownloadSyncer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class JellyfinHiltModule {

    @Provides
    @Singleton
    fun provideJellyfin(@ApplicationContext appContext: Context): Jellyfin {
        val version =
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName

        return createJellyfin {
            clientInfo = ClientInfo(appContext.getString(R.string.app_name), version ?: "unknown")
            deviceInfo = androidDevice(appContext)
            context = appContext
        }
    }

    @Provides
    @Singleton
    fun provideAccountManager(@ApplicationContext appContext: Context): JellyfinAccountManager {
        return JellyfinAccountManager(appContext)
    }

    @Provides
    @Singleton
    fun provideDownloadStore(
        @ApplicationContext appContext: Context,
        accountManager: JellyfinAccountManager
    ): DownloadStore {
        return DownloadStore(appContext, accountManager)
    }

    // Process-scoped on purpose: the media service dies whenever its last controller unbinds,
    // and a sync tied to it gets cancelled mid-download. The syncer owns its own ApiClient and
    // pulls credentials from storage at each sync.
    @Provides
    @Singleton
    fun provideDownloadSyncer(
        @ApplicationContext appContext: Context,
        jellyfin: Jellyfin,
        accountManager: JellyfinAccountManager,
        store: DownloadStore
    ): DownloadSyncer {
        return DownloadSyncer(appContext, jellyfin.createApi(), accountManager, store)
    }
}

