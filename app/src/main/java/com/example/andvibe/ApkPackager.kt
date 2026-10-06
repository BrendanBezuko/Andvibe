package com.example.andvibe

import android.content.Context
import com.android.apksig.ApkSigner
import org.spongycastle.asn1.x500.X500Name
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter
import org.spongycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.spongycastle.jce.provider.BouncyCastleProvider
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.math.BigInteger

object ApkPackager {
    private val webExt = setOf(
        "html", "htm", "css", "js", "mjs", "cjs", "json", "svg", "png", "jpg", "jpeg",
        "gif", "webp", "ico", "txt", "md", "map", "woff", "woff2"
    )
    private const val MAX_FILE = 5 * 1024 * 1024
    private const val MAX_TOTAL = 32 * 1024 * 1024

    fun packageApk(context: Context, root: File, log: (String) -> Unit): File {
        DebugLog.step("pack", "start ${root.absolutePath}")
        val template = File(context.cacheDir, "shell-template.apk")
        try {
            context.assets.open("shell.apk").use { input ->
                template.outputStream().use { input.copyTo(it) }
            }
            DebugLog.step("pack", "template bytes=${template.length()}")
        } catch (_: Exception) {
            DebugLog.step("pack", "missing shell.apk")
            error("APK template is missing. Rebuild AndVibe in Android Studio and install that build, then press Build APK again.")
        }
        val assets = collect(root, log)
        val unsigned = File(context.cacheDir, "unsigned.apk")
        log("packing ${assets.size} files")
        writeUnsigned(template, unsigned, assets)
        val signed = ApkLibrary.place(context, "${safeName(root.name)}.apk")
        val (key, cert) = debugSigner(context)
        val config = ApkSigner.SignerConfig.Builder("andvibe", key, listOf(cert)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setMinSdkVersion(24)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .build()
            .sign()
        DebugLog.step("pack", "signed path=${signed.absolutePath} bytes=${signed.length()}")
        return signed
    }

    internal fun collect(root: File, log: (String) -> Unit): List<Asset> {
        val out = mutableListOf<Asset>()
        var total = 0
        var skipped = 0
        RepoFiles.walk(root) { file ->
            if (file.extension.lowercase() !in webExt) return@walk
            if (file.length() > MAX_FILE) {
                skipped++
                return@walk
            }
            if (total + file.length() > MAX_TOTAL) {
                skipped++
                return@walk
            }
            val rel = RepoFiles.rel(file, root)
            if (rel.isBlank() || rel.split('/').any { it == ".." || it.isBlank() }) return@walk
            total += file.length().toInt()
            out.add(Asset(rel, file))
        }
        if (skipped > 0) log("skipped $skipped large files")
        if (out.none { it.path == "index.html" }) {
            val renamed = out.firstOrNull { it.path.equals("index.html", ignoreCase = true) }
            if (renamed != null) {
                out.add(Asset("index.html", renamed.file))
            } else {
                out.add(Asset("index.html", null, generatedIndex(root, out)))
            }
        }
        val gradle = File(root, "build.gradle").exists() || File(root, "build.gradle.kts").exists()
        if (gradle) {
            log("Gradle project: this phone cannot run Gradle. The APK packages the web files only.")
        }
        return out
    }

    private fun generatedIndex(root: File, files: List<Asset>): ByteArray {
        val entry = listOf("main.js", "index.js", "app.js", "src/main.js", "src/index.js")
            .firstOrNull { name -> files.any { it.path == name } }
        val title = root.name.replace("<", "").replace(">", "")
        val note = JsRunner.detect(root).replace("<", "").replace(">", "")
        val script = if (entry == null) "" else "<script src=\"$entry\"></script>"
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$title</title>
            </head>
            <body>
            <h1>$title</h1>
            <p>$note</p>
            $script
            </body>
            </html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
    }

    internal fun writeUnsigned(template: File, dest: File, assets: List<Asset>) {
        ZipFile(template).use { zip ->
            dest.outputStream().use { file ->
                val out = ZipWriter(file)
                for (entry in zip.entries().toList()) {
                    val name = entry.name.replace('\\', '/')
                    if (name.endsWith("/")) continue
                    if (name.startsWith("META-INF/")) continue
                    if (name.startsWith("assets/www/")) continue
                    val raw = zip.getInputStream(entry).use { it.readBytes() }
                    val method = if (name == "resources.arsc" || entry.method == ZipEntry.STORED) {
                        ZipEntry.STORED
                    } else {
                        ZipEntry.DEFLATED
                    }
                    out.add(name, raw, method)
                }
                for (asset in assets) {
                    val raw = asset.bytes ?: asset.file?.readBytes() ?: continue
                    out.add("assets/www/${asset.path}", raw, ZipEntry.DEFLATED)
                }
                out.finish()
            }
        }
    }

    private fun debugSigner(context: Context): Pair<PrivateKey, X509Certificate> {
        if (Security.getProvider("SC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val storeFile = File(context.filesDir, "debug.p12")
        val password = "andvibe".toCharArray()
        val store = KeyStore.getInstance("PKCS12", "SC")
        if (storeFile.isFile) {
            storeFile.inputStream().use { store.load(it, password) }
            val key = store.getKey("andvibe", password) as PrivateKey
            val cert = store.getCertificate("andvibe") as X509Certificate
            return key to cert
        }
        val keys = KeyPairGenerator.getInstance("RSA", "SC").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=AndVibe Debug")
        val now = Date()
        val later = Date(now.time + 3650L * 24L * 60L * 60L * 1000L)
        val holder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now.time),
            now,
            later,
            name,
            keys.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider("SC").build(keys.private)
        val cert = JcaX509CertificateConverter().setProvider("SC").getCertificate(holder.build(signer))
        store.load(null, password)
        store.setKeyEntry("andvibe", keys.private, password, arrayOf(cert))
        storeFile.outputStream().use { store.store(it, password) }
        return keys.private to cert
    }

    internal fun safeName(name: String): String {
        val clean = name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        return clean.ifBlank { "app" }
    }

    internal class Asset(val path: String, val file: File?, val bytes: ByteArray? = null)
}
