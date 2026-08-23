package io.nekohasekai.sfa.ktx

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.utils.MihomoProfileExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.appcompat.R as AppCompatR

suspend fun Context.shareProfile(profile: Profile) {
    val configDirectory = File(cacheDir, "share").also { it.mkdirs() }
    val profileFile = File(configDirectory, MihomoProfileExport.fileName(profile.name))
    profileFile.writeBytes(MihomoProfileExport.read(profile))
    val uri = FileProvider.getUriForFile(this, "$packageName.cache", profileFile)
    withContext(Dispatchers.Main) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(MihomoProfileExport.CONTENT_TYPE)
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_STREAM, uri),
                getString(AppCompatR.string.abc_shareactionprovider_share_with),
            ),
        )
    }
}
