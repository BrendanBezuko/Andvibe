package com.example.andvibe.core

import java.io.File

/**
 * Android permissions from source manifests (Understand) or a built APK's merged manifest
 * (Build), with a risk level and a plain-language note for the ones users get asked about.
 */
object AndroidPermissions {
    enum class Risk(val label: String) {
        DANGEROUS("asks the user at runtime"),
        SPECIAL("special access, granted in system settings"),
        NORMAL("granted at install"),
    }

    data class Permission(val name: String, val maxSdk: Int? = null) {
        val short: String get() = name.removePrefix("android.permission.")
        val risk: Risk get() = riskOf(name)
    }

    private val dangerous = mapOf(
        "CAMERA" to "take photos and video",
        "RECORD_AUDIO" to "record audio with the microphone",
        "ACCESS_FINE_LOCATION" to "precise location",
        "ACCESS_COARSE_LOCATION" to "approximate location",
        "ACCESS_BACKGROUND_LOCATION" to "location while the app is in the background",
        "ACCESS_MEDIA_LOCATION" to "locations stored in your photos",
        "READ_CONTACTS" to "read contacts",
        "WRITE_CONTACTS" to "change contacts",
        "GET_ACCOUNTS" to "list accounts on the device",
        "READ_CALENDAR" to "read calendar events",
        "WRITE_CALENDAR" to "add or change calendar events",
        "READ_PHONE_STATE" to "phone status and identity",
        "READ_PHONE_NUMBERS" to "read phone numbers",
        "CALL_PHONE" to "place phone calls",
        "ANSWER_PHONE_CALLS" to "answer phone calls",
        "READ_CALL_LOG" to "read call history",
        "WRITE_CALL_LOG" to "change call history",
        "ADD_VOICEMAIL" to "add voicemail",
        "USE_SIP" to "make internet (SIP) calls",
        "PROCESS_OUTGOING_CALLS" to "see and redirect outgoing calls",
        "SEND_SMS" to "send text messages (may cost money)",
        "RECEIVE_SMS" to "receive text messages",
        "READ_SMS" to "read text messages",
        "RECEIVE_MMS" to "receive picture messages",
        "RECEIVE_WAP_PUSH" to "receive WAP push messages",
        "READ_EXTERNAL_STORAGE" to "read shared storage",
        "WRITE_EXTERNAL_STORAGE" to "write shared storage",
        "READ_MEDIA_IMAGES" to "read photos",
        "READ_MEDIA_VIDEO" to "read videos",
        "READ_MEDIA_AUDIO" to "read music and audio",
        "READ_MEDIA_VISUAL_USER_SELECTED" to "read photos and videos the user picks",
        "BODY_SENSORS" to "body sensors such as heart rate",
        "BODY_SENSORS_BACKGROUND" to "body sensors in the background",
        "ACTIVITY_RECOGNITION" to "physical activity (steps, walking, driving)",
        "POST_NOTIFICATIONS" to "show notifications",
        "NEARBY_WIFI_DEVICES" to "find nearby Wi-Fi devices",
        "BLUETOOTH_SCAN" to "find nearby Bluetooth devices",
        "BLUETOOTH_CONNECT" to "connect to paired Bluetooth devices",
        "BLUETOOTH_ADVERTISE" to "advertise to nearby Bluetooth devices",
        "UWB_RANGING" to "measure distance to nearby ultra-wideband devices",
    )

    private val special = mapOf(
        "SYSTEM_ALERT_WINDOW" to "draw over other apps",
        "WRITE_SETTINGS" to "change system settings",
        "MANAGE_EXTERNAL_STORAGE" to "read and write all files",
        "REQUEST_INSTALL_PACKAGES" to "install other apps",
        "REQUEST_DELETE_PACKAGES" to "ask to uninstall apps",
        "QUERY_ALL_PACKAGES" to "see every installed app (Play restricts this)",
        "PACKAGE_USAGE_STATS" to "see app usage history",
        "SCHEDULE_EXACT_ALARM" to "schedule exact alarms",
        "USE_FULL_SCREEN_INTENT" to "full-screen alerts over the lock screen",
        "BIND_ACCESSIBILITY_SERVICE" to "accessibility service: can read and control the screen",
        "BIND_DEVICE_ADMIN" to "device administrator",
        "BIND_NOTIFICATION_LISTENER_SERVICE" to "read all notifications",
        "BIND_VPN_SERVICE" to "act as a VPN",
        "ACCESS_NOTIFICATION_POLICY" to "change Do Not Disturb",
        "READ_PRIVILEGED_PHONE_STATE" to "privileged phone identifiers",
    )

