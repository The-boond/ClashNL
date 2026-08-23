package io.nekohasekai.sfa.vendor

import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.utils.AppHttpTransport
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

class ApkDownloader : Closeable {
    suspend fun download(url: String): File = withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        val cacheDir = File(Application.application.cacheDir, "updates")
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw IOException("Unable to create the update cache directory")
        }
        val apkFile = File(cacheDir, "update.apk")
        val partialFile = File(cacheDir, "update.apk.part")

        deleteIfPresent(apkFile)
        deleteIfPresent(partialFile)

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream;q=0.9, */*;q=0.1")
            .header("User-Agent", HTTPClient.userAgent)
            .build()

        try {
            AppHttpTransport.execute(
                request,
                preferLocalSocks = true,
                callTimeoutSeconds = 0,
            ).use { response ->
                if (!response.isSuccessful) {
                    throw IOException("APK download failed (HTTP ${response.code})")
                }
                val body = response.body ?: throw IOException("APK download returned an empty response")
                val expectedLength = body.contentLength()
                var downloaded = 0L
                body.byteStream().use { input ->
                    FileOutputStream(partialFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            UpdateState.downloadProgress.value =
                                if (expectedLength > 0) {
                                    (downloaded.toDouble() / expectedLength.toDouble())
                                        .coerceIn(0.0, 1.0)
                                        .toFloat()
                                } else {
                                    null
                                }
                        }
                        coroutineContext.ensureActive()
                        output.fd.sync()
                    }
                }
                if (downloaded == 0L) throw IOException("APK download returned an empty file")
                if (expectedLength >= 0 && downloaded != expectedLength) {
                    throw IOException("APK download was incomplete ($downloaded of $expectedLength bytes)")
                }
            }

            if (!isApkArchive(partialFile)) {
                throw IOException("Downloaded file is not an APK")
            }
            if (!partialFile.renameTo(apkFile)) {
                throw IOException("Unable to publish the downloaded APK")
            }

            UpdateState.downloadProgress.value = 1f
            UpdateState.saveApkPath(apkFile)
            apkFile
        } finally {
            if (partialFile.exists()) partialFile.delete()
        }
    }

    private fun deleteIfPresent(file: File) {
        if (file.exists() && !file.delete()) {
            throw IOException("Unable to clear the previous update download")
        }
    }

    private fun isApkArchive(file: File): Boolean = runCatching {
        ZipFile(file).use { archive -> archive.getEntry("AndroidManifest.xml") != null }
    }.getOrDefault(false)

    override fun close() {
        // AppHttpTransport is stateless; retained for existing use-call sites.
    }
}
