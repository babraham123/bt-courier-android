package app.btcourier.core.transport.blehttp

/**
 * Client-side state of one response (§3, §4b, §7), fed the device's frames in arrival order.
 *
 * Pure: it decides, the caller does the I/O. After handing a [Event.Body] downstream the caller
 * asks [creditDue] and writes the CREDIT it returns. Crediting after the bytes were consumed (not
 * merely received) is what §6 asks for, and it makes a stalled consumer stall the device instead
 * of growing a buffer.
 */
class ResponseAssembler(
    val seq: Int,
    private val creditStep: Int = BleHttpWire.CLIENT_CREDIT_STEP,
) {
    init {
        // The device keeps at most 16 KB uncredited and needs a credit at least every 8 KB (§4b).
        require(creditStep in 1..BleHttpWire.DEVICE_CREDIT_STEP) { "credit step must be 1..8192, got $creditStep" }
    }

    sealed interface Event {
        /** Not part of this exchange: another seq (a late frame of an aborted request), a device CREDIT for an upload, or anything after LAST. */
        data object Ignored : Event

        class Head(val head: ResponseHead) : Event

        class Body(val bytes: ByteArray) : Event

        /** LAST: the response is complete. [tail] is its payload, normally empty. */
        class End(val tail: ByteArray) : Event
    }

    var head: ResponseHead? = null
        private set

    /** RSP_BODY payload bytes received so far. */
    var received: Long = 0
        private set

    /** The last cumulative value credited. */
    var credited: Long = 0
        private set

    var finished: Boolean = false
        private set

    private var expectedCounter = 0

    /** @throws BleHttpException.FrameLost on a counter gap, [BleHttpException.Truncated] on an early LAST. */
    fun accept(frame: Frame): Event {
        if (frame.seq != seq || finished) return Event.Ignored
        return when (frame.type) {
            FrameType.RSP -> {
                if (head != null) throw BleHttpException.Protocol("second RSP head for seq $seq")
                ResponseHead.decode(frame.payload).also { head = it }.let(Event::Head)
            }
            FrameType.RSP_BODY -> body(frame)
            // Device CREDITs pace uploads; REQ/REQ_BODY/ABORT never come from the device and §2
            // says unexpected types are ignored.
            else -> Event.Ignored
        }
    }

    private fun body(frame: Frame): Event {
        val head = head ?: throw BleHttpException.Protocol("RSP_BODY before RSP head for seq $seq")
        if (frame.counter != expectedCounter) {
            throw BleHttpException.FrameLost(expectedCounter, frame.counter, received)
        }
        expectedCounter = (expectedCounter + 1) and 0x0F
        received += frame.payload.size
        if (!frame.isLast) return Event.Body(frame.payload)

        finished = true
        if (head.length >= 0 && received < head.length) {
            throw BleHttpException.Truncated(head.length, received)
        }
        return Event.End(frame.payload)
    }

    /**
     * The cumulative byte count to send in a CREDIT now, or null when none is owed yet (§7: once
     * at least [creditStep] bytes arrived since the last credit). Never after LAST: the device
     * ignores credits of a finished response.
     */
    fun creditDue(): Long? {
        if (finished || received - credited < creditStep) return null
        credited = received
        return received
    }
}
