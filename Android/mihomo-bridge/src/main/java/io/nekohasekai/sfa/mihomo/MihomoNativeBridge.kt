package io.nekohasekai.sfa.mihomo

import android.content.Context
import android.os.Build
import java.io.File

/** Internal JNI boundary. Only MihomoController is allowed to call this object. */
object MihomoNativeBridge {
    interface TunCallback {
        fun protectSocket(fd: Int): Boolean

        fun querySocketUid(protocol: Int, source: String, target: String): Int
    }

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            System.loadLibrary("mihomo_bridge")
            val home = File(context.filesDir, "mihomo").apply { mkdirs() }
            nativeInit(home.absolutePath, Build.VERSION.SDK_INT)
            initialized = true
        }
    }

    fun validate(content: String, controller: String, secret: String) {
        nativeValidate(content, controller, secret)?.let(::error)
    }

    fun load(content: String, controller: String, secret: String) {
        nativeLoad(content, controller, secret)?.let(::error)
    }

    fun startTun(fd: Int, stack: String, gateway: String, dns: String, callback: TunCallback) {
        nativeStartTun(fd, stack, gateway, dns, callback)?.let(::error)
    }

    fun stopTun() = nativeStopTun()

    fun stopCore() = nativeStopCore()

    private external fun nativeInit(home: String, sdkVersion: Int)

    private external fun nativeValidate(content: String, controller: String, secret: String): String?

    private external fun nativeLoad(content: String, controller: String, secret: String): String?

    private external fun nativeStartTun(fd: Int, stack: String, gateway: String, dns: String, callback: TunCallback): String?

    private external fun nativeStopTun()

    private external fun nativeStopCore()
}
