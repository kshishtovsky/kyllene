package dev.hermeskotlin.android.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.hermeskotlin.core.update.AppUpdate
import dev.hermeskotlin.ui.update.UpdateInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Fetches a release's APK and hands it to the system installer, so an update is one tap instead of a browser
 * download. The first time Android asks whether Herald may install apps; the tap after that installs over this
 * copy, keeping the sign-in and settings. False — and the banner then opens the download in the browser — when the
 * download fails or nothing on this phone takes an APK.
 */
internal class AndroidUpdater(private val context: Context) : UpdateInstaller {

    override suspend fun install(update: AppUpdate): Boolean {
        val url = update.downloadUrl ?: return false
        val file = try {
            withContext(Dispatchers.IO) { download(url, update.version) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** One file per version, so a half-downloaded one is never offered as the next update. */
    private fun download(url: String, version: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "herald-$version.apk")
        var connection: HttpURLConnection? = null
        try {
            // GitHub answers a release asset with a redirect to its storage host; following it is the point.
            connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                // 30 MB on a slow mobile link.
                readTimeout = 120_000
                setRequestProperty("Accept", "application/octet-stream")
            }
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            connection.inputStream.use { input -> target.outputStream().use { out -> input.copyTo(out) } }
            return target
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