    fun riskOf(name: String): Risk {
        val short = name.removePrefix("android.permission.")
        if (!name.startsWith("android.permission.") && name.contains('.')) return Risk.NORMAL
        return when (short) {
            in dangerous -> Risk.DANGEROUS
            in special -> Risk.SPECIAL
            else -> Risk.NORMAL
        }
    }

    fun describe(name: String): String? {
        val short = name.removePrefix("android.permission.")
        return dangerous[short] ?: special[short]
    }

    private val comment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val usesPermission = Regex("""<uses-permission(?:-sdk-23|-sdk-m)?\b([^>]*)>""")
    private val nameAttr = Regex("""android:name\s*=\s*"([^"]+)"""")
    private val maxSdkAttr = Regex("""android:maxSdkVersion\s*=\s*"(\d+)"""")
    private val removeNode = Regex("""tools:node\s*=\s*"remove"""")

    /** Permissions requested by a source AndroidManifest.xml (text, not the binary APK form). */
    fun parseManifest(xml: String): List<Permission> =
        usesPermission.findAll(comment.replace(xml, "")).mapNotNull { m ->
            val attrs = m.groupValues[1]
            // tools:node="remove" strips a permission a library would merge in: it's the opposite of a request.
            if (removeNode.containsMatchIn(attrs)) return@mapNotNull null
            val name = nameAttr.find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            Permission(name, maxSdkAttr.find(attrs)?.groupValues?.get(1)?.toIntOrNull())
        }.distinctBy { it.name }.toList()

    private val skipDirs = setOf("build", ".gradle", ".git", "node_modules", ".idea", ".cxx", "intermediates")

    /** Source manifests under [root] (any module/source set), relative path → permissions. */
    fun fromSource(root: File): List<Pair<String, List<Permission>>> {
        val out = mutableListOf<Pair<String, List<Permission>>>()
        root.walkTopDown()
            .onEnter { it == root || it.name !in skipDirs }
            .maxDepth(8)
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .sortedBy { it.path }
            .forEach { file ->
                val perms = runCatching { parseManifest(file.readText()) }.getOrDefault(emptyList())
                out += RepoFiles.rel(file, root) to perms
            }
        return out
    }

    fun sorted(perms: List<Permission>): List<Permission> =
        perms.sortedWith(compareBy<Permission> { it.risk.ordinal }.thenBy { it.short })

    /** One line for logs and notifications. */
    fun summary(perms: List<Permission>): String {
        if (perms.isEmpty()) return "no permissions"
        val risky = sorted(perms).filter { it.risk != Risk.NORMAL }.map { it.short }
        val head = "${perms.size} permission${if (perms.size == 1) "" else "s"}"
        return if (risky.isEmpty()) "$head, none sensitive" else "$head · sensitive: ${risky.joinToString(", ")}"
    }

    /** Plain lines for the Build log. */
    fun lines(perms: List<Permission>): List<String> = sorted(perms).map { p ->
        val tag = when (p.risk) {
            Risk.DANGEROUS -> "[runtime]"
            Risk.SPECIAL -> "[special]"
            Risk.NORMAL -> "[normal] "
        }
        val note = describe(p.name)?.let { " — $it" }.orEmpty()
        val max = p.maxSdk?.let { " (up to API $it)" }.orEmpty()
        "  $tag ${p.short}$max$note"
    }

    /** Markdown section for the Understand doc. */
    fun markdown(manifests: List<Pair<String, List<Permission>>>): String = buildString {
        append("## Android permissions\n\n")
        if (manifests.isEmpty()) {
            append("No AndroidManifest.xml found in this repo.\n")
            return@buildString
        }
        append("From the source manifests. The built APK can request more: libraries merge their own in. ")
        append("Build the app to see the merged list in the Build log.\n")
        for ((path, perms) in manifests) {
            append("\n### `").append(path).append("`\n\n")
            if (perms.isEmpty()) {
                append("No permissions requested.\n")
                continue
            }
            for (p in sorted(perms)) {
                append("- `").append(p.short).append('`')
                if (p.risk != Risk.NORMAL) append(" — **").append(p.risk.label).append("**")
                describe(p.name)?.let { append(": ").append(it) }
                p.maxSdk?.let { append(" (up to API ").append(it).append(')') }
                append('\n')
            }
        }
    }
}
