package com.example.andvibe

import android.content.Context
import android.system.Os
import com.example.andvibe.core.ElfNeeded
import com.example.andvibe.core.ToolchainLayout
import com.example.andvibe.core.ToolchainPins
import com.example.andvibe.core.ZipWriter
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Download Termux OpenJDK + aapt/aapt2 (and deps), stage the JDK tree, pack
 * executables/.so into a companion APK, and prompt the user to install it.
 *
 * Natives are cached under the toolchain layout so reinstall / re-prompt skips
 * the slow download + extract unless the cache is missing.
 */
object CompanionInstaller {
    private const val APK_NAME = "andvibe-toolchain.apk"

    /** Fetch (if needed) + pack + install prompt. Reuses a good APK when present. */
    fun install(context: Context, layout: ToolchainLayout, log: (String) -> Unit): File =
        buildAndPrompt(context, layout, log, forceRebuildApk = false)

    /**
     * Open the installer again. Rebuilds the APK from the natives cache when
     * needed — does not re-download/extract Termux packages unless the cache
     * is gone.
     */
    fun reinstall(context: Context, layout: ToolchainLayout, log: (String) -> Unit): File =
        buildAndPrompt(context, layout, log, forceRebuildApk = true)

    private fun buildAndPrompt(
        context: Context,
        layout: ToolchainLayout,
        log: (String) -> Unit,
        forceRebuildApk: Boolean,
    ): File {
        val signed = apkFile(context)
        if (!forceRebuildApk && isInstallableCompanionApk(signed)) {
            log("Build Tools APK ready (${signed.length() / 1024} KB). Opening installer…")
            return prompt(context, signed, log)
        }

        val abi = ToolchainExec.abi()
        if (abi != "arm64-v8a" && abi != "armeabi-v7a") {
            error("Local Build Tools on-device install supports arm phones only (this device reports $abi).")
        }

        val nativesDir = nativesDir(layout, abi)
        val natives = if (nativesCacheReady(nativesDir)) {
            log("Using cached Build Tools natives (skipping download/extract).")
            loadNatives(nativesDir, log)
        } else {
            fetchAndCacheNatives(context, layout, abi, nativesDir, log)
        }

        if (!File(layout.jdkHome, "lib").isDirectory) {
            error("JDK data missing under ${layout.jdkHome}. Clear app storage and tap Reinstall Build Tools.")
        }

        if (signed.exists()) signed.delete()
        val apk = buildCompanionApk(context, abi, natives, log)
        return prompt(context, apk, log)
    }

    private fun prompt(context: Context, apk: File, log: (String) -> Unit): File {
        val err = ApkInstaller.install(context, apk, label = "AndVibe Build Tools")
        if (err != null) {
            log(err)
        } else {
            log("Install AndVibe Build Tools when prompted, then tap Build again.")
        }
        return apk
    }

    fun apkFile(context: Context): File {
        val dir = File(context.filesDir, "apk").also { it.mkdirs() }
        return File(dir, APK_NAME)
    }

    private fun nativesDir(layout: ToolchainLayout, abi: String): File =
        File(layout.root, "natives/$abi")

    private fun nativesCacheReady(dir: File): Boolean {
        val java = File(dir, ToolchainPins.JAVA_LIB)
        val aapt2 = File(dir, ToolchainPins.AAPT2_LIB)
        val zlib = File(dir, "libz.so")
        // Empty libz.so means tar symlinks were materialized as 0-byte files.
        return java.isFile && java.length() > 0 &&
            aapt2.isFile && aapt2.length() > 0 &&
            zlib.isFile && zlib.length() > 1_000
    }

