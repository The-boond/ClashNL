package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.BuildConfig

object CoreVersion {
    fun current(): String = display(
        embeddedVersion = null,
        pinnedVersion = BuildConfig.CORE_VERSION,
    )

    internal fun display(
        embeddedVersion: String?,
        pinnedVersion: String,
    ): String {
        val normalized = embeddedVersion?.trim().orEmpty()
        return if (normalized.isBlank() || normalized.equals("unknown", ignoreCase = true) || normalized == "—") {
            pinnedVersion
        } else {
            normalized
        }
    }
}
