package io.nekohasekai.sfa.compose.screen.account

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sfa.account.AccountRepositoryProvider
import io.nekohasekai.sfa.account.model.BillingPeriod
import kotlinx.coroutines.launch

class PlanPurchaseViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = AccountRepositoryProvider.get(application)
    val uiState = repository.purchaseState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { repository.loadPurchaseOptions() }
    }

    fun purchase(planId: Long, period: BillingPeriod, methodId: Long) {
        viewModelScope.launch { repository.purchase(planId, period, methodId) }
    }

    fun checkPendingOrder() {
        viewModelScope.launch { repository.checkPendingOrder() }
    }

    fun resumePendingCheckout(methodId: Long) {
        viewModelScope.launch { repository.resumePendingCheckout(methodId) }
    }

    fun cancelPendingOrder() {
        viewModelScope.launch { repository.cancelPendingOrder() }
    }

    fun consumeCheckoutResult() {
        repository.consumeCheckoutResult()
    }
}
