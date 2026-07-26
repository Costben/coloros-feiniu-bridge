package io.github.colorosfeiniu.bridge

/**
 * Finds Gallery's token decryptor by shape rather than by obfuscated name.
 *
 * A class qualifies only when all three hold, which across Gallery 16.40.22 matches exactly one
 * class in the whole APK:
 *
 * 1. the DEX declares the `TokenDecryptor` log tag at all,
 * 2. the class declares both the prefix loader `e()` and the decrypt entry point `b(String, String)`,
 * 3. the class body actually loads that log tag, ruling out unrelated classes that happen to share
 *    the two obfuscated member names.
 */
internal object TokenDecryptorLocator {

    /** Class name to hook, or null when [dex] does not carry the decryptor. */
    fun locate(dex: ByteArray): String? {
        val reader = DexFile.parse(dex) ?: return null
        val tagIndex = reader.indexOfString(TokenDecryptorTargets.LOG_TAG)
        if (tagIndex < 0) return null

        return reader.firstClass { clazz ->
            clazz.declaresMethod(
                TokenDecryptorTargets.PREFIX_METHOD,
                TokenDecryptorTargets.PREFIX_METHOD_DESCRIPTOR,
            ) &&
                clazz.declaresMethod(
                    TokenDecryptorTargets.DECRYPT_METHOD,
                    TokenDecryptorTargets.DECRYPT_METHOD_DESCRIPTOR,
                ) &&
                clazz.referencesString(tagIndex)
        }?.className
    }
}
