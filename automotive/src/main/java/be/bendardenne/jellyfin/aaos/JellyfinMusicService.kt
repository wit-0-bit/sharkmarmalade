package be.bendardenne.jellyfin.aaos

import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.preference.PreferenceManager
import be.bendardenne.jellyfin.aaos.JellyfinMediaLibrarySessionCallback.Companion.PLAYLIST_INDEX_PREF
import be.bendardenne.jellyfin.aaos.JellyfinMediaLibrarySessionCallback.Companion.PLAYLIST_TRACK_POSITON_MS_PREF
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.DOWNLOADED_ALBUMS
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.DOWNLOADED_ARTISTS
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.DOWNLOADS
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.ROOT_ID
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER
import be.bendardenne.jellyfin.aaos.downloads.DownloadStore
import be.bendardenne.jellyfin.aaos.downloads.DownloadSyncer
import dagger.hilt.android.AndroidEntryPoint
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.serializer.toUUID
import javax.inject.Inject

@AndroidEntryPoint
@OptIn(UnstableApi::class)
class JellyfinMusicService : MediaLibraryService() {

    @Inject
    lateinit var jellyfin: Jellyfin

    // Process-scoped singletons: downloads must survive this service's (frequent, controller-
    // driven) destruction.
    @Inject
    lateinit var downloadStore: DownloadStore

    @Inject
    lateinit var downloadSyncer: DownloadSyncer

    private lateinit var accountManager: JellyfinAccountManager
    private lateinit var jellyfinApi: ApiClient
    private lateinit var mediaSourceFactory: DefaultMediaSourceFactory
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private lateinit var callback: JellyfinMediaLibrarySessionCallback

    private val handler: Handler = Handler(Looper.getMainLooper())
    private var currentPlaybackTime: Long = 0;
    private var currentTrack: MediaItem? = null;

    private lateinit var playbackPoll: Runnable;


    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                // Persist the current index of the queue in the preferences.
                // This is restored in onPlaybackResumption
                PreferenceManager.getDefaultSharedPreferences(this@JellyfinMusicService).edit {
                    putInt(PLAYLIST_INDEX_PREF, player.currentMediaItemIndex)
                }

                SuspendToFutureAdapter.launchFuture { reportPlayback(player) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        Log.i(LOG_MARKER, "onCreate")

        accountManager = JellyfinAccountManager(applicationContext)
        jellyfinApi = jellyfin.createApi()
        mediaSourceFactory = DefaultMediaSourceFactory(this)

        downloadSyncer.onDownloadsChanged = {
            handler.post {
                // Only subscribed parents actually refresh; the rest are ignored by media3.
                mediaLibrarySession.notifyChildrenChanged(DOWNLOADS, 2, null)
                mediaLibrarySession.notifyChildrenChanged(DOWNLOADED_ARTISTS, Int.MAX_VALUE, null)
                mediaLibrarySession.notifyChildrenChanged(DOWNLOADED_ALBUMS, Int.MAX_VALUE, null)
            }
        }

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        player.addListener(playerListener)

        // Start in no repeat & no shuffle by default
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false
        // TODO  double check if we can't get events for this
        // https://proandroiddev.com/mastering-playback-state-with-exo-player-977016aa5003
        pollForPlaybackStatus(player)

        callback = JellyfinMediaLibrarySessionCallback(this, accountManager, jellyfinApi)

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, callback)
            .setMediaButtonPreferences(CommandButtons.createButtons(player))
            .build()

        if (accountManager.isAuthenticated) {
            onLogin()
        }

        // Periodic reconcile while the service lives, so collection edits made from the phone
        // reach the car within a sync interval of driving. syncIfDue self-throttles.
        handler.postDelayed(object : Runnable {
            override fun run() {
                downloadSyncer.requestSync()
                handler.postDelayed(this, 5 * 60_000L)
            }
        }, 5 * 60_000L)

