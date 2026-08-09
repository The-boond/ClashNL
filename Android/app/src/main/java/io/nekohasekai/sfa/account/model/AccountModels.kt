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

enum class BillingPeriod(
    val apiValue: String,
) {
    MONTH("month_price"),
    QUARTER("quarter_price"),
    HALF_YEAR("half_year_price"),
    YEAR("year_price"),
    TWO_YEARS("two_year_price"),
    THREE_YEARS("three_year_price"),
    ONETIME("onetime_price"),
    RESET_TRAFFIC("reset_price"),
    ;

    companion object {
        fun fromApiValue(value: String): BillingPeriod? = entries.firstOrNull { it.apiValue == value }
    }
}

data class PlanPrice(
    val period: BillingPeriod,
    val priceCents: Long,
)

data class PlanOffer(
    val id: Long,
    val name: String,
    val content: String,
    val transferLimitBytes: Long,
    val deviceLimit: Int?,
    val speedLimitMbps: Int?,
    val soldOut: Boolean,
    val purchaseAvailable: Boolean,
    val renewAvailable: Boolean,
    val prices: List<PlanPrice>,
)

data class PaymentMethod(
    val id: Long,
    val name: String,
    val provider: String,
    val handlingFeeFixedCents: Long,
    val handlingFeePercent: Double,
)

data class AccountOrder(
    val tradeNo: String,
    val planId: Long,
    val planName: String,
    val period: BillingPeriod?,
    val totalAmountCents: Long,
    val handlingAmountCents: Long,
    val status: Int,
    val createdAtEpochSeconds: Long,
)

data class CheckoutResult(
    val type: Int,
    val data: String?,
) {
    val completedWithoutGateway: Boolean
        get() = type == TYPE_COMPLETED

    val isQrCode: Boolean
        get() = type == TYPE_QR_CODE && !data.isNullOrBlank()

    val isUrl: Boolean
        get() = type == TYPE_URL && !data.isNullOrBlank()

    companion object {
        const val TYPE_COMPLETED = -1
        const val TYPE_QR_CODE = 0
        const val TYPE_URL = 1
    }
}

data class PurchaseUiState(
    val plans: List<PlanOffer> = emptyList(),
    val paymentMethods: List<PaymentMethod> = emptyList(),
    val pendingOrder: AccountOrder? = null,
    val checkoutResult: CheckoutResult? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
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
