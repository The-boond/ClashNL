package io.nekohasekai.sfa.compose.screen.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClashModeLabelTest {
    @Test
    fun translatesKnownCoreModesWithoutChangingTheirKeys() {
        assertEquals("路由", localizeClashMode("rule", "路由", "直连", "全局"))
        assertEquals("直连", localizeClashMode("DIRECT", "路由", "直连", "全局"))
        assertEquals("全局", localizeClashMode("global", "路由", "直连", "全局"))
    }

    @Test
    fun preservesCustomModeNames() {
        assertEquals("自定义", localizeClashMode("自定义", "路由", "直连", "全局"))
    }
}
