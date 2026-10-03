package com.example.andvibe

import android.content.Context
import com.android.apksig.ApkSigner
import org.spongycastle.asn1.x500.X500Name
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter
import org.spongycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.spongycastle.jce.provider.BouncyCastleProvider
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.CRC32
import java.util.zip.Deflater
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

    private fun collect(root: File, log: (String) -> Unit): List<Asset> {
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

    private fun writeUnsigned(template: File, dest: File, assets: List<Asset>) {
        val locals = mutableListOf<Central>()
        ZipFile(template).use { zip ->
            FileOutputStream(dest).use { file ->
                val out = ZipOut(file)
                val entries = zip.entries().toList()
                for (entry in entries) {
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
                    locals.add(out.write(name, raw, method))
                }
                for (asset in assets) {
                    val raw = asset.bytes ?: asset.file?.readBytes() ?: continue
                    locals.add(out.write("assets/www/${asset.path}", raw, ZipEntry.DEFLATED))
                }
                out.finish(locals)
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

    private fun safeName(name: String): String {
        val clean = name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        return clean.ifBlank { "app" }
    }

    private class Asset(val path: String, val file: File?, val bytes: ByteArray? = null)

    private class Central(
        val name: ByteArray,
        val method: Int,
        val crc: Long,
        val compressed: Long,
        val uncompressed: Long,
        val offset: Long
    )

    private class ZipOut(private val file: FileOutputStream) {
        private var offset = 0L

        fun write(name: String, raw: ByteArray, method: Int): Central {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val methodUsed = if (raw.isEmpty() || method == ZipEntry.STORED) ZipEntry.STORED else ZipEntry.DEFLATED
            val payload = if (methodUsed == ZipEntry.STORED) raw else deflate(raw)
            val crc = CRC32().apply { update(raw) }.value
            val extra = if (methodUsed == ZipEntry.STORED) {
                val dataStart = offset + 30 + nameBytes.size
                val pad = ((4 - (dataStart % 4)) % 4).toInt()
                ByteArray(pad)
            } else {
                ByteArray(0)
            }
            val local = offset
            writeInt(0x04034b50)
            writeShort(20)
            writeShort(0x0800)
            writeShort(methodUsed)
            writeShort(0)
            writeShort(0)
            writeInt(crc.toInt())
            writeInt(payload.size)
            writeInt(raw.size)
            writeShort(nameBytes.size)
            writeShort(extra.size)
            write(nameBytes)
            write(extra)
            write(payload)
            return Central(nameBytes, methodUsed, crc, payload.size.toLong(), raw.size.toLong(), local)
        }

        fun finish(entries: List<Central>) {
            val cdStart = offset
            for (entry in entries) {
                writeInt(0x02014b50)
                writeShort(20)
                writeShort(20)
                writeShort(0x0800)
                writeShort(entry.method)
                writeShort(0)
                writeShort(0)
                writeInt(entry.crc.toInt())
                writeInt(entry.compressed.toInt())
                writeInt(entry.uncompressed.toInt())
                writeShort(entry.name.size)
                writeShort(0)
                writeShort(0)
                writeShort(0)
                writeShort(0)
                writeInt(0)
                writeInt(entry.offset.toInt())
                write(entry.name)
            }
            val cdSize = offset - cdStart
            writeInt(0x06054b50)
            writeShort(0)
            writeShort(0)
            writeShort(entries.size)
            writeShort(entries.size)
            writeInt(cdSize.toInt())
            writeInt(cdStart.toInt())
            writeShort(0)
            file.flush()
        }

        private fun deflate(raw: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            deflater.setInput(raw)
            deflater.finish()
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream()
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                if (n > 0) out.write(buffer, 0, n)
            }
            deflater.end()
            return out.toByteArray()
        }

        private fun write(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            file.write(bytes)
            offset += bytes.size
        }

        private fun writeShort(value: Int) {
            write(byteArrayOf((value and 0xff).toByte(), ((value ushr 8) and 0xff).toByte()))
        }

        private fun writeInt(value: Int) {
            write(
                byteArrayOf(
                    (value and 0xff).toByte(),
                    ((value ushr 8) and 0xff).toByte(),
                    ((value ushr 16) and 0xff).toByte(),
                    ((value ushr 24) and 0xff).toByte()
                )
            )
        }
    }
}
