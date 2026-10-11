package com.example.andvibe.github

import com.example.andvibe.CardIssue
import com.example.andvibe.ProjectLink
import org.json.JSONArray
import org.json.JSONObject

/** GitHub Projects (v2) operations the Board sync needs. */
class GitHubProjects(private val api: GitHubApi) {
    data class Option(val id: String, val name: String)

    data class SelectField(val id: String, val name: String, val options: List<Option>)

    data class Project(
        val id: String,
        val title: String,
        val status: SelectField?,
        val type: SelectField?,
    )

    /** A project item whose content is a draft issue or a real issue (PRs and redacted items are skipped). */
    data class Item(
        val itemId: String,
        val contentId: String,
        val isIssue: Boolean,
        val title: String,
        val body: String,
        /** Option id per single-select field id. */
        val options: Map<String, String>,
        val itemUpdatedAt: String,
        val contentUpdatedAt: String,
        val issue: CardIssue?,
        val closed: Boolean,
    ) {
        /** Changes whenever the item's fields or its title/body change. */
        val stamp: String get() = "$itemUpdatedAt|$contentUpdatedAt"
    }

    fun project(link: ProjectLink): Project {
        val ownerField = if (link.org) "organization" else "user"
        val data = api.graphql(
            """
            query(${'$'}login: String!, ${'$'}number: Int!) {
              owner: $ownerField(login: ${'$'}login) {
                projectV2(number: ${'$'}number) {
                  id title
                  fields(first: 50) { nodes { ... on ProjectV2SingleSelectField { id name options { id name } } } }
                }
              }
            }
            """.trimIndent(),
            JSONObject().put("login", link.owner).put("number", link.number),
        )
        val project = data.optJSONObject("owner")?.optJSONObject("projectV2")
            ?: error("Project not found: ${link.url}")
        val fields = selectFields(project.optJSONObject("fields")?.optJSONArray("nodes"))
        return Project(
            id = project.getString("id"),
            title = project.optString("title"),
            status = fields.firstOrNull { it.name.equals("Status", ignoreCase = true) },
            type = fields.firstOrNull { it.name.equals(TYPE_FIELD, ignoreCase = true) },
        )
    }

    /** Adds a "Type" single-select field (Feature/Bug/Solution) when the project has none. */
    fun ensureTypeField(project: Project): Project {
        if (project.type != null) return project
        val options = JSONArray()
        for ((name, color) in listOf("Feature" to "BLUE", "Bug" to "RED", "Solution" to "GREEN")) {
            options.put(JSONObject().put("name", name).put("color", color).put("description", ""))
        }
        val data = api.graphql(
            """
            mutation(${'$'}project: ID!, ${'$'}options: [ProjectV2SingleSelectFieldOptionInput!]) {
              createProjectV2Field(input: {projectId: ${'$'}project, dataType: SINGLE_SELECT, name: "$TYPE_FIELD", singleSelectOptions: ${'$'}options}) {
                projectV2Field { ... on ProjectV2SingleSelectField { id name options { id name } } }
              }
            }
            """.trimIndent(),
            JSONObject().put("project", project.id).put("options", options),
        )
        val field = data.optJSONObject("createProjectV2Field")?.optJSONObject("projectV2Field")
        return project.copy(type = field?.let { selectField(it) })
    }

