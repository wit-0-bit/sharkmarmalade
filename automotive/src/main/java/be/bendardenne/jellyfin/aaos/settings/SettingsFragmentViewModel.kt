package be.bendardenne.jellyfin.aaos.settings

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import androidx.concurrent.futures.await
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import be.bendardenne.jellyfin.aaos.JellyfinAccountManager
import be.bendardenne.jellyfin.aaos.JellyfinMediaLibrarySessionCallback.Companion.SYNC_DOWNLOADS_COMMAND
import be.bendardenne.jellyfin.aaos.JellyfinMusicService
import be.bendardenne.jellyfin.aaos.R
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER
import be.bendardenne.jellyfin.aaos.auth
import be.bendardenne.jellyfin.aaos.downloads.DownloadStore
import com.google.common.base.Strings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.extensions.clientLogApi
import java.io.BufferedReader
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class SettingsFragmentViewModel
@Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accountManager: JellyfinAccountManager,
    private val downloadStore: DownloadStore,
) : ViewModel() {

    @Inject
    lateinit var jellyfin: Jellyfin

    val logUploadStatus = MutableLiveData<String>()

    fun versionString(): CharSequence =
        "Finale: ${jellyfin.clientInfo?.version}, Jellyfin API: ${Jellyfin.apiVersion}"

    /** e.g. "214 tracks · 2.1 GB used · 41 GB free on device · synced 12 min. ago" */
    fun downloadStatusSummary(): CharSequence {
        val lastSync = downloadStore.lastSyncAt()
        val syncedText = if (lastSync == 0L) {
            context.getString(R.string.download_status_never)
        } else {
            DateUtils.getRelativeTimeSpanString(lastSync)
        }
        return context.getString(
            R.string.download_status_summary,
            downloadStore.trackCount(),
            Formatter.formatShortFileSize(context, downloadStore.totalBytes()),
            Formatter.formatShortFileSize(context, downloadStore.freeBytes()),
            syncedText
        )
    }

    /** Asks the media service to reconcile downloads now (via a custom session command). */
    fun syncDownloadsNow() {
        val service = ComponentName(context, JellyfinMusicService::class.java)
        val future = MediaController.Builder(context, SessionToken(context, service)).buildAsync()

        viewModelScope.launch {
            try {
                val controller = future.await()
                try {
                    controller.sendCustomCommand(
                        SessionCommand(SYNC_DOWNLOADS_COMMAND, Bundle()),
                        Bundle()
                    )
                    logUploadStatus.postValue(context.getString(R.string.sync_requested))
                } finally {
                    controller.release()
                }
            } catch (e: Exception) {
                Log.w(LOG_MARKER, "Failed to request download sync", e)
                logUploadStatus.postValue(e.message ?: "Failed to request sync")
            }
        }
    }

    fun sendLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                logUploadStatus.postValue("Uploading...")

                val api = jellyfin.createApi()
                api.auth(accountManager)


                var content = ""
                var stderr = ""

                val processExited = try {
                    // Not just our own tag: crashes (AndroidRuntime) and player failures
                    // (ExoPlayer*) are exactly what an in-car log upload needs to capture —
                    // the sign-in saga's uploaded log showed browse activity but not WHY the
                    // process kept dying.
                    val process = Runtime.getRuntime().exec(
                        "logcat -t 800 $LOG_MARKER:V AndroidRuntime:E " +
                                "ExoPlayerImplInternal:E ExoPlayerImpl:E *:S"
                    )

                    // Drain stdout and stderr concurrently on separate threads. Reading
                    // them one after another can deadlock: if the unread stream's OS
                    // pipe buffer fills up, the child blocks writing to it and never
                    // exits, so the other stream's read never reaches EOF either.
                    val stdoutThread = Thread {
                        content = process.inputStream.bufferedReader().use(BufferedReader::readText)
                    }.apply { start() }
                    val stderrThread = Thread {
                        stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
                    }.apply { start() }

                    val exited = process.waitFor(10, TimeUnit.SECONDS)
                    if (!exited) {
                        // Force the process to terminate so the reader threads (which
                        // may still be blocked reading) can reach EOF and finish.
                        process.destroyForcibly()
                    }

                    stdoutThread.join()
                    stderrThread.join()

                    exited
                } catch (e: Exception) {
                    logUploadStatus.postValue(e.message ?: "Failed to collect logs")
                    return@withContext
                }

                if (!processExited) {
                    logUploadStatus.postValue("Timed out waiting for logs to be collected")
                    return@withContext
                }

                if (!Strings.isNullOrEmpty(stderr)) {
                    logUploadStatus.postValue(stderr)
                    return@withContext
                }

                try {
                    val response = api.clientLogApi.logFile(content)
                    logUploadStatus.postValue("Uploaded ${response.content.fileName}")
                } catch (e: Exception) {
                    // Expired token / dropped connection: report it instead of letting the
                    // failure propagate uncaught (there is no CoroutineExceptionHandler).
                    Log.w(LOG_MARKER, "Log upload failed", e)
                    logUploadStatus.postValue(e.message ?: "Log upload failed")
                }
            }

        }
    }
}