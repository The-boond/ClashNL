package io.nekohasekai.sfa.repository

import org.junit.Assert.assertThrows
import org.junit.Test

class MihomoRemoteProfileRepositoryTest {
    @Test
    fun acceptsTheUrlSnapshotUsedForTheFetch() {
        MihomoRemoteProfileRepository.requireUnchangedRemoteUrl(
            current = "https://example.com/current",
            expected = "https://example.com/current",
        )
    }

    @Test
    fun rejectsAStaleFetchAfterTheUrlChanges() {
        assertThrows(IllegalStateException::class.java) {
            MihomoRemoteProfileRepository.requireUnchangedRemoteUrl(
                current = "https://example.com/new",
                expected = "https://example.com/old",
            )
        }
    }
}
