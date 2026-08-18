package io.nekohasekai.sfa.bg

import android.app.KeyguardManager
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.runtime.ProfileRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@RequiresApi(24)
class TileService :
    TileService(),
    ServiceConnection.Callback {
    private val connection = ServiceConnection(this, this)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onServiceStatusChanged(status: Status) {
        qsTile?.apply {
            state =
                when (status) {
                    Status.Started -> Tile.STATE_ACTIVE
                    Status.Stopped -> Tile.STATE_INACTIVE
                    else -> Tile.STATE_UNAVAILABLE
                }
            updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        connection.connect()
    }

    override fun onStopListening() {
        connection.disconnect()
        super.onStopListening()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            unlockAndRun {
                toggleService()
            }
        } else {
            toggleService()
        }
    }

    private fun toggleService() {
        serviceScope.launch {
            when (connection.status) {
                Status.Stopped -> ProfileRuntime.startSelected(this@TileService)
                Status.Started -> ProfileRuntime.stopActive(this@TileService)
                else -> {}
            }
        }
    }
}
