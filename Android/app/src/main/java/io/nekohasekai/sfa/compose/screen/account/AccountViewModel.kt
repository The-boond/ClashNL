package io.nekohasekai.sfa.compose.screen.account

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sfa.account.AccountRepositoryProvider
import kotlinx.coroutines.launch

class AccountViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = AccountRepositoryProvider.get(application)
    val uiState = repository.state

    init {
        if (uiState.value.session != null && uiState.value.details == null) {
            refresh()
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch { repository.login(email, password) }
    }

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    fun logout() {
        repository.logout()
    }
}
