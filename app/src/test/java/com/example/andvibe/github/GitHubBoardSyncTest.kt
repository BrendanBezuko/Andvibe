package com.example.andvibe.github

import com.example.andvibe.CardStatus
import com.example.andvibe.CardType
import com.example.andvibe.ProjectLink
import com.example.andvibe.WorkspaceStore.Card
import com.example.andvibe.features.board.BoardSync
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Board sync and issue raising end to end against [FakeGitHub]. */
class GitHubBoardSyncTest {
    private val link = ProjectLink("me", org = false, number = 1)
    /** Local clock, later than every FakeGitHub timestamp, as on a real phone editing after GitHub. */
    private var clock = 1_900_000_000_000L
    private var ids = 0

    private fun setup(fake: FakeGitHub): Pair<GitHubProjects, BoardSync.Runner> {
        val gh = GitHubProjects(GitHubApi({ "tok" }, fake.http))
        return gh to BoardSync.Runner(gh) { "new${++ids}" }
    }

    private fun sync(runner: BoardSync.Runner, cards: List<Card>, deleted: List<String> = emptyList()): BoardSync.Outcome {
        clock += 1_000
        return runner.run(link, cards, deleted, clock)
    }

    private fun card(id: String, type: CardType, status: CardStatus, title: String) =
        Card(id, type, status, title, "note $id", updatedAt = clock)

    @Test
    fun firstSyncPushesEverythingThenSecondSyncIsANoOp() {
        val fake = FakeGitHub()
        val (_, runner) = setup(fake)
        val first = sync(
            runner,
            listOf(
                card("a", CardType.BUG, CardStatus.IN_PROGRESS, "Crash"),
                card("b", CardType.FEATURE, CardStatus.HUMAN_TESTING, "Search"),
            ),
        )
        assertTrue("creates the missing Card type field", "createField" in fake.calls)
        assertEquals(2, fake.items.size)
        val crash = fake.items.values.first { it.title == "Crash" }
        assertEquals("note a", crash.body)
        assertEquals("In Progress", fake.statusOf(crash.id))
        assertEquals("Bug", fake.typeOf(crash.id))
        val search = fake.items.values.first { it.title == "Search" }
        assertNull("no Human testing option on GitHub", fake.statusOf(search.id))
        assertTrue(first.report.warnings.single().contains("Back burner, Human testing"))
        assertTrue(first.cards.all { it.remote != null && it.remote!!.stamp.isNotBlank() })

        fake.calls.clear()
        val second = sync(runner, first.cards)
        assertTrue("no writes: ${fake.mutations}", fake.mutations.isEmpty())
        assertEquals(first.cards.map { it.title to it.remote!!.stamp }, second.cards.map { it.title to it.remote!!.stamp })
        assertTrue(second.report.summary("x").contains("up to date"))
    }

    @Test
    fun remoteEditsArePulledAndLocalEditsArePushed() {
        val fake = FakeGitHub(statusOptions = listOf("Back burner", "Todo", "In Progress", "Human testing", "Done"))
        val (_, runner) = setup(fake)
        var cards = sync(runner, listOf(card("a", CardType.FEATURE, CardStatus.TODO, "Proxy"), card("b", CardType.BUG, CardStatus.TODO, "Login"))).cards
        val proxyItem = cards.first { it.id == "a" }.remote!!.itemId
        val loginItem = cards.first { it.id == "b" }.remote!!.itemId

        fake.editOnGitHub(proxyItem, title = "Cloud proxy", status = "In Progress")
        cards = cards.map { if (it.id == "b") it.copy(status = CardStatus.HUMAN_TESTING, updatedAt = clock + 500) else it }
        fake.calls.clear()
        val out = sync(runner, cards)

        val proxy = out.cards.first { it.id == "a" }
        assertEquals("Cloud proxy", proxy.title)
        assertEquals(CardStatus.IN_PROGRESS, proxy.status)
        assertEquals("Human testing", fake.statusOf(loginItem))
        assertEquals(listOf("setOption"), fake.mutations)
        assertTrue(out.report.warnings.isEmpty())

        fake.calls.clear()
        sync(runner, out.cards)
        assertTrue("stable after round trip: ${fake.mutations}", fake.mutations.isEmpty())
    }

    @Test
    fun importsGitHubItemsAndHonoursDeletesBothWays() {
        val fake = FakeGitHub()
        val (_, runner) = setup(fake)
        val cards = sync(runner, listOf(card("a", CardType.FEATURE, CardStatus.TODO, "Keep"), card("b", CardType.FEATURE, CardStatus.TODO, "Drop locally"))).cards
        val dropItem = cards.first { it.id == "b" }.remote!!.itemId
        val keepItem = cards.first { it.id == "a" }.remote!!.itemId

        val imported = fake.addDraftOnGitHub("From the web", status = "Done")
        fake.items.remove(keepItem) // deleted on github.com
        val out = sync(runner, cards.filter { it.id != "b" }, deleted = listOf(dropItem))

        assertFalse(dropItem in fake.items)
        assertEquals(listOf(dropItem), out.processedDeletes)
        val titles = out.cards.map { it.title }
        assertEquals(listOf("From the web"), titles)
        assertEquals(CardStatus.DONE, out.cards.single().status)
        assertEquals(imported, out.cards.single().remote!!.itemId)
    }

