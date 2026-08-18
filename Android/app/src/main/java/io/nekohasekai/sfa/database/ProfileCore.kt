package io.nekohasekai.sfa.database

/**
 * The runtime that owns a profile's persisted configuration file.
 *
 * Existing profiles intentionally deserialize to [SingBox] so an app update
 * can never send a stored sing-box JSON profile to Mihomo by accident.
 */
enum class ProfileCore {
    SingBox,
    Mihomo,
    ;

    companion object {
        fun fromOrdinal(value: Int): ProfileCore = entries.getOrElse(value) { SingBox }
    }
}
