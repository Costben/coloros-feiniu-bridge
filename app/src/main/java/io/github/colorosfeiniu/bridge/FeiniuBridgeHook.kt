package io.github.colorosfeiniu.bridge

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.zip.ZipFile

class FeiniuBridgeHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        runCatching {
            val methods = TOKEN_DECRYPTOR_CLASSES
                .mapNotNull { className ->
                    runCatching {
                        XposedHelpers.findClass(className, lpparam.classLoader)
                    }.getOrNull()
                }
                .flatMap { tokenDecryptor ->
                    tokenDecryptor.declaredMethods.filter { method ->
                        method.name == PREFIX_METHOD &&
                            method.returnType == String::class.java &&
                            method.parameterTypes.isEmpty()
                    }
                }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, PrefixFallbackHook(lpparam))
            }

            if (methods.isEmpty()) {
                log("prefix fallback unavailable for ${lpparam.packageName}")
            } else {
                log("installed for ${lpparam.packageName}")
            }
        }.onFailure { error ->
            log("install failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private class PrefixFallbackHook(
        private val lpparam: XC_LoadPackage.LoadPackageParam,
    ) : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.hasThrowable()) return
            if (!param.result.isNullOrBlankString()) return

            val resolved = PrefixResolver.resolve(lpparam)
            if (resolved == null) {
                log("prefix fallback unavailable")
                return
            }

            param.result = resolved.value
            if (shouldLogFallback()) {
                log("prefix fallback supplied source=${resolved.source} len=${resolved.value.length}")
            }
        }

        private fun shouldLogFallback(): Boolean {
            return !fallbackLogged && synchronized(PrefixFallbackHook::class.java) {
                if (fallbackLogged) {
                    false
                } else {
                    fallbackLogged = true
                    true
                }
            }
        }

        companion object {
            @Volatile
            private var fallbackLogged = false
        }
    }

    private object PrefixResolver {
        @Volatile
        private var cachedPrefix: ResolvedPrefix? = null

        fun resolve(lpparam: XC_LoadPackage.LoadPackageParam): ResolvedPrefix? {
            cachedPrefix?.let { return it }

            val resolved = findFromApkStrings(lpparam)
                ?: ResolvedPrefix(KNOWN_PREFIX, "builtin")

            cachedPrefix = resolved
            return resolved
        }

        private fun findFromApkStrings(lpparam: XC_LoadPackage.LoadPackageParam): ResolvedPrefix? {
            val sourcePaths = buildList {
                add(lpparam.appInfo?.sourceDir)
                lpparam.appInfo?.splitSourceDirs?.let(::addAll)
            }.filterNotNull()

            if (sourcePaths.isEmpty()) {
                log("apk scan skipped: no source paths")
                return null
            }

            for (sourcePath in sourcePaths) {
                val prefix = findFromZip(File(sourcePath))
                if (!prefix.isNullOrBlank()) return ResolvedPrefix(prefix, "apk-dex")
            }
            return null
        }

        private fun findFromZip(apk: File): String? {
            if (!apk.isFile) {
                log("apk scan skipped: missing ${apk.path}")
                return null
            }

            return try {
                ZipFile(apk).use { zipFile ->
                    val dexEntries = zipFile.entries().asSequence()
                        .filter { it.name.endsWith(".dex") }
                        .toList()

                    if (dexEntries.isEmpty()) {
                        log("apk scan skipped: no dex entries in ${apk.name}")
                        return null
                    }

                    for (entry in dexEntries) {
                        val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
                        val prefix = findFromDexStrings(bytes, entry.name)
                        if (!prefix.isNullOrBlank()) return prefix
                    }
                }
                log("apk scan did not find Feiniu prefix in ${apk.name}")
                null
            } catch (error: Throwable) {
                log("apk scan failed for ${apk.name}: ${error.javaClass.simpleName}: ${error.message}")
                null
            }
        }

        private fun findFromDexStrings(dex: ByteArray, entryName: String): String? {
            if (dex.size < DEX_HEADER_SIZE) {
                log("dex scan skipped: $entryName is too small")
                return null
            }
            if (!dex.startsWithDexMagic()) {
                log("dex scan skipped: $entryName is not standard dex")
                return null
            }

            val stringIdsSize = dex.readUIntLe(DEX_STRING_IDS_SIZE_OFFSET)
            val stringIdsOffset = dex.readUIntLe(DEX_STRING_IDS_OFFSET_OFFSET)
            if (stringIdsSize <= 0 || stringIdsOffset <= 0) {
                log("dex scan skipped: $entryName has invalid string table")
                return null
            }

            for (index in 0 until stringIdsSize) {
                val stringIdOffset = stringIdsOffset + index * DEX_STRING_ID_SIZE
                if (stringIdOffset + DEX_STRING_ID_SIZE > dex.size) {
                    log("dex scan stopped: $entryName string id table out of bounds")
                    return null
                }

                val stringDataOffset = dex.readUIntLe(stringIdOffset)
                val stringValue = dex.readDexString(stringDataOffset) ?: continue
                if (stringValue.isFeiniuPrefix()) return stringValue
            }

            return null
        }

        private fun ByteArray.startsWithDexMagic(): Boolean {
            return size >= 4 && this[0] == 'd'.code.toByte() && this[1] == 'e'.code.toByte() &&
                this[2] == 'x'.code.toByte() && this[3] == '\n'.code.toByte()
        }

        private fun ByteArray.readUIntLe(offset: Int): Int {
            if (offset < 0 || offset + 4 > size) return -1
            return (this[offset].toInt() and 0xff) or
                ((this[offset + 1].toInt() and 0xff) shl 8) or
                ((this[offset + 2].toInt() and 0xff) shl 16) or
                ((this[offset + 3].toInt() and 0xff) shl 24)
        }

        private fun ByteArray.readDexString(offset: Int): String? {
            if (offset < 0 || offset >= size) return null

            var cursor = offset
            while (cursor < size) {
                val value = this[cursor].toInt() and 0xff
                cursor++
                if ((value and 0x80) == 0) break
            }
            if (cursor >= size) return null

            val start = cursor
            while (cursor < size && this[cursor].toInt() != 0) cursor++
            if (cursor >= size || cursor == start) return null

            return runCatching { String(this, start, cursor - start, Charsets.UTF_8) }.getOrNull()
        }

        private fun String.isFeiniuPrefix(): Boolean {
            return length in 16..80 && PREFIX_REGEX.matches(this)
        }

    }

    private data class ResolvedPrefix(
        val value: String,
        val source: String,
    )

    companion object {
        private const val TARGET_PACKAGE = "com.coloros.gallery3d"
        private val TOKEN_DECRYPTOR_CLASSES = arrayOf(
            "com.oplus.aiunit.vision.erq",
            "com.oplus.aiunit.vision.in80",
        )
        private const val PREFIX_METHOD = "e"
        private const val KNOWN_PREFIX = "tRiM@2025#GwToken!sEcReT*kEy&vALu"
        private const val DEX_HEADER_SIZE = 0x70
        private const val DEX_STRING_IDS_SIZE_OFFSET = 0x38
        private const val DEX_STRING_IDS_OFFSET_OFFSET = 0x3c
        private const val DEX_STRING_ID_SIZE = 4
        private val PREFIX_REGEX = Regex("""[A-Za-z][A-Za-z0-9@#_!*&$%+?.-]{7,79}GwToken[A-Za-z0-9@#_!*&$%+?.-]{4,80}""")

        private fun Any?.isNullOrBlankString(): Boolean {
            return (this as? String).isNullOrBlank()
        }

        private fun log(message: String) {
            XposedBridge.log("ColorOSFeiniuBridge: $message")
        }
    }
}
