package io.nekohasekai.sfa.account

import io.nekohasekai.sfa.account.model.AccountDetails
import io.nekohasekai.sfa.account.model.AccountOrder
import io.nekohasekai.sfa.account.model.AccountProfileSyncResult
import io.nekohasekai.sfa.account.model.AccountSession
import io.nekohasekai.sfa.account.model.BillingPeriod
import io.nekohasekai.sfa.account.model.CheckoutResult
import io.nekohasekai.sfa.account.model.PaymentMethod
import io.nekohasekai.sfa.account.model.PlanOffer
import io.nekohasekai.sfa.account.model.PlanPrice
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

    @Test
    fun `purchase options recover pending order`() = runBlocking {
        val store = signedInStore()
        val pending = sampleOrder()
        val api = FakeAccountApi(pendingOrders = mutableListOf(pending))
        val repository = AccountRepository(api, store, FakeProfileSync())

        repository.loadPurchaseOptions()

        assertEquals("TRADE_123", repository.purchaseState.value.pendingOrder?.tradeNo)
        assertEquals("TRADE_123", store.pendingTradeNo)
        assertEquals("旗舰版", repository.purchaseState.value.plans.single().name)
        assertEquals("微信支付", repository.purchaseState.value.paymentMethods.single().name)
    }

    @Test
    fun `purchase creates order and exposes secure cashier result`() = runBlocking {
        val store = signedInStore()
        val api = FakeAccountApi()
        val repository = AccountRepository(api, store, FakeProfileSync())
        repository.loadPurchaseOptions()

        repository.purchase(7L, BillingPeriod.MONTH, 9L)

        assertEquals("TRADE_123", store.pendingTradeNo)
        assertEquals("TRADE_123", repository.purchaseState.value.pendingOrder?.tradeNo)
        assertTrue(repository.purchaseState.value.checkoutResult?.isUrl == true)
        assertEquals(1, api.createOrderCalls)
        assertEquals(1, api.checkoutCalls)
    }

    @Test
    fun `cancel pending order clears recovery state`() = runBlocking {
        val store = signedInStore().apply { pendingTradeNo = "TRADE_123" }
        val api = FakeAccountApi(pendingOrders = mutableListOf(sampleOrder()))
        val repository = AccountRepository(api, store, FakeProfileSync())
        repository.loadPurchaseOptions()

        repository.cancelPendingOrder()

        assertNull(store.pendingTradeNo)
        assertNull(repository.purchaseState.value.pendingOrder)
        assertEquals(1, api.cancelCalls)
    }

    @Test
    fun `resume pending checkout reuses order without creating another`() = runBlocking {
        val store = signedInStore().apply { pendingTradeNo = "TRADE_123" }
        val api = FakeAccountApi(pendingOrders = mutableListOf(sampleOrder()))
        val repository = AccountRepository(api, store, FakeProfileSync())
        repository.loadPurchaseOptions()

        repository.resumePendingCheckout(9L)

        assertEquals(0, api.createOrderCalls)
        assertEquals(1, api.checkoutCalls)
        assertTrue(repository.purchaseState.value.checkoutResult?.isUrl == true)
    }

    @Test
    fun `completed payment refreshes subscription and profile`() = runBlocking {
        val store = signedInStore().apply { pendingTradeNo = "TRADE_123" }
        val api = FakeAccountApi(orderStatus = 3)
        val sync = FakeProfileSync()
        val repository = AccountRepository(api, store, sync)

        repository.checkPendingOrder()

        assertNull(store.pendingTradeNo)
        assertNotNull(repository.state.value.details)
        assertEquals(1, sync.calls)
        assertTrue(repository.purchaseState.value.statusMessage?.contains("支付成功") == true)
    }

    private class FakeAccountApi(
        private val subscriptionError: Throwable? = null,
        private val pendingOrders: MutableList<AccountOrder> = mutableListOf(),
        private val orderStatus: Int = 0,
    ) : AccountApi {
        var createOrderCalls = 0
        var checkoutCalls = 0
        var cancelCalls = 0

        override suspend fun login(email: String, password: String): AccountSession = AccountSession(email, "Bearer TOKEN")

        override suspend fun getSubscription(authorization: String): AccountDetails {
            subscriptionError?.let { throw it }
            return sampleDetails()
        }

        override suspend fun getPlans(authorization: String): List<PlanOffer> = listOf(samplePlan())

        override suspend fun getPaymentMethods(authorization: String): List<PaymentMethod> = listOf(
            PaymentMethod(9L, "微信支付", "EPay", 0L, 1.2),
        )

        override suspend fun createOrder(
            authorization: String,
            planId: Long,
            period: BillingPeriod,
        ): String {
            createOrderCalls += 1
            pendingOrders += sampleOrder()
            return "TRADE_123"
        }

        override suspend fun getPendingOrders(authorization: String): List<AccountOrder> = pendingOrders.toList()

        override suspend fun checkout(
            authorization: String,
            tradeNo: String,
            methodId: Long,
        ): CheckoutResult {
            checkoutCalls += 1
            return CheckoutResult(CheckoutResult.TYPE_URL, "https://pay.example.com/cashier")
        }

        override suspend fun checkOrder(authorization: String, tradeNo: String): Int = orderStatus

        override suspend fun cancelOrder(authorization: String, tradeNo: String): Boolean {
            cancelCalls += 1
            pendingOrders.removeAll { it.tradeNo == tradeNo }
            return true
        }
    }

    private class FakeSessionStore : AccountSessionStore {
        private var session: AccountSession? = null
        override var managedProfileId: Long = -1L
        override var pendingTradeNo: String? = null

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
        private fun signedInStore() = FakeSessionStore().apply {
            saveSession(AccountSession("user@example.com", "Bearer TOKEN"))
        }

        private fun samplePlan() = PlanOffer(
            id = 7L,
            name = "旗舰版",
            content = "高速套餐",
            transferLimitBytes = 1000L,
            deviceLimit = 5,
            speedLimitMbps = 200,
            soldOut = false,
            purchaseAvailable = true,
            renewAvailable = true,
            prices = listOf(PlanPrice(BillingPeriod.MONTH, 1200L)),
        )

        private fun sampleOrder() = AccountOrder(
            tradeNo = "TRADE_123",
            planId = 7L,
            planName = "旗舰版",
            period = BillingPeriod.MONTH,
            totalAmountCents = 1200L,
            handlingAmountCents = 0L,
            status = 0,
            createdAtEpochSeconds = 2_000_000_000L,
        )

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
