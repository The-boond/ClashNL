package io.nekohasekai.sfa.account

import io.nekohasekai.sfa.account.model.AccountDetails
import io.nekohasekai.sfa.account.model.AccountProfileSyncResult
import io.nekohasekai.sfa.account.model.AccountSession
import io.nekohasekai.sfa.account.network.AccountApi
import io.nekohasekai.sfa.account.security.AccountSessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRepositoryTest {
    @Test
    fun `login stores session loads entitlement and syncs profile`() = runBlocking {
        val store = FakeSessionStore()
        val api = FakeAccountApi()
        val sync = FakeProfileSync()
        val repository = AccountRepository(api, store, sync)

        repository.login("user@example.com", "password123")

        assertEquals("Bearer TOKEN", store.loadSession()?.authorization)
        assertEquals("标准版", repository.state.value.details?.plan?.name)
        assertEquals(1, sync.calls)
        assertNotNull(repository.state.value.profileSyncResult)
        assertFalse(repository.state.value.isLoading)
    }

    @Test
    fun `refresh preserves session when subscription request fails`() = runBlocking {
        val store = FakeSessionStore().apply {
            saveSession(AccountSession("user@example.com", "Bearer TOKEN"))
        }
        val api = FakeAccountApi(subscriptionError = IllegalStateException("暂时不可用"))
        val repository = AccountRepository(api, store, FakeProfileSync())

        repository.refresh()

        assertTrue(repository.state.value.isSignedIn)
        assertEquals("暂时不可用", repository.state.value.errorMessage)
        assertNull(repository.state.value.details)
    }

    @Test
    fun `logout clears only account session`() = runBlocking {
        val store = FakeSessionStore().apply {
            saveSession(AccountSession("user@example.com", "Bearer TOKEN"))
            managedProfileId = 42L
        }
        val repository = AccountRepository(FakeAccountApi(), store, FakeProfileSync())

        repository.logout()

        assertNull(store.loadSession())
        assertEquals(42L, store.managedProfileId)
        assertFalse(repository.state.value.isSignedIn)
    }

    private class FakeAccountApi(
        private val subscriptionError: Throwable? = null,
    ) : AccountApi {
        override suspend fun login(email: String, password: String): AccountSession = AccountSession(email, "Bearer TOKEN")

        override suspend fun getSubscription(authorization: String): AccountDetails {
            subscriptionError?.let { throw it }
            return sampleDetails()
        }
    }

    private class FakeSessionStore : AccountSessionStore {
        private var session: AccountSession? = null
        override var managedProfileId: Long = -1L

        override fun loadSession(): AccountSession? = session

        override fun saveSession(session: AccountSession) {
            this.session = session
        }

        override fun clearSession() {
            session = null
        }
    }

    private class FakeProfileSync : AccountProfileSync {
        var calls = 0

        override suspend fun sync(details: AccountDetails): AccountProfileSyncResult {
            calls += 1
            return AccountProfileSyncResult(42L, created = true, contentChanged = true)
        }
    }

    companion object {
        private fun sampleDetails() = AccountDetails(
            email = "user@example.com",
            plan = io.nekohasekai.sfa.account.model.AccountPlan(
                id = 3L,
                name = "标准版",
                transferLimitBytes = 1000L,
                deviceLimit = 4,
                speedLimitMbps = 50,
            ),
            uploadedBytes = 100L,
            downloadedBytes = 200L,
            transferLimitBytes = 1000L,
            expiresAtEpochSeconds = 2_000_000_000L,
            nextResetAtEpochSeconds = 2_000_000_100L,
            subscribeUrl = "https://example.com/s/TOKEN",
        )
    }
}
