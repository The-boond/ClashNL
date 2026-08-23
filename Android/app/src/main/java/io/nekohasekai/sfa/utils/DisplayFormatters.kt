package io.nekohasekai.sfa.utils

import java.util.Locale
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

private val DECIMAL_BYTE_UNITS = arrayOf("kB", "MB", "GB", "TB", "PB", "EB")

/** Formats traffic counters without loading either native core. */
fun formatBytes(bytes: Long): String = formatBytes(bytes, 1000.0)

/** Formats memory counters without loading either native core. */
fun formatMemoryBytes(bytes: Long): String = formatBytes(bytes, 1024.0)

private fun formatBytes(bytes: Long, base: Double): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    if (safeBytes == 0L) return "0 kB"

    val exponent = floor(ln(safeBytes.toDouble()) / ln(base))
        .toInt()
        .coerceIn(1, DECIMAL_BYTE_UNITS.size)
    val value = floor(safeBytes / base.pow(exponent) * 10.0 + 0.5) / 10.0
    val pattern = if (value < 10.0) "%.1f %s" else "%.0f %s"
    return String.format(Locale.ROOT, pattern, value, DECIMAL_BYTE_UNITS[exponent - 1])
}

/** Formats a millisecond duration for compact connection details. */
fun formatDuration(durationMillis: Long): String {
    val safeDuration = durationMillis.coerceAtLeast(0L)
    if (safeDuration < 1_000L) return "${safeDuration}ms"
    if (safeDuration < 60_000L) {
        val wholeSeconds = safeDuration / 1_000L
        val hundredths = (safeDuration % 1_000L) / 10L
        return if (hundredths == 0L) {
            "${wholeSeconds}s"
        } else {
            "$wholeSeconds.${hundredths.toString().padStart(2, '0').trimEnd('0')}s"
        }
    }
    return "${safeDuration / 60_000L}m${safeDuration / 1_000L % 60L}s"
}

/** Human-readable names for both sing-box and Mihomo proxy type identifiers. */
fun proxyDisplayType(type: String): String = when (type.trim().lowercase(Locale.ROOT)) {
    "tun" -> "TUN"
    "redirect" -> "Redirect"
    "tproxy" -> "TProxy"
    "direct" -> "Direct"
    "block", "reject", "reject-drop" -> "Reject"
    "dns" -> "DNS"
    "socks", "socks5" -> "SOCKS"
    "http" -> "HTTP"
    "mixed" -> "Mixed"
    "shadowsocks", "ss" -> "Shadowsocks"
    "shadowsocksr", "ssr" -> "ShadowsocksR"
    "vmess" -> "VMess"
    "vless" -> "VLESS"
    "trojan" -> "Trojan"
    "naive" -> "Naive"
    "wireguard" -> "WireGuard"
    "hysteria" -> "Hysteria"
    "hysteria2", "hysteria-2" -> "Hysteria2"
    "tuic" -> "TUIC"
    "tor" -> "Tor"
    "ssh" -> "SSH"
    "shadowtls" -> "ShadowTLS"
    "anytls" -> "AnyTLS"
    "mieru" -> "Mieru"
    "tailscale" -> "Tailscale"
    "selector" -> "Selector"
    "urltest", "url-test" -> "URLTest"
    "fallback" -> "Fallback"
    "loadbalance", "load-balance" -> "LoadBalance"
    "relay" -> "Relay"
    "pass" -> "Pass"
    "compatible" -> "Compatible"
    else -> type.ifBlank { "Unknown" }
}
