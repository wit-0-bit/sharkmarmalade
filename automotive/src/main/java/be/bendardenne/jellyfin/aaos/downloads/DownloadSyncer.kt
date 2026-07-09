package be.bendardenne.jellyfin.aaos.downloads

import android.util.Log
import be.bendardenne.jellyfin.aaos.JellyfinAccountManager
import be.bendardenne.jellyfin.aaos.MediaItemFactory.Companion.TRANSCODE_BITRATE
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER
import be.bendardenne.jellyfin.aaos.auth
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reconciles the on-device download set against the `finale-downloads` collection on the server.
 *
 * The collection is the management UI: adding an album or a whole artist to it (phone/web,
 * "Add to collection" on the item's detail page) marks it for download; removing it evicts the
 * files on the next sync. No in-car download management exists on purpose — the car host offers
 * no browse affordance for it, and curating 10 GB by touchscreen is nobody's idea of fun.
 *
 * Tracks are downloaded as discrete transcoded files (AAC-256 in m4a via /Audio/{id}/stream —
 * verified against the live server: valid seekable files, ~2 MB/min), sequentially, temp+rename.
 * The index is only given tracks whose files actually landed, so the Downloaded tab never lists
 * something unplayable.
 */
class DownloadSyncer(
    private val api: ApiClient,
    private val accountManager: JellyfinAccountManager,
    private val store: DownloadStore,
) {

    companion object {
        const val COLLECTION_NAME = "finale-downloads"
        private const val SYNC_INTERVAL_MS = 15 * 60_000L

        // Refresh the index (and the host's Downloaded tab) as batches land, so a long first
        // sync shows progress instead of an empty tab until completion.
        private const val INDEX_EVERY_N_DOWNLOADS = 10
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Transcode-on-demand can stall while ffmpeg spins up on a cold track.
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var lastSyncAt = 0L

    /** Notified (on the syncer's IO context) when the downloaded set actually changed. */
    var onDownloadsChanged: (() -> Unit)? = null

    /** Entry point: throttled, single-flight. Safe to call opportunistically. */
    suspend fun syncIfDue(force: Boolean = false) {
        if (!accountManager.isAuthenticated) {
            return
        }
        if (!force && System.currentTimeMillis() - lastSyncAt < SYNC_INTERVAL_MS) {
            return
        }
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        try {
            sync()
            lastSyncAt = System.currentTimeMillis()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network died mid-sync, server unreachable, etc. Whatever already landed on disk
            // stays usable; the next sync picks up where this one stopped.
            Log.w(LOG_MARKER, "Download sync failed; will retry later", e)
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun sync() {
        val collection = findCollection()
        if (collection == null) {
            // Deliberately NOT treated as "evict everything": a missing collection is far more
            // likely a rename/mistake than an intentional purge of 10 GB. Emptying the
            // collection (leaving it existing) is the explicit way to clear downloads.
            Log.i(LOG_MARKER, "No '$COLLECTION_NAME' collection on server; downloads unchanged")
            return
        }

        val children = api.itemsApi.getItems(parentId = collection.id).content.items

        // Expand the collection's entities into the desired album set (insertion-ordered so the
        // 10 GB cap cuts off the most recently added items, not arbitrary ones).
        val albums = LinkedHashMap<UUID, BaseItemDto>()
        for (child in children) {
            when (child.type) {
                BaseItemKind.MUSIC_ALBUM -> albums[child.id] = child
                BaseItemKind.MUSIC_ARTIST -> {
                    api.itemsApi.getItems(
                        albumArtistIds = listOf(child.id),
                        includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                        recursive = true,
                        sortBy = listOf(ItemSortBy.PREMIERE_DATE),
                    ).content.items.forEach { albums[it.id] = it }
                }

                else -> Log.i(
                    LOG_MARKER,
                    "Ignoring ${child.type} '${child.name}' in $COLLECTION_NAME"
                )
            }
        }

        // Desired tracks, per album, in playback order.
        val desiredTracks = LinkedHashMap<String, BaseItemDto>()
        for (album in albums.values) {
            api.itemsApi.getItems(
                parentId = album.id,
                sortBy = listOf(
                    ItemSortBy.PARENT_INDEX_NUMBER,
                    ItemSortBy.INDEX_NUMBER,
                    ItemSortBy.SORT_NAME
                ),
            ).content.items
                .filter { it.type == BaseItemKind.AUDIO }
                .forEach { desiredTracks[it.id.toString()] = it }
        }

        // Evict first: tracks no longer wanted free budget for tracks newly wanted.
        val existing = store.downloadedTrackIds()
        val evicted = existing - desiredTracks.keys
        evicted.forEach { store.deleteTrack(it) }
        if (evicted.isNotEmpty()) {
            Log.i(LOG_MARKER, "Evicted ${evicted.size} downloaded tracks no longer in collection")
        }

        // Index only what is actually on disk, pruning albums/artists that ended up empty
        // (failed downloads, cap cut-off) so the tab never shows dead branches. Written
        // incrementally DURING the download loop, not just at the end: a first sync of a big
        // collection takes many minutes and a radio drop must not leave finished downloads
        // invisible until some later full pass.
        val indexArtists = fetchArtists(albums.values.toList(), children)
        fun writeCurrentIndex(): Int {
            val onDisk = store.downloadedTrackIds()
            val indexTracks = desiredTracks.values.filter { onDisk.contains(it.id.toString()) }
            val liveAlbumIds = indexTracks.mapNotNull { it.albumId?.toString() }.toSet()
            val indexAlbums = albums.values.filter { liveAlbumIds.contains(it.id.toString()) }
            val liveArtistIds = indexAlbums
                .flatMap { it.albumArtists.orEmpty() }
                .mapNotNull { it.id }
                .toSet()
            store.writeIndex(
                indexArtists.filter { liveArtistIds.contains(it.id) },
                indexAlbums,
                indexTracks
            )
            return indexTracks.size
        }

        // Download what's missing, oldest-in-collection first, within budget.
        var totalBytes = store.totalBytes()
        var downloaded = 0
        var failed = 0
        var capped = false
        val headers = api.auth(accountManager)
        for (id in desiredTracks.keys) {
            if (store.localTrack(id) != null) {
                continue
            }
            if (totalBytes >= DownloadStore.MAX_DOWNLOAD_BYTES) {
                capped = true
                break
            }
            val bytes = downloadTrack(id, headers)
            if (bytes > 0) {
                totalBytes += bytes
                downloaded++
                if (downloaded % INDEX_EVERY_N_DOWNLOADS == 0) {
                    writeCurrentIndex()
                    onDownloadsChanged?.invoke()
                }
            } else {
                failed++
            }
        }
        if (capped) {
            Log.w(
                LOG_MARKER,
                "Download budget (${DownloadStore.MAX_DOWNLOAD_BYTES} bytes) reached; " +
                        "remaining collection items skipped"
            )
        }

        val indexedTracks = writeCurrentIndex()
        val changed = evicted.isNotEmpty() || downloaded > 0

        Log.i(
            LOG_MARKER,
            "Download sync: $indexedTracks tracks on disk (${totalBytes / 1_000_000} MB); " +
                    "+$downloaded -${evicted.size} failed=$failed capped=$capped"
        )
        if (changed) {
            onDownloadsChanged?.invoke()
        }
    }

    private suspend fun findCollection(): BaseItemDto? =
        api.itemsApi.getItems(
            includeItemTypes = listOf(BaseItemKind.BOX_SET),
            recursive = true,
            searchTerm = COLLECTION_NAME,
        ).content.items.firstOrNull { it.name == COLLECTION_NAME }

    /** Artist DTOs for the album set: reuse the collection's own artist entries, fetch the rest. */
    private suspend fun fetchArtists(
        albums: List<BaseItemDto>,
        collectionChildren: List<BaseItemDto>
    ): List<BaseItemDto> {
        val wanted = albums
            .flatMap { it.albumArtists.orEmpty() }
            .mapNotNull { it.id }
            .toSet()

        val known = collectionChildren
            .filter { it.type == BaseItemKind.MUSIC_ARTIST && wanted.contains(it.id) }
            .associateBy { it.id }

        return wanted.map { id ->
            known[id] ?: api.userLibraryApi.getItem(id).content
        }
    }

    /** Returns the file size on success, 0 on failure (logged, sync continues). */
    private fun downloadTrack(trackId: String, headers: Map<String, String>): Long {
        val temp = store.tempFile() ?: return 0
        val url = "${api.baseUrl}/Audio/$trackId/stream.m4a" +
                "?audioCodec=aac&audioBitRate=$TRANSCODE_BITRATE&container=m4a&static=false"
        return try {
            val request = Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body
                if (response.code != 200 || body == null) {
                    Log.w(LOG_MARKER, "Track $trackId download failed: HTTP ${response.code}")
                    return 0
                }
                temp.sink().buffer().use { sink -> sink.writeAll(body.source()) }
            }
            val size = temp.length()
            if (size > 0 && store.commitTrack(trackId, temp)) size else 0
        } catch (e: Exception) {
            Log.w(LOG_MARKER, "Track $trackId download failed", e)
            0
        } finally {
            temp.delete()
        }
    }
}
