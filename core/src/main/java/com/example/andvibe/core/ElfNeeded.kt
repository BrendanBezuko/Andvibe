package com.example.andvibe.core

/**
 * Helpers for Termux shared libraries packed into an Android companion APK.
 *
 * Android only extracts `lib*.so` from `lib/<abi>/` (not `libz.so.1`). OpenJDK's
 * `libjli.so` DT_NEEDED and zlib's DT_SONAME still use the versioned soname, so we
 * flatten filenames when packaging and rewrite DT_NEEDED / DT_SONAME in-place when
 * the replacement fits.
 */
object ElfNeeded {
    // Android PackageManager extracts lib/<abi>/lib*.so with [A-Za-z0-9_-] only.
    // Termux also uses hyphens (libandroid-spawn.so) and '+' (libc++_shared.so).
    private val ANDROID_LIB = Regex("^lib[\\w-]+\\.so$")

    fun isElf(bytes: ByteArray): Boolean =
        bytes.size >= 64 &&
            bytes[0] == 0x7f.toByte() &&
            bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

    /**
     * Name to store under lib/<abi>/ in the companion APK.
     * Flattens `libz.so.1` → `libz.so` and rewrites `libc++_shared.so` → `libcxx_shared.so`.
     */
    fun packagedLibName(name: String): String? {
        if (!name.startsWith("lib") || !name.contains(".so")) return null
        val flat =
            if (name.contains(".so.")) {
                name.substring(0, name.indexOf(".so.") + 3)
            } else if (name.endsWith(".so")) {
                name
            } else {
                return null
            }
        // '+' is not extractable; libc++_shared.so → libcxx_shared.so (shorter, in-place OK).
        val packaged = flat.replace("+", "x")
        return packaged.takeIf { isAndroidLibName(it) }
    }

    /** `libz.so.1` / `libz.so.1.3.1` → `libz.so`; already-flat names unchanged. */
    fun flattenSoname(name: String): String? = packagedLibName(name)

    fun isAndroidLibName(name: String): Boolean = ANDROID_LIB.matches(name)

    /**
     * Rewrite DT_NEEDED and DT_SONAME entries whose names appear in [renames].
     * Replacement must be the same length or shorter (remaining bytes zeroed).
     * Returns a copy. Rewriting DT_SONAME (and the shared VERDEF base string) is
     * required so Bionic matches flattened filenames like `libz.so`.
     */
    fun rewrite(bytes: ByteArray, renames: Map<String, String>): ByteArray {
        if (renames.isEmpty() || !isElf(bytes)) return bytes
        if (bytes[4] != 2.toByte() || bytes[5] != 1.toByte()) return bytes // ELF64 LE only
        val out = bytes.copyOf()
        val eShOff = u64(out, 40)
        val eShEntSize = u16(out, 58)
        val eShNum = u16(out, 60)
        val eShStrNdx = u16(out, 62)
        if (eShEntSize < 64 || eShNum == 0 || eShStrNdx >= eShNum) return bytes

        // Elf64_Shdr: name@0 type@4 flags@8 addr@16 offset@24 size@32 link@40
        fun section(i: Int): LongArray {
            val off = eShOff + i.toLong() * eShEntSize
            return longArrayOf(
                u32(out, off).toLong(), // sh_name
                u32(out, off + 4).toLong(), // sh_type
                u64(out, off + 24), // sh_offset
                u64(out, off + 32), // sh_size
                u32(out, off + 40).toLong(), // sh_link
            )
        }

        val shstr = section(eShStrNdx)
        val shstrOff = shstr[2].toInt()
        val shstrSize = shstr[3].toInt()
        if (shstrOff < 0 || shstrSize <= 0 || shstrOff + shstrSize > out.size) return bytes

        var dynOff = -1
        var dynSize = 0
        var dynstrOff = -1
        var dynstrSize = 0
        for (i in 0 until eShNum) {
            val s = section(i)
            val nameOff = shstrOff + s[0].toInt()
            if (nameOff < 0 || nameOff >= out.size) continue
            val name = readCString(out, nameOff)
            when (name) {
                ".dynamic" -> {
                    dynOff = s[2].toInt()
                    dynSize = s[3].toInt()
                }
                ".dynstr" -> {
                    dynstrOff = s[2].toInt()
                    dynstrSize = s[3].toInt()
                }
            }
        }
        if (dynOff < 0 || dynstrOff < 0) return bytes
        if (dynOff + dynSize > out.size || dynstrOff + dynstrSize > out.size) return bytes

        var pos = dynOff
        val end = dynOff + dynSize
        while (pos + 16 <= end) {
            val tag = u64(out, pos.toLong())
            val value = u64(out, pos + 8L)
            if (tag == 0L) break
            // DT_NEEDED=1, DT_SONAME=14 — both point into .dynstr.
            if (tag == 1L || tag == 14L) {
                rewriteDynstr(out, dynstrOff + value.toInt(), renames)
            }
            pos += 16
        }
        return out
    }

