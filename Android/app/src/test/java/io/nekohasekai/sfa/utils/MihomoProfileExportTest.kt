package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileCore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MihomoProfileExportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readsRawMihomoYamlWithoutReencoding() {
        val bytes = "proxies:\r\n  - name: 节点\r\n".toByteArray()
        val file = temporaryFolder.newFile("profile.yaml").apply { writeBytes(bytes) }
        val profile = Profile().apply { typed.path = file.path }

        assertArrayEquals(bytes, MihomoProfileExport.read(profile))
    }

    @Test
    fun refusesToMislabelLegacyJsonAsYaml() {
        val file = temporaryFolder.newFile("profile.json").apply { writeText("{\"outbounds\":[]}") }
        val profile =
            Profile().apply {
                typed.core = ProfileCore.SingBox
                typed.path = file.path
            }

        assertThrows(IllegalArgumentException::class.java) {
            MihomoProfileExport.read(profile)
        }
    }

    @Test
    fun producesSafeYamlFileNames() {
        assertEquals("A_B_C.yaml", MihomoProfileExport.fileName(" A/B:C. "))
        assertEquals("profile.yaml", MihomoProfileExport.fileName(" .. "))
    }

    @Test
    fun producesEncodedClashNlRemoteImportLink() {
        assertEquals(
            "clashnl://import-remote-profile" +
                "?url=https%3A%2F%2Fexample.com%2Fsub%3Fa%3D1%26b%3D%E4%B8%AD" +
                "&name=A%20B%26%E4%B8%AD",
            MihomoProfileExport.remoteImportLink(
                profileName = "A B&中",
                remoteURL = "https://example.com/sub?a=1&b=中",
            ),
        )
    }
}
