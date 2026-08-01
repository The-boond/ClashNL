package io.nekohasekai.sfa.compose.screen.configuration

import android.content.Context
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.ProfileManager

internal object ProfileNameGenerator {
    suspend fun nextDefaultSubscriptionName(context: Context): String {
        val existingNames = ProfileManager.list().mapTo(mutableSetOf()) { it.name }
        val index =
            nextDefaultSubscriptionIndex(existingNames) { candidate ->
                context.getString(R.string.default_subscription_name, candidate)
            }
        return context.getString(R.string.default_subscription_name, index)
    }
}

internal fun nextDefaultSubscriptionIndex(
    existingNames: Set<String>,
    formatName: (Int) -> String,
): Int {
    var index = 1
    while (formatName(index) in existingNames) {
        index += 1
    }
    return index
}
