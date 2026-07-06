package be.bendardenne.jellyfin.aaos

import android.content.Context
import android.util.Log
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import java.io.File
import java.security.MessageDigest

/**
 * Disk cache for raw Jellyfin DTOs, so the browse tree can be served instantly on cold start.
 * MediaItems are never persisted: they embed stream URLs and preference-dependent values, and
 * must always be rebuilt through MediaItemFactory.
 */
class MediaTreeDiskCache(context: Context, private val api: ApiClient) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(BaseItemDto.serializer())

    // Namespaced per server+account so an account switch never serves another library's cache.
    // Computed per operation, since login updates the ApiClient in place.
    private fun namespaceDir(): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${api.baseUrl}|${api.accessToken}".toByteArray())
        val namespace = digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
        return File(File(appContext.cacheDir, "tree"), namespace)
    }

    private fun sanitize(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun childrenFile(key: String) =
        File(File(namespaceDir(), "children"), "${sanitize(key)}.json")

    private fun itemFile(id: String) =
        File(File(namespaceDir(), "items"), "${sanitize(id)}.json")

    suspend fun readChildren(key: String): List<BaseItemDto>? =
        read(childrenFile(key), listSerializer)

    suspend fun writeChildren(key: String, items: List<BaseItemDto>) =
        write(childrenFile(key), listSerializer, items)

    suspend fun readItem(id: String): BaseItemDto? =
        read(itemFile(id), BaseItemDto.serializer())

    suspend fun writeItem(item: BaseItemDto) =
        write(itemFile(item.id.toString()), BaseItemDto.serializer(), item)

    private suspend fun <T> read(file: File, serializer: KSerializer<T>): T? =
        withContext(Dispatchers.IO) {
            try {
                if (!file.isFile) return@withContext null
                json.decodeFromString(serializer, file.readText())
            } catch (e: Exception) {
                // A corrupt cache entry is just a miss; it must never break browsing.
                Log.d(LOG_MARKER, "Deleting unreadable cache file $file", e)
                file.delete()
                null
            }
        }

    /** Returns false if the value did not reach disk, so callers can avoid trusting stale data. */
    private suspend fun <T> write(file: File, serializer: KSerializer<T>, value: T): Boolean =
        withContext(Dispatchers.IO) {
            var tmp: File? = null
            try {
                file.parentFile?.mkdirs()
                tmp = File.createTempFile(file.name, ".tmp", file.parentFile)
                tmp.writeText(json.encodeToString(serializer, value))
                tmp.renameTo(file)
            } catch (e: Exception) {
                Log.d(LOG_MARKER, "Failed to write cache file $file", e)
                false
            }.also { if (!it) tmp?.delete() }
        }
}
