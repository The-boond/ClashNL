package io.nekohasekai.sfa.account

import android.content.Context
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.account.model.AccountSession
import io.nekohasekai.sfa.account.model.AccountUiState
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
    }

    private suspend fun refreshSession(session: AccountSession) {
        runCatching { api.getSubscription(session.authorization) }
            .onSuccess { details ->
                val syncResult = runCatching { profileSynchronizer.sync(details) }
                _state.value = AccountUiState(
                    session = session,
                    details = details,
                    isLoading = false,
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
