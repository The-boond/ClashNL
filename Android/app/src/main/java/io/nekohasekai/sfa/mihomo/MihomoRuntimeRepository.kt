package io.nekohasekai.sfa.mihomo

import android.content.Context

/** Process-wide owner of the single native Mihomo process. */
object MihomoRuntimeRepository {
    @Volatile
    private var instance: MihomoController? = null

    fun controller(context: Context): MihomoController = instance ?: synchronized(this) {
        instance ?: AndroidMihomoController.create(context.applicationContext).also { instance = it }
    }
}
