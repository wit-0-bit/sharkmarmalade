package be.bendardenne.jellyfin.aaos

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.preference.PreferenceManager
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.PREF_BITRATE
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import org.jellyfin.sdk.api.operations.ImageApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaStreamProtocol

@OptIn(UnstableApi::class)
class MediaItemFactory(
    private val context: Context,
    private val jellyfinApi: ApiClient,
    private val artSize: Int
) {

    companion object {
        const val ROOT_ID = "ROOT_ID"
        const val LATEST_ALBUMS = "LATEST_ALBUMS_ID"
        const val RANDOM_ALBUMS = "RANDOM_ALBUMS_ID"
        const val FAVOURITES = "FAVOURITES_ID"
        const val PLAYLISTS = "PLAYLISTS_ID"
        const val ARTISTS = "ARTISTS_ID"
        const val BROWSE = "BROWSE_ID"
        const val BROWSE_ARTISTS = "BROWSE_ARTISTS_ID"
        const val GENRES = "GENRES_ID"
        const val ALBUMS = "ALBUMS_ID"
        const val PLAY_ALL_PREFIX = "PLAY_ALL:"
        // A track row tagged with this prefix plays only itself when tapped, instead of expanding
        // to its album. Used for tracks listed outside an album view (Favourites, search results),
        // where the album is not the context the user is looking at.
        const val SINGLE_PREFIX = "SINGLE:"
        const val PARENT_KEY = "PARENT_KEY"

        // Transcode target when no bitrate preference is set. FDK-AAC is transparent well below
        // this.
        const val TRANSCODE_BITRATE = 256_000
    }

    fun rootNode(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle("Root")
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()

        return MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(metadata)
            .build()
    }

    fun latestAlbums(): MediaItem {
        return albumCategory(LATEST_ALBUMS, context.getString(R.string.recents), "schedule")
    }

    fun randomAlbums(): MediaItem {
        return albumCategory(RANDOM_ALBUMS, "Random", "casino")
    }

    fun albums(): MediaItem {
        return albumCategory(ALBUMS, context.getString(R.string.albums), "album")
    }

    fun artists(): MediaItem {
        return artistCategory(ARTISTS)
    }

    fun browseArtists(): MediaItem {
        return artistCategory(BROWSE_ARTISTS)
    }

    private fun artistCategory(id: String): MediaItem {
        val extras = Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(context.getString(R.string.artists))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri(drawableUri("artists"))
            .setExtras(extras)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }

    fun browse(): MediaItem {
        val extras = Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(context.getString(R.string.browse))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri(drawableUri("browse"))
            .setExtras(extras)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()

        return MediaItem.Builder()
            .setMediaId(BROWSE)
            .setMediaMetadata(metadata)
            .build()
    }

    fun genres(): MediaItem {
        val extras = Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(context.getString(R.string.genres))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri(drawableUri("genre"))
            .setExtras(extras)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_GENRES)
            .build()

        return MediaItem.Builder()
            .setMediaId(GENRES)
            .setMediaMetadata(metadata)
            .build()
    }

    fun playAllRow(parentId: String, isArtist: Boolean): MediaItem {
        val label = when {
            isArtist -> R.string.shuffle_all_songs
            parentId == FAVOURITES -> R.string.play_all_favourites
            else -> R.string.play_album
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(context.getString(label))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setArtworkUri(drawableUri("play_all"))
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()

        return MediaItem.Builder()
            .setMediaId(PLAY_ALL_PREFIX + parentId)
            .setMediaMetadata(metadata)
            .build()
    }


    private fun albumCategory(id: String, label: String, icon: String): MediaItem {
        val extras = Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(label)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri(drawableUri(icon))
            .setExtras(extras)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }

    fun favourites(): MediaItem {
        val extras = Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle("Favourites")
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri(drawableUri("star_filled"))
            .setExtras(extras)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()

        return MediaItem.Builder()
            .setMediaId(FAVOURITES)
            .setMediaMetadata(metadata)
            .build()
    }

    fun playlists(): MediaItem {
        val extras = Bundle()
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle("Playlists")
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri(drawableUri("playlists"))
            .setExtras(extras)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
            .build()

        return MediaItem.Builder()
            .setMediaId(PLAYLISTS)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun forArtist(item: BaseItemDto, group: String? = null): MediaItem {
        val extras = Bundle()
        if (group != null) {
            extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }

        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(item.name)
            .setAlbumArtist(item.albumArtist)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri(artUri(item.id))
            .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    private fun forAlbum(item: BaseItemDto, group: String? = null): MediaItem {
        val extras = Bundle()
        if (group != null) {
            extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(item.name)
            .setAlbumArtist(item.albumArtist)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri(artUri(item.id))
            .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    private fun forGenre(item: BaseItemDto, group: String? = null): MediaItem {
        val extras = Bundle()
        if (group != null) {
            extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }

        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        extras.putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(item.name)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setArtworkUri(artUri(item.id))
            .setMediaType(MediaMetadata.MEDIA_TYPE_GENRE)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    private fun forPlaylist(item: BaseItemDto, group: String? = null): MediaItem {
        val extras = Bundle()
        if (group != null) {
            extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(item.name)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setArtworkUri(artUri(item.id))
            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    private fun forTrack(
        item: BaseItemDto,
        group: String? = null
    ): MediaItem {
        // Use the album ID for album art, if present.
        // This way, all tracks in an album have the same URI, which saves some downloads.
        // It probably makes sense most of the time, unless someone uses different images for
        // tracks within the same album, which seems weird.
        val artUrl = artUri(item.albumId ?: item.id)

        // Unset, or any non-numeric legacy value (e.g. an old "Direct stream" that predates the
        // always-transcode switch), falls back to the default transcode bitrate.
        val bitrate = PreferenceManager
            .getDefaultSharedPreferences(context)
            .getString(PREF_BITRATE, null)
            ?.toIntOrNull()

        // Nice-to-have: it would be nice to force transcoding when the codec is not supported
        //  (eg ALAC).
        //  This would require that we know the codec upfront. we currently don't have access to
        //  it because our BaseItemDtos are mostly fetched via getItemChildren, which queries the
        //  /Items endpoint, which does not include the codec (mediaSources/mediaStreams) in its
        //  response.

        // Everything transcodes to AAC 256 over HLS: one consistent delivered format, ~18x
        // smaller than hi-res FLAC, and — unlike a plain transcoded HTTP stream, which is an
        // unseekable chunked pipe that makes hosts hide the seek bar — HLS is seekable, with the
        // server transcoding from the seek point on demand. "ts" matches no music container, so
        // nothing ever direct-plays. The server's ffmpeg has libfdk_aac (verified), which
        // Jellyfin prefers automatically over ffmpeg's mediocre native AAC encoder.
        val allowedContainers = listOf("ts")
        val audioStream =
            jellyfinApi.universalAudioApi.getUniversalAudioStreamUrl(
                item.id,
                container = allowedContainers,
                audioBitRate = bitrate ?: TRANSCODE_BITRATE,
                maxStreamingBitrate = bitrate,
                transcodingContainer = "ts",
                transcodingProtocol = MediaStreamProtocol.HLS,
                audioCodec = "aac",
            )

        val extras = Bundle()
        if (group != null) {
            extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }

        // A track's album-expand context is its own album, taken straight from the DTO — so a tap
        // inside an album view queues the whole album (positioned at the track), and that survives
        // a cold rebuild from disk/network. Rows the user is NOT browsing as an album (Favourites,
        // search results) are re-tagged with SINGLE_PREFIX by the tree so tapping them plays just
        // the track; see JellyfinMediaTree.asSingle and the callback's isSingleItemWithParent.
        item.albumId?.let { extras.putString(PARENT_KEY, it.toString()) }

        val metadata = MediaMetadata.Builder()
            .setTitle(item.name)
            .setAlbumArtist(item.albumArtist)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setArtworkUri(artUrl)
            .setUserRating(HeartRating(item.userData?.isFavorite == true))
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setDurationMs(item.runTimeTicks?.div(10_000))
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(item.id.toString())
            .setMediaMetadata(metadata)
            .setUri(audioStream)
            // The universal URL gives no hint that the response is an HLS playlist; without an
            // explicit MIME type, ExoPlayer would try to parse it as progressive media.
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
    }

    // The package in an android.resource:// URI must be the installed package (applicationId),
    // which deliberately differs from this code's namespace.
    private fun drawableUri(name: String): Uri =
        "android.resource://${context.packageName}/drawable/$name".toUri()

    private fun artUri(id: UUID): Uri {
        val artUrl = ImageApi(jellyfinApi).getItemImageUrl(
            id,
            ImageType.PRIMARY,
            quality = 90,
            maxWidth = artSize,
            maxHeight = artSize,
        )
        val localUrl = AlbumArtContentProvider.mapUri(artUrl.toUri())
        return localUrl
    }


    fun create(
        baseItemDto: BaseItemDto,
        group: String? = null
    ): MediaItem {
        return when (baseItemDto.type) {
            BaseItemKind.MUSIC_ARTIST -> forArtist(baseItemDto, group)
            BaseItemKind.MUSIC_GENRE -> forGenre(baseItemDto, group)
            BaseItemKind.MUSIC_ALBUM -> forAlbum(baseItemDto, group)
            BaseItemKind.PLAYLIST -> forPlaylist(baseItemDto, group)
            BaseItemKind.AUDIO -> forTrack(baseItemDto, group)
            else -> throw UnsupportedOperationException("Can't create mediaItem for ${baseItemDto.type}")
        }
    }
}