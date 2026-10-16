package app.btcourier.fakes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * A scripted ELM327 behind a [FakeGattLink], as a BLE OBD-II adapter presents its UART: commands
 * written to the primary channel, output notified on it in PDUs of [notifySize] bytes.
 *
 * - Starts as after power-up and after `ATZ`: echo on, headers off, spaces on, no linefeeds.
 *   `ATE`, `ATH`, `ATS`, `ATL` 0/1 switch them and answer `OK`; echo applies from the next command,
 *   so `ATE0` itself is still echoed. `ATZ` answers `\r\r<version>`, `ATRV` [voltage], any other AT
 *   command `OK`.
 * - Other commands answer from [responses], keyed by the command in upper case without spaces;
 *   anything else answers `NO DATA`, and every one `UNABLE TO CONNECT` while [carOff].
 * - Answers end with two line ends and the `>` prompt, line ends being `\r`, or `\r\n` after `ATL1`.
 * - [responses] hold the `ATH1 ATS1` text, lines separated by `\r`. With spaces off they are sent
 *   without spaces; headers off is not modelled, answers always carry headers.
 * - [delays] hold an answer back (needs [scope]); [silent] commands never answer.
 *
 * Not thread-safe; drive it from one dispatcher (`runTest`).
 *
 * @param scope runs delayed answers; pass the test scope so they run on virtual time.
 */
class FakeElm327Adapter(
    responses: Map<String, String> = emptyMap(),
    mtu: Int = 517,
    private val scope: CoroutineScope? = null,
    private val notifySize: Int = mtu - 3,
) {
    val responses: MutableMap<String, String> = responses.toMutableMap()
    val delays: MutableMap<String, Duration> = mutableMapOf()
    val silent: MutableSet<String> = mutableSetOf()

    var carOff: Boolean = false
    var version: String = "ELM327 v2.2"
    var voltage: String = "12.6V"

    var echo: Boolean = true
        private set
    var headers: Boolean = false
        private set
    var spaces: Boolean = true
        private set
    var linefeeds: Boolean = false
        private set

    private val line = StringBuilder()
    private val _commands = mutableListOf<String>()

    /** Every command line received, as written (without its `\r`), in order. */
    val commands: List<String> get() = _commands.toList()

    val link: FakeGattLink = FakeGattLink(mtu) { bytes, _ -> receive(bytes) }

    /** The adapter loses power or the link: notifications complete and writes fail. */
    fun drop() = link.drop()

    private fun receive(bytes: ByteArray) {
        for (byte in bytes) {
            when (val c = (byte.toInt() and 0xFF).toChar()) {
                '\r' -> handle(line.toString()).also { line.clear() }
                '\n' -> Unit
                else -> line.append(c)
            }
        }
    }

    private fun handle(raw: String) {
        _commands += raw
        val key = raw.uppercase().replace(" ", "")
        val echoed = if (echo) raw + "\r" else ""
        val body = answer(key)
        val eol = if (linefeeds) "\r\n" else "\r"
        val text = echoed + body.replace("\r", eol) + eol + eol + ">"
        if (key in silent) return
        val hold = delays[key]
        if (hold == null) {
            deliver(text)
        } else {
            checkNotNull(scope) { "delayed answers need a scope" }.launch {
                delay(hold)
                deliver(text)
            }
        }
    }

    private fun answer(key: String): String =
        when {
            key == "ATZ" -> {
                echo = true
                headers = false
                spaces = true
                linefeeds = false
                "\r\r$version"
            }

            key == "ATRV" -> {
                voltage
            }

            key.length == 4 && key[3] in "01" && key.substring(0, 3) in setOf("ATE", "ATH", "ATS", "ATL") -> {
                val on = key[3] == '1'
                when (key[2]) {
                    'E' -> echo = on
                    'H' -> headers = on
                    'S' -> spaces = on
                    else -> linefeeds = on
                }
                "OK"
            }

            key.startsWith("AT") -> {
                "OK"
            }

            carOff -> {
                "UNABLE TO CONNECT"
            }

            else -> {
                val canned = responses[key] ?: "NO DATA"
                if (spaces || canned == "NO DATA") canned else canned.replace(" ", "")
            }
        }

    private fun deliver(text: String) {
        text
            .encodeToByteArray()
            .asList()
            .chunked(notifySize)
            .forEach { link.notify(it.toByteArray()) }
    }

    companion object {
        /**
         * An ISO-TP answer as an ELM327 prints it with `ATH1 ATS1`: one line per CAN frame, each
         * `<header>` and 8 bytes with the PCI first. A payload of up to 7 bytes is one single frame
         * (`0N`); a longer one a first frame (`1L LL` and 6 bytes) and consecutive frames (`21`,
         * `22`, ... wrapping after `2F`, 7 bytes each), the last padded with `AA`.
         */
        fun isoTpAnswer(
            header: String,
            payload: ByteArray,
        ): String {
            val frames = mutableListOf<List<Int>>()
            val data = payload.map { it.toInt() and 0xFF }
            if (data.size <= 7) {
                frames += listOf(data.size) + data
            } else {
                frames += listOf(0x10 or (data.size shr 8), data.size and 0xFF) + data.take(6)
                data.drop(6).chunked(7).forEachIndexed { i, chunk ->
                    frames +=
                        listOf(0x20 or ((i + 1) and 0x0F)) + chunk
                }
            }
            return frames.joinToString("\r") { frame ->
                val padded = if (frame.size < 8 && data.size > 7) frame + List(8 - frame.size) { 0xAA } else frame
                header + " " + padded.joinToString(" ") { "%02X".format(it) }
            }
        }
    }
}
