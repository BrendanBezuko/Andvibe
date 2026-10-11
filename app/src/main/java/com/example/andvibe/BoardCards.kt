package com.example.andvibe

/** What a Board card is. Orthogonal to [CardStatus]: a bug stays a bug as it moves. */
enum class CardType(val id: String, val label: String) {
    FEATURE("feature", "Feature"),
    BUG("bug", "Bug"),
    SOLUTION("solution", "Solution");

    companion object {
        /** Lenient: ids, labels, and common aliases. Null if unknown. */
        fun parse(raw: String?): CardType? {
            val key = raw.orEmpty().trim().lowercase()
            return entries.firstOrNull { it.id == key || it.label.lowercase() == key }
                ?: when (key) {
                    "idea", "ideas", "features", "story", "enhancement" -> FEATURE
                    "bugs", "defect" -> BUG
                    "solutions", "fix", "approach" -> SOLUTION
                    else -> null
                }
        }

        fun from(raw: String?): CardType = parse(raw) ?: FEATURE
    }
}

/** Where a Board card is; one column per status, left to right. */
enum class CardStatus(val id: String, val label: String) {
    BACK_BURNER("back_burner", "Back burner"),
    TODO("todo", "To do"),
    IN_PROGRESS("in_progress", "In progress"),
    HUMAN_TESTING("human_testing", "Human testing"),
    DONE("done", "Done");

    companion object {
        /** Lenient: accepts ids, labels, and common GitHub Project / kanban spellings. Null if unknown. */
        fun parse(raw: String?): CardStatus? {
            val key = raw.orEmpty().trim().lowercase().replace(Regex("[\\s_-]+"), " ")
            if (key.isEmpty()) return null
            return when (key) {
                "back burner", "backburner", "icebox", "later", "someday", "parked", "on hold" -> BACK_BURNER
                "todo", "to do", "backlog", "ready", "new", "open" -> TODO
                "in progress", "doing", "wip", "started", "building" -> IN_PROGRESS
                "human testing", "testing", "in testing", "qa", "review", "in review", "verify" -> HUMAN_TESTING
                "done", "complete", "completed", "closed", "shipped" -> DONE
                else -> null
            }
        }

        fun from(raw: String?): CardStatus = parse(raw) ?: TODO
    }
}

/** A GitHub issue raised from a card. */
data class CardIssue(val number: Int, val url: String, val nodeId: String)

/**
 * Link to the card's GitHub Project item.
 * [stamp] is the remote updatedAt seen at the last sync; [syncedAt] is the local clock then.
 */
data class CardRemote(
    val itemId: String,
    val contentId: String,
    val stamp: String,
    val syncedAt: Long,
)

/** A GitHub Projects (v2) board: github.com/users|orgs/{owner}/projects/{number}. */
data class ProjectLink(val owner: String, val org: Boolean, val number: Int) {
    val url: String get() = "https://github.com/${if (org) "orgs" else "users"}/$owner/projects/$number"

    companion object {
        private val pattern = Regex("""(?i)(?:https?://)?(?:www\.)?github\.com/(users|orgs)/([A-Za-z0-9-]+)/projects/(\d+)""")

        fun parse(raw: String): ProjectLink? {
            val m = pattern.find(raw.trim()) ?: return null
            val number = m.groupValues[3].toIntOrNull() ?: return null
            return ProjectLink(m.groupValues[2], m.groupValues[1].equals("orgs", true), number)
        }
    }
}
