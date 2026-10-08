package com.example.andvibe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxPackagesTest {
    @Test
    fun parsePackagesReadsDependsAndFilename() {
        val index = TermuxPackages.parsePackages(
            """
            Package: openjdk-17
            Version: 17.0.20
            Depends: libandroid-shmem, zlib
            Filename: pool/main/o/openjdk-17/openjdk-17_17.0.20_aarch64.deb
            Size: 100

            Package: aapt2
            Version: 16.0.0.4-2
            Depends: libprotobuf | something, fmt
            Filename: pool/main/a/aapt2/aapt2_16.0.0.4-2_aarch64.deb
            Size: 50
            """.trimIndent()
        )
        assertEquals(2, index.size)
        assertEquals(
            listOf("libandroid-shmem", "zlib"),
            index.getValue("openjdk-17").depends,
        )
        assertTrue(index.getValue("aapt2").depends.contains("libprotobuf"))
        assertTrue(index.getValue("aapt2").url.endsWith("aapt2_16.0.0.4-2_aarch64.deb"))
    }

    @Test
    fun resolveClosurePullsDependencies() {
        val index = TermuxPackages.parsePackages(
            """
            Package: openjdk-17
            Version: 1
            Depends: zlib
            Filename: pool/o.deb
            Size: 1

            Package: aapt2
            Version: 1
            Depends: fmt
            Filename: pool/a2.deb
            Size: 1

            Package: aapt
            Version: 1
            Depends: fmt
            Filename: pool/a.deb
            Size: 1

            Package: zlib
            Version: 1
            Filename: pool/z.deb
            Size: 1

            Package: fmt
            Version: 1
            Filename: pool/f.deb
            Size: 1
            """.trimIndent()
        )
        val closure = TermuxPackages.resolveClosure(index)
        val names = closure.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("openjdk-17", "aapt2", "aapt", "zlib", "fmt")))
    }
}
