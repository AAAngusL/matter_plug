package app.matterplug.matter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1. Needs the Matter jars on the classpath (app/libs) but no phone. */
class SetupCodeTest {
    // Example values from the Matter specification: passcode 20202021, discriminator 3840 (0xF00).
    private val qr = "MT:Y.K9042C00KA0648G00"
    private val manual = "34970112332"

    @Test
    fun validQrCodeIsParsed() {
        val result = parseSetupCode(qr)
        assertTrue(result is SetupCodeResult.Ok)
        val code = (result as SetupCodeResult.Ok).code
        assertEquals(20202021L, code.passcode)
        assertEquals(3840, code.discriminator)
        assertEquals(false, code.isShortDiscriminator)
    }

    @Test
    fun validManualCodeIsParsed() {
        val result = parseSetupCode(manual)
        assertTrue(result is SetupCodeResult.Ok)
        val code = (result as SetupCodeResult.Ok).code
        assertEquals(20202021L, code.passcode)
        assertEquals(true, code.isShortDiscriminator)
    }

    @Test
    fun manualCodeWithSpacesAndDashesIsParsed() {
        assertTrue(parseSetupCode(" 3497-011 2332 ") is SetupCodeResult.Ok)
    }

    @Test
    fun randomTextIsNotAMatterCode() {
        assertEquals(SetupCodeResult.NotAMatterCode, parseSetupCode("hello world"))
        assertEquals(SetupCodeResult.NotAMatterCode, parseSetupCode(""))
    }
}
