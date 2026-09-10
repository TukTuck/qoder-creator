package app.consolepocket.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobMatcherTest {

    @Test
    fun `doppelstern matcht ueber slashes`() {
        assertTrue(GlobMatcher.matches("**/log**", "/v1/play/log"))
        assertTrue(GlobMatcher.matches("/m/_/js/**", "/m/_/js/main.js"))
        assertTrue(GlobMatcher.matches("**/batchexecute**", "/_/CloudUi/data/batchexecute"))
    }

    @Test
    fun `einfacher stern matcht nicht ueber slashes`() {
        assertTrue(GlobMatcher.matches("*.google.com", "play.google.com"))
        assertFalse(GlobMatcher.matches("*.google.com", "play.google.com/x"))
    }

    @Test
    fun `host-wildcard trifft subdomains`() {
        assertTrue(GlobMatcher.matches("*.googleapis.com", "firebaselogging.googleapis.com"))
        assertFalse(GlobMatcher.matches("*.googleapis.com", "googleapis.com"))
    }

    @Test
    fun `punkt wird nicht als wildcard missverstanden`() {
        assertFalse(GlobMatcher.matches("console.cloud.google.com", "consoleXcloudXgoogleXcom"))
        assertTrue(GlobMatcher.matches("console.cloud.google.com", "console.cloud.google.com"))
    }

    @Test
    fun `fragezeichen matcht genau ein zeichen`() {
        assertTrue(GlobMatcher.matches("gen_20?", "gen_204"))
        assertFalse(GlobMatcher.matches("gen_20?", "gen_2044"))
    }

    @Test
    fun `vollstaendige wildcards matchen alles`() {
        assertTrue(GlobMatcher.matches("**", "irgend/was"))
        assertEquals(true, GlobMatcher.matches("*", "abc"))
    }
}