    private fun loadNatives(dir: File, log: (String) -> Unit): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        dir.listFiles()?.filter { it.isFile && it.length() > 0 }?.forEach { out[it.name] = it }
        log("Loaded ${out.size} cached native files")
        return out
    }

    private fun fetchAndCacheNatives(
        context: Context,
        layout: ToolchainLayout,
        abi: String,
        nativesDir: File,
        log: (String) -> Unit,
    ): Map<String, File> {
        val work = File(context.cacheDir, "termux-fetch")
        work.deleteRecursively()
        work.mkdirs()
        val debsDir = File(work, "debs")
        val extractRoot = File(work, "root")
        extractRoot.mkdirs()

        val index = TermuxPackages.fetchIndex(abi, log)
        val pkgs = TermuxPackages.resolveClosure(index)
        log("Resolved ${pkgs.size} Termux packages (OpenJDK 17 + aapt/aapt2 + libs)")
        val debs = TermuxPackages.downloadAll(pkgs, debsDir, log)

        log("Extracting packages…")
        for (deb in debs) {
            DebExtractor.extract(deb, extractRoot)
        }

        stageJdk(extractRoot, layout, log)
        val natives = collectNatives(extractRoot, abi, log)

        nativesDir.deleteRecursively()
        nativesDir.mkdirs()
        for ((name, file) in natives) {
            file.copyTo(File(nativesDir, name), overwrite = true)
        }
        log("Cached ${natives.size} natives at ${nativesDir.absolutePath}")
        work.deleteRecursively()
        return loadNatives(nativesDir, log)
    }

    /** Reject APKs that would fail PackageInstaller (resources.arsc must be STORED). */
    private fun isInstallableCompanionApk(apk: File): Boolean {
        if (!apk.isFile || apk.length() < 1_000_000L) return false
        return try {
            ZipFile(apk).use { zip ->
                val arsc = zip.getEntry("resources.arsc") ?: return false
                arsc.method == ZipEntry.STORED
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * After the companion APK is installed, symlink helpers into the staged JDK/SDK
     * tree. The runnable `java` stays in the companion nativeLibraryDir (W^X);
     * [libjrehome] makes JRE discovery treat `jdk/bin/java` as /proc/self/exe.
     */
    fun linkExecutables(context: Context, layout: ToolchainLayout, log: (String) -> Unit) {
        val native = ToolchainExec.companionNativeDir(context) ?: return
        val javaSo = File(native, ToolchainPins.JAVA_LIB)
        val javaWrap = File(native, ToolchainPins.JAVA_WRAP_LIB).takeIf { it.isFile } ?: javaSo
        val aapt2So = File(native, ToolchainPins.AAPT2_LIB)
        val spawnSo = File(native, "libjspawnhelper.so")
        if (!javaSo.isFile) return

        val bin = File(layout.jdkHome, "bin")
        bin.mkdirs()
        // Wrapper injects sun.jnu.encoding for Gradle's forked daemon JVM.
        // ANDVIBE_JAVA_EXE still points here so libjrehome can fake /proc/self/exe.
        // Other JDK CLIs (javac/jlink/jmod/…) stay as staged stubs; libjrehome
        // rewrites them to ANDVIBE_JDK_BIN_DIR/lib<tool>bin.so at spawn time.
        symlinkReplace(File(bin, "java"), javaWrap)
        if (spawnSo.isFile) {
            File(layout.jdkHome, "lib").mkdirs()
            symlinkReplace(File(layout.jdkHome, "lib/jspawnhelper"), spawnSo)
        }
        if (aapt2So.isFile) {
            layout.buildTools.mkdirs()
            symlinkReplace(File(layout.buildTools, "aapt2"), aapt2So)
            val aaptSo = File(native, ToolchainPins.AAPT_LIB)
            if (aaptSo.isFile) symlinkReplace(File(layout.buildTools, "aapt"), aaptSo)
        }
        // AGP override must be a path whose file name is exactly `aapt2` (not
        // libaapt2bin.so). stageAapt2 refreshes the symlink after every app
        // reinstall when nativeLibraryDir moves.
        if (aapt2So.isFile) {
            val staged = stageAapt2(context, layout, native, log)
            if (staged != null) {
                File(layout.root, "gradle-aapt2.properties").writeText(
                    "android.aapt2FromMavenOverride=${staged.absolutePath}\n",
                )
            }
        }
        log("Linked java/aapt2 into toolchain from companion package")
    }

    /**
     * AGP requires the override path's file name to be exactly `aapt2`, but
     * PackageManager only extracts `lib*.so`. Symlink `layout/aapt2` → a
     * nativeLibraryDir `.so` so the name matches and the inode stays W^X-safe.
     *
     * Always rewrites the link: after AndVibe reinstall, nativeLibraryDir
     * changes and a stale symlink looks like EEXIST / dangling → AGP then
     * rejects a fallback `libaapt2bin.so` path.
     */
    fun stageAapt2(
        context: Context,
        layout: ToolchainLayout,
        nativeDir: File?,
        log: (String) -> Unit,
    ): File? {
        val appNative = File(context.applicationInfo.nativeLibraryDir)
        val real = sequenceOf(
            File(appNative, ToolchainPins.AAPT2_LIB),
            nativeDir?.let { File(it, ToolchainPins.AAPT2_LIB) },
        ).firstOrNull { it != null && it.isFile } ?: run {
            log("aapt2 missing from app/companion native libs")
            return null
        }
        val link = File(layout.root, "aapt2")
        symlinkReplace(link, real)
        if (!link.isFile) {
            log("aapt2 link broken after stage: ${link.absolutePath} → ${real.absolutePath}")
            return null
        }
        if (link.name != "aapt2") {
            log("aapt2 override must be named aapt2, got ${link.name}")
            return null
        }
        log("aapt2 → ${real.absolutePath}")
        return link
    }

    private fun stageJdk(extractRoot: File, layout: ToolchainLayout, log: (String) -> Unit) {
        val jvm = File(extractRoot, "lib/jvm/java-17-openjdk")
        if (!jvm.isDirectory) error("Termux openjdk-17 did not unpack lib/jvm/java-17-openjdk")
        layout.jdkHome.deleteRecursively()
        layout.jdkHome.parentFile?.mkdirs()
        // Copy tree; executables will be symlinked to the companion APK after install.
        jvm.copyRecursively(layout.jdkHome, overwrite = true)
        log("JDK data staged at ${layout.jdkHome.absolutePath}")
    }

    private fun collectNatives(extractRoot: File, abi: String, log: (String) -> Unit): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        fun putLib(name: String, file: File) {
            if (!file.isFile || file.length() == 0L) return
            out[name] = file
        }

        val jvm = File(extractRoot, "lib/jvm/java-17-openjdk")
        putLib(ToolchainPins.JAVA_LIB, File(jvm, "bin/java"))
        putLib("libjspawnhelper.so", File(jvm, "lib/jspawnhelper"))
        // All JVM shared objects (including server/libjvm.so → libjvm.so).
        jvm.walkTopDown().forEach { file ->
            if (!file.isFile || file.length() == 0L) return@forEach
            if (!isSharedLibName(file.name)) return@forEach
            val name = if (file.name.startsWith("lib")) file.name else "lib${file.name}"
            // Prefer the first occurrence; server/libjvm.so overwrites plain if later.
            out[name] = file
        }
        putLib(ToolchainPins.AAPT2_LIB, File(extractRoot, "bin/aapt2"))
        putLib(ToolchainPins.AAPT_LIB, File(extractRoot, "bin/aapt"))

        // Shared libs from dependency packages under usr/lib → extractRoot/lib
        // Include versioned sonames (libz.so.1); empty files are broken tar symlinks.
        File(extractRoot, "lib").walkTopDown().maxDepth(2).forEach { file ->
            if (!file.isFile || file.length() == 0L) return@forEach
            if (!isSharedLibName(file.name)) return@forEach
            val name = if (file.name.startsWith("lib")) file.name else "lib${file.name}"
            val prev = out[name]
            if (prev == null || file.length() > prev.length()) out[name] = file
        }

        // Ensure flat Android names carry real content (symlink targets often versioned).
        val extras = LinkedHashMap<String, File>()
        for ((name, file) in out) {
            val flat = ElfNeeded.flattenSoname(name) ?: continue
            if (flat == name) continue
            val prev = out[flat] ?: extras[flat]
            if (prev == null || prev.length() == 0L || file.length() > prev.length()) {
                extras[flat] = file
            }
        }
        out.putAll(extras)

        if (ToolchainPins.JAVA_LIB !in out) error("missing java binary from Termux openjdk")
        if (ToolchainPins.AAPT2_LIB !in out) error("missing aapt2 binary from Termux aapt2 package")
        val zlib = out["libz.so"]
        if (zlib == null || zlib.length() < 1_000) {
            error("missing zlib (libz.so) from Termux packages — OpenJDK needs it to start")
        }
        log("Packaging ${out.size} native files for $abi")
        return out
    }

    private fun isSharedLibName(name: String): Boolean =
        name.endsWith(".so") || name.contains(".so.")

    /** Ship prebuilt companion helpers (java wrapper + spawn interposer) from assets. */
    private fun injectJavaWrapper(
        context: Context,
        abi: String,
        natives: MutableMap<String, File>,
        log: (String) -> Unit,
    ) {
        for (name in listOf(ToolchainPins.JAVA_WRAP_LIB, ToolchainPins.SPAWN_WRAP_LIB)) {
            val assetPath = "toolchain-natives/$abi/$name"
            val bytes = try {
                context.assets.open(assetPath).use { it.readBytes() }
            } catch (_: Exception) {
                log("companion helper missing ($assetPath)")
                continue
            }
            if (bytes.isEmpty()) continue
            val out = File(context.cacheDir, name)
            out.writeBytes(bytes)
            natives[name] = out
            log("Included $name (${bytes.size} bytes)")
        }
    }

    private fun buildCompanionApk(
        context: Context,
        abi: String,
        natives: Map<String, File>,
        log: (String) -> Unit,
    ): File {
        val template = File(context.cacheDir, "toolchain-template.apk")
        context.assets.open("toolchain-template.apk").use { input ->
            template.outputStream().use { input.copyTo(it) }
        }
        val unsigned = File(context.cacheDir, "toolchain-unsigned.apk")
        val signed = apkFile(context)
        val withWrap = LinkedHashMap(natives)
        injectJavaWrapper(context, abi, withWrap, log)
        rewriteApkWithNatives(template, unsigned, abi, withWrap)
        log("Signing Build Tools APK…")
        if (signed.exists()) signed.delete()
        ApkPackager.signApk(context, unsigned, signed)
        log("Build Tools APK ready (${signed.length() / 1024} KB)")
        return signed
    }

    /**
     * Repack like [ApkPackager.writeUnsigned]: skip META-INF, keep resources.arsc
     * STORED + 4-byte aligned (Android R+ install requirement), STORED natives.
     */
    private fun rewriteApkWithNatives(
        template: File,
        dest: File,
        abi: String,
        natives: Map<String, File>,
    ) {
        if (dest.exists()) dest.delete()
        ZipFile(template).use { zip ->
            dest.outputStream().use { file ->
                val out = ZipWriter(file)
                for (entry in zip.entries().toList()) {
                    val name = entry.name.replace('\\', '/')
                    if (name.endsWith("/")) continue
                    if (name.startsWith("META-INF/")) continue
                    if (name.startsWith("lib/")) continue
                    val raw = zip.getInputStream(entry).use { it.readBytes() }
                    val method =
                        if (name == "resources.arsc" || entry.method == ZipEntry.STORED) {
                            ZipEntry.STORED
                        } else {
                            ZipEntry.DEFLATED
                        }
                    out.add(name, raw, method)
                }
                val packaged = nativesForApk(natives)
                for ((name, bytes) in packaged) {
                    out.add("lib/$abi/$name", bytes, ZipEntry.STORED)
                }
                out.finish()
            }
        }
    }

    /**
     * Android PackageManager only extracts `lib*.so` (not `libz.so.1`). Flatten
     * versioned sonames and rewrite DT_NEEDED so libjli loads `libz.so`.
     */
    internal fun nativesForApk(natives: Map<String, File>): Map<String, ByteArray> {
        val byAndroid = LinkedHashMap<String, ByteArray>()
        val fileRenames = LinkedHashMap<String, String>()
        for ((name, file) in natives) {
            if (!file.isFile || file.length() == 0L) continue
            val androidName = ElfNeeded.packagedLibName(name) ?: continue
            if (name != androidName) fileRenames[name] = androidName
            val bytes = file.readBytes()
            if (bytes.isEmpty()) continue
            val prev = byAndroid[androidName]
            if (prev == null || bytes.size > prev.size) {
                byAndroid[androidName] = bytes
            }
        }
        val androidNames = byAndroid.keys
        val needed = LinkedHashSet<String>()
        for ((name, bytes) in byAndroid) {
            if (!ElfNeeded.isElf(bytes)) continue
            needed.addAll(ElfNeeded.listNeeded(bytes))
            // Cache may only keep flat filenames; still flatten DT_SONAME (libz.so.1 → libz.so).
            val soname = ElfNeeded.listSoname(bytes) ?: continue
            val packaged = ElfNeeded.packagedLibName(soname) ?: continue
            if (soname != packaged && packaged == name) {
                fileRenames[soname] = packaged
            }
        }
        // Also rename based on original filenames (libc++_shared.so may not appear in NEEDED
        // of already-read bytes until we rewrite dependents that still reference it).
        needed.addAll(fileRenames.keys)
        val renames = LinkedHashMap(ElfNeeded.renamesFor(androidNames, needed) + fileRenames)
        // Route OpenJDK ProcessBuilder through our spawn interposer (W^X-safe java path).
        if (ToolchainPins.SPAWN_WRAP_LIB in androidNames) {
            renames["libandroid-spawn.so"] = ToolchainPins.SPAWN_WRAP_LIB
        }
        val out = LinkedHashMap<String, ByteArray>(byAndroid.size)
        for ((name, bytes) in byAndroid) {
            out[name] =
                if (renames.isNotEmpty() && ElfNeeded.isElf(bytes)) {
                    ElfNeeded.rewrite(bytes, renames)
                } else {
                    bytes
                }
        }
        val jli = out["libjli.so"]
        if (jli != null && ElfNeeded.listNeeded(jli).any { it.contains(".so.") }) {
            error("failed to flatten versioned DT_NEEDED in libjli.so (still needs versioned .so)")
        }
        val zlib = out["libz.so"]
        if (zlib != null) {
            val soname = ElfNeeded.listSoname(zlib)
            if (soname != null && soname.contains(".so.")) {
                error("failed to flatten DT_SONAME in libz.so (still $soname)")
            }
        }
        for (required in listOf(
            "libjava.so",
            "libandroid-spawn.so",
            "libandroid-shmem.so",
            "libcxx_shared.so",
        )) {
            if (required !in out) {
                error("companion APK missing $required (OpenJDK will fail to start)")
            }
        }
        return out
    }

    private fun symlinkReplace(link: File, target: File) {
        link.parentFile?.mkdirs()
        // Broken symlinks: File.exists()/delete() often miss them; File.writeText then
        // follows the dangling target and throws ENOENT (seen after companion reinstall).
        removePath(link)
        try {
            Os.symlink(target.absolutePath, link.absolutePath)
        } catch (_: Throwable) {
            try {
                removePath(link)
                // Fall back to a tiny shell trampoline; may fail W^X on some devices.
                link.writeText("#!/system/bin/sh\nexec \"${target.absolutePath}\" \"\$@\"\n")
                link.setExecutable(true, false)
            } catch (_: Throwable) {
                // Leave unlinked; build will use companion nativeLibraryDir paths directly.
            }
        }
    }

    private fun removePath(file: File) {
        try {
            java.nio.file.Files.deleteIfExists(file.toPath())
        } catch (_: Throwable) {
            try {
                Os.remove(file.absolutePath)
            } catch (_: Throwable) {
                if (file.exists() || file.isFile) file.delete()
            }
        }
    }
}
