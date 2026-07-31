package io.github.colorosfeiniu.bridge

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.lang.reflect.Method
import java.util.zip.ZipFile

class FeiniuBridgeHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        runCatching {
            val target = TargetResolver.resolve(lpparam)
            target.methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, PrefixFallbackHook(lpparam))
            }

            if (target.methods.isEmpty()) {
                log("prefix fallback unavailable for ${lpparam.packageName}")
            } else {
                log("installed for ${lpparam.packageName} class=${target.className} via=${target.source}")
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

    /**
     * Locates the prefix loader to hook.
     *
     * A known class is accepted immediately only when its full method contract is confirmed. If
     * none qualify, structural DEX discovery runs before the name-only fallback kept for legacy
     * Gallery builds that expose the prefix loader but not the newer decrypt entry point.
     */
    private object TargetResolver {

        fun resolve(lpparam: XC_LoadPackage.LoadPackageParam): Target {
            val knownCandidates = TokenDecryptorTargets.classNames
                .mapNotNull { className -> findClass(className, lpparam) }
            val resolved = TokenDecryptorTargetResolver.resolve(
                knownCandidates,
                hasPrefixLoader = { prefixMethodsOf(it).isNotEmpty() },
                hasDecryptEntryPoint = ::declaresDecryptEntryPoint,
                locateByShape = { resolveClassByShape(lpparam) },
            ) ?: return Target(emptyList(), null, "none")

            return Target(
                prefixMethodsOf(resolved.target),
                resolved.target.name,
                resolved.source.logValue,
            )
        }

        private fun resolveClassByShape(lpparam: XC_LoadPackage.LoadPackageParam): Class<*>? {
            val className = ApkDex.scan(lpparam) { bytes -> TokenDecryptorLocator.locate(bytes) }
            if (className == null) {
                log("dex scan did not find a token decryptor class")
                return null
            }

            val clazz = findClass(className, lpparam)
            if (clazz == null) {
                log("dex scan matched $className but it is not loadable")
                return null
            }

            val methods = prefixMethodsOf(clazz)
            if (methods.isEmpty()) return null
            return clazz
        }

        private fun findClass(className: String, lpparam: XC_LoadPackage.LoadPackageParam): Class<*>? =
            runCatching { XposedHelpers.findClass(className, lpparam.classLoader) }.getOrNull()

        private fun prefixMethodsOf(clazz: Class<*>): List<Method> =
            runCatching {
                clazz.declaredMethods.filter { method ->
                    method.name == TokenDecryptorTargets.PREFIX_METHOD &&
                        method.returnType == String::class.java &&
                        method.parameterTypes.isEmpty()
                }
            }.getOrDefault(emptyList())

        private fun declaresDecryptEntryPoint(clazz: Class<*>): Boolean =
            runCatching {
                clazz.declaredMethods.any { method ->
                    method.name == TokenDecryptorTargets.DECRYPT_METHOD &&
                        method.returnType == String::class.java &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes.all { it == String::class.java }
                }
            }.getOrDefault(false)
    }

    private class Target(
        val methods: List<Method>,
        val className: String?,
        val source: String,
    )

    private object PrefixResolver {
        @Volatile
        private var cachedPrefix: ResolvedPrefix? = null

        fun resolve(lpparam: XC_LoadPackage.LoadPackageParam): ResolvedPrefix? {
            cachedPrefix?.let { return it }

            val fromApk = ApkDex.scan(lpparam) { bytes ->
                DexFile.parse(bytes)?.firstString { it.isFeiniuPrefix() }
            }
            val resolved = fromApk?.let { ResolvedPrefix(it, "apk-dex") }
                ?: ResolvedPrefix(KNOWN_PREFIX, "builtin")

            cachedPrefix = resolved
            return resolved
        }

        private fun String.isFeiniuPrefix(): Boolean =
            length in 16..80 && PREFIX_REGEX.matches(this)
    }

    /** Walks the DEX images of the installed Gallery APKs. */
    private object ApkDex {

        fun <T : Any> scan(
            lpparam: XC_LoadPackage.LoadPackageParam,
            transform: (ByteArray) -> T?,
        ): T? {
            val sourcePaths = buildList {
                add(lpparam.appInfo?.sourceDir)
                lpparam.appInfo?.splitSourceDirs?.let(::addAll)
            }.filterNotNull()

            if (sourcePaths.isEmpty()) {
                log("apk scan skipped: no source paths")
                return null
            }

            for (sourcePath in sourcePaths) {
                scanApk(File(sourcePath), transform)?.let { return it }
            }
            return null
        }

        private fun <T : Any> scanApk(apk: File, transform: (ByteArray) -> T?): T? {
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
                        transform(bytes)?.let { return it }
                    }
                }
                null
            } catch (error: Throwable) {
                log("apk scan failed for ${apk.name}: ${error.javaClass.simpleName}: ${error.message}")
                null
            }
        }
    }

    private data class ResolvedPrefix(
        val value: String,
        val source: String,
    )

    companion object {
        private const val TARGET_PACKAGE = "com.coloros.gallery3d"
        private const val KNOWN_PREFIX = "tRiM@2025#GwToken!sEcReT*kEy&vALu"
        private val PREFIX_REGEX = Regex("""[A-Za-z][A-Za-z0-9@#_!*&$%+?.-]{7,79}GwToken[A-Za-z0-9@#_!*&$%+?.-]{4,80}""")

        private fun Any?.isNullOrBlankString(): Boolean {
            return (this as? String).isNullOrBlank()
        }

        private fun log(message: String) {
            XposedBridge.log("ColorOSFeiniuBridge: $message")
        }
    }
}