        // The car's radio comes and goes; sync in whatever connectivity windows appear instead
        // of waiting for the next periodic tick.
        try {
            getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(LOG_MARKER, "Could not register network callback", e)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            downloadSyncer.requestSync()
        }
    }

    /** Settings' "Sync now" lands here via the SYNC_DOWNLOADS custom session command. */
    fun requestDownloadSync() {
        downloadSyncer.requestSync(force = true)
    }

    private fun pollForPlaybackStatus(player: ExoPlayer) {
        // Repeatedly poll the player for current elapsed playback time
        // We need this to report elapsed time on playback stop, which is needed for scrobbling.
        playbackPoll = Runnable {
            if (player.isPlaying) {
                currentPlaybackTime = player.currentPosition
                currentTrack = player.currentMediaItem

                PreferenceManager.getDefaultSharedPreferences(this@JellyfinMusicService).edit {
                    putLong(PLAYLIST_TRACK_POSITON_MS_PREF, currentPlaybackTime)
                }
            }

            handler.postDelayed(playbackPoll, 1000)
        }
        handler.postDelayed(playbackPoll, 1000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        Log.i(LOG_MARKER, "onDestroy")

        mediaLibrarySession.release()
        mediaLibrarySession.player.removeListener(playerListener)
        mediaLibrarySession.player.release()
        handler.removeCallbacks(playbackPoll)
        handler.removeCallbacksAndMessages(null)
        try {
            getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Never registered (or already gone); nothing to clean up.
        }
        // The syncer outlives this service; don't leave it notifying a released session.
        downloadSyncer.onDownloadsChanged = null
        super.onDestroy()
    }

    fun onLogin() {
        applyAuth()

        // Trigger a refresh upon login.
        mediaLibrarySession.notifyChildrenChanged(ROOT_ID, 3, null)

        // Reconcile downloads whenever credentials (re)land. Throttled internally.
        downloadSyncer.requestSync()
    }

    /**
     * (Re)applies the stored credentials to the API client and the streaming data source.
     *
     * This must be callable at any time, not just at login: the API client's token is process
     * state, while the stored account is persistent, and the two can drift — e.g. when the
     * sign-in activity's LOGIN_COMMAND handshake is lost (seen on the Polestar head unit), the
     * fresh token lands in account storage while the service keeps browsing with the token it
     * had at startup, and the server 401s everything. The session callback re-syncs via this
     * before serving requests, so a stale client token can never outlive one browse call.
     */
    fun applyAuth() {
        val headers = jellyfinApi.auth(accountManager)

        val authedFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        val cachedFactory = CacheDataSource.Factory()
            .setCache(AudioCache.getInstance(this))
            .setCacheKeyFactory(AudioCache.cacheKeyFactory)
            .setUpstreamDataSourceFactory(authedFactory)
            // A cache I/O problem should degrade to plain network, not fail playback.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Route by scheme: downloaded tracks are file:// URIs and must go straight to disk
        // (FileDataSource), NOT through the HTTP/cache chain — DefaultHttpDataSource throws on
        // file URLs, and SimpleCache-ing a local file would just duplicate it.
        val schemeRoutingFactory = DefaultDataSource.Factory(this, cachedFactory)
        mediaSourceFactory.setDataSourceFactory(schemeRoutingFactory)
    }

    private suspend fun reportPlayback(player: Player) {
        val exoPlayer = player as ExoPlayer
        // MediaItem has changed; if there was a previous item playing, mark it stopped.
        if (currentTrack != null) {
            try {
                Log.i(LOG_MARKER, "Reporting playback stopped: ${currentPlaybackTime}")
                jellyfinApi.playStateApi.onPlaybackStopped(
                    currentTrack!!.mediaId.toUUID(),
                    positionTicks = 10000 * currentPlaybackTime
                )
            } catch (e: Exception) {
                // The playstate report is launched into a discarded future, so a failure here is
                // otherwise completely silent (lost scrobble). At least log it.
                Log.w(LOG_MARKER, "Failed to report playback stopped", e)
            }
        }

        if (player.currentMediaItem != null) {
            try {
                val format = exoPlayer.audioFormat
                val formatString = "${format?.containerMimeType} at ${format?.averageBitrate} bps"

                Log.i(
                    LOG_MARKER,
                    "Playing $formatString: ${exoPlayer.currentMediaItem?.localConfiguration?.uri}"
                )
                jellyfinApi.playStateApi.onPlaybackStart(
                    player.currentMediaItem!!.mediaId.toUUID(),
                    canSeek = true
                )
            } catch (e: Exception) {
                Log.w(LOG_MARKER, "Failed to report playback start", e)
            }
        }
    }
}
