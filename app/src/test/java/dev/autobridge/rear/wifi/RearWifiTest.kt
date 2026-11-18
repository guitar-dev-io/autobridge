package dev.autobridge.rear.wifi

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RearWifiTest {

    // --- protocol ---

    @Test fun aPlayCommandSurvivesTheTrip() {
        val play = RearProtocol.Play("http://tv/ch1.m3u8", "Ch 1", 61_000L, muted = true)
        assertEquals(play, RearProtocol.Play.parse(play.toJson()))
    }

    @Test fun aPlayWithoutAUrlOrNotJsonIsRefused() {
        assertNull(RearProtocol.Play.parse("""{"title":"x"}"""))
        assertNull(RearProtocol.Play.parse("not json"))
        assertEquals(0L, RearProtocol.Play.parse("""{"url":"u","positionMs":-5}""")!!.positionMs)
    }

    // --- pairing ---

    private class Clock(var ms: Long = 0L) { fun now() = ms }

    @Test fun theRightCodeGivesATokenAndIsThenSpent() {
        val saved = mutableListOf<Set<String>>()
        val gate = PairingGate(onTokensChanged = { saved += it })
        val code = gate.code
        val result = gate.pair(code) as PairingGate.Result.Paired
        assertTrue(gate.isAuthorized(result.token))
        assertEquals(setOf(result.token), saved.last())
        assertNotEquals("a used code must not pair a second phone", code, gate.code)
    }

    @Test fun fiveWrongCodesLockPairingForAMinute() {
        val clock = Clock()
        val gate = PairingGate(now = clock::now)
        val wrong = if (gate.code == "0000") "0001" else "0000"
        repeat(5) { assertEquals(PairingGate.Result.WrongCode, gate.pair(wrong)) }
        assertEquals(PairingGate.Result.Locked, gate.pair(gate.code))
        clock.ms += 60_001L
        assertTrue(gate.pair(gate.code) is PairingGate.Result.Paired)
    }

    @Test fun savedTokensStillWorkAndForgetAllDropsThem() {
        val gate = PairingGate(tokens = setOf("abc"))
        assertTrue(gate.isAuthorized("abc"))
        assertFalse(gate.isAuthorized("other"))
        assertFalse(gate.isAuthorized(null))
        gate.forgetAll()
        assertFalse(gate.isAuthorized("abc"))
    }

    // --- server routing ---

    private class FakeTarget : RearServer.Target {
        val calls = mutableListOf<String>()
        override fun play(command: RearProtocol.Play) { calls += "play ${command.url} muted=${command.muted}" }
        override fun toggle() { calls += "toggle" }
        override fun mute() { calls += "mute" }
        override fun stop() { calls += "stop" }
        override fun status() = RearProtocol.Status(playing = true, muted = false, title = "t")
    }

    private fun request(method: String, path: String, body: String = "", token: String? = null) =
        RearServer.Request(method, path, token?.let { mapOf("x-autobridge-token" to it) } ?: emptyMap(), body)

    @Test fun commandsNeedAPairedPhone() {
        val target = FakeTarget()
        val gate = PairingGate(tokens = setOf("good"))
        val server = RearServer(gate, RearProtocol.Info("id1", "Tab"), target)

        assertEquals(200, server.handle(request("GET", "/info")).code)
        assertEquals(401, server.handle(request("POST", "/play", """{"url":"u"}""")).code)
        assertEquals(401, server.handle(request("POST", "/stop", token = "bad")).code)
        assertTrue(target.calls.isEmpty())

        assertEquals(200, server.handle(request("POST", "/play", """{"url":"u","muted":true}""", "good")).code)
        assertEquals(200, server.handle(request("POST", "/mute", token = "good")).code)
        assertEquals(200, server.handle(request("POST", "/stop", token = "good")).code)
        assertEquals(listOf("play u muted=true", "mute", "stop"), target.calls)
        assertEquals(400, server.handle(request("POST", "/play", "{}", "good")).code)
        assertEquals(404, server.handle(request("GET", "/nope", token = "good")).code)
    }

    @Test fun pairingOverHttpHandsBackAToken() {
        val gate = PairingGate()
        val server = RearServer(gate, RearProtocol.Info("id1", "Tab"), FakeTarget())
        assertEquals(403, server.handle(request("POST", "/pair", RearProtocol.pairBody("x"))).code)
        val reply = server.handle(request("POST", "/pair", RearProtocol.pairBody(gate.code)))
        assertEquals(200, reply.code)
        val token = RearProtocol.token(reply.body)
        assertTrue(gate.isAuthorized(token))
        assertEquals(200, server.handle(request("GET", "/status", token = token)).code)
    }

    @Test fun anHttpRequestIsReadWithItsHeadersAndBody() {
        val body = """{"url":"u"}"""
        val raw = "POST /play HTTP/1.1\r\nHost: x\r\nX-AutoBridge-Token: t1\r\nContent-Length: ${body.length}\r\n\r\n$body"
        val request = RearServer.readRequest(ByteArrayInputStream(raw.toByteArray()))!!
        assertEquals("POST", request.method)
        assertEquals("/play", request.path)
        assertEquals("t1", request.headers["x-autobridge-token"])
        assertEquals(body, request.body)
    }

    @Test fun anOversizedOrBrokenRequestIsRefused() {
        assertNull(RearServer.readRequest(ByteArrayInputStream("POST /play HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n".toByteArray())))
        assertNull(RearServer.readRequest(ByteArrayInputStream(ByteArray(0))))
    }

    // --- sending ---

    @Test fun onlyAddressesTheTabletCanOpenAreSent() {
        assertTrue(RearSender.canReach("http://tv.example/ch1.m3u8"))
        assertTrue(RearSender.canReach("https://192.168.43.1:8080/a.mp4"))
        assertFalse(RearSender.canReach("content://media/external/video/media/12"))
        assertFalse(RearSender.canReach("file:///sdcard/Movies/a.mp4"))
        assertFalse(RearSender.canReach("/sdcard/Movies/a.mp4"))
        assertFalse(RearSender.canReach("http://127.0.0.1:8080/proxy.m3u8"))
        assertFalse(RearSender.canReach("http://localhost/a.mp4"))
        assertFalse(RearSender.canReach("not a url"))
    }

    @Test fun aRefusalSaysWhetherToPairAgain() {
        assertEquals(RearSender.Outcome.SENT, RearSender.outcomeOf(RearClient.Reply(200, "{}")))
        assertEquals(RearSender.Outcome.NOT_PAIRED, RearSender.outcomeOf(RearClient.Reply(401, "{}")))
        assertEquals(RearSender.Outcome.FAILED, RearSender.outcomeOf(RearClient.Reply(400, "{}")))
        assertEquals(RearSender.Outcome.UNREACHABLE, RearSender.outcomeOf(null))
    }

    // --- the phone's tablet list ---

    @Test fun pairedTabletsSurviveSavingAndBadEntriesAreDropped() {
        val tablets = listOf(
            RearTablets.Tablet("a", "Tab A", "t1", "192.168.43.20", 47820),
            RearTablets.Tablet("b", "Tab B", "t2", "192.168.43.21", 47821),
        )
        assertEquals(tablets, RearTablets.decode(RearTablets.encode(tablets)))
        assertEquals(emptyList<RearTablets.Tablet>(), RearTablets.decode("""[{"id":"x"}]"""))
        assertEquals(emptyList<RearTablets.Tablet>(), RearTablets.decode("garbage"))
        assertEquals(emptyList<RearTablets.Tablet>(), RearTablets.decode(null))
    }
}
