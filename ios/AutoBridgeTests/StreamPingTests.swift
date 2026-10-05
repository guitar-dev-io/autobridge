import XCTest
@testable import AutoBridge

/// The decisions `StreamPing` makes before and after the one network call: what an address resolves
/// to, and what a status code means for a channel. Both are pure, so they are pinned down here; the
/// probe itself needs a server and is covered by the hardware pass instead. Mirrors the Android
/// `StreamPingTest`.
final class StreamPingTests: XCTestCase {

    // MARK: - What is connectable

    func testHttpAddressKeepsItsDefaultPortAndIsProbedOverHttp() {
        let endpoint = StreamPing.endpoint("http://portal.tv/live/1.ts")
        XCTAssertEqual(endpoint?.host, "portal.tv")
        XCTAssertEqual(endpoint?.port, 80)
        XCTAssertEqual(endpoint?.httpLike, true)
    }

    func testHttpsDefaultsTo443() {
        XCTAssertEqual(StreamPing.endpoint("https://portal.tv/live/1.m3u8")?.port, 443)
    }

    func testAnExplicitPortWins() {
        let endpoint = StreamPing.endpoint("http://portal.tv:8080/live/1.ts")
        XCTAssertEqual(endpoint?.host, "portal.tv")
        XCTAssertEqual(endpoint?.port, 8080)
    }

    func testCredentialsInTheAuthorityAreNotMistakenForTheHost() {
        XCTAssertEqual(StreamPing.endpoint("http://user:pw@portal.tv/live")?.host, "portal.tv")
    }

    func testIPv6LiteralSurvivesThePortSplit() {
        let endpoint = StreamPing.endpoint("http://[2001:db8::1]:8080/live/1.ts")
        XCTAssertEqual(endpoint?.host, "2001:db8::1")
        XCTAssertEqual(endpoint?.port, 8080)
    }

    func testBracketedIPv6WithoutAPortTakesTheSchemePort() {
        let endpoint = StreamPing.endpoint("http://[2001:db8::1]/live/1.ts")
        XCTAssertEqual(endpoint?.host, "2001:db8::1")
        XCTAssertEqual(endpoint?.port, 80)
    }

    func testNonHttpStreamSchemeIsProbedByConnectingToItsOwnPort() {
        let rtmp = StreamPing.endpoint("rtmp://edge.example.com/live/stream")
        XCTAssertEqual(rtmp?.port, 1935)
        XCTAssertEqual(rtmp?.httpLike, false)
        XCTAssertEqual(StreamPing.endpoint("rtsp://cam.example.com/stream")?.port, 554)
    }

    func testAnAddressNothingCanConnectToIsNotAnAddress() {
        // Multicast answers no connection, so it is reported as not checkable, never as dead.
        XCTAssertNil(StreamPing.endpoint("udp://@239.0.0.1:1234"))
        XCTAssertNil(StreamPing.endpoint("rtp://239.0.0.1:1234"))
        XCTAssertNil(StreamPing.endpoint("/var/mobile/clip.mp4"))
        XCTAssertNil(StreamPing.endpoint("http://"))
        XCTAssertNil(StreamPing.endpoint(""))
    }

    func testAPortOutsideTheRangeFallsBackToTheSchemesOwn() {
        XCTAssertEqual(StreamPing.endpoint("http://portal.tv:0/live")?.port, 80)
        XCTAssertEqual(StreamPing.endpoint("http://portal.tv:99999/live")?.port, 80)
    }

    // MARK: - What a status means

    func testAServableStatusIsAlive() {
        XCTAssertEqual(StreamPing.classify(status: 200, millis: 12), .alive(millis: 12))
        // Many providers answer a ranged request with 206, and some with a redirect to a token URL.
        XCTAssertEqual(StreamPing.classify(status: 206, millis: 12), .alive(millis: 12))
        XCTAssertEqual(StreamPing.classify(status: 302, millis: 12), .alive(millis: 12))
    }

    func testARefusalKeepsItsStatusBecauseTheStatusIsTheDiagnosis() {
        XCTAssertEqual(StreamPing.classify(status: 403, millis: 9), .refused(status: 403, millis: 9))
        XCTAssertEqual(StreamPing.classify(status: 404, millis: 9), .refused(status: 404, millis: 9))
        XCTAssertEqual(StreamPing.classify(status: 503, millis: 9), .refused(status: 503, millis: 9))
    }

    // MARK: - How a result reads

    func testEachResultReadsAsARowSubtitle() {
        XCTAssertEqual(StreamPing.describe(.alive(millis: 142)), "142 ms")
        XCTAssertEqual(StreamPing.describe(.refused(status: 403, millis: 20)), "HTTP 403")
        XCTAssertEqual(StreamPing.describe(.unreachable(reason: "Timed out")), "Timed out")
        XCTAssertEqual(StreamPing.describe(.unsupported), "Not checkable")
    }

    func testAFastAnswerReadsAsGoodAndASlowOneAsSlow() {
        XCTAssertEqual(StreamPing.tone(.alive(millis: 120)), .good)
        XCTAssertEqual(StreamPing.tone(.alive(millis: 1_000)), .good)
        XCTAssertEqual(StreamPing.tone(.alive(millis: 1_001)), .slow)
    }

    func testARefusalAndASilenceBothReadAsBad() {
        XCTAssertEqual(StreamPing.tone(.refused(status: 403, millis: 20)), .bad)
        XCTAssertEqual(StreamPing.tone(.unreachable(reason: "Timed out")), .bad)
    }

    func testAnAddressThatCannotBeProbedIsNotCalledDead() {
        // The middle reading: it is neither an answer nor a refusal, so it must not read as red.
        XCTAssertEqual(StreamPing.tone(.unsupported), .slow)
    }

    // MARK: - The remembered answers

    func testForgettingAnAddressThatWasNeverCheckedChangesNothing() {
        StreamPing.forget(["http://portal.tv/live/never-checked.ts", ""])
        XCTAssertNil(StreamPing.cached("http://portal.tv/live/never-checked.ts"))
    }

    func testNothingIsRememberedAboutAnAddressThatWasNeverChecked() {
        XCTAssertNil(StreamPing.cached("http://portal.tv/live/never-checked.ts"))
    }

    /// A sweep of nothing still has to report once with `done`, because every surface waits for
    /// that call before it stops saying "checking".
    func testASweepOfNothingStillFinishes() {
        let finished = expectation(description: "progress reported done")
        StreamPing.checkAll([]) { progress in
            XCTAssertTrue(progress.done)
            XCTAssertEqual(progress.total, 0)
            finished.fulfill()
        }
        wait(for: [finished], timeout: 2)
    }

    /// An address nothing can connect to is answered without a network round trip, which is what
    /// makes it safe to run the automatic pass over a page of mixed addresses.
    func testAnUncheckableAddressIsAnsweredWithoutANetwork() {
        let answered = expectation(description: "result delivered")
        StreamPing.check("udp://@239.0.0.1:1234") { result in
            XCTAssertEqual(result, .unsupported)
            answered.fulfill()
        }
        wait(for: [answered], timeout: 2)
    }
}