    fun items(project: Project): List<Item> {
        val out = mutableListOf<Item>()
        var cursor: String? = null
        do {
            val vars = JSONObject().put("project", project.id)
            if (cursor != null) vars.put("after", cursor)
            val data = api.graphql(ITEMS_QUERY, vars)
            val items = data.optJSONObject("node")?.optJSONObject("items") ?: break
            val nodes = items.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until nodes.length()) {
                parseItem(nodes.optJSONObject(i) ?: continue)?.let { out.add(it) }
            }
            val page = items.optJSONObject("pageInfo")
            cursor = if (page?.optBoolean("hasNextPage") == true) page.optString("endCursor") else null
        } while (cursor != null && out.size < MAX_ITEMS)
        return out
    }

    /** Returns (itemId, draftIssueId). */
    fun createDraft(projectId: String, title: String, body: String): Pair<String, String> {
        val data = api.graphql(
            """
            mutation(${'$'}project: ID!, ${'$'}title: String!, ${'$'}body: String) {
              addProjectV2DraftIssue(input: {projectId: ${'$'}project, title: ${'$'}title, body: ${'$'}body}) {
                projectItem { id content { ... on DraftIssue { id } } }
              }
            }
            """.trimIndent(),
            JSONObject().put("project", projectId).put("title", title).put("body", body),
        )
        val item = data.getJSONObject("addProjectV2DraftIssue").getJSONObject("projectItem")
        return item.getString("id") to item.optJSONObject("content")?.optString("id").orEmpty()
    }

    fun updateDraft(draftId: String, title: String, body: String) {
        api.graphql(
            """
            mutation(${'$'}id: ID!, ${'$'}title: String!, ${'$'}body: String) {
              updateProjectV2DraftIssue(input: {draftIssueId: ${'$'}id, title: ${'$'}title, body: ${'$'}body}) { draftIssue { id } }
            }
            """.trimIndent(),
            JSONObject().put("id", draftId).put("title", title).put("body", body),
        )
    }

    fun updateIssue(issueId: String, title: String, body: String) {
        api.graphql(
            """
            mutation(${'$'}id: ID!, ${'$'}title: String!, ${'$'}body: String) {
              updateIssue(input: {id: ${'$'}id, title: ${'$'}title, body: ${'$'}body}) { issue { id } }
            }
            """.trimIndent(),
            JSONObject().put("id", issueId).put("title", title).put("body", body),
        )
    }

    fun setOption(projectId: String, itemId: String, fieldId: String, optionId: String) {
        api.graphql(
            """
            mutation(${'$'}project: ID!, ${'$'}item: ID!, ${'$'}field: ID!, ${'$'}option: String!) {
              updateProjectV2ItemFieldValue(input: {projectId: ${'$'}project, itemId: ${'$'}item, fieldId: ${'$'}field, value: {singleSelectOptionId: ${'$'}option}}) {
                projectV2Item { id }
              }
            }
            """.trimIndent(),
            JSONObject().put("project", projectId).put("item", itemId).put("field", fieldId).put("option", optionId),
        )
    }

    fun deleteItem(projectId: String, itemId: String) {
        api.graphql(
            """
            mutation(${'$'}project: ID!, ${'$'}item: ID!) {
              deleteProjectV2Item(input: {projectId: ${'$'}project, itemId: ${'$'}item}) { deletedItemId }
            }
            """.trimIndent(),
            JSONObject().put("project", projectId).put("item", itemId),
        )
    }

    /** Adds an existing issue to the project; returns the new item id. */
    fun addContent(projectId: String, contentId: String): String {
        val data = api.graphql(
            """
            mutation(${'$'}project: ID!, ${'$'}content: ID!) {
              addProjectV2ItemById(input: {projectId: ${'$'}project, contentId: ${'$'}content}) { item { id } }
            }
            """.trimIndent(),
            JSONObject().put("project", projectId).put("content", contentId),
        )
        return data.getJSONObject("addProjectV2ItemById").getJSONObject("item").getString("id")
    }

    fun repositoryId(slug: String): String {
        val (owner, name) = splitSlug(slug)
        val data = api.graphql(
            "query(${'$'}owner: String!, ${'$'}name: String!) { repository(owner: ${'$'}owner, name: ${'$'}name) { id } }",
            JSONObject().put("owner", owner).put("name", name),
        )
        return data.optJSONObject("repository")?.optString("id")?.takeIf { it.isNotBlank() }
            ?: error("Repository not found: $slug")
    }

    /** Turns a draft item into a real issue in the repo; the project item id stays the same. */
    fun convertDraft(itemId: String, repositoryId: String): CardIssue {
        val data = api.graphql(
            """
            mutation(${'$'}item: ID!, ${'$'}repo: ID!) {
              convertProjectV2DraftIssueItemToIssue(input: {itemId: ${'$'}item, repositoryId: ${'$'}repo}) {
                item { id content { ... on Issue { id number url } } }
              }
            }
            """.trimIndent(),
            JSONObject().put("item", itemId).put("repo", repositoryId),
        )
        val content = data.getJSONObject("convertProjectV2DraftIssueItemToIssue")
            .getJSONObject("item").getJSONObject("content")
        return CardIssue(content.getInt("number"), content.getString("url"), content.getString("id"))
    }

    /** Creates an issue with REST. Retries without [labels] if the repo rejects them. */
    fun createIssue(slug: String, title: String, body: String, labels: List<String>): CardIssue {
        splitSlug(slug)
        val payload = JSONObject().put("title", title).put("body", body)
        val json = try {
            api.restPost("/repos/$slug/issues", JSONObject(payload.toString()).put("labels", JSONArray(labels)))
        } catch (e: IllegalStateException) {
            if (labels.isEmpty() || !e.message.orEmpty().startsWith("HTTP 422")) throw e
            api.restPost("/repos/$slug/issues", payload)
        }
        return CardIssue(json.getInt("number"), json.getString("html_url"), json.optString("node_id"))
    }

    companion object {
        const val TYPE_FIELD = "Type"
        private const val MAX_ITEMS = 1000

        private val ITEMS_QUERY = """
            query(${'$'}project: ID!, ${'$'}after: String) {
              node(id: ${'$'}project) {
                ... on ProjectV2 {
                  items(first: 100, after: ${'$'}after) {
                    pageInfo { hasNextPage endCursor }
                    nodes {
                      id updatedAt isArchived
                      fieldValues(first: 20) {
                        nodes {
                          ... on ProjectV2ItemFieldSingleSelectValue {
                            optionId
                            field { ... on ProjectV2SingleSelectField { id } }
                          }
                        }
                      }
                      content {
                        __typename
                        ... on DraftIssue { id title body updatedAt }
                        ... on Issue { id title body updatedAt number url state }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        internal fun parseItem(node: JSONObject): Item? {
            if (node.optBoolean("isArchived")) return null
            val content = node.optJSONObject("content") ?: return null
            val kind = content.optString("__typename")
            if (kind != "DraftIssue" && kind != "Issue") return null
            val options = mutableMapOf<String, String>()
            val values = node.optJSONObject("fieldValues")?.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until values.length()) {
                val v = values.optJSONObject(i) ?: continue
                val fieldId = v.optJSONObject("field")?.optString("id").orEmpty()
                val optionId = v.optString("optionId")
                if (fieldId.isNotBlank() && optionId.isNotBlank()) options[fieldId] = optionId
            }
            val isIssue = kind == "Issue"
            return Item(
                itemId = node.getString("id"),
                contentId = content.optString("id"),
                isIssue = isIssue,
                title = content.optString("title").trim(),
                body = content.optString("body").trim(),
                options = options,
                itemUpdatedAt = node.optString("updatedAt"),
                contentUpdatedAt = content.optString("updatedAt"),
                issue = if (isIssue) {
                    CardIssue(content.optInt("number"), content.optString("url"), content.optString("id"))
                } else {
                    null
                },
                closed = isIssue && content.optString("state") == "CLOSED",
            )
        }

        private fun selectFields(nodes: JSONArray?): List<SelectField> {
            if (nodes == null) return emptyList()
            return (0 until nodes.length()).mapNotNull { i -> nodes.optJSONObject(i)?.let { selectField(it) } }
        }

        private fun selectField(obj: JSONObject): SelectField? {
            val id = obj.optString("id")
            val options = obj.optJSONArray("options") ?: return null
            if (id.isBlank()) return null
            return SelectField(
                id,
                obj.optString("name"),
                (0 until options.length()).mapNotNull { i ->
                    options.optJSONObject(i)?.let { Option(it.optString("id"), it.optString("name")) }
                },
            )
        }

        private fun splitSlug(slug: String): Pair<String, String> {
            val parts = slug.split('/')
            if (parts.size != 2 || parts.any { it.isBlank() }) error("Not a GitHub repo: $slug")
            return parts[0] to parts[1]
        }
    }
}
