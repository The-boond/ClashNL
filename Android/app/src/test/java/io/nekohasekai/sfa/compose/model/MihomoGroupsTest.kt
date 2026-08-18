package io.nekohasekai.sfa.compose.model

import io.nekohasekai.sfa.mihomo.MihomoProxy
import io.nekohasekai.sfa.mihomo.MihomoProxyGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MihomoGroupsTest {
    @Test
    fun mapsMihomoSelectorIntoExistingGroupPresentation() {
        val group = MihomoProxyGroup(
            name = "Proxy",
            type = "Selector",
            selected = "Japan-01",
            selectable = true,
            proxies = listOf(MihomoProxy("Japan-01", "vmess", delay = 42)),
        ).toGroup()

        assertEquals("Proxy", group.tag)
        assertTrue(group.selectable)
        assertEquals("Japan-01", group.selected)
        assertEquals(42, group.items.single().urlTestDelay)
    }

    @Test
    fun preservesUiExpansionWithoutInventingAControllerDelay() {
        val existing = Group("Proxy", "Selector", true, "Old", false, emptyList())
        val group = MihomoProxyGroup(
            name = "Proxy",
            type = "Selector",
            selected = "New",
            selectable = true,
            proxies = listOf(MihomoProxy("New", "ss")),
        ).toGroup(existing)

        assertFalse(group.isExpand)
        assertEquals(0, group.items.single().urlTestDelay)
    }
}