    /** Collect DT_NEEDED sonames from an ELF64 LE image. */
    fun listNeeded(bytes: ByteArray): List<String> = listDynamicStrings(bytes, tag = 1L)

    /** DT_SONAME, if present. */
    fun listSoname(bytes: ByteArray): String? = listDynamicStrings(bytes, tag = 14L).firstOrNull()

    private fun listDynamicStrings(bytes: ByteArray, tag: Long): List<String> {
        if (!isElf(bytes) || bytes[4] != 2.toByte() || bytes[5] != 1.toByte()) return emptyList()
        val eShOff = u64(bytes, 40)
        val eShEntSize = u16(bytes, 58)
        val eShNum = u16(bytes, 60)
        val eShStrNdx = u16(bytes, 62)
        if (eShEntSize < 64 || eShNum == 0 || eShStrNdx >= eShNum) return emptyList()

        fun section(i: Int): LongArray {
            val off = eShOff + i.toLong() * eShEntSize
            return longArrayOf(
                u32(bytes, off).toLong(), // sh_name
                u32(bytes, off + 4).toLong(), // sh_type
                u64(bytes, off + 24), // sh_offset
                u64(bytes, off + 32), // sh_size
            )
        }

        val shstr = section(eShStrNdx)
        val shstrOff = shstr[2].toInt()
        var dynOff = -1
        var dynSize = 0
        var dynstrOff = -1
        for (i in 0 until eShNum) {
            val s = section(i)
            val name = readCString(bytes, shstrOff + s[0].toInt())
            when (name) {
                ".dynamic" -> {
                    dynOff = s[2].toInt()
                    dynSize = s[3].toInt()
                }
                ".dynstr" -> dynstrOff = s[2].toInt()
            }
        }
        if (dynOff < 0 || dynstrOff < 0) return emptyList()
        val out = ArrayList<String>()
        var pos = dynOff
        val end = dynOff + dynSize
        while (pos + 16 <= end) {
            val t = u64(bytes, pos.toLong())
            val value = u64(bytes, pos + 8L)
            if (t == 0L) break
            if (t == tag) out.add(readCString(bytes, dynstrOff + value.toInt()))
            pos += 16
        }
        return out
    }

    private fun rewriteDynstr(out: ByteArray, strOff: Int, renames: Map<String, String>) {
        if (strOff !in out.indices) return
        val old = readCString(out, strOff)
        val neu = renames[old] ?: return
        if (neu.length > old.length) return
        for (i in neu.indices) out[strOff + i] = neu[i].code.toByte()
        for (i in neu.length..old.length) {
            if (strOff + i < out.size) out[strOff + i] = 0
        }
    }

    /**
     * Build renames from DT_NEEDED names to packaged `lib*.so` names that exist
     * in [androidLibNames] (versioned sonames and libc++_shared.so → libcxx_shared.so).
     */
    fun renamesFor(androidLibNames: Set<String>, needed: Iterable<String>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (name in needed) {
            val packaged = packagedLibName(name) ?: continue
            if (name != packaged && packaged in androidLibNames) {
                map[name] = packaged
            }
        }
        return map
    }

    private fun readCString(bytes: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= bytes.size) return ""
        var end = offset
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return bytes.copyOfRange(offset, end).toString(Charsets.US_ASCII)
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun u32(b: ByteArray, off: Long): Int {
        val o = off.toInt()
        return (b[o].toInt() and 0xff) or
            ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or
            ((b[o + 3].toInt() and 0xff) shl 24)
    }

    private fun u64(b: ByteArray, off: Long): Long {
        val o = off.toInt()
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((b[o + i].toLong() and 0xff) shl (8 * i))
        }
        return v
    }

    private fun u64(b: ByteArray, off: Int): Long = u64(b, off.toLong())
}
