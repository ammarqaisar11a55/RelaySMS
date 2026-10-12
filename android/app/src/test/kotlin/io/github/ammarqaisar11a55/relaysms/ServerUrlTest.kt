package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.core.network.ServerUrl
import io.github.ammarqaisar11a55.relaysms.core.network.ServerUrl.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    @Test fun `https url is accepted and normalised`() {
        assertEquals(Result.Valid("https://relay.example.com"), ServerUrl.parse("  HTTPS://Relay.Example.com/ ", false))
        assertEquals(Result.Valid("https://relay.example.com:8443/base"), ServerUrl.parse("https://relay.example.com:8443/base/", false))
    }

    @Test fun `cleartext is rejected in release configuration`() {
        assertTrue(ServerUrl.parse("http://10.0.2.2:8080", false) is Result.Invalid)
        assertTrue(ServerUrl.parse("http://relay.example.com", false) is Result.Invalid)
    }

    @Test fun `cleartext is allowed only for local hosts in debug configuration`() {
        assertEquals(Result.Valid("http://10.0.2.2:8080"), ServerUrl.parse("http://10.0.2.2:8080", true))
        assertEquals(Result.Valid("http://localhost:8080"), ServerUrl.parse("http://localhost:8080", true))
        assertTrue(ServerUrl.parse("http://relay.example.com", true) is Result.Invalid)
        assertTrue(ServerUrl.parse("http://192.168.1.10:8080", true) is Result.Invalid)
    }

    @Test fun `malformed and dangerous input is rejected`() {
        for (bad in listOf("", "   ", "relay.example.com", "ftp://relay.example.com", "https://", "https://user:pw@relay.example.com",
            "https://relay.example.com/?a=b", "https://relay.example.com/#x", "https://exa mple.com")) {
            assertTrue("expected invalid: '$bad'", ServerUrl.parse(bad, true) is Result.Invalid)
        }
    }

    @Test fun `websocket url keeps transport security`() {
        assertEquals("wss://relay.example.com/v1/ws", ServerUrl.toWebSocketUrl("https://relay.example.com", "/v1/ws"))
        assertEquals("ws://10.0.2.2:8080/v1/ws", ServerUrl.toWebSocketUrl("http://10.0.2.2:8080", "/v1/ws"))
    }
}
