package com.example.andvibe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardCardsTest {
    @Test
    fun statusParsesIdsLabelsAndGitHubDefaults() {
        assertEquals(CardStatus.TODO, CardStatus.parse("Todo"))
        assertEquals(CardStatus.IN_PROGRESS, CardStatus.parse("In Progress"))
        assertEquals(CardStatus.IN_PROGRESS, CardStatus.parse("in_progress"))
        assertEquals(CardStatus.HUMAN_TESTING, CardStatus.parse("Human testing"))
        assertEquals(CardStatus.HUMAN_TESTING, CardStatus.parse("QA"))
        assertEquals(CardStatus.BACK_BURNER, CardStatus.parse("back-burner"))
        assertEquals(CardStatus.DONE, CardStatus.parse("Done"))
        assertNull(CardStatus.parse("Blocked"))
        assertEquals(CardStatus.TODO, CardStatus.from("Blocked"))
    }

    @Test
    fun typeParseIsStrictFromIsLenient() {
        assertEquals(CardType.BUG, CardType.parse("Bug"))
        assertEquals(CardType.FEATURE, CardType.parse("idea"))
        assertEquals(CardType.SOLUTION, CardType.parse("fix"))
        assertNull(CardType.parse("Chore"))
        assertEquals(CardType.FEATURE, CardType.from("Chore"))
    }

    @Test
    fun projectLinkParsesUserAndOrgUrls() {
        assertEquals(ProjectLink("BrendanBezuko", false, 3), ProjectLink.parse("https://github.com/users/BrendanBezuko/projects/3"))
        assertEquals(ProjectLink("acme", true, 12), ProjectLink.parse(" github.com/orgs/acme/projects/12/views/1 "))
        assertEquals("https://github.com/orgs/acme/projects/12", ProjectLink("acme", true, 12).url)
        assertNull(ProjectLink.parse("https://github.com/acme/app"))
        assertNull(ProjectLink.parse("not a url"))
    }
}
