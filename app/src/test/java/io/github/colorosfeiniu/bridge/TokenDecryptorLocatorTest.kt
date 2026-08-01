package io.github.colorosfeiniu.bridge

import io.github.colorosfeiniu.bridge.SyntheticDex.Clazz
import io.github.colorosfeiniu.bridge.SyntheticDex.Method
import io.github.colorosfeiniu.bridge.SyntheticDex.PROTO_NO_ARG
import io.github.colorosfeiniu.bridge.SyntheticDex.PROTO_TWO_STRINGS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenDecryptorLocatorTest {

    private val decoy = Clazz(
        descriptor = "Lcom/example/Decoy;",
        methods = listOf(Method("e", PROTO_NO_ARG, constString = "unrelated")),
    )

    private val impostor = Clazz(
        descriptor = "Lcom/example/Impostor;",
        methods = listOf(
            Method("e", PROTO_NO_ARG, constString = "unrelated"),
            Method("b", PROTO_TWO_STRINGS, constString = "unrelated"),
        ),
    )

    private val target = Clazz(
        descriptor = "Lcom/example/Target;",
        methods = listOf(
            Method("e", PROTO_NO_ARG, constString = "TokenDecryptor"),
            Method("b", PROTO_TWO_STRINGS, constString = "unrelated"),
        ),
    )

    @Test
    fun `locates the class carrying both decryptor methods and the log tag`() {
        val dex = SyntheticDex.build(listOf(decoy, impostor, target))

        assertEquals("com.example.Target", TokenDecryptorLocator.locate(dex))
    }

    @Test
    fun `ignores a class that only exposes the prefix loader`() {
        val dex = SyntheticDex.build(listOf(decoy, target))

        assertEquals("com.example.Target", TokenDecryptorLocator.locate(dex))
    }

    @Test
    fun `ignores a class with both methods but no log tag reference`() {
        val dex = SyntheticDex.build(listOf(decoy, impostor))

        assertNull(TokenDecryptorLocator.locate(dex))
    }

    @Test
    fun `returns null when the dex never mentions the log tag`() {
        val dex = SyntheticDex.build(listOf(decoy))

        assertNull(TokenDecryptorLocator.locate(dex))
    }

    @Test
    fun `returns null for bytes that are not a standard dex`() {
        assertNull(TokenDecryptorLocator.locate(ByteArray(512)))
        assertNull(TokenDecryptorLocator.locate("not a dex".toByteArray()))
    }

    @Test
    fun `exposes the string pool of a synthetic dex`() {
        val dex = DexFile.parse(SyntheticDex.build(listOf(target)))

        requireNotNull(dex)
        assertEquals("TokenDecryptor", dex.firstString { it == "TokenDecryptor" })
        assertNull(dex.firstString { it == "absent" })
    }
}
