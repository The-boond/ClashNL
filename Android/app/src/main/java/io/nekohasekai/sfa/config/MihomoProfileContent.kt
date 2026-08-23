package io.nekohasekai.sfa.config

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Applies the storage invariants shared by every Mihomo profile entry point.
 * Native validation remains the runtime authority because it tracks Mihomo's
 * accepted YAML schema; this guard prevents the retired sing-box JSON format
 * from re-entering the profile store.
 */
object MihomoProfileContent {
    const val MAX_PROFILE_BYTES = 16 * 1024 * 1024

    fun readUtf8(input: InputStream, declaredLength: Long = -1L): String {
        require(declaredLength <= MAX_PROFILE_BYTES || declaredLength < 0L) {
            "配置文件过大（最大 16 MiB）"
        }
        val initialCapacity = declaredLength.takeIf { it in 1..MAX_PROFILE_BYTES.toLong() }?.toInt() ?: 8 * 1024
        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= MAX_PROFILE_BYTES) { "配置文件过大（最大 16 MiB）" }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    fun decodeUtf8(content: ByteArray): String {
        require(content.size <= MAX_PROFILE_BYTES) { "配置文件过大（最大 16 MiB）" }
        return String(content, StandardCharsets.UTF_8)
    }

    fun normalize(content: String): String {
        require(content.toByteArray(StandardCharsets.UTF_8).size <= MAX_PROFILE_BYTES) {
            "配置文件过大（最大 16 MiB）"
        }
        val normalized = content.replace("\r\n", "\n").removePrefix("\uFEFF")
        require(normalized.isNotBlank()) { "配置内容为空" }
        require(!normalized.trimStart().startsWith("{")) {
            "此版本仅支持 Clash/Mihomo YAML，不支持 sing-box JSON"
        }
        return if (normalized.endsWith('\n')) normalized else "$normalized\n"
    }
}
