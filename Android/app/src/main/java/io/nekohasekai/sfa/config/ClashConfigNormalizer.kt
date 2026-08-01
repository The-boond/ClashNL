package io.nekohasekai.sfa.config

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.ktx.unwrap

/**
 * Accepts either a native sing-box JSON configuration or a Clash/Mihomo YAML
 * profile and always returns validated sing-box JSON for storage and runtime use.
 */
object ClashConfigNormalizer {
    enum class SourceFormat {
        SING_BOX,
        CLASH,
    }

    data class Result(
        val content: String,
        val sourceFormat: SourceFormat,
    )

    fun normalize(source: String): Result {
        val content = source.trim()
        require(content.isNotEmpty()) { "Configuration is empty" }

        // Normalize every native JSON object before validation. Some obsolete
        // combinations still decode successfully but fail only when the service
        // starts, so checking the original document first would skip migrations.
        val migrated = SingBoxConfigMigrator.migrate(content)
        if (migrated != null) {
            val normalized = migrated.content.takeIf { migrated.changed } ?: content
            try {
                Libbox.checkConfig(normalized)
                return Result(normalized, SourceFormat.SING_BOX)
            } catch (nativeException: Exception) {
                val message =
                    nativeException.message?.takeIf { it.isNotBlank() }
                        ?: if (migrated.changed) {
                            "invalid sing-box configuration after compatibility migration"
                        } else {
                            "invalid native config"
                        }
                throw IllegalArgumentException(
                    "sing-box configuration is incompatible with this app: $message",
                    nativeException,
                )
            }
        }

        val singBoxError =
            try {
                Libbox.checkConfig(content)
                return Result(content, SourceFormat.SING_BOX)
            } catch (exception: Exception) {
                exception
            }

        try {
            val converted = Libbox.convertClashConfig(content).unwrap
            require(converted.isNotBlank()) { "Clash converter returned an empty configuration" }
            Libbox.checkConfig(converted)
            return Result(converted, SourceFormat.CLASH)
        } catch (clashException: Exception) {
            val singBoxMessage = singBoxError.message?.takeIf { it.isNotBlank() } ?: "invalid native config"
            val clashMessage = clashException.message?.takeIf { it.isNotBlank() } ?: "invalid Clash/Mihomo profile"
            throw IllegalArgumentException(
                "Configuration is neither valid sing-box JSON nor supported Clash/Mihomo YAML. " +
                    "sing-box: $singBoxMessage; Clash/Mihomo: $clashMessage",
                clashException,
            )
        }
    }
}
