package io.nekohasekai.sfa.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProfileManagerInstrumentedTest {
    @Test
    fun profileLookupFromMainThreadUsesRoomsCoroutineDispatcher() {
        val result = CompletableFuture<Throwable?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                result.complete(runCatching { ProfileManager.get(Long.MIN_VALUE) }.exceptionOrNull())
            }
        }

        assertNull(
            "ProfileManager.get must not execute a Room query on the main thread",
            result.get(10, TimeUnit.SECONDS),
        )
    }
}
