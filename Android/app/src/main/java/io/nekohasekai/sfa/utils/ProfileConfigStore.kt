package io.nekohasekai.sfa.utils

import android.util.AtomicFile
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Writes profile configuration with an atomic replace.
 *
 * A failed download, normalization, process kill, or full disk must leave the
 * previously working configuration intact.
 */
object ProfileConfigStore {
    fun read(file: File): String = AtomicFile(file).openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (exception: Exception) {
            atomicFile.failWrite(output)
            throw exception
        }
    }

    fun writeIfChanged(file: File, content: String): Boolean {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val existing = if (file.exists()) {
            runCatching { AtomicFile(file).openRead().use { it.readBytes() } }.getOrNull()
        } else {
            null
        }
        if (existing?.contentEquals(bytes) == true) {
            return false
        }
        write(file, content)
        return true
    }
}
