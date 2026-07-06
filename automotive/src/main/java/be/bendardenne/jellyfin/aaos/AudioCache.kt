package be.bendardenne.jellyfin.aaos

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER
import java.io.File

/**
 * Process-wide disk cache for streamed audio, so recently played tracks replay without
 * re-streaming.
 */
@OptIn(UnstableApi::class)
object AudioCache {

    private const val MAX_CACHE_BYTES = 5L * 1024 * 1024 * 1024
    private const val FALLBACK_CACHE_BYTES = 2L * 1024 * 1024 * 1024

    // Session/identity query params which don't affect the audio bytes. The universal audio
    // URLs built in MediaItemFactory don't currently contain any of these (auth is via request
    // header), but strip them defensively so cache keys stay stable if they ever appear.
    private val VOLATILE_PARAMS = setOf("api_key", "apikey", "deviceid", "userid", "playsessionid")

    @Volatile
    private var cache: SimpleCache? = null

    // SimpleCache refuses to open the same directory twice in one process, and the service can
    // be destroyed and recreated within a process, so this instance is never released.
    fun getInstance(context: Context): Cache {
        return cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, "audio"),
                LeastRecentlyUsedCacheEvictor(cacheSizeFor(context.applicationContext)),
                StandaloneDatabaseProvider(context.applicationContext)
            ).also { cache = it }
        }
    }

    // Up to 5 GiB, but never more than the system's fair-share cache quota: staying under the
    // quota means the OS clears this app's cache last under storage pressure. The quota is
    // usage-weighted and changes over time; it's re-read each process start.
    private fun cacheSizeFor(context: Context): Long {
        return try {
            val storageManager = context.getSystemService(StorageManager::class.java)
            val quota =
                storageManager.getCacheQuotaBytes(storageManager.getUuidForPath(context.cacheDir))
            quota.coerceAtMost(MAX_CACHE_BYTES).also {
                Log.d(LOG_MARKER, "Audio cache size: $it (cache quota $quota)")
            }
        } catch (e: Exception) {
            Log.d(LOG_MARKER, "Cache quota unavailable, using fixed audio cache size", e)
            FALLBACK_CACHE_BYTES
        }
    }

    val cacheKeyFactory = CacheKeyFactory { dataSpec ->
        dataSpec.key ?: stableKey(dataSpec.uri)
    }

    private fun stableKey(uri: Uri): String {
        if (uri.queryParameterNames.none { it.lowercase() in VOLATILE_PARAMS }) {
            return uri.toString()
        }

        val stable = uri.buildUpon().clearQuery()
        for (name in uri.queryParameterNames) {
            if (name.lowercase() in VOLATILE_PARAMS) continue
            uri.getQueryParameters(name).forEach { stable.appendQueryParameter(name, it) }
        }
        return stable.build().toString()
    }
}
