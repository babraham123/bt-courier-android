package app.btcourier.core.elm327

import app.btcourier.core.transport.GattLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The ELM327 command line over a BLE serial bridge: the adapter's UART on a [GattLink]'s primary
 * channel (notify for its output, write for its input).
 *
 * The protocol is text: a command ends with `\r`, and the adapter answers with lines and then the
 * `>` prompt, which is the only reliable end of an answer (line counts vary with protocol and ECU
 * count). Notifications split the text anywhere, so bytes are buffered until a prompt arrives;
 * anything after it stays buffered for the next command.
 *
 * Commands run one at a time, in call order.
 *
 * @param scope owns the coroutine reading [GattLink.notifications] for the link's lifetime, started
 *   before the first command so no output arrives unobserved.
 * @param promptTimeout how long an answer may take. The adapter's own CAN timeout (`ATST96`, about
 *   0.6 s per frame) bounds a healthy answer far below the default.
 */
class Elm327Transport(
    private val link: GattLink,
    scope: CoroutineScope,
    private val promptTimeout: Duration = DEFAULT_PROMPT_TIMEOUT,
) {
    private val chunks = Channel<String>(Channel.UNLIMITED)
    private val buffer = StringBuilder()
    private val serial = Mutex()

    @Volatile
    private var linkFailure: Throwable? = null

    /**
     * Set when a command was abandoned before its prompt (timeout, cancellation): the late rest of
     * its answer must not be read as the next command's.
     */
    private var owesPrompt = false

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // Latin-1 maps every byte to one char, so a notification boundary never splits one.
                link.notifications.collect { chunks.send(String(it, Charsets.ISO_8859_1)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                linkFailure = e
            } finally {
                chunks.close()
            }
        }
    }

    /**
     * Sends [command] and returns its answer: the text before the next `>`, with a leading echo of
     * the command removed, line ends normalised to `\n`, and surrounding whitespace trimmed.
     *
     * @throws Elm327Exception.PromptTimeout when no prompt arrives within the prompt timeout.
     * @throws Elm327Exception.LinkLost when the link drops, before or during the exchange.
     */
    suspend fun command(command: String): String =
        serial.withLock {
            if (owesPrompt) {
                // An answer that has not come in by now is lost; whatever arrives later is stray text.
                withTimeoutOrNull(promptTimeout) { readUntilPrompt() }
                buffer.clear()
                owesPrompt = false
            }
            write((command + "\r").encodeToByteArray())
            owesPrompt = true
            val raw =
                withTimeoutOrNull(promptTimeout) { readUntilPrompt() }
                    ?: throw Elm327Exception.PromptTimeout(command, promptTimeout)
            owesPrompt = false
            clean(command, raw)
        }

    /**
     * Writes in PDUs of at most `mtu - 3` bytes. Not cancellable: half a command would join the
     * next one into a line the adapter cannot parse.
     */
    private suspend fun write(bytes: ByteArray) =
        withContext(NonCancellable) {
            val size = link.mtu - 3
            for (start in bytes.indices step size) {
                try {
                    link.write(bytes.copyOfRange(start, minOf(start + size, bytes.size)), withResponse = false)
                } catch (e: IOException) {
                    throw Elm327Exception.LinkLost(e)
                }
            }
        }

    /** Takes the buffered text up to the next prompt, consuming the prompt. */
    private suspend fun readUntilPrompt(): String {
        while (true) {
            val at = buffer.indexOf(PROMPT)
            if (at >= 0) {
                val answer = buffer.substring(0, at)
                buffer.delete(0, at + 1)
                return answer
            }
            val chunk = chunks.receiveCatching().getOrNull() ?: throw Elm327Exception.LinkLost(linkFailure)
            buffer.append(chunk)
        }
    }

    private fun clean(
        command: String,
        raw: String,
    ): String {
        // Some clones pad their output with NULs.
        val text = raw.replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n')
        val echoEnd = command.length
        val echoed =
            text.startsWith(command, ignoreCase = true) &&
                (text.length == echoEnd || text[echoEnd] == '\n')
        return (if (echoed) text.substring(echoEnd) else text).trim()
    }

    companion object {
        val DEFAULT_PROMPT_TIMEOUT: Duration = 5.seconds

        private const val PROMPT = ">"
    }
}

/** ELM327 exchange failures; [IOException]s like every link failure. */
sealed class Elm327Exception(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    /**
     * No `>` prompt within [timeout] after [command] went out: the adapter hangs or the link is
     * stalled. The next command first gives the late answer one more [timeout] to finish.
     */
    class PromptTimeout(
        val command: String,
        val timeout: Duration,
    ) : Elm327Exception("no prompt within $timeout after '$command'")

    /** The link dropped (notifications completed, or a write failed). */
    class LinkLost(
        cause: Throwable? = null,
    ) : Elm327Exception("BLE link to the ELM327 lost", cause)
}
