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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Fetches a release's APK and hands it to the system installer, so an update is one tap instead of a browser
 * download. The first time Android asks whether Herald may install apps; the tap after that installs over this
 * copy, keeping the sign-in and settings. False when the download or the handover failed, and the banner then says
 * so and offers the release page on the next tap.
 */
internal class AndroidUpdater(private val context: Context) : UpdateInstaller {

    override suspend fun install(update: AppUpdate, onProgress: (Float) -> Unit): Boolean {
        val url = update.downloadUrl ?: return false
        val file = try {
            withContext(Dispatchers.IO) { download(url, update.version, onProgress) }
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
    private fun download(url: String, version: String, onProgress: (Float) -> Unit): File =
        downloadApk(url, File(File(context.cacheDir, "updates").apply { mkdirs() }, "herald-$version.apk"), onProgress = onProgress)

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}

/**
 * Resumes rather than restarts: a release asset link drops a large transfer every few seconds on a bad line, and a
 * fresh connection usually gets another chunk. Bounded, so a dead link fails in minutes instead of hanging. Throws,
 * leaving no file behind, unless every byte arrived.
 */
internal fun downloadApk(
    url: String,
    target: File,
    attempts: Int = DOWNLOAD_ATTEMPTS,
    budget: Long = DOWNLOAD_BUDGET_MS,
    onProgress: (Float) -> Unit = {},
): File {
    val deadline = System.currentTimeMillis() + budget
    var total = -1L
    var lastError: Exception? = null
    for (attempt in 1..attempts) {
        if (System.currentTimeMillis() > deadline) break
        val got = if (target.exists()) target.length() else 0L
        if (total > 0 && got >= total) break
        var connection: HttpURLConnection? = null
        try {
            // GitHub answers a release asset with a redirect to its storage host; following it is the point.
            connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                // An attempt that stalls is resumed by the next one, so it doesn't wait two minutes.
                readTimeout = 30_000
                setRequestProperty("Accept", "application/octet-stream")
                if (got > 0) setRequestProperty("Range", "bytes=$got-")
            }
            val code = connection.responseCode
            if (code != 200 && code != 206) error("HTTP $code")
            // 200 to a ranged request means the host ignored the range: start this file over.
            if (code == 200 && got > 0) target.delete()
            val length = connection.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                ?: connection.contentLengthLong.takeIf { code == 200 && it > 0 }
            if (length != null && length > 0) total = length
            connection.inputStream.use { input ->
                FileOutputStream(target, true).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        if (total > 0) onProgress((target.length().toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } catch (e: Exception) {
            lastError = e
        } finally {
            connection?.disconnect()
        }
    }
    // A partial file is never offered as the next update.
    if (total <= 0 || target.length() != total) {
        val size = target.length()
        target.delete()
        error("downloaded $size of $total bytes: ${lastError?.message}")
    }
    return target
}

internal const val DOWNLOAD_ATTEMPTS = 40
internal const val DOWNLOAD_BUDGET_MS = 8 * 60_000L
