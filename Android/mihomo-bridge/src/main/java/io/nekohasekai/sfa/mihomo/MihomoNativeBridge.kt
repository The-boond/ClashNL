package io.nekohasekai.sfa.mihomo

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

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
            installBundledGeoDatabase(context, home)
            nativeInit(home.absolutePath, Build.VERSION.SDK_INT)
            initialized = true
        }
    }

    fun validate(content: String, controller: String, secret: String) {
        nativeValidate(content, controller, secret)?.let(::error)
    }

    fun describeProxyGroups(content: String): List<MihomoNativeProxyGroup> {
        val response = JSONObject(nativeDescribeProxyGroups(content))
        response.optString("error").takeIf { it.isNotBlank() }?.let(::error)
        val groups = response.optJSONArray("groups") ?: return emptyList()
        return List(groups.length()) { index ->
            val group = groups.getJSONObject(index)
            val proxies = group.optJSONArray("proxies")
            MihomoNativeProxyGroup(
                name = group.getString("name"),
                type = group.optString("type"),
                selected = group.optString("selected"),
                selectable = group.optString("type").equals("select", ignoreCase = true),
                proxies = List(proxies?.length() ?: 0) { proxyIndex -> MihomoNativeProxy(proxies!!.getString(proxyIndex)) },
            )
        }
    }

    fun load(content: String, controller: String, secret: String, httpProxyPort: Int = 0) {
        require(httpProxyPort in 0..65_535) { "Invalid app-owned HTTP proxy port: $httpProxyPort" }
        nativeLoad(content, controller, secret, httpProxyPort)?.let(::error)
    }

    fun setMode(mode: String) {
        nativeSetMode(mode)?.let(::error)
    }

    fun prepareTun(callback: TunCallback) = nativePrepareTun(callback)

    /** Transfers ownership of [fd] to native code, including on failure. */
    fun startTun(fd: Int, stack: String, gateway: String, dns: String) {
        nativeStartTun(fd, stack, gateway, dns)?.let(::error)
    }

    fun stopTun() = nativeStopTun()

    fun stopCore() = nativeStopCore()

    private fun installBundledGeoDatabase(context: Context, home: File) {
        val target = File(home, BUNDLED_GEO_DATABASE_NAME)
        if (target.isFile && sha256(target) == BUNDLED_GEO_DATABASE_SHA256) return

        val atomicTarget = AtomicFile(target)
        var output: FileOutputStream? = null
        try {
            output = atomicTarget.startWrite()
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(BUNDLED_GEO_DATABASE_ASSET).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
            }
            output.fd.sync()
            check(digest.digest().toHex() == BUNDLED_GEO_DATABASE_SHA256) {
                "Bundled Mihomo GeoIP database failed its integrity check"
            }
            atomicTarget.finishWrite(output)
            output = null
        } catch (exception: Exception) {
            output?.let(atomicTarget::failWrite)
            throw IllegalStateException("Unable to install the bundled Mihomo GeoIP database", exception)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private external fun nativeInit(home: String, sdkVersion: Int)

    private external fun nativeValidate(content: String, controller: String, secret: String): String?

    private external fun nativeDescribeProxyGroups(content: String): String

    private external fun nativeLoad(content: String, controller: String, secret: String, httpProxyPort: Int): String?

    private external fun nativeSetMode(mode: String): String?

    private external fun nativePrepareTun(callback: TunCallback)

    private external fun nativeStartTun(fd: Int, stack: String, gateway: String, dns: String): String?

    private external fun nativeStopTun()

    private external fun nativeStopCore()

    private const val BUNDLED_GEO_DATABASE_ASSET = "mihomo/country-lite.mmdb"
    private const val BUNDLED_GEO_DATABASE_NAME = "Country.mmdb"
    private const val BUNDLED_GEO_DATABASE_SHA256 =
        "248b91c2c9cf46c9ae75b27ab8bee7b44d3f745b6a1372da75b2120494284f7b"
}

data class MihomoNativeProxyGroup(
    val name: String,
    val type: String,
    val selected: String,
    val selectable: Boolean,
    val proxies: List<MihomoNativeProxy>,
)

data class MihomoNativeProxy(
    val name: String,
)
