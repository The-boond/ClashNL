package io.nekohasekai.sfa.mihomo

enum class MihomoNetworkMode(val storageValue: String) {
    SystemProxy("system_proxy"),
    VirtualNic("virtual_nic"),
    ;

    companion object {
        fun fromStorage(value: String): MihomoNetworkMode = entries.firstOrNull { it.storageValue == value } ?: VirtualNic
    }
}
