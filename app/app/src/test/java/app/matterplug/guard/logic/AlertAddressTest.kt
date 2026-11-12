package app.matterplug.guard.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertAddressTest {
    @Test fun validAddresses() {
        assertTrue(AlertAddress.isValid("http://192.168.1.20:8080/battery"))
        assertTrue(AlertAddress.isValid("https://example.com/x"))
        assertTrue(AlertAddress.isValid(" https://example.com "))
    }

    @Test fun invalidAddresses() {
        for (t in listOf("", "ftp://a.b", "http://", "http:///path", "192.168.1.1:8080", "http://bad host")) {
            assertFalse("'$t'", AlertAddress.isValid(t))
        }
    }
}
