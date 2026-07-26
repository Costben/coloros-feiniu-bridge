package io.github.colorosfeiniu.bridge

/**
 * Describes the Gallery token decryptor the bridge attaches to.
 *
 * OPPO reshuffles the obfuscated class names on nearly every Gallery release, so [classNames] is
 * only the fast path. When none of them resolve, [TokenDecryptorLocator] finds the class by shape
 * instead, using the members and log tag captured here.
 */
internal object TokenDecryptorTargets {
    /**
     * Known token decryptor classes, oldest first:
     * `erq` and `in80` on early ColorOS 16 builds, `op80` on Gallery 16.40.13, `qp80` on 16.40.22.
     */
    val classNames = listOf(
        "com.oplus.aiunit.vision.erq",
        "com.oplus.aiunit.vision.in80",
        "com.oplus.aiunit.vision.op80",
        "com.oplus.aiunit.vision.qp80",
    )

    /** Prefix loader the bridge hooks — `getOrLoadPrefix()` before obfuscation. */
    const val PREFIX_METHOD = "e"
    const val PREFIX_METHOD_DESCRIPTOR = "()Ljava/lang/String;"

    /** Token decrypt entry point, kept as a structural marker only. Never hooked. */
    const val DECRYPT_METHOD = "b"
    const val DECRYPT_METHOD_DESCRIPTOR =
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"

    /** Log tag the decryptor passes to Gallery's logger; survives obfuscation as a literal. */
    const val LOG_TAG = "TokenDecryptor"
}
