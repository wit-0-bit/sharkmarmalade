package be.bendardenne.jellyfin.aaos

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


/**
 * ContentProvider for album arts.
 */
class AlbumArtContentProvider : ContentProvider() {

    private val client = OkHttpClient()

    companion object {
        private const val URI_MAP_MAX_ENTRIES = 2000

        // Bounded, evicting map from our own content:// uris to the remote (Jellyfin) uri they
        // were resolved from. Access must always go through the synchronized helpers below, as
        // this is written from mapUri() (called from whatever thread resolves the art) and read
        // from openFile() (ContentProvider binder threads, potentially concurrently).
        private val uriMap = object : LinkedHashMap<Uri, Uri>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, Uri>?): Boolean {
                return size > URI_MAP_MAX_ENTRIES
            }
        }

        private val inProgress = HashMap<Uri, CountDownLatch>()

        fun mapUri(uri: Uri): Uri {
            val path = uri.encodedPath?.substring(1)?.replace('/', ':') ?: return Uri.EMPTY
            val contentUri = Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(BuildConfig.APPLICATION_ID)
                .path(path)
                .build()
            synchronized(uriMap) {
                uriMap[contentUri] = uri
            }
            return contentUri
        }

        private fun getMappedUri(uri: Uri): Uri? = synchronized(uriMap) { uriMap[uri] }
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = this.context ?: return null
        val remoteUri = getMappedUri(uri) ?: throw FileNotFoundException(uri.path)
        val file = File(context.cacheDir, uri.path)

        if (file.exists()) {
            Log.d(LOG_MARKER, "Returning existing file for $remoteUri: $file")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // Several threads may request the same image (typical when listing an album).
        // To avoid firing multiple downloads, the first thread makes the request, others will
        // wait. The synchronized section below only ever does the quick "claim or join" check;
        // the actual (potentially long) waiting/downloading happens after it is released, so
        // unrelated URIs are never blocked behind this one.
        var latch: CountDownLatch? = null
        var isDownloader = false
        synchronized(inProgress) {
            val existing = inProgress[remoteUri]
            if (existing != null) {
                latch = existing
            } else {
                // Any other thread will now see a countdownlatch and wait for it.
                // This thread will continue and download.
                val newLatch = CountDownLatch(1)
                inProgress[remoteUri] = newLatch
                latch = newLatch
                isDownloader = true
            }
        }

        if (!isDownloader) {
            Log.d(LOG_MARKER, "Waiting for image download in separate thread... $remoteUri")
            latch!!.await(15, TimeUnit.SECONDS)
            // The download this thread was waiting on may have failed (or timed out), in which
            // case there is genuinely no art available for this item.
            return if (file.exists()) {
                Log.d(LOG_MARKER, "... Available!")
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                Log.d(LOG_MARKER, "... Not available.")
                null
            }
        }

        val tmpFile = File.createTempFile("sharkmarmalade-albumart", ".png", context.cacheDir)
        var success = false
        try {
            val request: Request = Request.Builder()
                .url(remoteUri.toString())
                .build()

            Log.d(LOG_MARKER, "Downloading $remoteUri ...")
            client.newCall(request).execute().use {
                if (it.body != null && it.code == 200) {
                    Log.d(LOG_MARKER, "Downloaded $remoteUri")
                    val source = it.body!!.source()
                    source.request(Long.MAX_VALUE)

                    val sink = tmpFile.sink().buffer()
                    sink.writeAll(source)
                    sink.flush()
                    sink.close()

                    success = tmpFile.renameTo(file)
                } else {
                    Log.w(LOG_MARKER, "Failed to download $remoteUri: \n ${it.code} - ${it.body}")
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_MARKER, "Failed to download $remoteUri", e)
        } finally {
            if (!success) {
                tmpFile.delete()
            }
            synchronized(inProgress) {
                inProgress.remove(remoteUri)
            }
            latch!!.countDown()
        }

        return if (success) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ) = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    override fun getType(uri: Uri): String? = null
}
