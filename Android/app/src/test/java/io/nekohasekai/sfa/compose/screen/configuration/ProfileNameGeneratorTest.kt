package io.nekohasekai.sfa.compose.screen.configuration

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileNameGeneratorTest {
    @Test
    fun startsAtOneWhenNoDefaultNamesExist() {
        assertEquals(
            1,
            nextDefaultSubscriptionIndex(setOf("Custom")) { index -> "Default subscription %02d".format(index) },
        )
    }

    @Test
    fun advancesPastExistingSequentialNames() {
        assertEquals(
            3,
            nextDefaultSubscriptionIndex(
                setOf("默认订阅01", "默认订阅02", "Custom"),
            ) { index -> "默认订阅%02d".format(index) },
        )
    }
}
