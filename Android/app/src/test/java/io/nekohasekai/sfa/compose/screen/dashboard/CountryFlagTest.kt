package io.nekohasekai.sfa.compose.screen.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class CountryFlagTest {
    @Test
    fun convertsTwoLetterCountryCodeToFlag() {
        assertEquals("🇸🇬", countryCodeToFlagEmoji("sg"))
    }

    @Test
    fun fallsBackForMissingCountryCode() {
        assertEquals("🌐", countryCodeToFlagEmoji(null))
    }
}
