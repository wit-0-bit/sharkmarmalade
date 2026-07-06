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
import java.util.concurrent.atomic.AtomicInteger


/**
 * ContentProvider for album arts.
 */
class AlbumArtContentProvider : ContentProvider() {

    private val client = OkHttpClient()

    companion object {
        private const val URI_MAP_MAX_ENTRIES = 2000

        // Downloaded art lives in its own subdirectory so it can be size-capped independently of
        // the other caches under cacheDir (audio/, tree/).
        private const val ART_DIR = "albumart"

        // Cap the on-disk art cache: trim down to the low-water mark once it exceeds the high one.
        private const val ART_CACHE_MAX_BYTES = 256L * 1024 * 1024
        private const val ART_CACHE_LOW_BYTES = 200L * 1024 * 1024

        // Only walk the directory to trim every N downloads, to keep openFile cheap.
        private const val TRIM_EVERY_N_DOWNLOADS = 50
        private val downloadsSinceTrim = AtomicInteger(0)
        private val trimLock = Any()

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

        // Deletes least-recently-modified art until under the low-water mark. Wrapped so it can
        // never break an openFile call.
        private fun trimArtCache(dir: File) {
            synchronized(trimLock) {
                try {
                    val files = dir.listFiles()?.filter { it.isFile } ?: return
                    var total = files.sumOf { it.length() }
                    if (total <= ART_CACHE_MAX_BYTES) {
                        return
                    }
                    for (f in files.sortedBy { it.lastModified() }) {
                        if (total <= ART_CACHE_LOW_BYTES) {
                            break
                        }
                        val len = f.length()
                        if (f.delete()) {
                            total -= len
                        }
                    }
                } catch (e: Exception) {
                    Log.w(LOG_MARKER, "Album art cache trim failed", e)
                }
            }
        }

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
        val artDir = File(context.cacheDir, ART_DIR)
        val file = File(artDir, uri.path)

        // Serve a cached file without needing the in-memory uri mapping: the path derives purely
        // from uri.path, so art requested after a process restart (or after its mapping was
        // LRU-evicted) still resolves as long as the bytes are on disk.
        if (file.exists()) {
            try {
                Log.d(LOG_MARKER, "Returning existing file for $uri: $file")
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: FileNotFoundException) {
                // Raced with a cache trim that deleted it between exists() and open(); fall
                // through and re-download.
                Log.d(LOG_MARKER, "Cached art vanished (trim race), re-downloading $uri")
            }
        }

        // The remote URI is only needed to download a missing file.
        val remoteUri = getMappedUri(uri) ?: throw FileNotFoundException(uri.path)

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
            return try {
                if (file.exists()) {
                    Log.d(LOG_MARKER, "... Available!")
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                } else {
                    Log.d(LOG_MARKER, "... Not available.")
                    null
                }
            } catch (e: FileNotFoundException) {
                null
            }
        }

        // tmpFile is created inside the try so that even a failure here (e.g. disk full) still
        // runs the finally: otherwise the inProgress latch would never count down and every
        // later request for this URI would block the full 15s and fail, forever.
        var tmpFile: File? = null
        var success = false
        try {
            artDir.mkdirs()
            tmpFile = File.createTempFile("sharkmarmalade-albumart", ".png", context.cacheDir)

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
                tmpFile?.delete()
            }
            synchronized(inProgress) {
                inProgress.remove(remoteUri)
            }
            latch!!.countDown()
        }

        if (success && downloadsSinceTrim.incrementAndGet() >= TRIM_EVERY_N_DOWNLOADS) {
            downloadsSinceTrim.set(0)
            trimArtCache(artDir)
        }

        return try {
            if (success) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                null
            }
        } catch (e: FileNotFoundException) {
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
