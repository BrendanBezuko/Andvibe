package com.example.andvibe.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry

/**
 * Writes a zip archive with 4-byte alignment for stored entries.
 *
 * java.util.zip.ZipOutputStream cannot align stored entries, and Android
 * requires resources.arsc to be stored and 4-byte aligned, so APK repacking
 * writes headers by hand. No zip64 support: writing fails fast at the 4 GB /
 * 65535-entry limits instead of producing a corrupt archive.
 */
class ZipWriter(private val out: OutputStream) {
    private class Entry(
        val name: ByteArray,
        val method: Int,
        val crc: Long,
        val compressed: Int,
        val uncompressed: Int,
        val offset: Long
    )

    private val entries = mutableListOf<Entry>()
    private val names = mutableSetOf<String>()
    private var offset = 0L
    private var finished = false

    fun add(name: String, raw: ByteArray, method: Int) {
        check(!finished) { "zip already finished" }
        require(name.isNotEmpty() && !name.endsWith("/")) { "bad entry name: $name" }
        require(names.add(name)) { "duplicate entry: $name" }
        require(entries.size < MAX_ENTRIES) { "too many zip entries" }
        require(offset <= MAX_OFFSET) { "zip too large" }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val methodUsed = if (raw.isEmpty() || method == ZipEntry.STORED) ZipEntry.STORED else ZipEntry.DEFLATED
        val payload = if (methodUsed == ZipEntry.STORED) raw else deflate(raw)
        val crc = CRC32().apply { update(raw) }.value
        // Zero-pad the extra field so stored data starts on a 4-byte boundary (zipalign).
        val extra = if (methodUsed == ZipEntry.STORED) {
            val dataStart = offset + LOCAL_HEADER_SIZE + nameBytes.size
            ByteArray(((4 - (dataStart % 4)) % 4).toInt())
        } else {
            ByteArray(0)
        }
        val local = offset
        writeInt(LOCAL_HEADER_SIG)
        writeShort(VERSION)
        writeShort(FLAG_UTF8)
        writeShort(methodUsed)
        writeShort(0) // time
        writeShort(0) // date
        writeInt(crc.toInt())
        writeInt(payload.size)
        writeInt(raw.size)
        writeShort(nameBytes.size)
        writeShort(extra.size)
        write(nameBytes)
        write(extra)
        write(payload)
        entries.add(Entry(nameBytes, methodUsed, crc, payload.size, raw.size, local))
    }

    fun finish() {
        check(!finished) { "zip already finished" }
        finished = true
        val cdStart = offset
        require(cdStart <= MAX_OFFSET) { "zip too large" }
        for (entry in entries) {
            writeInt(CENTRAL_HEADER_SIG)
            writeShort(VERSION)
            writeShort(VERSION)
            writeShort(FLAG_UTF8)
            writeShort(entry.method)
            writeShort(0) // time
            writeShort(0) // date
            writeInt(entry.crc.toInt())
            writeInt(entry.compressed)
            writeInt(entry.uncompressed)
            writeShort(entry.name.size)
            writeShort(0) // extra length
            writeShort(0) // comment length
            writeShort(0) // disk number
            writeShort(0) // internal attributes
            writeInt(0) // external attributes
            writeInt(entry.offset.toInt())
            write(entry.name)
        }
        val cdSize = offset - cdStart
        writeInt(END_SIG)
        writeShort(0)
        writeShort(0)
        writeShort(entries.size)
        writeShort(entries.size)
        writeInt(cdSize.toInt())
        writeInt(cdStart.toInt())
        writeShort(0)
        out.flush()
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(raw)
        deflater.finish()
        val buffer = ByteArray(8192)
        val result = ByteArrayOutputStream()
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n > 0) result.write(buffer, 0, n)
        }
        deflater.end()
        return result.toByteArray()
    }

    private fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        out.write(bytes)
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

    companion object {
        const val LOCAL_HEADER_SIZE = 30
        private const val LOCAL_HEADER_SIG = 0x04034b50
        private const val CENTRAL_HEADER_SIG = 0x02014b50
        private const val END_SIG = 0x06054b50
        private const val VERSION = 20
        private const val FLAG_UTF8 = 0x0800
        private const val MAX_ENTRIES = 0xffff
        private const val MAX_OFFSET = 0xffffffffL
    }
}
