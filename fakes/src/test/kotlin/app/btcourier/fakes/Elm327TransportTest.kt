package app.btcourier.fakes

import app.btcourier.core.elm327.Elm327Exception
import app.btcourier.core.elm327.Elm327Transport
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [Elm327Transport] against [FakeElm327Adapter] and a bare [FakeGattLink]; framing per the ELM327 datasheet. */
class Elm327TransportTest {
    private fun TestScope.connect(
        adapter: FakeElm327Adapter,
        promptTimeout: Duration = Elm327Transport.DEFAULT_PROMPT_TIMEOUT,
    ) = Elm327Transport(adapter.link, backgroundScope, promptTimeout)

    private suspend fun failureOf(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (e: IOException) {
            e
        }

    @Test
    fun `the echo of the command is removed`() =
        runTest {
            val adapter = FakeElm327Adapter()
            val transport = connect(adapter)
            assertTrue(adapter.echo)
            assertEquals("12.6V", transport.command("ATRV"))
            assertEquals("ELM327 v2.2", transport.command("ATZ"))
            assertEquals("OK", transport.command("ATE0"))
            assertEquals("12.6V", transport.command("ATRV"))
        }

    @Test
    fun `commands end with CR and go out in MTU-sized writes without response`() =
        runTest {
            val adapter = FakeElm327Adapter(mtu = 23)
            val transport = connect(adapter)
            val long = "ATSH" + "7E4".repeat(7)
            transport.command(long)
            val writes = adapter.link.writes
            assertEquals(listOf(20, 6), writes.map { it.size })
            assertEquals("$long\r", writes.joinToString("") { it.decodeToString() })
            assertTrue(adapter.link.writesWithResponse.none { it })
            assertEquals(listOf(long), adapter.commands)
        }

    @Test
    fun `an answer split across many notifications is reassembled up to the prompt`() =
        runTest {
            val adapter = FakeElm327Adapter(mapOf(Ev6Fixture.COMMAND to Ev6Fixture.RESPONSE), notifySize = 1)
            val transport = connect(adapter)
            transport.command("ATE0")
            assertEquals(Ev6Fixture.RESPONSE.replace('\r', '\n'), transport.command(Ev6Fixture.COMMAND))
        }

    @Test
    fun `line ends are normalised to LF`() =
        runTest {
            val adapter = FakeElm327Adapter(mapOf("0100" to "7E8 06 41 00 BE 3F A8 13\r7E9 06 41 00 98 18 80 11"))
            val transport = connect(adapter)
            transport.command("ATL1")
            assertEquals("7E8 06 41 00 BE 3F A8 13\n7E9 06 41 00 98 18 80 11", transport.command("0100"))
        }

    @Test
    fun `bytes after the prompt stay buffered for the next command`() =
        runTest {
            var answered = false
            val link =
                FakeGattLink { _, _ ->
                    // Both answers arrive in one notification, right after the first command.
                    if (!answered) notify("FIRST\r\r>SEC".encodeToByteArray())
                    answered = true
                }
            val transport = Elm327Transport(link, backgroundScope)
            assertEquals("FIRST", transport.command("A"))
            launch {
                delay(100)
                link.notify("OND\r\r>".encodeToByteArray())
            }
            assertEquals("SECOND", transport.command("B"))
        }

    @Test
    fun `no prompt within the timeout is a PromptTimeout`() =
        runTest {
            val adapter = FakeElm327Adapter()
            adapter.silent += "010D"
            val transport = connect(adapter, promptTimeout = 1.seconds)
            val failure = failureOf { transport.command("010D") }
            val timeout = assertInstanceOf(Elm327Exception.PromptTimeout::class.java, failure)
            assertEquals("010D", timeout.command)
            assertEquals(1000L, testScheduler.currentTime)
        }

    @Test
    fun `a late answer after a timeout is not taken for the next command's`() =
        runTest {
            val adapter = FakeElm327Adapter(mapOf("010D" to "7E8 03 41 0D 32"), scope = backgroundScope)
            adapter.delays["010D"] = 6.seconds
            val transport = connect(adapter)
            transport.command("ATE0")
            assertInstanceOf(Elm327Exception.PromptTimeout::class.java, failureOf { transport.command("010D") })
            assertEquals("12.6V", transport.command("ATRV"))
            adapter.delays.clear()
            assertEquals("7E8 03 41 0D 32", transport.command("010D"))
        }

    @Test
    fun `a cancelled command's answer is not taken for the next command's`() =
        runTest {
            val adapter = FakeElm327Adapter(mapOf("010D" to "7E8 03 41 0D 32"), scope = backgroundScope)
            adapter.delays["010D"] = 1.seconds
            val transport = connect(adapter)
            transport.command("ATE0")
            val abandoned = launch { transport.command("010D") }
            delay(500.milliseconds)
            abandoned.cancel()
            assertEquals("12.6V", transport.command("ATRV"))
        }

    @Test
    fun `concurrent commands run one at a time in call order`() =
        runTest {
            val adapter = FakeElm327Adapter(mapOf("010D" to "7E8 03 41 0D 32"), scope = backgroundScope)
            adapter.delays["010D"] = 1.seconds
            val transport = connect(adapter)
            transport.command("ATE0")
            val first = async { transport.command("010D") }
            val second = async { transport.command("ATRV") }
            delay(500.milliseconds)
            // The second command waits for the first answer before it is written.
            assertEquals(listOf("ATE0", "010D"), adapter.commands)
            assertEquals("7E8 03 41 0D 32", first.await())
            assertEquals("12.6V", second.await())
            assertEquals(listOf("ATE0", "010D", "ATRV"), adapter.commands)
        }

    @Test
    fun `a link that dropped before the command is LinkLost`() =
        runTest {
            val adapter = FakeElm327Adapter()
            val transport = connect(adapter)
            adapter.drop()
            assertInstanceOf(Elm327Exception.LinkLost::class.java, failureOf { transport.command("ATRV") })
        }

    @Test
    fun `a link dropping while an answer is owed is LinkLost`() =
        runTest {
            val adapter = FakeElm327Adapter()
            adapter.silent += "010D"
            val transport = connect(adapter)
            launch {
                delay(1.seconds)
                adapter.drop()
            }
            assertInstanceOf(Elm327Exception.LinkLost::class.java, failureOf { transport.command("010D") })
            assertEquals(1000L, testScheduler.currentTime)
        }
}
