package io.github.colorosfeiniu.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenDecryptorTargetResolverTest {
    private data class Candidate(
        val className: String,
        val hasPrefixLoader: Boolean,
        val hasDecryptEntryPoint: Boolean,
    )

    @Test
    fun `uses a confirmed known target without scanning dex`() {
        val target = Candidate("qp80", hasPrefixLoader = true, hasDecryptEntryPoint = true)
        var dexScans = 0

        val result = resolve(listOf(target)) {
            dexScans++
            null
        }

        assertEquals(target, result?.target)
        assertEquals(TokenDecryptorTargetResolver.Source.KNOWN_NAME, result?.source)
        assertEquals(0, dexScans)
    }

    @Test
    fun `scans dex before using an unconfirmed known target`() {
        val stale = Candidate("op80", hasPrefixLoader = true, hasDecryptEntryPoint = false)
        val target = Candidate("future90", hasPrefixLoader = true, hasDecryptEntryPoint = true)
        var dexScans = 0

        val result = resolve(listOf(stale)) {
            dexScans++
            target
        }

        assertEquals(target, result?.target)
        assertEquals(TokenDecryptorTargetResolver.Source.DEX_SCAN, result?.source)
        assertEquals(1, dexScans)
    }

    @Test
    fun `uses an unconfirmed known target only after dex scanning fails`() {
        val legacy = Candidate("erq", hasPrefixLoader = true, hasDecryptEntryPoint = false)
        var dexScans = 0

        val result = resolve(listOf(legacy)) {
            dexScans++
            null
        }

        assertEquals(legacy, result?.target)
        assertEquals(TokenDecryptorTargetResolver.Source.KNOWN_NAME_UNCONFIRMED, result?.source)
        assertEquals(1, dexScans)
    }

    private fun resolve(
        candidates: List<Candidate>,
        locateByShape: () -> Candidate?,
    ) = TokenDecryptorTargetResolver.resolve(
        knownCandidates = candidates,
        hasPrefixLoader = Candidate::hasPrefixLoader,
        hasDecryptEntryPoint = Candidate::hasDecryptEntryPoint,
        locateByShape = locateByShape,
    )
}
