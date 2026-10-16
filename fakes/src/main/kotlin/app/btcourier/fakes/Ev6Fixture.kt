package app.btcourier.fakes

/**
 * A synthetic Kia EV6 BMS answer to UDS `22 0101` (ECU `7E4`, answering on `7EC`) with known
 * values, laid out as the vendored "Kia: EV6" profile reads it. Invented numbers, no real car.
 *
 * The 62-byte positive response `62 01 01 …` travels as an ISO-TP first frame (`10 3E 62 01 01`
 * and 3 more bytes) and eight consecutive frames `21`..`28`. Payload offsets below count from the
 * `62`; with the ELM327's PCI bytes at every eighth `B<n>`, payload byte p of a consecutive frame
 * k is `B(8k + 1 + (p - 6) % 7)`, so the four 4-byte counters at payload 33-48 straddle the PCIs
 * at B40, B48 and B56.
 */
object Ev6Fixture {
    const val COMMAND: String = "220101"
    const val HEADER: String = "7EC"

    /** `SOC` = B10 / 2: payload 7. */
    const val SOC: Double = 80.5

    /** `LV_V` = B38 x 0.1: payload 32. */
    const val LV_V: Double = 14.0

    /** `HV_AH_CHARGED`, tenths of Ah at payload 33-36 (B39, B41-B43). */
    const val HV_AH_CHARGED: Double = 12345.6

    /** `HV_AH_DISCHARGED`, tenths of Ah at payload 37-40 (B44-B47). */
    const val HV_AH_DISCHARGED: Double = 11987.3

    /** `KWH_CHARGED`, tenths of kWh at payload 41-44 (B49-B52). */
    const val KWH_CHARGED: Double = 8604.9

    /** `KWH_DISCHARGED`, tenths of kWh at payload 45-48 (B53-B55, B57). */
    const val KWH_DISCHARGED: Double = 8355.0

    private const val PAYLOAD_SIZE = 62

    /** The UDS response payload, `62 01 01` and 59 data bytes. */
    val payload: ByteArray =
        ByteArray(PAYLOAD_SIZE).also { p ->
            p[0] = 0x62
            p[1] = 0x01
            p[2] = 0x01
            p[7] = (SOC * 2).toInt().toByte()
            p[32] = (LV_V * 10).toInt().toByte()
            p.putTenths(33, HV_AH_CHARGED)
            p.putTenths(37, HV_AH_DISCHARGED)
            p.putTenths(41, KWH_CHARGED)
            p.putTenths(45, KWH_DISCHARGED)
        }

    /** The answer as the adapter prints it with `ATH1 ATS1`, nine lines on [HEADER]. */
    val RESPONSE: String = FakeElm327Adapter.isoTpAnswer(HEADER, payload)

    /** Big-endian 32-bit tenths of [value] at [offset]. */
    private fun ByteArray.putTenths(
        offset: Int,
        value: Double,
    ) {
        val raw = Math.round(value * 10)
        for (i in 0 until 4) this[offset + i] = (raw shr (8 * (3 - i))).toByte()
    }
}
