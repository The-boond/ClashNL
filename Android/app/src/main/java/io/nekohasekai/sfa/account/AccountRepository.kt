package io.nekohasekai.sfa.account

import android.content.Context
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.account.model.AccountOrder
import io.nekohasekai.sfa.account.model.AccountSession
import io.nekohasekai.sfa.account.model.AccountUiState
import io.nekohasekai.sfa.account.model.BillingPeriod
import io.nekohasekai.sfa.account.model.PurchaseUiState
import io.nekohasekai.sfa.account.network.AccountApi
import io.nekohasekai.sfa.account.network.XBoardAccountApi
import io.nekohasekai.sfa.account.security.AccountSessionStore
import io.nekohasekai.sfa.account.security.AndroidAccountSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AccountRepository(
    private val api: AccountApi,
    private val sessionStore: AccountSessionStore,
    private val profileSynchronizer: AccountProfileSync,
) {
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow(
        AccountUiState(session = sessionStore.loadSession()),
    )
    val state: StateFlow<AccountUiState> = _state.asStateFlow()
    private val _purchaseState = MutableStateFlow(PurchaseUiState())
    val purchaseState: StateFlow<PurchaseUiState> = _purchaseState.asStateFlow()

    suspend fun login(email: String, password: String) = operationMutex.withLock {
        _state.value = _state.value.copy(isLoading = true, errorMessage = null, profileSyncResult = null)
        runCatching {
            api.login(email.trim(), password).also(sessionStore::saveSession)
        }.onSuccess { session ->
            refreshSession(session)
        }.onFailure { error ->
            _state.value = AccountUiState(errorMessage = error.userMessage())
        }
    }

    suspend fun refresh() = operationMutex.withLock {
        val session = sessionStore.loadSession()
        if (session == null) {
            _state.value = AccountUiState()
            return@withLock
        }
        _state.value = _state.value.copy(
            session = session,
            isLoading = true,
            errorMessage = null,
            profileSyncResult = null,
        )
        refreshSession(session)
    }

    fun logout() {
        sessionStore.clearSession()
        _state.value = AccountUiState()
        _purchaseState.value = PurchaseUiState()
    }

    suspend fun loadPurchaseOptions() = operationMutex.withLock {
        val session = requireSession() ?: return@withLock
        _purchaseState.value = _purchaseState.value.copy(isLoading = true, errorMessage = null)
        runCatching {
            val plans = api.getPlans(session.authorization)
            val paymentMethods = api.getPaymentMethods(session.authorization)
            val pendingOrders = api.getPendingOrders(session.authorization)
            Triple(plans, paymentMethods, pendingOrders.firstOrNull())
        }.onSuccess { (plans, paymentMethods, pendingOrder) ->
            val storedTradeNo = sessionStore.pendingTradeNo
            var recoveredOrder = pendingOrder
            var statusMessage: String? = null
            if (recoveredOrder == null && !storedTradeNo.isNullOrBlank()) {
                when (runCatching { api.checkOrder(session.authorization, storedTradeNo) }.getOrNull()) {
                    ORDER_PENDING, ORDER_PROCESSING -> {
                        recoveredOrder = AccountOrder(
                            tradeNo = storedTradeNo,
                            planId = 0L,
                            planName = "",
                            period = null,
                            totalAmountCents = 0L,
                            handlingAmountCents = 0L,
                            status = ORDER_PROCESSING,
                            createdAtEpochSeconds = 0L,
                        )
                        statusMessage = "订单正在处理"
                    }

                    ORDER_COMPLETED, ORDER_DISCOUNTED -> {
                        sessionStore.pendingTradeNo = null
                        refreshSession(session)
                        statusMessage = "支付成功，套餐与订阅已刷新"
                    }

                    ORDER_CANCELLED -> sessionStore.pendingTradeNo = null

                    else -> {
                        recoveredOrder = AccountOrder(
                            tradeNo = storedTradeNo,
                            planId = 0L,
                            planName = "",
                            period = null,
                            totalAmountCents = 0L,
                            handlingAmountCents = 0L,
                            status = ORDER_PENDING,
                            createdAtEpochSeconds = 0L,
                        )
                        statusMessage = "请检查订单状态"
                    }
                }
            }
            sessionStore.pendingTradeNo = recoveredOrder?.tradeNo ?: sessionStore.pendingTradeNo
            _purchaseState.value = PurchaseUiState(
                plans = plans,
                paymentMethods = paymentMethods,
                pendingOrder = recoveredOrder,
                statusMessage = statusMessage,
            )
        }.onFailure { error ->
            _purchaseState.value = _purchaseState.value.copy(
                isLoading = false,
                errorMessage = error.userMessage(),
            )
        }
    }

    suspend fun purchase(
        planId: Long,
        period: BillingPeriod,
        methodId: Long,
    ) = operationMutex.withLock {
        val session = requireSession() ?: return@withLock
        if (_purchaseState.value.pendingOrder != null || !sessionStore.pendingTradeNo.isNullOrBlank()) {
            _purchaseState.value = _purchaseState.value.copy(errorMessage = "请先完成或取消现有待支付订单")
            return@withLock
        }
        _purchaseState.value = _purchaseState.value.copy(
            isLoading = true,
            errorMessage = null,
            statusMessage = null,
            checkoutResult = null,
        )
        runCatching {
            val tradeNo = api.createOrder(session.authorization, planId, period)
            sessionStore.pendingTradeNo = tradeNo
            val checkout = api.checkout(session.authorization, tradeNo, methodId)
            tradeNo to checkout
        }.onSuccess { (tradeNo, checkout) ->
            if (checkout.completedWithoutGateway) {
                sessionStore.pendingTradeNo = null
                refreshSession(session)
                _purchaseState.value = _purchaseState.value.copy(
                    isLoading = false,
                    pendingOrder = null,
                    checkoutResult = null,
                    statusMessage = "订单已完成，套餐与订阅已刷新",
                )
            } else {
                val pendingOrder = runCatching { api.getPendingOrders(session.authorization) }
                    .getOrNull()
                    ?.firstOrNull { it.tradeNo == tradeNo }
                    ?: AccountOrder(
                        tradeNo = tradeNo,
                        planId = planId,
                        planName = _purchaseState.value.plans.firstOrNull { it.id == planId }?.name.orEmpty(),
                        period = period,
                        totalAmountCents = _purchaseState.value.plans
                            .firstOrNull { it.id == planId }
                            ?.prices
                            ?.firstOrNull { it.period == period }
                            ?.priceCents
                            ?: 0L,
                        handlingAmountCents = 0L,
                        status = 0,
                        createdAtEpochSeconds = System.currentTimeMillis() / 1000L,
                    )
                _purchaseState.value = _purchaseState.value.copy(
                    isLoading = false,
                    pendingOrder = pendingOrder,
                    checkoutResult = checkout,
                    statusMessage = "订单已创建，请在收银台完成支付",
                )
            }
        }.onFailure { error ->
            val pendingTradeNo = sessionStore.pendingTradeNo
            val pendingOrder = runCatching { api.getPendingOrders(session.authorization) }
                .getOrNull()
                ?.firstOrNull { pendingTradeNo == null || it.tradeNo == pendingTradeNo }
                ?: pendingTradeNo?.let {
                    AccountOrder(
                        tradeNo = it,
                        planId = planId,
                        planName = _purchaseState.value.plans.firstOrNull { plan -> plan.id == planId }?.name.orEmpty(),
                        period = period,
                        totalAmountCents = 0L,
                        handlingAmountCents = 0L,
                        status = ORDER_PENDING,
                        createdAtEpochSeconds = System.currentTimeMillis() / 1000L,
                    )
                }
            if (pendingOrder == null) sessionStore.pendingTradeNo = null
            _purchaseState.value = _purchaseState.value.copy(
                isLoading = false,
                pendingOrder = pendingOrder,
                errorMessage = error.userMessage(),
            )
        }
    }

    suspend fun checkPendingOrder() = operationMutex.withLock {
        val session = requireSession() ?: return@withLock
        val tradeNo = sessionStore.pendingTradeNo ?: _purchaseState.value.pendingOrder?.tradeNo
        if (tradeNo.isNullOrBlank()) {
            loadPurchaseOptionsWithoutLock(session)
            return@withLock
        }
        _purchaseState.value = _purchaseState.value.copy(isLoading = true, errorMessage = null)
        runCatching { api.checkOrder(session.authorization, tradeNo) }
            .onSuccess { status ->
                when (status) {
                    ORDER_COMPLETED, ORDER_DISCOUNTED -> {
                        sessionStore.pendingTradeNo = null
                        refreshSession(session)
                        loadPurchaseOptionsWithoutLock(session, "支付成功，套餐与订阅已刷新")
                    }

                    ORDER_PROCESSING -> _purchaseState.value = _purchaseState.value.copy(
                        isLoading = false,
                        statusMessage = "支付已确认，套餐正在开通",
                    )

                    ORDER_CANCELLED -> {
                        sessionStore.pendingTradeNo = null
                        loadPurchaseOptionsWithoutLock(session, "订单已取消")
                    }

                    else -> _purchaseState.value = _purchaseState.value.copy(
                        isLoading = false,
                        statusMessage = "订单仍待支付",
                    )
                }
            }
            .onFailure { error ->
                _purchaseState.value = _purchaseState.value.copy(
                    isLoading = false,
                    errorMessage = error.userMessage(),
                )
            }
    }

    suspend fun resumePendingCheckout(methodId: Long) = operationMutex.withLock {
        val session = requireSession() ?: return@withLock
        val tradeNo = sessionStore.pendingTradeNo ?: _purchaseState.value.pendingOrder?.tradeNo
        if (tradeNo.isNullOrBlank()) return@withLock
        _purchaseState.value = _purchaseState.value.copy(
            isLoading = true,
            errorMessage = null,
            checkoutResult = null,
        )
        runCatching { api.checkout(session.authorization, tradeNo, methodId) }
            .onSuccess { checkout ->
                if (checkout.completedWithoutGateway) {
                    sessionStore.pendingTradeNo = null
                    refreshSession(session)
                    loadPurchaseOptionsWithoutLock(session, "订单已完成，套餐与订阅已刷新")
                } else {
                    _purchaseState.value = _purchaseState.value.copy(
                        isLoading = false,
                        checkoutResult = checkout,
                        statusMessage = "请在收银台完成支付",
                    )
                }
            }
            .onFailure { error ->
                _purchaseState.value = _purchaseState.value.copy(
                    isLoading = false,
                    errorMessage = error.userMessage(),
                )
            }
    }

    suspend fun cancelPendingOrder() = operationMutex.withLock {
        val session = requireSession() ?: return@withLock
        val tradeNo = sessionStore.pendingTradeNo ?: _purchaseState.value.pendingOrder?.tradeNo
        if (tradeNo.isNullOrBlank()) return@withLock
        _purchaseState.value = _purchaseState.value.copy(isLoading = true, errorMessage = null)
        runCatching { api.cancelOrder(session.authorization, tradeNo) }
            .onSuccess {
                sessionStore.pendingTradeNo = null
                loadPurchaseOptionsWithoutLock(session, "待支付订单已取消")
            }
            .onFailure { error ->
                _purchaseState.value = _purchaseState.value.copy(
                    isLoading = false,
                    errorMessage = error.userMessage(),
                )
            }
    }

    fun consumeCheckoutResult() {
        _purchaseState.value = _purchaseState.value.copy(checkoutResult = null)
    }

    private suspend fun loadPurchaseOptionsWithoutLock(
        session: AccountSession,
        message: String? = null,
    ) {
        runCatching {
            Triple(
                api.getPlans(session.authorization),
                api.getPaymentMethods(session.authorization),
                api.getPendingOrders(session.authorization).firstOrNull(),
            )
        }.onSuccess { (plans, methods, pendingOrder) ->
            sessionStore.pendingTradeNo = pendingOrder?.tradeNo
            _purchaseState.value = PurchaseUiState(
                plans = plans,
                paymentMethods = methods,
                pendingOrder = pendingOrder,
                statusMessage = message,
            )
        }.onFailure { error ->
            _purchaseState.value = _purchaseState.value.copy(
                isLoading = false,
                errorMessage = error.userMessage(),
                statusMessage = message,
            )
        }
    }

    private fun requireSession(): AccountSession? {
        val session = sessionStore.loadSession()
        if (session == null) {
            _purchaseState.value = PurchaseUiState(errorMessage = "请先登录账户")
        }
        return session
    }

    private suspend fun refreshSession(session: AccountSession) {
        runCatching { api.getSubscription(session.authorization) }
            .onSuccess { details ->
                // Authentication and entitlement are complete here. Render the
                // account before the profile download so subscription network
                // conditions never leave the login action spinning.
                _state.value = AccountUiState(
                    session = session,
                    details = details,
                    isLoading = false,
                )
                val syncResult = runCatching { profileSynchronizer.sync(details) }
                _state.value = _state.value.copy(
                    errorMessage = syncResult.exceptionOrNull()?.userMessage(),
                    profileSyncResult = syncResult.getOrNull(),
                )
            }
            .onFailure { error ->
                _state.value = AccountUiState(
                    session = session,
                    isLoading = false,
                    errorMessage = error.userMessage(),
                )
            }
    }

    private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: "请求失败，请稍后重试"

    companion object {
        private const val ORDER_PENDING = 0
        private const val ORDER_PROCESSING = 1
        private const val ORDER_CANCELLED = 2
        private const val ORDER_COMPLETED = 3
        private const val ORDER_DISCOUNTED = 4
    }
}

object AccountRepositoryProvider {
    @Volatile
    private var instance: AccountRepository? = null

    fun get(context: Context): AccountRepository = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(context: Context): AccountRepository {
        val sessionStore = AndroidAccountSessionStore(context)
        return AccountRepository(
            api = XBoardAccountApi(BuildConfig.ACCOUNT_API_BASE_URL),
            sessionStore = sessionStore,
            profileSynchronizer = AccountProfileSynchronizer(context, sessionStore),
        )
    }
}
