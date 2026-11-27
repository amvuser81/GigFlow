package app.gigflow.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Point-at-the-card teaching: what gets saved must be a pure function of what
 * was tapped, so a session can be replayed and a re-teach can't inherit state.
 */
class TeachSessionTest {

    /** A clickable card wrapping a money line, a distance line and an
     *  unlabeled close button in the corner. */
    private fun card() = listOf(
        OfferParser.FlatNode("", null, Bounds(0, 0, 1080, 1400), true),
        OfferParser.FlatNode("18,50 $", null, Bounds(40, 200, 700, 280), false),
        OfferParser.FlatNode("8.2 mi", null, Bounds(40, 300, 700, 360), false),
        OfferParser.FlatNode("", null, Bounds(980, 40, 1060, 120), true),
    )

    @Test
    fun aTapMapsToTheLeafUnderTheFinger() {
        val s = TeachSession("com.ubercab.driver", card())
        s.pick("Payout", 300, 240)
        // The money line, not the clickable card that contains it.
        assertEquals("18,50 $", s.nodeFor("Payout")?.text)
        assertTrue(s.anchorFor("Payout")!!.startsWith("idx:"))
    }

    @Test
    fun anUnlabeledButtonIsTaughtByItsPosition() {
        val s = TeachSession("com.ubercab.driver", card())
        s.pick("Decline", 1020, 80)
        assertEquals(Bounds(980, 40, 1060, 120), s.nodeFor("Decline")?.bounds)
        assertEquals("pos:980,40,1060,120", s.anchorFor("Decline"))
    }

    @Test
    fun theSameTapsAlwaysBuildTheSameHint() {
        fun run(): ParseHints.Hint {
            val s = TeachSession("com.ubercab.driver", card())
            s.pick("Payout", 300, 240)
            s.pick("Distance", 300, 330)
            s.pick("Decline", 1020, 80)
            return s.buildHint()!!
        }
        val a = run()
        val b = run()
        assertEquals(a.screen, b.screen)
        assertEquals(a.payout, b.payout)
        assertEquals(a.distance, b.distance)
        assertEquals(a.accept, b.accept)
        assertEquals(a.decline, b.decline)
    }

    @Test
    fun aDistanceAloneTeachesNothing() {
        val s = TeachSession("app.test", card())
        assertNull(s.buildHint())
        s.pick("Distance", 300, 330)
        assertNull(s.buildHint())
        s.pick("Decline", 1020, 80)
        assertNotNull(s.buildHint())
    }

    @Test
    fun teachingAppliesToTheLiveParser() {
        val nodes = card()
        val s = TeachSession("app.test", nodes)
        s.pick("Payout", 300, 240)
        s.pick("Distance", 300, 330)
        s.pick("Decline", 1020, 80)
        val offer = OfferParser.parse("app.test", nodes, hint = s.buildHint(), unit = "MI")
        assertNotNull(offer)
        assertEquals(1_850, offer!!.payoutCents)
        assertNotNull("the taught × must reach the offer", offer.declineNode)
    }

    @Test
    fun undoRemovesTheLastPickOnly() {
        val s = TeachSession("app.test", card())
        s.pick("Payout", 300, 240)
        s.pick("Decline", 1020, 80)
        assertEquals("Decline", s.undo())
        assertNull(s.nodeFor("Decline"))
        assertEquals("18,50 $", s.nodeFor("Payout")?.text)
    }
}
