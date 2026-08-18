package io.nekohasekai.sfa.compose.model

import io.nekohasekai.sfa.mihomo.MihomoProxyGroup

/** Maps Mihomo's controller response onto the existing Groups UI contract. */
fun MihomoProxyGroup.toGroup(existing: Group? = null): Group = Group(
    tag = name,
    type = type,
    selectable = selectable,
    selected = selected,
    isExpand = existing?.isExpand ?: true,
    items = proxies.map { proxy ->
        GroupItem(
            tag = proxy.name,
            type = proxy.type,
            urlTestTime = 0,
            urlTestDelay = proxy.delay ?: 0,
        )
    },
)
