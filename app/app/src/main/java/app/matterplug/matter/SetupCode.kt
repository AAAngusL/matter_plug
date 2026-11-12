package app.matterplug.matter

import matter.onboardingpayload.OnboardingPayloadParser

data class SetupCode(val passcode: Long, val discriminator: Int, val isShortDiscriminator: Boolean)

sealed interface SetupCodeResult {
    data class Ok(val code: SetupCode) : SetupCodeResult
    data object NotAMatterCode : SetupCodeResult
}

/** Parses a scanned QR string ("MT:...") or a typed manual code (11 or 21 digits, spaces and dashes allowed). */
fun parseSetupCode(text: String): SetupCodeResult {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return SetupCodeResult.NotAMatterCode
    return try {
        val parser = OnboardingPayloadParser()
        val payload = if (trimmed.startsWith("MT:")) {
            parser.parseQrCode(trimmed)
        } else {
            parser.parseManualPairingCode(trimmed.filterNot { it == ' ' || it == '-' })
        }
        // Settled: OnboardingPayload has setupPinCode: Long, discriminator: Int, hasShortDiscriminator: Boolean.
        // VERIFY: for a manual code, `discriminator` holds the 4-bit short value (and hasShortDiscriminator is true), which is what BleTransport.getBluetoothDevice(.., isShortDiscriminator = true) expects. Otherwise use payload.getShortDiscriminatorValue().
        SetupCodeResult.Ok(SetupCode(payload.setupPinCode, payload.discriminator, payload.hasShortDiscriminator))
    } catch (e: Exception) {
        // Includes UnrecognizedQrCodeException and any other parser failure.
        SetupCodeResult.NotAMatterCode
    }
}
