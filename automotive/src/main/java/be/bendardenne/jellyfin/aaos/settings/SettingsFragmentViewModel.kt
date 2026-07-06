package be.bendardenne.jellyfin.aaos.settings

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.bendardenne.jellyfin.aaos.JellyfinAccountManager
import be.bendardenne.jellyfin.aaos.SharkMarmaladeConstants.LOG_MARKER
import be.bendardenne.jellyfin.aaos.auth
import com.google.common.base.Strings
import dagger.hilt.android.lifecycle.HiltViewModel
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
@Inject constructor(private val accountManager: JellyfinAccountManager) : ViewModel() {

    @Inject
    lateinit var jellyfin: Jellyfin

    val logUploadStatus = MutableLiveData<String>()

    fun versionString(): CharSequence =
        "SharkMarmalade: ${jellyfin.clientInfo?.version}, Jellyfin API: ${Jellyfin.apiVersion}"

    fun sendLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                logUploadStatus.postValue("Uploading...")

                val api = jellyfin.createApi()
                api.auth(accountManager)


                var content = ""
                var stderr = ""

                val processExited = try {
                    val process = Runtime.getRuntime().exec("logcat -t 500 -s $LOG_MARKER")

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
                    logUploadStatus.postValue(e.message)
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

                val response = api.clientLogApi.logFile(content)

                logUploadStatus.postValue("Uploaded ${response.content.fileName}")
            }

        }
    }
}