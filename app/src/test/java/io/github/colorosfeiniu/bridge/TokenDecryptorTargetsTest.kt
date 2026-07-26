package io.github.colorosfeiniu.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenDecryptorTargetsTest {
    @Test
    fun `supports known Gallery token decryptor classes in compatibility order`() {
        assertEquals(
            listOf(
                "com.oplus.aiunit.vision.erq",
                "com.oplus.aiunit.vision.in80",
                "com.oplus.aiunit.vision.op80",
            ),
            TokenDecryptorTargets.classNames,
        )
    }
}
