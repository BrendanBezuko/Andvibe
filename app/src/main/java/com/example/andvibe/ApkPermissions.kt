package com.example.andvibe

import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.android.apksig.util.DataSources
import com.example.andvibe.core.AndroidPermissions
import java.io.File
import java.io.RandomAccessFile

/** Permissions in a built APK's merged (binary) manifest, read with apksig. */
object ApkPermissions {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val ATTR_NAME = 0x01010003
    private const val ATTR_MAX_SDK = 0x01010271

    fun read(apk: File): List<AndroidPermissions.Permission> {
        RandomAccessFile(apk, "r").use { raf ->
            val manifest = ApkUtils.getAndroidManifest(DataSources.asDataSource(raf))
            val parser = AndroidBinXmlParser(manifest)
            val out = mutableListOf<AndroidPermissions.Permission>()
            var event = parser.eventType
            while (event != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
                if (event == AndroidBinXmlParser.EVENT_START_ELEMENT && parser.depth == 2 &&
                    parser.name.startsWith("uses-permission")
                ) {
                    var name: String? = null
                    var maxSdk: Int? = null
                    for (i in 0 until parser.attributeCount) {
                        // aapt2 may strip attribute names and keep only resource ids, so match both.
                        val id = parser.getAttributeNameResourceId(i)
                        val named = parser.getAttributeNamespace(i) == ANDROID_NS
                        when {
                            id == ATTR_NAME || (named && parser.getAttributeName(i) == "name") ->
                                name = parser.getAttributeStringValue(i)
                            id == ATTR_MAX_SDK || (named && parser.getAttributeName(i) == "maxSdkVersion") ->
                                maxSdk = runCatching { parser.getAttributeIntValue(i) }.getOrNull()
                        }
                    }
                    if (name != null) out += AndroidPermissions.Permission(name, maxSdk)
                }
                event = parser.next()
            }
            return out.distinctBy { it.name }
        }
    }

    /** Never throws: a manifest we can't read shouldn't fail the build report. */
    fun readOrNull(apk: File): List<AndroidPermissions.Permission>? = runCatching { read(apk) }.getOrNull()
}
