package io.nekohasekai.sfa.compose.screen.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.account.model.AccountOrder
import io.nekohasekai.sfa.account.model.BillingPeriod
import io.nekohasekai.sfa.account.model.CheckoutResult
import io.nekohasekai.sfa.account.model.PaymentMethod
import io.nekohasekai.sfa.account.model.PlanOffer
import io.nekohasekai.sfa.account.model.PlanPrice
import io.nekohasekai.sfa.compose.component.qr.QRCodeDialog
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.compose.util.QRCodeGenerator
import io.nekohasekai.sfa.ktx.launchCustomTab
import java.net.URI
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlanPurchaseScreen(
    navController: NavController,
    viewModel: PlanPurchaseViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedPlanId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedPlan = state.plans.firstOrNull { it.id == selectedPlanId }

    BackHandler(enabled = selectedPlan != null) { selectedPlanId = null }

    OverrideTopBar {
        TopAppBar(
            title = {
                Text(
                    stringResource(
                        if (selectedPlan == null) {
                            R.string.account_plans_title
                        } else {
                            R.string.account_plan_details
                        },
                    ),
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = {
                        if (selectedPlan == null) {
                            navController.navigateUp()
                        } else {
                            selectedPlanId = null
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                    )
                }
            },
        )
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var purchaseCandidate by remember { mutableStateOf<Pair<PlanOffer, PlanPrice>?>(null) }
    var selectedMethodId by remember { mutableStateOf<Long?>(null) }
    var showPendingCheckoutDialog by remember { mutableStateOf(false) }
    var qrContent by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.plans) {
        if (selectedPlanId != null && state.plans.none { it.id == selectedPlanId }) {
            selectedPlanId = null
        }
    }

    DisposableEffect(lifecycleOwner, state.pendingOrder?.tradeNo) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && state.pendingOrder != null) {
                viewModel.checkPendingOrder()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.checkoutResult) {
        val checkout = state.checkoutResult ?: return@LaunchedEffect
        when {
            checkout.isUrl -> {
                val url = checkout.data.orEmpty()
                if (isSecureCheckoutUrl(url)) {
                    context.launchCustomTab(url)
                } else {
                    localError = context.getString(R.string.account_checkout_invalid_url)
                }
            }

            checkout.isQrCode -> qrContent = checkout.data
            checkout.type != CheckoutResult.TYPE_COMPLETED -> {
                localError = context.getString(R.string.account_checkout_unknown_type, checkout.type)
            }
        }
        viewModel.consumeCheckoutResult()
    }

    qrContent?.let { content ->
        QRCodeDialog(
            bitmap = QRCodeGenerator.rememberBitmap(content),
            onDismiss = { qrContent = null },
        )
    }

    purchaseCandidate?.let { (plan, price) ->
        PurchaseConfirmationDialog(
            plan = plan,
            price = price,
            paymentMethods = state.paymentMethods,
            selectedMethodId = selectedMethodId,
            onMethodSelected = { selectedMethodId = it },
            onDismiss = { purchaseCandidate = null },
            onConfirm = {
                val methodId = selectedMethodId ?: state.paymentMethods.firstOrNull()?.id
                if (methodId != null) {
                    purchaseCandidate = null
                    viewModel.purchase(plan.id, price.period, methodId)
                }
            },
        )
    }

    if (showPendingCheckoutDialog) {
        PaymentMethodDialog(
            paymentMethods = state.paymentMethods,
            selectedMethodId = selectedMethodId,
            onMethodSelected = { selectedMethodId = it },
            onDismiss = { showPendingCheckoutDialog = false },
            onConfirm = {
                selectedMethodId?.let(viewModel::resumePendingCheckout)
                showPendingCheckoutDialog = false
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }

        if (state.isLoading && state.plans.isEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        state.pendingOrder?.let { order ->
            item {
                PendingOrderCard(
                    order = order,
                    isLoading = state.isLoading,
                    canContinue = state.paymentMethods.isNotEmpty(),
                    onContinue = {
                        selectedMethodId = state.paymentMethods.firstOrNull()?.id
                        showPendingCheckoutDialog = true
                    },
                    onCheck = viewModel::checkPendingOrder,
                    onCancel = viewModel::cancelPendingOrder,
                )
            }
        }

        state.statusMessage?.let { message ->
            item { MessageCard(message, isError = false) }
        }
        (localError ?: state.errorMessage)?.let { message ->
            item { MessageCard(message, isError = true) }
        }

        if (!state.isLoading && state.plans.isEmpty() && state.errorMessage != null) {
            item {
                OutlinedButton(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.account_retry_action))
                }
            }
        }

        if (selectedPlan == null && state.plans.isNotEmpty()) {
            item {
                PlanSelector(
                    plans = state.plans,
                    onPlanSelected = { selectedPlanId = it },
                )
            }
        }

        selectedPlan?.let { plan ->
            item(key = plan.id) {
                PlanOfferCard(
                    plan = plan,
                    enabled = state.pendingOrder == null && !state.isLoading && state.paymentMethods.isNotEmpty(),
                    onPriceSelected = { price ->
                        selectedMethodId = state.paymentMethods.firstOrNull()?.id
                        purchaseCandidate = plan to price
                    },
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanSelector(
    plans: List<PlanOffer>,
    onPlanSelected: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.account_choose_plan),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 2,
        ) {
            plans.forEach { plan ->
                val featuredPrice = plan.prices.firstOrNull { it.period == BillingPeriod.MONTH }
                    ?: plan.prices.firstOrNull()
                Card(
                    onClick = { onPlanSelected(plan.id) },
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        featuredPrice?.let { price ->
                            Text(
                                text = stringResource(
                                    R.string.account_period_price,
                                    billingPeriodLabel(price.period),
                                    formatMoney(price.priceCents),
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = plan.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.heightIn(min = 48.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Devices, contentDescription = null)
                            Text(
                                text = plan.deviceLimit?.let {
                                    stringResource(R.string.account_device_value, it)
                                } ?: stringResource(R.string.account_device_unlimited),
                                modifier = Modifier.padding(start = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (plans.size % 2 != 0) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanOfferCard(
    plan: PlanOffer,
    enabled: Boolean,
    onPriceSelected: (PlanPrice) -> Unit,
) {
    val description = remember(plan.content) {
        HtmlCompat.fromHtml(plan.content, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(plan.name, style = MaterialTheme.typography.titleLarge)
            if (description.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Devices, contentDescription = null)
                Text(
                    text = plan.deviceLimit?.let { stringResource(R.string.account_device_value, it) }
                        ?: stringResource(R.string.account_device_unlimited),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            if (plan.soldOut || !plan.purchaseAvailable || plan.prices.isEmpty()) {
                Text(
                    text = if (plan.soldOut) {
                        stringResource(R.string.account_plan_sold_out)
                    } else {
                        stringResource(R.string.account_plan_unavailable)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    plan.prices.forEach { price ->
                        AssistChip(
                            onClick = { onPriceSelected(price) },
                            enabled = enabled,
                            label = {
                                Text(
                                    stringResource(
                                        R.string.account_period_price,
                                        billingPeriodLabel(price.period),
                                        formatMoney(price.priceCents),
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PurchaseConfirmationDialog(
    plan: PlanOffer,
    price: PlanPrice,
    paymentMethods: List<PaymentMethod>,
    selectedMethodId: Long?,
    onMethodSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_confirm_purchase)) },
        text = {
            Column {
                Text(plan.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.account_period_price,
                        billingPeriodLabel(price.period),
                        formatMoney(price.priceCents),
                    ),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.account_payment_method), style = MaterialTheme.typography.labelLarge)
                paymentMethods.forEach { method ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedMethodId == method.id,
                                onClick = { onMethodSelected(method.id) },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedMethodId == method.id,
                            onClick = { onMethodSelected(method.id) },
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(method.name)
                            if (method.handlingFeeFixedCents > 0 || method.handlingFeePercent > 0) {
                                Text(
                                    stringResource(
                                        R.string.account_payment_fee,
                                        formatMoney(method.handlingFeeFixedCents),
                                        method.handlingFeePercent,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.account_purchase_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = selectedMethodId != null) {
                Text(stringResource(R.string.account_create_and_pay))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun PendingOrderCard(
    order: AccountOrder,
    isLoading: Boolean,
    canContinue: Boolean,
    onContinue: () -> Unit,
    onCheck: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(stringResource(R.string.account_pending_order), style = MaterialTheme.typography.titleMedium)
            Text(order.planName.ifBlank { stringResource(R.string.account_plan) })
            order.period?.let {
                Text(stringResource(R.string.account_period_price, billingPeriodLabel(it), formatMoney(order.totalAmountCents)))
            }
            Text(
                stringResource(R.string.account_trade_no, order.tradeNo),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onContinue,
                enabled = !isLoading && canContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.account_continue_payment))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCheck, enabled = !isLoading) {
                    Text(stringResource(R.string.account_check_payment))
                }
                OutlinedButton(onClick = onCancel, enabled = !isLoading) {
                    Text(stringResource(R.string.account_cancel_order))
                }
            }
        }
    }
}

@Composable
private fun PaymentMethodDialog(
    paymentMethods: List<PaymentMethod>,
    selectedMethodId: Long?,
    onMethodSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_payment_method)) },
        text = {
            Column {
                paymentMethods.forEach { method ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedMethodId == method.id,
                                onClick = { onMethodSelected(method.id) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedMethodId == method.id,
                            onClick = { onMethodSelected(method.id) },
                        )
                        Text(method.name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = selectedMethodId != null) {
                Text(stringResource(R.string.account_open_cashier))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun MessageCard(message: String, isError: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Text(message, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun billingPeriodLabel(period: BillingPeriod): String = stringResource(
    when (period) {
        BillingPeriod.MONTH -> R.string.account_period_month
        BillingPeriod.QUARTER -> R.string.account_period_quarter
        BillingPeriod.HALF_YEAR -> R.string.account_period_half_year
        BillingPeriod.YEAR -> R.string.account_period_year
        BillingPeriod.TWO_YEARS -> R.string.account_period_two_years
        BillingPeriod.THREE_YEARS -> R.string.account_period_three_years
        BillingPeriod.ONETIME -> R.string.account_period_onetime
        BillingPeriod.RESET_TRAFFIC -> R.string.account_period_reset
    },
)

private fun formatMoney(cents: Long): String = NumberFormat.getCurrencyInstance(Locale.CHINA).format(cents / 100.0)

private fun isSecureCheckoutUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)
