package app.consolepocket.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Keine extra Fenster"-Policy (PLAN Kapitel 7): Kein Pfad darf die App automatisch verlassen.
 */
class NavigationPolicyTest {

    private val policy = NavigationPolicy(keepLinksInApp = true)

    @Test
    fun `console-links bleiben hier`() {
        val d = policy.decide("https://console.cloud.google.com/compute/instances")
        assertEquals(NavigationPolicy.Decision.LoadHere, d)
    }

    @Test
    fun `google-login bleibt hier`() {
        val d = policy.decide("https://accounts.google.com/signin/v2/identifier?flowName=GlifWebSignIn")
        assertEquals(NavigationPolicy.Decision.LoadHere, d)
    }

    @Test
    fun `fremde domains bleiben in-app`() {
        val d = policy.decide("https://cloud.google.com/compute/docs/instances")
        assertEquals(NavigationPolicy.Decision.LoadHere, d)
        val github = policy.decide("https://github.com/GoogleCloudPlatform")
        assertEquals(NavigationPolicy.Decision.LoadHere, github)
    }

    @Test
    fun `mailto tel und intent werden nur nach rueckfrage geoeffnet`() {
        listOf(
            "mailto:support@example.com",
            "tel:+4922812345",
            "intent://scan/#Intent;scheme=zxing;end",
            "market://details?id=com.android.chrome",
        ).forEach { url ->
            val d = policy.decide(url)
            assertTrue("$url muss nachfragen, war aber $d", d is NavigationPolicy.Decision.AskUser)
        }
    }

    @Test
    fun `javascript und about werden ignoriert`() {
        assertEquals(NavigationPolicy.Decision.Ignore, policy.decide("javascript:void(0)"))
        assertEquals(NavigationPolicy.Decision.Ignore, policy.decide("about:blank"))
        assertEquals(NavigationPolicy.Decision.Ignore, policy.decide("data:text/html,<h1>x</h1>"))
    }

    @Test
    fun `neues fenster wird zu in-app-tab`() {
        val d = policy.decide("https://cloud.google.com/docs", isNewWindow = true)
        assertEquals(NavigationPolicy.Decision.LoadInNewTab, d)
    }

    @Test
    fun `auch bei deaktiviertem in-app-toggle verlaesst die app sich nicht automatisch`() {
        val loose = NavigationPolicy(keepLinksInApp = false)
        val d = loose.decide("https://github.com/foo")
        assertEquals(NavigationPolicy.Decision.LoadHere, d)
    }

    @Test
    fun `kaputte urls fuehren nicht zum absturz`() {
        val d = policy.decide("https://console.cloud.google.com/x y z?q=1")
        assertTrue(d is NavigationPolicy.Decision.LoadHere || d is NavigationPolicy.Decision.Ignore)
    }
}
