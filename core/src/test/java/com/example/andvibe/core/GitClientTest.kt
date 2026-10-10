package com.example.andvibe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitClientTest {
    @Test
    fun normalizeGitUrlAcceptsHttpsAndSsh() {
        assertEquals(
            "https://github.com/owner/repo.git",
            GitClient.normalizeGitUrl("https://github.com/owner/repo.git"),
        )
        assertEquals(
            "git@github.com:owner/repo.git",
            GitClient.normalizeGitUrl("git@github.com:owner/repo.git"),
        )
        assertEquals(
            "ssh://git@github.com/owner/repo",
            GitClient.normalizeGitUrl("ssh://git@github.com/owner/repo"),
        )
    }

    @Test
    fun normalizeGitUrlRejectsBareHostPath() {
        assertThrows(IllegalStateException::class.java) {
            GitClient.normalizeGitUrl("github.com/owner/repo")
        }
    }

    @Test
    fun normalizeRemoteUrlRejectsWhitespaceAndSchemeless() {
        assertThrows(IllegalStateException::class.java) {
            GitClient.normalizeRemoteUrl("https://github.com/owner/repo with space")
        }
        assertThrows(IllegalStateException::class.java) {
            GitClient.normalizeRemoteUrl("owner/repo")
        }
        assertThrows(IllegalStateException::class.java) {
            GitClient.normalizeRemoteUrl("")
        }
    }

    @Test
    fun safeRepoNameFiltersAndRejectsDots() {
        assertEquals("my-repo_1.0", GitClient.safeRepoName(" my-repo_1.0! "))
        assertThrows(IllegalStateException::class.java) { GitClient.safeRepoName("..") }
        assertThrows(IllegalStateException::class.java) { GitClient.safeRepoName(".") }
        assertThrows(IllegalStateException::class.java) { GitClient.safeRepoName("@@@") }
    }

    @Test
    fun repoNameFromUrlStripsGitSuffix() {
        assertEquals("repo", GitClient.repoNameFromUrl("https://github.com/owner/repo.git"))
        assertEquals("repo", GitClient.repoNameFromUrl("https://github.com/owner/repo/"))
        assertEquals("Andvibe", GitClient.repoNameFromUrl("git@github.com:BrendanBezuko/Andvibe.git"))
    }

    @Test
    fun archiveCandidatesGithubDefaultAndTreeBranch() {
        val default = GitClient.archiveCandidates("https://github.com/owner/repo")
        assertEquals(
            listOf(
                "https://api.github.com/repos/owner/repo/zipball",
                "https://codeload.github.com/owner/repo/zip/refs/heads/main",
                "https://codeload.github.com/owner/repo/zip/refs/heads/master",
            ),
            default,
        )
        val tree = GitClient.archiveCandidates("https://github.com/owner/repo/tree/develop")
        assertEquals(
            listOf("https://codeload.github.com/owner/repo/zip/refs/heads/develop"),
            tree,
        )
        val withGit = GitClient.archiveCandidates("https://www.github.com/owner/repo.git")
        assertTrue(withGit.any { it.contains("/owner/repo/") || it.contains("/owner/repo/zipball") })
    }

    @Test
    fun archiveCandidatesOtherHosts() {
        assertEquals(
            listOf(
                "https://gitlab.com/api/v4/projects/group%2Fproj/repository/archive.zip",
            ),
            GitClient.archiveCandidates("https://gitlab.com/group/proj"),
        )
        assertEquals(
            listOf(
                "https://codeberg.org/owner/repo/archive/main.zip",
                "https://codeberg.org/owner/repo/archive/master.zip",
            ),
            GitClient.archiveCandidates("https://codeberg.org/owner/repo"),
        )
        assertEquals(
            listOf("https://bitbucket.org/owner/repo/get/HEAD.zip"),
            GitClient.archiveCandidates("https://bitbucket.org/owner/repo"),
        )
        assertTrue(GitClient.archiveCandidates("https://example.com/owner/repo").isEmpty())
        assertTrue(GitClient.archiveCandidates("not a url").isEmpty())
    }
}
