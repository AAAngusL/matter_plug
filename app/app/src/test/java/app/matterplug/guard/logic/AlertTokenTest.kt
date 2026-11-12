package app.matterplug.guard.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertTokenTest {
    @Test fun blankTokenHasNoHeader() {
        assertNull(authorizationHeader(""))
        assertNull(authorizationHeader("   "))
        assertNull(authorizationHeader(" \n\t "))
    }

    @Test fun tokenIsTrimmedIntoBearer() {
        assertEquals("Bearer x", authorizationHeader("x"))
        assertEquals("Bearer s3cret", authorizationHeader("  s3cret \n"))
        assertEquals("Bearer a b", authorizationHeader("a b"))     // inner characters are kept as typed
    }

    @Test fun rejectedForTheTwoAuthCodesOnly() {
        assertEquals(PostResult(401, "rejected"), resultForCode(401))
        assertEquals(PostResult(403, "rejected"), resultForCode(403))
        assertEquals(PostResult(200, null), resultForCode(200))
        assertEquals(PostResult(404, null), resultForCode(404))
        assertEquals(PostResult(302, null), resultForCode(302))
        assertEquals(PostResult(500, null), resultForCode(500))
    }

    @Test fun rejectedIsNeverDelivered() {
        assertFalse(resultForCode(401).delivered)
        assertFalse(resultForCode(403).delivered)
        assertTrue(resultForCode(204).delivered)
    }

    @Test fun rejectedTokenRoundTrip() {
        assertEquals("rejected", DeliveryError.forFailure(resultForCode(401)))
        assertEquals("http:404", DeliveryError.forFailure(resultForCode(404)))
        assertEquals(DeliveryError.Parsed.Rejected, DeliveryError.parse("rejected"))
    }

    @Test fun testButtonShowsRejected() {
        val rejected = TestOutcome.Failed(DeliveryError.Parsed.Rejected)
        assertEquals(rejected, DeliveryPlan.describeTest(resultForCode(401)))
        assertEquals(rejected, DeliveryPlan.describeTest(resultForCode(403)))
        assertEquals(rejected, DeliveryPlan.describeTest(PostResult(401, null)))
        assertEquals(TestOutcome.Ok, DeliveryPlan.describeTest(resultForCode(200)))
        assertEquals(TestOutcome.Http(404), DeliveryPlan.describeTest(resultForCode(404)))
    }

    @Test fun plainHttpWarning() {
        assertTrue(tokenOverPlainHttp("x", "http://192.168.1.2:8083/battery"))
        assertTrue(tokenOverPlainHttp("x", "  HTTP://host/x"))
        assertFalse(tokenOverPlainHttp("x", "https://host/x"))
        assertFalse(tokenOverPlainHttp("", "http://host/x"))
        assertFalse(tokenOverPlainHttp("  ", "http://host/x"))
        assertFalse(tokenOverPlainHttp("x", ""))
    }

    @Test fun settingsKeepTheTokenThroughCoercion() {
        val s = GuardSettings(alertAddress = "https://h/x", alertToken = "tok")
        assertEquals("tok", s.coerced().alertToken)
        assertEquals("", GuardSettings().alertToken)
    }
}
