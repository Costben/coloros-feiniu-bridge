package io.github.colorosfeiniu.bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional device-validation test. Supply `-Dgallery.dex.path=/path/to/classes17.dex` when
 * validating a legally obtained Gallery 16.40.22 APK; CI intentionally has no proprietary DEX.
 */
class RealGalleryDexValidationTest {

    @Test
    fun `locates qp80 in the supplied Gallery 16 40 22 dex`() {
        val path = System.getProperty(DEX_PATH_PROPERTY)
        assumeTrue(
            "Set -D$DEX_PATH_PROPERTY to a Gallery 16.40.22 classes17.dex path",
            !path.isNullOrBlank(),
        )

        val dex = File(requireNotNull(path))
        assertTrue("Supplied DEX does not exist: $dex", dex.isFile)
        assertEquals("com.oplus.aiunit.vision.qp80", TokenDecryptorLocator.locate(dex.readBytes()))
    }

    private companion object {
        const val DEX_PATH_PROPERTY = "gallery.dex.path"
    }
}
