package app.matterplug.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsResultTest {
    @Test fun sentWhenAnyNumberAccepted() {
        assertEquals(SmsOutcome.Sent, SmsResult.outcome(listOf(false, true)))
        assertEquals(SmsOutcome.Sent, SmsResult.outcome(listOf(true)))
    }
    @Test fun failedWhenNoneAcceptedOrNoNumbers() {
        assertEquals(SmsOutcome.Failed, SmsResult.outcome(listOf(false, false)))
        assertEquals(SmsOutcome.Failed, SmsResult.outcome(emptyList()))
    }
    @Test fun lastSmsRoundTrips() {
        assertEquals(SmsResult.Last(5, SmsOutcome.Failed, "RESULT_ERROR_NO_SERVICE"),
            SmsResult.decode(SmsResult.encode(5, SmsOutcome.Failed, "RESULT_ERROR_NO_SERVICE")))
        assertEquals(SmsResult.Last(5, SmsOutcome.Sent, null), SmsResult.decode(SmsResult.encode(5, SmsOutcome.Sent, null)))
    }
    @Test fun damagedLastSmsIsNull() {
        for (bad in listOf(null, "", "x|sent|", "5|maybe|", "5|sent")) assertEquals(null, SmsResult.decode(bad))
    }
}
