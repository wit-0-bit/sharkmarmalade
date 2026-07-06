package be.bendardenne.jellyfin.aaos

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Rating
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT
import androidx.media3.session.MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT
import androidx.media3.session.MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_USING_CAR_APP_LIBRARY_INTENT_COMPAT
import androidx.media3.session.MediaConstants.EXTRAS_KEY_MEDIA_ART_SIZE_PIXELS
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.preference.PreferenceManager
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.PARENT_KEY
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.PLAY_ALL_PREFIX
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.RANDOM_ALBUMS
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.ROOT_ID
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.PREF_BITRATE
import be.bendardenne.jellyfin.aaos.signin.SignInActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.serializer.toUUID
import kotlin.collections.listOf


@OptIn(UnstableApi::class)
class JellyfinMediaLibrarySessionCallback(
    private val service: JellyfinMusicService,
    private val accountManager: JellyfinAccountManager,
    private val jellyfinApi: ApiClient
) : MediaLibraryService.MediaLibrarySession.Callback {

    companion object {
        const val LOGIN_COMMAND = "be.bendardenne.jellyfin.aaos.COMMAND.LOGIN"
        const val REPEAT_COMMAND = "be.bendardenne.jellyfin.aaos.COMMAND.REPEAT"
        const val SHUFFLE_COMMAND = "be.bendardenne.jellyfin.aaos.COMMAND.SHUFFLE"

        const val PLAYLIST_IDS_PREF = "playlistIds"
        const val PLAYLIST_INDEX_PREF = "playlistIndex"
        const val PLAYLIST_TRACK_POSITON_MS_PREF = "playlistTrackPositionMs"
    }

    private lateinit var tree: JellyfinMediaTree;

    private val mainHandler = Handler(Looper.getMainLooper())

    private val subscriptions:
            MutableMap<MediaLibraryService.MediaLibrarySession, MutableSet<String>> = mutableMapOf()

    // Listener must be kept in a field to prevent GC (weak referenced by SharedPref.)
    private val prefListener: SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == PREF_BITRATE) {
                Log.i(LOG_MARKER, "Preferences invalidated")

                if (!::tree.isInitialized) {
                    Log.d(LOG_MARKER, "Tree not initialized yet, ignoring preference change")
                    return@OnSharedPreferenceChangeListener
                }

                // Clear the item cache on any setting impacting the media items.
                tree.evictCache()
                // And force Sessions to refetch the new items.
                subscriptions.forEach { session ->
                    subscriptions[session.key]!!.forEach { item ->
                        session.key.notifyChildrenChanged(item, Int.MAX_VALUE, null)
                    }
                }
            }
        }

    init {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(service)
        sharedPreferences.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ConnectionResult {
        Log.i(LOG_MARKER, "onConnect")
        val connectionResult = super.onConnect(session, controller)

        val sessionCommands = connectionResult.availableSessionCommands
            .buildUpon()
            .add(SessionCommand(LOGIN_COMMAND, Bundle()))
            .add(SessionCommand(REPEAT_COMMAND, Bundle()))
            .add(SessionCommand(SHUFFLE_COMMAND, Bundle()))
            .build()

        return ConnectionResult.accept(
            sessionCommands,
            connectionResult.availablePlayerCommands
        )
    }

    override fun onSubscribe(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        Log.i(LOG_MARKER, "OnSubscribe $session - $parentId")
        subscriptions.computeIfAbsent(session) { mutableSetOf() }.add(parentId)
        return super.onSubscribe(session, browser, parentId, params)
    }

    override fun onUnsubscribe(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String
    ): ListenableFuture<LibraryResult<Void>> {
        Log.i(LOG_MARKER, "OnUnsubscribe $session - $parentId")
        subscriptions[session]?.remove(parentId)
        return super.onUnsubscribe(session, browser, parentId)
    }

    /**
     * The tree is normally built on the first onGetLibraryRoot (which carries the art-size hint),
     * but voice search and playback resumption can arrive first on a cold process — e.g.
     * "Hey Google, play X" or resuming from the head unit without ever opening the app.
     */
    private fun ensureTree(artSize: Int = 512) {
        if (::tree.isInitialized) {
            return
        }

        val itemFactory = MediaItemFactory(service, jellyfinApi, artSize)
        tree = JellyfinMediaTree(service, jellyfinApi, itemFactory)
        tree.onChildrenUpdated = { parentId, itemCount ->
            // Fires from Dispatchers.IO; subscriptions is main-thread-confined.
            mainHandler.post {
                subscriptions.forEach { (session, parentIds) ->
                    if (parentIds.contains(parentId)) {
                        session.notifyChildrenChanged(parentId, itemCount, null)
                    }
                }
            }
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        Log.i(LOG_MARKER, "onGetRoot")

        val artSize = params?.extras?.getInt(EXTRAS_KEY_MEDIA_ART_SIZE_PIXELS) ?: 512
        Log.d(LOG_MARKER, "Art size hint from system: $artSize")
        ensureTree(artSize)

        return SuspendToFutureAdapter.launchFuture {
            LibraryResult.ofItem(
                tree.getItem(ROOT_ID),
                params
            )
        }
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        Log.i(LOG_MARKER, "onGetChildren $parentId")
        if (!accountManager.isAuthenticated) {
            return Futures.immediateFuture(authErrorResult())
        }
        ensureTree()

        return SuspendToFutureAdapter.launchFuture {
            try {
                val children = paginate(tree.getChildren(parentId), page, pageSize)
                LibraryResult.ofItemList(children, params)
            } catch (e: Exception) {
                Log.w(LOG_MARKER, "onGetChildren failed for $parentId", e)
                errorResult(e)
            }
        }
    }

    private fun authenticationExtras(): Bundle {
        return Bundle().also {
            it.putString(
                EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT,
                service.getString(R.string.sign_in_to_your_jellyfin_server)
            )

            val signInIntent = Intent(service, SignInActivity::class.java)

            val flags = if (Util.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            it.putParcelable(
                EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT,
                PendingIntent.getActivity(service, 0, signInIntent, flags)
            )

            it.putParcelable(
                EXTRAS_KEY_ERROR_RESOLUTION_USING_CAR_APP_LIBRARY_INTENT_COMPAT,
                PendingIntent.getActivity(service, 0, signInIntent, flags)
            )
        }
    }

    private fun <T : Any> authErrorResult(): LibraryResult<T> {
        return LibraryResult.ofError<T>(
            SessionError(
                SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
                service.getString(R.string.sign_in_to_your_jellyfin_server)
            ),
            MediaLibraryService.LibraryParams.Builder()
                .setExtras(authenticationExtras()).build()
        )
    }

    // Maps a fetch failure to a distinguishable result instead of an opaque failed future: an
    // expired token (HTTP 401) routes the user back to sign-in; anything else is a generic error.
    private fun <T : Any> errorResult(e: Exception): LibraryResult<T> {
        // Never swallow coroutine cancellation (e.g. the browser disconnecting mid-fetch).
        if (e is kotlinx.coroutines.CancellationException) {
            throw e
        }
        if (e is InvalidStatusException && e.status == 401) {
            return authErrorResult()
        }
        return LibraryResult.ofError<T>(
            SessionError(SessionError.ERROR_UNKNOWN, service.getString(R.string.could_not_load)),
            MediaLibraryService.LibraryParams.Builder().build()
        )
    }

    // The car host asks for children/search results a page at a time; honour page/pageSize instead
    // of returning the same full list for every page (which reads as duplicates to a paging host).
    private fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) {
            return items
        }
        val start = page.coerceAtLeast(0).toLong() * pageSize
        if (start >= items.size) {
            return emptyList()
        }
        val from = start.toInt()
        val to = minOf(items.size.toLong(), start + pageSize).toInt()
        return items.subList(from, to)
    }

    // An empty resolved queue must never be applied: setMediaItems(emptyList) wipes the live queue
    // and saving it destroys resumption state. Failing the request makes media3's stub ignore it,
    // keeping both intact (the same protection the voice-search path already uses).
    private fun requireNonEmpty(items: List<MediaItem>): List<MediaItem> {
        if (items.isEmpty()) {
            throw UnsupportedOperationException("Resolved to an empty queue")
        }
        return items
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        Log.i(LOG_MARKER, "onGetItem $mediaId")
        if (!accountManager.isAuthenticated) {
            return Futures.immediateFuture(authErrorResult())
        }
        ensureTree()
        return SuspendToFutureAdapter.launchFuture {
            try {
                LibraryResult.ofItem(tree.getItem(mediaId), null)
            } catch (e: Exception) {
                Log.w(LOG_MARKER, "onGetItem failed for $mediaId", e)
                errorResult(e)
            }
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        Log.i(LOG_MARKER, "onAddMediaItems $mediaItems")
        ensureTree()
        return SuspendToFutureAdapter.launchFuture {
            val searchQuery = searchQueryOrNull(mediaItems)
            if (searchQuery != null) {
                resolveSearch(
                    mediaSession,
                    searchQuery,
                    mediaItems[0].requestMetadata.extras
                ).mediaItems
            } else if (isPlayAll(mediaItems)) {
                resolvePlayAll(mediaSession, mediaItems[0].mediaId)
            } else {
                resolveMediaItems(mediaItems)
            }
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        browser: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Log.i(LOG_MARKER, "onSetMediaItems $mediaItems")
        ensureTree()
        return SuspendToFutureAdapter.launchFuture {
            // Assistant's playFromSearch arrives as a single item with a blank mediaId and the
            // query in requestMetadata. Handle it before anything can pass "" to tree.getItem().
            val searchQuery = searchQueryOrNull(mediaItems)
            if (searchQuery != null) {
                val result =
                    resolveSearch(mediaSession, searchQuery, mediaItems[0].requestMetadata.extras)
                if (result.mediaItems.isEmpty()) {
                    // Returning an empty result would be applied as setMediaItems(emptyList),
                    // wiping the live queue, and saving it would destroy resumption state. A
                    // failed future is ignored by media3's legacy stub, keeping both intact.
                    throw UnsupportedOperationException("Voice search matched nothing")
                }
                savePlaylist(result.mediaItems)
                return@launchFuture result
            }

            if (isPlayAll(mediaItems)) {
                val resolvedItems = requireNonEmpty(resolvePlayAll(mediaSession, mediaItems[0].mediaId))
                savePlaylist(resolvedItems)
                // For the shuffled (artist) flavour, an explicit start index would override
                // shuffle order: INDEX_UNSET lets the player start at the shuffle order's first
                // item instead of always the first track of the first album.
                return@launchFuture if (mediaSession.player.shuffleModeEnabled) {
                    MediaSession.MediaItemsWithStartPosition(
                        resolvedItems,
                        C.INDEX_UNSET,
                        C.TIME_UNSET
                    )
                } else {
                    MediaSession.MediaItemsWithStartPosition(
                        resolvedItems,
                        0,
                        startPositionMs
                    )
                }
            }

            if (isSingleItemWithParent(mediaItems)) {
                val singleItem = mediaItems[0]
                val resolvedItems = requireNonEmpty(expandSingleItem(singleItem))

                val mediaItemsWithStartPosition = MediaSession.MediaItemsWithStartPosition(
                    resolvedItems,
                    resolvedItems.indexOfFirst { it.mediaId == singleItem.mediaId },
                    startPositionMs
                )
                savePlaylist(resolvedItems)
                return@launchFuture mediaItemsWithStartPosition
            }

            val resolvedItems = requireNonEmpty(resolveMediaItems(mediaItems))
            val mediaItemsWithStartPosition = MediaSession.MediaItemsWithStartPosition(
                resolvedItems,
                startIndex,
                startPositionMs
            )
            savePlaylist(resolvedItems)
            mediaItemsWithStartPosition
        }
    }

    /**
     * Saves the playlist to shared preferences, so it can be restored in onPlaybackResumption.
     */
    private fun savePlaylist(resolvedItems: List<MediaItem>) {
        val playlistIDs = resolvedItems.joinToString(",") { it.mediaId }
        Log.d(LOG_MARKER, "Saving playlist $playlistIDs")

        PreferenceManager.getDefaultSharedPreferences(service).edit {
            putString(PLAYLIST_IDS_PREF, playlistIDs)
        }
    }

    private fun isPlayAll(mediaItems: List<MediaItem>): Boolean {
        return mediaItems.size == 1 && mediaItems[0].mediaId.startsWith(PLAY_ALL_PREFIX)
    }

    /**
     * Resolves a synthetic "Play All" row to its parent's real playable tracks: everything by an
     * artist (shuffled), or an album in track order.
     */
    private suspend fun resolvePlayAll(session: MediaSession, mediaId: String): List<MediaItem> {
        return resolveParentTracks(session, mediaId.removePrefix(PLAY_ALL_PREFIX))
    }

    /**
     * Resolves a container (artist/album/playlist) to its real playable tracks: everything by an
     * artist (shuffled), or an album/playlist in track order.
     */
    private suspend fun resolveParentTracks(
        session: MediaSession,
        parentId: String
    ): List<MediaItem> {
        val isArtist =
            tree.getItem(parentId).mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_ARTIST

        val children = realChildren(parentId)
        val tracks = if (isArtist) {
            children.flatMap { album ->
                realChildren(album.mediaId).filter { it.mediaMetadata.isPlayable == true }
            }
        } else {
            children.filter { it.mediaMetadata.isPlayable == true }
        }

        if (tracks.isNotEmpty()) {
            session.player.shuffleModeEnabled = isArtist
            session.setMediaButtonPreferences(CommandButtons.createButtons(session.player))
        }

        return tracks
    }

    /**
     * Detects a (legacy) play/prepare-from-search request. Assistant's onPlayFromSearch is
     * bridged by media3's MediaSessionLegacyStub into onSetMediaItems with a single MediaItem
     * whose mediaId is blank and whose requestMetadata carries the search query and the
     * Assistant extras. Returns the query ("" for "play some music"-style empty queries, which
     * also covers blank-id items with no query at all), or null if this is not a search request.
     */
    private fun searchQueryOrNull(mediaItems: List<MediaItem>): String? {
        if (mediaItems.size != 1 || mediaItems[0].mediaId.isNotBlank()) {
            return null
        }

        return mediaItems[0].requestMetadata.searchQuery ?: ""
    }

    /**
     * Resolves a voice search query to a playable queue. An empty query plays a random album; a
     * matching artist plays their whole discography shuffled; a matching album/playlist plays in
     * order; a matching track plays in its parent context. No match resolves to an empty queue.
     */
    private suspend fun resolveSearch(
        session: MediaSession,
        query: String,
        extras: Bundle?
    ): MediaSession.MediaItemsWithStartPosition {
        val trimmed = query.trim()

        if (trimmed.isEmpty()) {
            // "Play some music": play the first random album in order, not the whole random tab.
            val album = realChildren(RANDOM_ALBUMS).firstOrNull()
                ?: return emptySearchResult("blank query, no albums available")
            Log.i(
                LOG_MARKER,
                "Voice search: blank query -> random album \"${album.mediaMetadata.title}\""
            )
            return searchResultOf(session, resolveParentTracks(session, album.mediaId))
        }

        val focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)
        val candidates = rankSearchResults(trimmed, tree.search(trimmed), focus)
        if (candidates.isEmpty()) {
            return emptySearchResult("no results for \"$trimmed\" (focus=$focus)")
        }

        // A winner can resolve to zero tracks (e.g. an empty playlist whose title matches the
        // query exactly); fall back to the next-best result instead of queueing nothing.
        for (winner in candidates) {
            val result = resolveSearchWinner(session, winner)
            if (result.mediaItems.isNotEmpty()) {
                Log.i(
                    LOG_MARKER,
                    "Voice search: \"$trimmed\" (focus=$focus) -> " +
                            "\"${winner.mediaMetadata.title}\" " +
                            "(mediaType=${winner.mediaMetadata.mediaType})"
                )
                return result
            }
            Log.i(
                LOG_MARKER,
                "Voice search: \"${winner.mediaMetadata.title}\" has no playable tracks, " +
                        "trying next result"
            )
        }

        return emptySearchResult(
            "all results for \"$trimmed\" resolved to zero tracks (focus=$focus)"
        )
    }

    /**
     * Resolves a single search result to a playable queue: a container (artist/album/playlist)
     * plays its tracks, a track plays in its parent context when one is known.
     */
    private suspend fun resolveSearchWinner(
        session: MediaSession,
        winner: MediaItem
    ): MediaSession.MediaItemsWithStartPosition {
        return when (winner.mediaMetadata.mediaType) {
            MediaMetadata.MEDIA_TYPE_ARTIST,
            MediaMetadata.MEDIA_TYPE_ALBUM,
            MediaMetadata.MEDIA_TYPE_PLAYLIST ->
                searchResultOf(session, resolveParentTracks(session, winner.mediaId))

            else -> {
                // A track: play it in its parent context when one is known, like a browse tap.
                if (isSingleItemWithParent(listOf(winner))) {
                    val items = expandSingleItem(winner)
                    MediaSession.MediaItemsWithStartPosition(
                        items,
                        items.indexOfFirst { it.mediaId == winner.mediaId }.coerceAtLeast(0),
                        C.TIME_UNSET
                    )
                } else {
                    searchResultOf(session, resolveMediaItems(listOf(winner)))
                }
            }
        }
    }

    private fun emptySearchResult(reason: String): MediaSession.MediaItemsWithStartPosition {
        Log.i(LOG_MARKER, "Voice search: $reason")
        return MediaSession.MediaItemsWithStartPosition(listOf(), C.INDEX_UNSET, C.TIME_UNSET)
    }

    private fun searchResultOf(
        session: MediaSession,
        items: List<MediaItem>
    ): MediaSession.MediaItemsWithStartPosition {
        // With shuffle on, an explicit start index would override shuffle order (see
        // onSetMediaItems' PLAY_ALL branch). An empty list must not get an explicit index either.
        val startIndex =
            if (items.isEmpty() || session.player.shuffleModeEnabled) C.INDEX_UNSET else 0
        return MediaSession.MediaItemsWithStartPosition(items, startIndex, C.TIME_UNSET)
    }

    /**
     * Ranks the search results by play-worthiness: title matches in category order (artist >
     * album > playlist > track, with Assistant's media-focus category boosted to the front when
     * present, and exact title matches beating contains-matches within a category), then any
     * artist, then anything playable, then anything at all.
     */
    private fun rankSearchResults(
        query: String,
        results: List<MediaItem>,
        focus: String?
    ): List<MediaItem> {
        val focusType = when (focus) {
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> MediaMetadata.MEDIA_TYPE_ARTIST
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> MediaMetadata.MEDIA_TYPE_ALBUM
            "vnd.android.cursor.item/playlist" -> MediaMetadata.MEDIA_TYPE_PLAYLIST
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> MediaMetadata.MEDIA_TYPE_MUSIC
            else -> null
        }

        val categories = listOf(
            MediaMetadata.MEDIA_TYPE_ARTIST,
            MediaMetadata.MEDIA_TYPE_ALBUM,
            MediaMetadata.MEDIA_TYPE_PLAYLIST,
            MediaMetadata.MEDIA_TYPE_MUSIC
        ).sortedByDescending { it == focusType } // Stable sort: keeps the default order otherwise.

        val title = { item: MediaItem -> item.mediaMetadata.title?.toString().orEmpty() }
        val ranked = mutableListOf<MediaItem>()
        for (type in categories) {
            val ofType = results.filter { it.mediaMetadata.mediaType == type }
            val (exact, rest) = ofType.partition { title(it).equals(query, ignoreCase = true) }
            ranked += exact
            ranked += rest.filter { title(it).contains(query, ignoreCase = true) }
        }

        ranked += results.filter { it.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_ARTIST }
        ranked += results.filter { it.mediaMetadata.isPlayable == true }
        ranked += results

        // Keeps the first (highest-ranked) occurrence of each item.
        return ranked.distinctBy { it.mediaId }
    }

    private suspend fun realChildren(parentId: String): List<MediaItem> {
        return tree.getChildren(parentId).filterNot { it.mediaId.startsWith(PLAY_ALL_PREFIX) }
    }

    private suspend fun isSingleItemWithParent(mediaItems: List<MediaItem>): Boolean {
        return mediaItems.size == 1 &&
                tree.getItem(mediaItems[0].mediaId).mediaMetadata.extras?.containsKey(PARENT_KEY) == true
    }

    private suspend fun expandSingleItem(item: MediaItem): List<MediaItem> {
        // This could load a lot of tracks if the parent has many children.
        val parentId = tree.getItem(item.mediaId).mediaMetadata.extras?.getString(PARENT_KEY)!!
        return resolveMediaItems(tree.getChildren(parentId))
    }

    /**
     * Expands items to a list of playable items: collections are expanded to get to the playable
     * nodes.
     */
    private suspend fun resolveMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
        val playlist = mutableListOf<MediaItem>()

        mediaItems.forEach {
            // Pinned "Play All" rows are intercepted upstream; never queue one as a track.
            // Blank ids (search requests are intercepted upstream too) would crash toUUID().
            if (it.mediaId.isBlank() || it.mediaId.startsWith(PLAY_ALL_PREFIX)) {
                return@forEach
            }
            // We need to call getItem to resolve the full item: the provided MediaItem only has an ID.
            val item = tree.getItem(it.mediaId)
            // If the item is an album or playlist, get its children and add them to the playlist.
            // Albums and playlists are "immediately playable" items, that actually load their
            // children (tracks).
            if (item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_ALBUM ||
                item.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_PLAYLIST
            ) {
                resolveMediaItems(tree.getChildren(item.mediaId)).forEach(playlist::add)
            } else if (item.mediaMetadata.isPlayable == true) {
                playlist.add(item)
            } else {
                Log.e(LOG_MARKER, "Cannot add media ${item.mediaMetadata.title}")
            }
        }

        return playlist
    }

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        if (!accountManager.isAuthenticated) {
            return Futures.immediateFuture(authErrorResult())
        }
        ensureTree()
        return SuspendToFutureAdapter.launchFuture {
            try {
                val results = tree.search(query).size
                session.notifySearchResultChanged(browser, query, results, params)
                LibraryResult.ofVoid(params)
            } catch (e: Exception) {
                Log.w(LOG_MARKER, "onSearch failed for $query", e)
                errorResult(e)
            }
        }
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (!accountManager.isAuthenticated) {
            return Futures.immediateFuture(authErrorResult())
        }
        ensureTree()
        return SuspendToFutureAdapter.launchFuture {
            try {
                val results = paginate(tree.search(query), page, pageSize)
                LibraryResult.ofItemList(results, params)
            } catch (e: Exception) {
                Log.w(LOG_MARKER, "onGetSearchResult failed for $query", e)
                errorResult(e)
            }
        }
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        ensureTree()
        return SuspendToFutureAdapter.launchFuture {
            val prefs = PreferenceManager.getDefaultSharedPreferences(service)

            val mediaItemsToRestore = prefs
                .getString(PLAYLIST_IDS_PREF, "")
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.map { async { tree.getItem(it) } }
                ?.awaitAll() ?: listOf()

            Log.d(LOG_MARKER, "Resuming playback with $mediaItemsToRestore")

            MediaSession.MediaItemsWithStartPosition(
                mediaItemsToRestore,
                prefs.getInt(PLAYLIST_INDEX_PREF, 0),
                prefs.getLong(PLAYLIST_TRACK_POSITON_MS_PREF, 0),
            )
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        Log.i(LOG_MARKER, "CustomCommand: ${customCommand.customAction}")
        when (customCommand.customAction) {
            LOGIN_COMMAND -> {
                service.onLogin()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            REPEAT_COMMAND -> {
                val currentMode = session.player.repeatMode
                session.player.repeatMode = (currentMode + 1) % 3 // There are 3 repeat modes
                session.setMediaButtonPreferences(CommandButtons.createButtons(session.player))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            SHUFFLE_COMMAND -> {
                session.player.shuffleModeEnabled = !session.player.shuffleModeEnabled
                session.setMediaButtonPreferences(CommandButtons.createButtons(session.player))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }

        return super.onCustomCommand(session, controller, customCommand, args)
    }

    override fun onSetRating(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaId: String,
        rating: Rating
    ): ListenableFuture<SessionResult> {
        // The session only supports heart (favourite) ratings. A controller can send other Rating
        // types; casting them unchecked would crash on the main thread, so skip instead.
        if (rating !is HeartRating) {
            Log.w(LOG_MARKER, "Ignoring unsupported rating type ${rating.javaClass.simpleName}")
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_INFO_SKIPPED))
        }
        Log.i(LOG_MARKER, "onSetRating ${rating.isHeart}")

        // Update the queued item whose id matches the rated one, not whichever item happens to be
        // playing (rating a non-current track otherwise toggled the heart on the wrong track).
        val player = session.player
        for (i in 0 until player.mediaItemCount) {
            val queued = player.getMediaItemAt(i)
            if (queued.mediaId == mediaId) {
                val metadata = queued.mediaMetadata.buildUpon().setUserRating(rating).build()
                player.replaceMediaItem(i, queued.buildUpon().setMediaMetadata(metadata).build())
                break
            }
        }

        return SuspendToFutureAdapter.launchFuture {
            applyRating(mediaId, rating)
            SessionResult(SessionResult.RESULT_SUCCESS)
        }
    }

    private suspend fun applyRating(currentMediaItem: String, newRating: Rating) {
        val id = currentMediaItem.toUUID()

        if (newRating == HeartRating(true)) {
            Log.i(LOG_MARKER, "Marking as favorite")
            jellyfinApi.userLibraryApi.markFavoriteItem(id)
        } else {
            Log.i(LOG_MARKER, "Unmarking as favorite")
            jellyfinApi.userLibraryApi.unmarkFavoriteItem(id)
        }
    }
}