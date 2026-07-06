package be.bendardenne.jellyfin.aaos

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_ALBUM
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_ARTIST
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_GENRE
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_PLAYLIST
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.ALBUMS
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.ARTISTS
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.BROWSE
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.BROWSE_ARTISTS
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.FAVOURITES
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.GENRES
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.LATEST_ALBUMS
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.PLAYLISTS
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.PLAY_ALL_PREFIX
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.RANDOM_ALBUMS
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.ROOT_ID
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.musicGenresApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.serializer.toUUID
import java.util.concurrent.ConcurrentHashMap

class JellyfinMediaTree(
    private val context: Context,
    private val api: ApiClient,
    private val itemFactory: MediaItemFactory,
    private val maxItemsPerPage: Int = 120
) {

    companion object {
        private const val REVALIDATION_INTERVAL_MS = 30_000L
    }

    private val mediaItems: Cache<String, MediaItem> = CacheBuilder.newBuilder()
        .maximumSize(1000)
        .build()

    private val diskCache = MediaTreeDiskCache(context, api)
    private val revalidationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastRevalidated = ConcurrentHashMap<String, Long>()
    private val revalidationsInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    var onChildrenUpdated: ((parentId: String, itemCount: Int) -> Unit)? = null

    suspend fun getItem(id: String): MediaItem {
        if (mediaItems.getIfPresent(id) == null) {
            // Cache miss
            val newItem = when (id) {
                ROOT_ID -> itemFactory.rootNode()
                LATEST_ALBUMS -> itemFactory.latestAlbums()
                RANDOM_ALBUMS -> itemFactory.randomAlbums()
                FAVOURITES -> itemFactory.favourites()
                PLAYLISTS -> itemFactory.playlists()
                ARTISTS -> itemFactory.artists()
                BROWSE -> itemFactory.browse()
                BROWSE_ARTISTS -> itemFactory.browseArtists()
                GENRES -> itemFactory.genres()
                ALBUMS -> itemFactory.albums()
                else -> if (id.startsWith(PLAY_ALL_PREFIX)) {
                    val parentId = id.removePrefix(PLAY_ALL_PREFIX)
                    val isArtist = getItem(parentId).mediaMetadata.mediaType == MEDIA_TYPE_ARTIST
                    itemFactory.playAllRow(parentId, isArtist)
                } else {
                    // Stream URLs / art URIs are rebuilt by the factory on every create().
                    val cached = diskCache.readItem(id)
                    val dto = cached
                        ?: api.userLibraryApi.getItem(id.toUUID()).content.also {
                            revalidationScope.launch { diskCache.writeItem(it) }
                        }
                    if (cached != null) {
                        revalidateItem(id)
                    }
                    itemFactory.create(dto)
                }
            }

            mediaItems.put(id, newItem)
        }

        return mediaItems.getIfPresent(id)!!
    }

    suspend fun getChildren(id: String): List<MediaItem> {
        return when (id) {
            ROOT_ID -> listOf(
                getItem(RANDOM_ALBUMS),
                getItem(ARTISTS),
                getItem(FAVOURITES),
                getItem(BROWSE)
            )

            BROWSE -> listOf(
                getItem(BROWSE_ARTISTS),
                getItem(GENRES),
                getItem(ALBUMS),
                getItem(LATEST_ALBUMS),
                getItem(PLAYLISTS)
            )

            LATEST_ALBUMS -> childrenWithCache(id, ::fetchLatestAlbums, ::buildItems)
            RANDOM_ALBUMS -> getRandomAlbums()
            FAVOURITES -> childrenWithCache(id, ::fetchFavourites, ::buildFavouriteItems)
            PLAYLISTS -> childrenWithCache(id, ::fetchPlaylists, ::buildItems)
            // Same data, but each keyed by the browsed parent id so revalidation notifies the
            // right subscription.
            ARTISTS -> childrenWithCache(id, ::fetchArtists, ::buildItems)
            BROWSE_ARTISTS -> childrenWithCache(id, ::fetchArtists, ::buildItems)
            GENRES -> childrenWithCache(id, ::fetchGenres, ::buildItems)
            ALBUMS -> childrenWithCache(id, ::fetchAlbums, ::buildItems)
            else -> getItemChildren(id)
        }
    }

    /**
     * Serves cached children from disk immediately (revalidating in the background), or fetches
     * and caches them on a miss.
     */
    private suspend fun childrenWithCache(
        key: String,
        fetch: suspend () -> List<BaseItemDto>,
        build: (List<BaseItemDto>) -> List<MediaItem>
    ): List<MediaItem> {
        val cached = diskCache.readChildren(key)

        if (cached == null) {
            val fresh = fetch()
            revalidationScope.launch { persist(key, fresh) }
            return build(fresh)
        }

        revalidate(key, cached, fetch)
        return build(cached)
    }

    private suspend fun persist(key: String, dtos: List<BaseItemDto>): Boolean {
        val persisted = diskCache.writeChildren(key, dtos)
        // Persist tracks individually so playback resumption works from disk after a restart.
        dtos.filter { it.type == BaseItemKind.AUDIO }.forEach { diskCache.writeItem(it) }
        return persisted
    }

    private fun revalidate(
        key: String,
        cached: List<BaseItemDto>,
        fetch: suspend () -> List<BaseItemDto>
    ) {
        val last = lastRevalidated[key]
        if (last != null && System.currentTimeMillis() - last < REVALIDATION_INTERVAL_MS) {
            return
        }

        if (!revalidationsInFlight.add(key)) {
            return
        }

        revalidationScope.launch {
            try {
                val fresh = fetch()
                lastRevalidated[key] = System.currentTimeMillis()
                // Fresh data is written before the notify fires, so the host's refetch reads
                // fresh data and the next revalidation is both throttled and
                // fingerprint-identical — no notify loop. If the write failed, the host's
                // refetch would only re-read stale disk data, so don't notify at all.
                if (!persist(key, fresh)) {
                    return@launch
                }
                if (cached.map(::fingerprint) != fresh.map(::fingerprint)) {
                    onChildrenUpdated?.invoke(key, fresh.size)
                }
            } catch (e: Exception) {
                // The user keeps the stale list.
                Log.d(LOG_MARKER, "Revalidation failed for $key", e)
            } finally {
                revalidationsInFlight.remove(key)
            }
        }
    }

    // There is no per-item notify in the browse protocol, so a changed DTO only updates the disk
    // cache and the in-memory item; hosts pick it up on their next fetch.
    private fun revalidateItem(id: String) {
        val key = "item:$id"
        val last = lastRevalidated[key]
        if (last != null && System.currentTimeMillis() - last < REVALIDATION_INTERVAL_MS) {
            return
        }

        if (!revalidationsInFlight.add(key)) {
            return
        }

        revalidationScope.launch {
            try {
                val fresh = api.userLibraryApi.getItem(id.toUUID()).content
                lastRevalidated[key] = System.currentTimeMillis()
                if (diskCache.writeItem(fresh)) {
                    mediaItems.put(id, itemFactory.create(fresh))
                }
            } catch (e: Exception) {
                // The user keeps the stale item.
                Log.d(LOG_MARKER, "Revalidation failed for $key", e)
            } finally {
                revalidationsInFlight.remove(key)
            }
        }
    }

    // Deliberately not full DTO equality: playCount/lastPlayedDate churn on every play and must
    // not trigger UI refreshes.
    private fun fingerprint(dto: BaseItemDto) = "${dto.id}|${dto.name}|${dto.userData?.isFavorite}"

    private fun buildItems(dtos: List<BaseItemDto>): List<MediaItem> {
        return dtos.map {
            val item = itemFactory.create(it)
            mediaItems.put(item.mediaId, item)
            item
        }
    }

    private fun buildFavouriteItems(dtos: List<BaseItemDto>): List<MediaItem> {
        return dtos.map {
            val item = itemFactory.create(
                it,
                groupForItem(it),
                parent = FAVOURITES
            )
            mediaItems.put(item.mediaId, item)
            item
        }
    }

    private fun buildChildItems(parentId: String, dtos: List<BaseItemDto>): List<MediaItem> {
        return dtos.map {
            val item = itemFactory.create(it, parent = parentId)
            mediaItems.put(item.mediaId, item)
            item
        }
    }

    private suspend fun fetchLatestAlbums(): List<BaseItemDto> {
        return api.userLibraryApi.getLatestMedia(
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            limit = maxItemsPerPage
        ).content
    }

    // Deliberately not disk-cached: a Random tab that silently reshuffles under the user is worse
    // than a spinner, and caching randomness defeats its purpose.
    private suspend fun getRandomAlbums(): List<MediaItem> {
        val response = api.itemsApi.getItems(
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            recursive = true,
            sortBy = listOf(ItemSortBy.RANDOM),
            limit = maxItemsPerPage
        )

        return response.content.items.map {
            val item = itemFactory.create(it)
            mediaItems.put(item.mediaId, item)
            item
        }
    }

    private suspend fun fetchPlaylists(): List<BaseItemDto> {
        return api.itemsApi.getItems(
            includeItemTypes = listOf(BaseItemKind.PLAYLIST),
            recursive = true,
            sortOrder = listOf(SortOrder.DESCENDING),
            sortBy = listOf(ItemSortBy.DATE_CREATED),
            limit = maxItemsPerPage
        ).content.items
    }

    private suspend fun getItemChildren(id: String): List<MediaItem> {
        val mediaType = getItem(id).mediaMetadata.mediaType

        if (mediaType == MEDIA_TYPE_ARTIST) {
            return childrenWithCache(id, { fetchArtistAlbums(id) }) {
                withPlayAll(id, isArtist = true, buildItems(it))
            }
        }

        if (mediaType == MEDIA_TYPE_GENRE) {
            return childrenWithCache(id, { fetchGenreAlbums(id) }, ::buildItems)
        }

        var sortBy = listOf(
            ItemSortBy.PARENT_INDEX_NUMBER,
            ItemSortBy.INDEX_NUMBER,
            ItemSortBy.SORT_NAME
        );

        // For playlists, we should respect the default order (user's track order)
        if (mediaType == MEDIA_TYPE_PLAYLIST) {
            sortBy = listOf(ItemSortBy.DEFAULT)
        }

        return childrenWithCache(id, { fetchItemChildren(id, sortBy) }) {
            val children = buildChildItems(id, it)
            if (mediaType == MEDIA_TYPE_ALBUM) {
                withPlayAll(id, isArtist = false, children)
            } else {
                children
            }
        }
    }

    /**
     * Prepends the synthetic "Play All" row. Only ever added by builders, never persisted: the
     * disk cache must contain real DTOs only.
     */
    private fun withPlayAll(
        parentId: String,
        isArtist: Boolean,
        children: List<MediaItem>
    ): List<MediaItem> {
        val playAll = itemFactory.playAllRow(parentId, isArtist)
        mediaItems.put(playAll.mediaId, playAll)
        return listOf(playAll) + children
    }

    // No limit: these children are queued for playback, so truncating them changes what plays.
    private suspend fun fetchItemChildren(id: String, sortBy: List<ItemSortBy>): List<BaseItemDto> {
        return api.itemsApi.getItems(
            sortBy = sortBy,
            parentId = id.toUUID()
        ).content.items
    }

    private suspend fun fetchArtistAlbums(id: String): List<BaseItemDto> {
        return api.itemsApi.getItems(
            sortBy = listOf(
                ItemSortBy.PARENT_INDEX_NUMBER,
                ItemSortBy.INDEX_NUMBER,
                ItemSortBy.SORT_NAME
            ),
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            albumArtistIds = listOf(id.toUUID()),
            limit = maxItemsPerPage
        ).content.items
    }

    private suspend fun fetchGenreAlbums(id: String): List<BaseItemDto> {
        return api.itemsApi.getItems(
            sortBy = listOf(ItemSortBy.SORT_NAME),
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            genreIds = listOf(id.toUUID()),
            limit = maxItemsPerPage
        ).content.items
    }

    private suspend fun fetchArtists(): List<BaseItemDto> {
        return api.artistsApi.getAlbumArtists(
            limit = maxItemsPerPage
        ).content.items
    }

    private suspend fun fetchGenres(): List<BaseItemDto> {
        return api.musicGenresApi.getMusicGenres(
            limit = maxItemsPerPage
        ).content.items
    }

    private suspend fun fetchAlbums(): List<BaseItemDto> {
        return api.itemsApi.getItems(
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            recursive = true,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            limit = maxItemsPerPage
        ).content.items
    }

    private suspend fun fetchFavourites(): List<BaseItemDto> {
        return api.itemsApi.getItems(
            recursive = true,
            filters = listOf(ItemFilter.IS_FAVORITE),
            includeItemTypes = listOf(
                BaseItemKind.AUDIO,
                BaseItemKind.MUSIC_ALBUM,
                BaseItemKind.MUSIC_ARTIST
            )
        ).content.items
    }

    private fun groupForItem(dto: BaseItemDto): String = (
            if (dto.type == BaseItemKind.MUSIC_ALBUM)
                context.getString(R.string.albums)
            else if (dto.type == BaseItemKind.MUSIC_ARTIST)
                context.getString(R.string.artists)
            else
                context.getString(R.string.tracks)
            )


    suspend fun search(query: String): List<MediaItem> {
        val items = mutableListOf<MediaItem>()

        var response = api.artistsApi.getAlbumArtists(
            searchTerm = query,
            limit = 10,
        )

        items.addAll(response.content.items.map {
            val item = itemFactory.create(it, context.getString(R.string.artists))
            mediaItems.put(item.mediaId, item)
            item
        })

        response = api.itemsApi.getItems(
            recursive = true,
            searchTerm = query,
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            limit = 10
        )

        items.addAll(response.content.items.map {
            val item = itemFactory.create(it, context.getString(R.string.albums))
            mediaItems.put(item.mediaId, item)
            item
        })

        response = api.itemsApi.getItems(
            recursive = true,
            searchTerm = query,
            includeItemTypes = listOf(BaseItemKind.PLAYLIST),
            limit = 10
        )

        items.addAll(response.content.items.map {
            val item = itemFactory.create(it, context.getString(R.string.playlists))
            mediaItems.put(item.mediaId, item)
            item
        })


        response = api.itemsApi.getItems(
            recursive = true,
            searchTerm = query,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            limit = 20
        )

        items.addAll(response.content.items.map {
            val item = itemFactory.create(it, context.getString(R.string.tracks))
            mediaItems.put(item.mediaId, item)
            item
        })

        return items
    }

    /**
     * Clears the cached MediaItems. Disk DTOs are kept on purpose: preference changes only affect
     * MediaItem construction, so rebuilt items pick up new prefs without a network round-trip.
     */
    fun evictCache() {
        mediaItems.invalidateAll()
    }
}
