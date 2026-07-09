package be.bendardenne.jellyfin.aaos.downloads

import android.content.Context
import android.util.Log
import be.bendardenne.jellyfin.aaos.JellyfinAccountManager
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemDto
import java.io.File
import java.security.MessageDigest

/**
 * On-disk store for downloaded music: transcoded AAC track files plus the raw DTOs needed to
 * build a fully offline, browse-shaped "Downloaded" tab (artists -> albums -> tracks).
 *
 * Layout: `filesDir/downloads/<sha256(serverUrl) prefix>/`
 *   - `tracks/<trackId>.m4a`   the audio files
 *   - `artists.json` / `albums.json` / `tracks.json`   DTO lists (the index)
 *
 * Deliberately under filesDir (the car head unit grants a 64 MB *cache* quota — measured — and
 * cacheDir is the OS's to purge) and namespaced by server URL only, NOT token: a re-login to the
 * same server must not orphan gigabytes of downloads.
 *
 * The index is the source of truth for *structure* (which artists/albums/tracks are in the
 * download set); track file existence is the source of truth for *playability*. The syncer only
 * indexes tracks whose files actually landed on disk.
 */
class DownloadStore(context: Context, private val accountManager: JellyfinAccountManager) {

    companion object {
        // Owner-sized: ~1300 tracks at AAC-256 (~2 MB/min). Generous but not greedy.
        const val MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024 * 1024
    }

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(BaseItemDto.serializer())

    private data class Index(
        val artists: List<BaseItemDto>,
        val albums: List<BaseItemDto>,
        val tracks: List<BaseItemDto>,
    ) {
        val tracksById by lazy { tracks.associateBy { it.id.toString() } }
    }

    @Volatile
    private var cached: Pair<String, Index>? = null // namespace dir path -> index

    private fun namespaceDir(): File? {
        val server = accountManager.server ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(server.toByteArray())
        val namespace = digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
        return File(File(appContext.filesDir, "downloads"), namespace)
    }

    private fun tracksDir(ns: File) = File(ns, "tracks")

    private fun index(): Index {
        val ns = namespaceDir() ?: return Index(emptyList(), emptyList(), emptyList())
        cached?.let { (path, index) ->
            if (path == ns.path) {
                return index
            }
        }
        val loaded = Index(
            readList(File(ns, "artists.json")),
            readList(File(ns, "albums.json")),
            readList(File(ns, "tracks.json")),
        )
        cached = ns.path to loaded
        return loaded
    }

    private fun readList(file: File): List<BaseItemDto> {
        return try {
            if (!file.isFile) emptyList()
            else json.decodeFromString(listSerializer, file.readText())
        } catch (e: Exception) {
            Log.w(LOG_MARKER, "Corrupt download index ${file.name}, treating as empty", e)
            emptyList()
        }
    }

    /** Atomically replaces the index. Called by the syncer after reconciling files on disk. */
    fun writeIndex(
        artists: List<BaseItemDto>,
        albums: List<BaseItemDto>,
        tracks: List<BaseItemDto>
    ) {
        val ns = namespaceDir() ?: return
        ns.mkdirs()
        writeList(File(ns, "artists.json"), artists)
        writeList(File(ns, "albums.json"), albums)
        writeList(File(ns, "tracks.json"), tracks)
        cached = ns.path to Index(artists, albums, tracks)
    }

    private fun writeList(file: File, items: List<BaseItemDto>) {
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(listSerializer, items))
            if (!tmp.renameTo(file)) {
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(LOG_MARKER, "Failed to write download index ${file.name}", e)
        }
    }

    // ---- Structure queries (drive the Downloaded tab) ----

    fun artists(): List<BaseItemDto> = index().artists.sortedBy { it.sortName ?: it.name }

    fun albums(): List<BaseItemDto> = index().albums.sortedBy { it.sortName ?: it.name }

    fun albumsFor(artistId: String): List<BaseItemDto> =
        index().albums.filter { album ->
            album.albumArtists.orEmpty().any { it.id.toString() == artistId } ||
                    album.artistItems.orEmpty().any { it.id.toString() == artistId }
        }.sortedBy { it.sortName ?: it.name }

    /** Tracks of an album present in the download set, in playback order. */
    fun tracksFor(albumId: String): List<BaseItemDto> =
        index().tracks.filter { it.albumId.toString() == albumId }
            .sortedWith(compareBy({ it.parentIndexNumber ?: 0 }, { it.indexNumber ?: 0 }))

    fun hasAlbum(albumId: String): Boolean = index().tracks.any { it.albumId.toString() == albumId }

    fun track(trackId: String): BaseItemDto? = index().tracksById[trackId]

    fun isEmpty(): Boolean = index().tracks.isEmpty()

    // ---- File management ----

    fun trackFile(trackId: String): File? {
        val ns = namespaceDir() ?: return null
        return File(tracksDir(ns), "$trackId.m4a")
    }

    /** The local audio file for a track, or null when not downloaded. Fast: one stat. */
    fun localTrack(trackId: String): File? = trackFile(trackId)?.takeIf { it.isFile }

    fun downloadedTrackIds(): Set<String> {
        val ns = namespaceDir() ?: return emptySet()
        return tracksDir(ns).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".m4a") }
            ?.map { it.name.removeSuffix(".m4a") }
            ?.toSet()
            ?: emptySet()
    }

    fun totalBytes(): Long {
        val ns = namespaceDir() ?: return 0
        return tracksDir(ns).listFiles()?.sumOf { it.length() } ?: 0
    }

    fun tempFile(): File? {
        val ns = namespaceDir() ?: return null
        tracksDir(ns).mkdirs()
        return File(tracksDir(ns), "download.tmp")
    }

    fun commitTrack(trackId: String, temp: File): Boolean {
        val target = trackFile(trackId) ?: return false
        return temp.renameTo(target)
    }

    fun deleteTrack(trackId: String) {
        trackFile(trackId)?.delete()
    }
}
