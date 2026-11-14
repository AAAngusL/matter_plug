package app.matterplug.matter

/** The Matter BLE service data (opcode, version + 12-bit discriminator) and the mask the scan filter applies to it. */
object BleFilter {
    /**
     * A typed (manual) code gives only the short discriminator, the TOP 4 bits of the 12 (0..15, unshifted), so it is
     * moved up into place; the mask then ignores the low 8 bits. A scanned QR code gives all 12 bits as they are.
     */
    fun serviceData(discriminator: Int, isShortDiscriminator: Boolean = false): ByteArray {
        val opcode = 0
        val version = 0
        val long = if (isShortDiscriminator) (discriminator and 0xf) shl 8 else discriminator and 0xfff
        val versionDiscriminator = ((version and 0xf) shl 12) or long
        return intArrayOf(opcode, versionDiscriminator, versionDiscriminator shr 8).map { it.toByte() }.toByteArray()
    }

    /** A short discriminator is the top 4 bits of the 12: byte 1 (the low 8 bits) is not compared. */
    fun serviceDataMask(isShortDiscriminator: Boolean): ByteArray {
        val shortDiscriminatorMask = if (isShortDiscriminator) 0x00 else 0xff
        return intArrayOf(0xff, shortDiscriminatorMask, 0xff).map { it.toByte() }.toByteArray()
    }
}
