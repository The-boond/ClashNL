package io.nekohasekai.sfa.account.model

data class AccountSession(
    val email: String,
    val authorization: String,
)

data class AccountPlan(
    val id: Long,
    val name: String,
    val transferLimitBytes: Long,
    val deviceLimit: Int?,
    val speedLimitMbps: Int?,
)

data class AccountDetails(
    val email: String,
    val plan: AccountPlan?,
    val uploadedBytes: Long,
    val downloadedBytes: Long,
    val transferLimitBytes: Long,
    val expiresAtEpochSeconds: Long,
    val nextResetAtEpochSeconds: Long,
    val subscribeUrl: String,
) {
    val usedBytes: Long
        get() = (uploadedBytes + downloadedBytes).coerceAtLeast(0L)

    fun isExpired(nowEpochSeconds: Long): Boolean = expiresAtEpochSeconds > 0L && expiresAtEpochSeconds <= nowEpochSeconds
}

data class AccountProfileSyncResult(
    val profileId: Long,
    val created: Boolean,
    val contentChanged: Boolean,
)

data class AccountUiState(
    val session: AccountSession? = null,
    val details: AccountDetails? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val profileSyncResult: AccountProfileSyncResult? = null,
) {
    val isSignedIn: Boolean
        get() = session != null
}
