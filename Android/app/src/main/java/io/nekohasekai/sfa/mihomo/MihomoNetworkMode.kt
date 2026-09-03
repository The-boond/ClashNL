package io.nekohasekai.sfa.mihomo

enum class MihomoNetworkMode(val storageValue: String) {
    SystemProxy("system_proxy"),
    VirtualNic("virtual_nic"),
    ;

    companion object {
        fun fromStorage(value: String): MihomoNetworkMode = entries.firstOrNull { it.storageValue == value } ?: VirtualNic
    }
}

/** Virtual NIC keeps the proven post-establish VpnService pinning sequence. */
internal fun MihomoNetworkMode.pinsUnderlyingAfterEstablish(): Boolean = this == MihomoNetworkMode.VirtualNic
