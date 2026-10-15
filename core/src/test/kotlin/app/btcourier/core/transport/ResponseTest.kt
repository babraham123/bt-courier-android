package app.btcourier.core.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ResponseTest {
    @Test
    fun `responses with equal content are equal`() {
        val a = Response(200, mapOf("content-type" to "application/json"), "{}".encodeToByteArray())
        val b = Response(200, mapOf("content-type" to "application/json"), "{}".encodeToByteArray())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `responses with different bodies differ`() {
        assertNotEquals(
            Response(200, emptyMap(), byteArrayOf(1)),
            Response(200, emptyMap(), byteArrayOf(2)),
        )
    }
}
