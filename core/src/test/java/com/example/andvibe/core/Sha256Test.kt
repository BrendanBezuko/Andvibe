package com.example.andvibe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Sha256Test {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun knownDigest() {
        val f = tmp.newFile("x.txt")
        f.writeText("abc")
        // echo -n abc | shasum -a 256
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex(f),
        )
        assertTrue(Sha256.matches(f, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
    }
}
