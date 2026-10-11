package com.example.andvibe.github

import com.example.andvibe.agent.HttpPost
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-memory GitHub Projects v2 + issues, answering the exact queries GitHubProjects sends.
 * Every write bumps a clock so updatedAt stamps change like the real API.
 */
class FakeGitHub(
    statusOptions: List<String> = listOf("Todo", "In Progress", "Done"),
    withTypeField: Boolean = false,
    /** Simulates GitHub rejecting createProjectV2Field (e.g. reserved name). */
    private val rejectCreateField: String? = null,
) {
    class Item(
        val id: String,
        var contentId: String,
        var isIssue: Boolean,
        var title: String,
        var body: String,
        val options: MutableMap<String, String> = mutableMapOf(),
        var itemUpdated: String,
        var contentUpdated: String,
        var number: Int = 0,
        var closed: Boolean = false,
    )

    private var tick = 0L
    private var seq = 0
    val items = linkedMapOf<String, Item>()
    val calls = mutableListOf<String>()
    val statusField = Field("F_status", "Status", statusOptions.map { "O_${it.replace(' ', '_')}" to it })
    var typeField: Field? = if (withTypeField) typeField() else null

    class Field(val id: String, val name: String, val options: List<Pair<String, String>>) {
        fun option(name: String) = options.first { it.second == name }.first
        fun name(optionId: String?) = options.firstOrNull { it.first == optionId }?.second
    }

    private fun typeField() = Field(
        "F_type",
        GitHubProjects.TYPE_FIELD,
        listOf("O_Feature" to "Feature", "O_Bug" to "Bug", "O_Solution" to "Solution"),
    )

    private fun now(): String = Instant.ofEpochSecond(1_800_000_000L + ++tick).toString()

    private fun id(prefix: String) = "${prefix}_${++seq}"

    val mutations: List<String> get() = calls.filter { it != "project" && it != "items" }

    /** Simulates a change someone made on github.com. */
    fun editOnGitHub(itemId: String, title: String? = null, status: String? = null) {
        val item = items.getValue(itemId)
        if (title != null) {
            item.title = title
            item.contentUpdated = now()
        }
        if (status != null) {
            item.options[statusField.id] = statusField.option(status)
            item.itemUpdated = now()
        }
    }

    fun addDraftOnGitHub(title: String, status: String? = null): String {
        val stamp = now()
        val item = Item(id("PVTI"), id("DI"), false, title, "", itemUpdated = stamp, contentUpdated = stamp)
        if (status != null) item.options[statusField.id] = statusField.option(status)
        items[item.id] = item
        return item.id
    }

    fun statusOf(itemId: String): String? = statusField.name(items.getValue(itemId).options[statusField.id])

    fun typeOf(itemId: String): String? = typeField?.name(items.getValue(itemId).options[typeField!!.id])

    val http = HttpPost { url, _, body ->
        val json = JSONObject(body)
        if (url.startsWith("https://api.github.com/repos/")) return@HttpPost restIssue(url, json)
        val query = json.getString("query")
        val v = json.optJSONObject("variables") ?: JSONObject()
        val data = when {
            "projectV2(number" in query -> { calls += "project"; projectData() }
            "createProjectV2Field" in query -> {
                calls += "createField"
                rejectCreateField?.let { error(it) }
                createTypeField()
            }
            "items(first: 100" in query -> { calls += "items"; itemsData() }
            "addProjectV2DraftIssue" in query -> { calls += "createDraft"; createDraft(v) }
            "updateProjectV2DraftIssue" in query -> { calls += "updateDraft"; updateContent(v, issue = false) }
            "updateIssue" in query -> { calls += "updateIssue"; updateContent(v, issue = true) }
            "updateProjectV2ItemFieldValue" in query -> { calls += "setOption"; setOption(v) }
            "deleteProjectV2Item" in query -> { calls += "delete"; items.remove(v.getString("item")); JSONObject().put("deleteProjectV2Item", JSONObject()) }
            "addProjectV2ItemById" in query -> { calls += "addContent"; addContent(v) }
            "repository(owner" in query -> { calls += "repo"; JSONObject().put("repository", JSONObject().put("id", "R_1")) }
            "convertProjectV2DraftIssueItemToIssue" in query -> { calls += "convert"; convert(v) }
            else -> error("unexpected query: $query")
        }
        JSONObject().put("data", data).toString()
    }

    private fun fieldJson(f: Field) = JSONObject().put("id", f.id).put("name", f.name).put(
        "options",
        JSONArray().apply { f.options.forEach { put(JSONObject().put("id", it.first).put("name", it.second)) } },
    )

    private fun projectData(): JSONObject {
        val fields = JSONArray().put(JSONObject()) // a non-select field: empty fragment
        fields.put(fieldJson(statusField))
        typeField?.let { fields.put(fieldJson(it)) }
        val project = JSONObject().put("id", "PVT_1").put("title", "Fake Board")
            .put("fields", JSONObject().put("nodes", fields))
        return JSONObject().put("owner", JSONObject().put("projectV2", project))
    }

    private fun createTypeField(): JSONObject {
        val f = typeField()
        typeField = f
        return JSONObject().put("createProjectV2Field", JSONObject().put("projectV2Field", fieldJson(f)))
    }

    private fun itemsData(): JSONObject {
        val nodes = JSONArray()
        for (item in items.values) {
            val values = JSONArray()
            for ((field, option) in item.options) {
                values.put(JSONObject().put("optionId", option).put("field", JSONObject().put("id", field)))
            }
            val content = JSONObject()
                .put("__typename", if (item.isIssue) "Issue" else "DraftIssue")
                .put("id", item.contentId).put("title", item.title).put("body", item.body)
                .put("updatedAt", item.contentUpdated)
            if (item.isIssue) {
                content.put("number", item.number).put("url", "https://github.com/o/r/issues/${item.number}")
                    .put("state", if (item.closed) "CLOSED" else "OPEN")
            }
            nodes.put(
                JSONObject().put("id", item.id).put("updatedAt", item.itemUpdated).put("isArchived", false)
                    .put("fieldValues", JSONObject().put("nodes", values)).put("content", content)
            )
        }
        val page = JSONObject().put("hasNextPage", false).put("endCursor", JSONObject.NULL)
        return JSONObject().put("node", JSONObject().put("items", JSONObject().put("pageInfo", page).put("nodes", nodes)))
    }

    private fun createDraft(v: JSONObject): JSONObject {
        val stamp = now()
        val item = Item(id("PVTI"), id("DI"), false, v.getString("title"), v.optString("body"), itemUpdated = stamp, contentUpdated = stamp)
        items[item.id] = item
        return JSONObject().put(
            "addProjectV2DraftIssue",
            JSONObject().put("projectItem", JSONObject().put("id", item.id).put("content", JSONObject().put("id", item.contentId))),
        )
    }

    private fun updateContent(v: JSONObject, issue: Boolean): JSONObject {
        val item = items.values.first { it.contentId == v.getString("id") }
        check(item.isIssue == issue) { "wrong mutation for content kind" }
        item.title = v.getString("title")
        item.body = v.optString("body")
        item.contentUpdated = now()
        return JSONObject()
    }

    private fun setOption(v: JSONObject): JSONObject {
        val item = items.getValue(v.getString("item"))
        item.options[v.getString("field")] = v.getString("option")
        item.itemUpdated = now()
        return JSONObject()
    }

    private fun addContent(v: JSONObject): JSONObject {
        val stamp = now()
        val contentId = v.getString("content")
        val item = Item(id("PVTI"), contentId, true, "issue $contentId", "", itemUpdated = stamp, contentUpdated = stamp, number = 99)
        items[item.id] = item
        return JSONObject().put("addProjectV2ItemById", JSONObject().put("item", JSONObject().put("id", item.id)))
    }

    private fun convert(v: JSONObject): JSONObject {
        val item = items.getValue(v.getString("item"))
        item.isIssue = true
        item.contentId = id("I")
        item.number = 7
        item.contentUpdated = now()
        val content = JSONObject().put("id", item.contentId).put("number", 7).put("url", "https://github.com/o/r/issues/7")
        return JSONObject().put(
            "convertProjectV2DraftIssueItemToIssue",
            JSONObject().put("item", JSONObject().put("id", item.id).put("content", content)),
        )
    }

    var rejectLabels = false

    private fun restIssue(url: String, body: JSONObject): String {
        calls += "restIssue"
        if (rejectLabels && body.has("labels")) error("HTTP 422: Validation Failed")
        lastIssueRequest = body
        return JSONObject().put("number", 5).put("html_url", url.replace("api.github.com/repos", "github.com") + "/5")
            .put("node_id", "I_rest").toString()
    }

    var lastIssueRequest: JSONObject? = null
}
