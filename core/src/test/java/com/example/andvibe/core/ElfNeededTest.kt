package com.example.andvibe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElfNeededTest {
    @Test
    fun flattenSonameStripsVersions() {
        assertEquals("libz.so", ElfNeeded.flattenSoname("libz.so"))
        assertEquals("libz.so", ElfNeeded.flattenSoname("libz.so.1"))
        assertEquals("libz.so", ElfNeeded.flattenSoname("libz.so.1.3.1"))
        assertEquals("libpng16.so", ElfNeeded.flattenSoname("libpng16.so.16"))
        assertEquals(null, ElfNeeded.flattenSoname("not-a-lib"))
        assertFalse(ElfNeeded.isAndroidLibName("libz.so.1"))
        assertTrue(ElfNeeded.isAndroidLibName("libz.so"))
        assertTrue(ElfNeeded.isAndroidLibName("libandroid-spawn.so"))
        assertTrue(ElfNeeded.isAndroidLibName("libandroid-shmem.so"))
        assertEquals("libandroid-spawn.so", ElfNeeded.flattenSoname("libandroid-spawn.so"))
        assertEquals("libcxx_shared.so", ElfNeeded.packagedLibName("libc++_shared.so"))
        assertFalse(ElfNeeded.isAndroidLibName("libc++_shared.so"))
    }

    @Test
    fun renamesForMapsVersionedNeededToFlat() {
        val renames = ElfNeeded.renamesFor(
            androidLibNames = setOf("libz.so", "libpng16.so"),
            needed = listOf("libz.so.1", "libc.so", "libpng16.so.16"),
        )
        assertEquals(mapOf("libz.so.1" to "libz.so", "libpng16.so.16" to "libpng16.so"), renames)
    }

    @Test
    fun rewriteShortensDtNeededInPlace() {
        val original = minimalElf(needed = listOf("libz.so.1", "libc.so"))
        assertEquals(listOf("libz.so.1", "libc.so"), ElfNeeded.listNeeded(original))
        val rewritten = ElfNeeded.rewrite(original, mapOf("libz.so.1" to "libz.so"))
        assertEquals(listOf("libz.so", "libc.so"), ElfNeeded.listNeeded(rewritten))
        // Original buffer unchanged.
        assertEquals(listOf("libz.so.1", "libc.so"), ElfNeeded.listNeeded(original))
    }

    @Test
    fun rewriteShortensDtSonameInPlace() {
        val original = minimalElf(needed = listOf("libc.so"), soname = "libz.so.1")
        assertEquals("libz.so.1", ElfNeeded.listSoname(original))
        val rewritten = ElfNeeded.rewrite(original, mapOf("libz.so.1" to "libz.so"))
        assertEquals("libz.so", ElfNeeded.listSoname(rewritten))
        assertEquals("libz.so.1", ElfNeeded.listSoname(original))
    }

    @Test
    fun rewriteRealTermuxLibzSoname() {
        val original = javaClass.classLoader!!
            .getResourceAsStream("libz-termux.so")!!
            .readBytes()
        assertEquals("libz.so.1", ElfNeeded.listSoname(original))
        val rewritten = ElfNeeded.rewrite(original, mapOf("libz.so.1" to "libz.so"))
        assertEquals("libz.so", ElfNeeded.listSoname(rewritten))
    }

    @Test
    fun rewriteRealTermuxLibjli() {
        val original = javaClass.classLoader!!
            .getResourceAsStream("libjli-termux.so")!!
            .readBytes()
        assertEquals(listOf("libz.so.1", "libdl.so", "libc.so"), ElfNeeded.listNeeded(original))
        val rewritten = ElfNeeded.rewrite(original, mapOf("libz.so.1" to "libz.so"))
        assertEquals(listOf("libz.so", "libdl.so", "libc.so"), ElfNeeded.listNeeded(rewritten))
    }

    /** Tiny ELF64 LE with .shstrtab, .dynstr, and .dynamic (DT_NEEDED + DT_NULL). */
    private fun minimalElf(needed: List<String>, soname: String? = null): ByteArray {
        val shstr = byteArrayOf(0) + ".shstrtab\u0000.dynstr\u0000.dynamic\u0000".toByteArray(Charsets.US_ASCII)
        val dynstrBuf = ArrayList<Byte>()
        dynstrBuf.add(0)
        val neededOffsets = ArrayList<Int>()
        for (name in needed) {
            neededOffsets.add(dynstrBuf.size)
            dynstrBuf.addAll(name.toByteArray(Charsets.US_ASCII).toList())
            dynstrBuf.add(0)
        }
        var sonameOff = -1
        if (soname != null) {
            sonameOff = dynstrBuf.size
            dynstrBuf.addAll(soname.toByteArray(Charsets.US_ASCII).toList())
            dynstrBuf.add(0)
        }
        val dynstr = dynstrBuf.toByteArray()

        val entryCount = needed.size + (if (soname != null) 1 else 0) + 1
        val dyn = ByteArray(entryCount * 16)
        for (i in needed.indices) {
            val off = i * 16
            writeU64(dyn, off, 1) // DT_NEEDED
            writeU64(dyn, off + 8, neededOffsets[i].toLong())
        }
        if (soname != null) {
            val off = needed.size * 16
            writeU64(dyn, off, 14) // DT_SONAME
            writeU64(dyn, off + 8, sonameOff.toLong())
        }
        // DT_NULL already zero

        val eHdrSize = 64
        val shEnt = 64
        val shNum = 4 // NULL, .shstrtab, .dynstr, .dynamic
        val shOff = eHdrSize
        val dataOff = shOff + shNum * shEnt

        val shstrOff = dataOff
        val dynstrOff = shstrOff + shstr.size
        val dynOff = dynstrOff + dynstr.size
        val total = dynOff + dyn.size

        val out = ByteArray(total)
        // ELF magic + class/data/version
        out[0] = 0x7f
        out[1] = 'E'.code.toByte()
        out[2] = 'L'.code.toByte()
        out[3] = 'F'.code.toByte()
        out[4] = 2 // ELFCLASS64
        out[5] = 1 // ELFDATA2LSB
        out[6] = 1
        writeU16(out, 16, 3) // ET_DYN
        writeU16(out, 18, 0xb7) // EM_AARCH64
        writeU32(out, 20, 1)
        writeU64(out, 40, shOff.toLong())
        writeU16(out, 54, eHdrSize)
        writeU16(out, 58, shEnt)
        writeU16(out, 60, shNum)
        writeU16(out, 62, 1) // e_shstrndx

        fun writeSh(
            index: Int,
            nameOff: Int,
            type: Int,
            offset: Int,
            size: Int,
            link: Int = 0,
        ) {
            val base = shOff + index * shEnt
            writeU32(out, base, nameOff) // sh_name
            writeU32(out, base + 4, type) // sh_type
            // sh_flags@8, sh_addr@16 left zero
            writeU64(out, base + 24, offset.toLong()) // sh_offset
            writeU64(out, base + 32, size.toLong()) // sh_size
            writeU32(out, base + 40, link) // sh_link
        }

        // names in shstr: \0 .shstrtab\0 .dynstr\0 .dynamic\0
        val nameShstr = 1
        val nameDynstr = 1 + ".shstrtab".length + 1
        val nameDynamic = nameDynstr + ".dynstr".length + 1
        writeSh(1, nameShstr, 3, shstrOff, shstr.size) // SHT_STRTAB
        writeSh(2, nameDynstr, 3, dynstrOff, dynstr.size)
        writeSh(3, nameDynamic, 6, dynOff, dyn.size, link = 2) // SHT_DYNAMIC

        System.arraycopy(shstr, 0, out, shstrOff, shstr.size)
        System.arraycopy(dynstr, 0, out, dynstrOff, dynstr.size)
        System.arraycopy(dyn, 0, out, dynOff, dyn.size)
        return out
    }

    private fun writeU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v shr 8) and 0xff).toByte()
    }

    private fun writeU32(b: ByteArray, off: Int, v: Int) {
        for (i in 0 until 4) b[off + i] = ((v shr (8 * i)) and 0xff).toByte()
    }

    private fun writeU64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v shr (8 * i)) and 0xff).toByte()
    }
}
