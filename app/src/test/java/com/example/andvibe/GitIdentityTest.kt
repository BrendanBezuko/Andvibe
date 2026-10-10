package com.example.andvibe

import org.junit.Assert.assertEquals
import org.junit.Test

class GitIdentityTest {
    @Test
    fun githubSlugFromHttpsAndSsh() {
        assertEquals(
            "owner/repo",
            GitIdentity.githubSlug("https://github.com/owner/repo.git"),
        )
        assertEquals(
            "owner/repo",
            GitIdentity.githubSlug("git@github.com:owner/repo.git"),
        )
        assertEquals(
            "owner/repo",
            GitIdentity.githubSlug("ssh://git@github.com/owner/repo"),
        )
        assertEquals("", GitIdentity.githubSlug("https://gitlab.com/owner/repo"))
    }

    @Test
    fun githubSlugEdges() {
        assertEquals(
            "owner/repo",
            GitIdentity.githubSlug("https://www.github.com/owner/repo/"),
        )
        assertEquals(
            "owner/repo",
            GitIdentity.githubSlug("HTTPS://GitHub.com/owner/repo.git"),
        )
        assertEquals(
            "owner/repo",
            GitIdentity.githubSlug("  git@github.com:owner/repo.git  "),
        )
        assertEquals("", GitIdentity.githubSlug(""))
        assertEquals("", GitIdentity.githubSlug("not-a-url"))
    }
}
