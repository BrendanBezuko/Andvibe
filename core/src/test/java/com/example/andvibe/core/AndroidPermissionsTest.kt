package com.example.andvibe.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidPermissionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val manifest = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:tools="http://schemas.android.com/tools">
            <uses-permission android:name="android.permission.INTERNET" />
            <uses-permission
                android:name="android.permission.CAMERA" />
            <!-- <uses-permission android:name="android.permission.READ_SMS" /> -->
            <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />
            <uses-permission-sdk-23 android:name="android.permission.ACCESS_FINE_LOCATION" />
            <uses-permission android:name="android.permission.READ_PHONE_STATE" tools:node="remove" />
            <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
            <uses-permission android:name="com.example.app.permission.C2D_MESSAGE" />
            <uses-permission android:name="android.permission.INTERNET" />
        </manifest>
    """.trimIndent()

    @Test
    fun parsesRequestsSkippingCommentsRemovalsAndDuplicates() {
        val perms = AndroidPermissions.parseManifest(manifest)
        assertEquals(
            listOf("INTERNET", "CAMERA", "WRITE_EXTERNAL_STORAGE", "ACCESS_FINE_LOCATION", "SYSTEM_ALERT_WINDOW", "com.example.app.permission.C2D_MESSAGE"),
            perms.map { it.short },
        )
        assertEquals(28, perms.first { it.short == "WRITE_EXTERNAL_STORAGE" }.maxSdk)
    }

    @Test
    fun classifiesRisk() {
        assertEquals(AndroidPermissions.Risk.DANGEROUS, AndroidPermissions.riskOf("android.permission.CAMERA"))
        assertEquals(AndroidPermissions.Risk.SPECIAL, AndroidPermissions.riskOf("android.permission.SYSTEM_ALERT_WINDOW"))
        assertEquals(AndroidPermissions.Risk.NORMAL, AndroidPermissions.riskOf("android.permission.INTERNET"))
        // An app-defined permission named like a platform one is still not the platform one.
        assertEquals(AndroidPermissions.Risk.NORMAL, AndroidPermissions.riskOf("com.evil.CAMERA"))
    }

    @Test
    fun summaryAndLinesPutSensitiveFirst() {
        val perms = AndroidPermissions.parseManifest(manifest)
        assertEquals(
            "6 permissions · sensitive: ACCESS_FINE_LOCATION, CAMERA, WRITE_EXTERNAL_STORAGE, SYSTEM_ALERT_WINDOW",
            AndroidPermissions.summary(perms),
        )
        val lines = AndroidPermissions.lines(perms)
        assertEquals("  [runtime] ACCESS_FINE_LOCATION — precise location", lines.first())
        assertTrue(lines.any { it == "  [runtime] WRITE_EXTERNAL_STORAGE (up to API 28) — write shared storage" })
        assertEquals("no permissions", AndroidPermissions.summary(emptyList()))
    }

    @Test
    fun fromSourceFindsModuleManifestsAndSkipsBuildOutputs() {
        val root = tmp.newFolder("app")
        File(root, "app/src/main").mkdirs()
        File(root, "app/src/main/AndroidManifest.xml").writeText(manifest)
        File(root, "lib/src/main").mkdirs()
        File(root, "lib/src/main/AndroidManifest.xml").writeText("<manifest/>")
        File(root, "app/build/intermediates/merged").mkdirs()
        File(root, "app/build/intermediates/merged/AndroidManifest.xml").writeText(manifest)

        val found = AndroidPermissions.fromSource(root)
        assertEquals(listOf("app/src/main/AndroidManifest.xml", "lib/src/main/AndroidManifest.xml"), found.map { it.first })
        val md = AndroidPermissions.markdown(found)
        assertTrue(md.startsWith("## Android permissions"))
        assertTrue(md.contains("- `CAMERA` — **asks the user at runtime**: take photos and video"))
        assertTrue(md.contains("### `lib/src/main/AndroidManifest.xml`\n\nNo permissions requested."))
    }
}