    @Test
    fun raiseIssueConvertsSyncedDraftInPlace() {
        val fake = FakeGitHub(withTypeField = true)
        val (gh, runner) = setup(fake)
        val cards = sync(runner, listOf(card("a", CardType.BUG, CardStatus.TODO, "Crash"))).cards
        val itemId = cards.single().remote!!.itemId

        val issue = gh.convertDraft(itemId, gh.repositoryId("o/r"))
        assertEquals(7, issue.number)
        assertTrue(fake.items.getValue(itemId).isIssue)

        // Later local edits to an issue card go through updateIssue, not the draft mutation.
        val edited = cards.single().copy(issue = issue, title = "Crash on open", updatedAt = clock + 500)
        fake.calls.clear()
        val out = sync(runner, listOf(edited))
        assertTrue("updateIssue" in fake.mutations)
        assertEquals("Crash on open", fake.items.getValue(itemId).title)
        assertEquals(issue, out.cards.single().issue)
    }

    @Test
    fun raisedIssueWithoutProjectIsAddedByIdOnNextSync() {
        val fake = FakeGitHub(withTypeField = true)
        val (gh, runner) = setup(fake)
        val issue = gh.createIssue("o/r", "Crash", "body", listOf("bug"))
        assertEquals(JSONArray().put("bug").toString(), fake.lastIssueRequest!!.getJSONArray("labels").toString())
        assertEquals("https://github.com/o/r/issues/5", issue.url)

        val out = sync(runner, listOf(card("a", CardType.BUG, CardStatus.TODO, "Crash").copy(issue = issue)))
        assertTrue("addContent" in fake.calls)
        assertFalse("createDraft" in fake.calls)
        assertEquals("I_rest", out.cards.single().remote!!.contentId)
    }

    @Test
    fun createIssueRetriesWithoutLabelsWhenRejected() {
        val fake = FakeGitHub().apply { rejectLabels = true }
        val (gh, _) = setup(fake)
        gh.createIssue("o/r", "Crash", "", listOf("bug"))
        assertFalse(fake.lastIssueRequest!!.has("labels"))
    }

    @Test
    fun graphqlErrorsSurfaceWithScopeHint() {
        val api = GitHubApi({ "tok" }) { _, _, _ ->
            JSONObject().put("errors", JSONArray().put(JSONObject().put("type", "INSUFFICIENT_SCOPES").put("message", "needs read:project"))).toString()
        }
        try {
            GitHubProjects(api).project(link)
            fail("expected error")
        } catch (e: IllegalStateException) {
            assertEquals(GitHubApi.SCOPE_HINT, e.message)
        }
    }

    @Test
    fun missingTokenFailsBeforeAnyRequest() {
        val api = GitHubApi({ " " }) { _, _, _ -> fail("no request without a token"); "" }
        try {
            api.graphql("query { viewer { login } }")
            fail("expected error")
        } catch (e: IllegalStateException) {
            assertEquals(GitHubApi.NO_TOKEN, e.message)
        }
    }

    @Test
    fun parseItemSkipsArchivedAndPullRequests() {
        fun node(type: String, archived: Boolean = false) = JSONObject()
            .put("id", "PVTI").put("updatedAt", "2026-01-01T00:00:00Z").put("isArchived", archived)
            .put("content", JSONObject().put("__typename", type).put("id", "C").put("title", "t"))
        assertNull(GitHubProjects.parseItem(node("PullRequest")))
        assertNull(GitHubProjects.parseItem(node("DraftIssue", archived = true)))
        assertEquals("t", GitHubProjects.parseItem(node("DraftIssue"))!!.title)
    }

    @Test
    fun closedIssueWithoutStatusCountsAsDone() {
        val fake = FakeGitHub(withTypeField = true)
        val (_, runner) = setup(fake)
        val itemId = fake.addDraftOnGitHub("closed one")
        fake.items.getValue(itemId).apply { isIssue = true; closed = true; number = 3 }
        val out = sync(runner, emptyList())
        assertEquals(CardStatus.DONE, out.cards.single().status)
        assertEquals(3, out.cards.single().issue!!.number)
    }

    @Test
    fun reservedTypeFieldNameDoesNotAbortSync() {
        val fake = FakeGitHub(rejectCreateField = "Name cannot have a reserved value, Name has already been taken")
        val (_, runner) = setup(fake)
        val out = sync(runner, listOf(card("a", CardType.BUG, CardStatus.TODO, "Crash")))
        assertEquals(1, fake.items.size)
        assertEquals("Crash", fake.items.values.single().title)
        assertNull(fake.typeOf(fake.items.keys.single()))
        assertTrue(out.report.warnings.any { it.contains(GitHubProjects.TYPE_FIELD) })
    }

    @Test
    fun findTypeFieldPrefersCardTypeThenKindThenLegacyType() {
        fun field(name: String, vararg options: String) =
            GitHubProjects.SelectField("id_$name", name, options.map { GitHubProjects.Option(it, it) })
        assertEquals(
            "Card type",
            GitHubProjects.findTypeField(listOf(field("Kind", "Feature"), field("Card type", "Bug")))!!.name,
        )
        assertEquals("Kind", GitHubProjects.findTypeField(listOf(field("Kind", "Feature")))!!.name)
        assertEquals(
            "Type",
            GitHubProjects.findTypeField(listOf(field("Type", "Feature", "Bug")))!!.name,
        )
        assertNull(GitHubProjects.findTypeField(listOf(field("Type", "Epic"))))
    }
}
