package io.github.colorosfeiniu.bridge

/** Orders known-name compatibility and structural DEX discovery without hiding either path. */
internal object TokenDecryptorTargetResolver {

    enum class Source(val logValue: String) {
        KNOWN_NAME("known-name"),
        DEX_SCAN("dex-scan"),
        KNOWN_NAME_UNCONFIRMED("known-name-unconfirmed"),
    }

    data class Resolution<T>(
        val target: T,
        val source: Source,
    )

    fun <T> resolve(
        knownCandidates: Iterable<T>,
        hasPrefixLoader: (T) -> Boolean,
        hasDecryptEntryPoint: (T) -> Boolean,
        locateByShape: () -> T?,
    ): Resolution<T>? {
        val legacyCandidates = knownCandidates.filter(hasPrefixLoader)
        legacyCandidates.firstOrNull(hasDecryptEntryPoint)?.let { confirmed ->
            return Resolution(confirmed, Source.KNOWN_NAME)
        }

        locateByShape()?.let { located ->
            return Resolution(located, Source.DEX_SCAN)
        }

        return legacyCandidates.firstOrNull()?.let { legacy ->
            Resolution(legacy, Source.KNOWN_NAME_UNCONFIRMED)
        }
    }
}
