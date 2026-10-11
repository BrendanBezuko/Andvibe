package com.example.andvibe

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CustomServer(
    val id: String,
    val name: String,
    val key: String = "",
    val model: String = "",
    val base: String = "",
    val style: ApiStyle = ApiStyle.OPENAI,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("key", key)
        .put("model", model)
        .put("base", base)
        .put("style", style.id)

    companion object {
        fun new(
            name: String = "Custom",
            key: String = "",
            model: String = "",
            base: String = "",
            style: ApiStyle = ApiStyle.OPENAI,
        ): CustomServer = CustomServer(
            id = UUID.randomUUID().toString(),
            name = name,
            key = key,
            model = model,
            base = base,
            style = style,
        )

        fun fromJson(obj: JSONObject): CustomServer = CustomServer(
            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = obj.optString("name").ifBlank { "Custom" },
            key = obj.optString("key"),
            model = obj.optString("model"),
            base = obj.optString("base"),
            style = ApiStyle.fromId(obj.optString("style")),
        )

        fun listFromJson(raw: String?): List<CustomServer> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        add(fromJson(obj))
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun listToJson(servers: List<CustomServer>): String {
            val arr = JSONArray()
            for (server in servers) arr.put(server.toJson())
            return arr.toString()
        }
    }
}
