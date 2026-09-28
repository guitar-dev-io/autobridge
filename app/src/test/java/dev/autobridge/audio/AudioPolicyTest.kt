package dev.autobridge.audio

import org.junit.Assert.*
import org.junit.Test

class AudioFocusPolicyTest {
    private val policy = AudioFocusPolicy()

    @Test fun grantedFocusStartsPlayback() {
        assertEquals(AudioFocusAction.PLAY, policy.onFocusGranted())
        assertEquals(AudioFocusState.GAINED, policy.state)
    }

    @Test fun deniedFocusStartsNothing() {
        assertEquals(AudioFocusAction.NOTHING, policy.onFocusDenied())
        assertEquals(AudioFocusState.NONE, policy.state)
    }

    /** A navigation prompt or a call: pause, and come back when it is over. */
    @Test fun transientLossPausesAndResumes() {
        policy.onFocusGranted()
        assertEquals(
            AudioFocusAction.PAUSE,
            policy.onFocusChange(AudioFocusConstants.LOSS_TRANSIENT, isPlaying = true)
        )
        assertEquals(AudioFocusState.TRANSIENT_LOSS, policy.state)
        assertTrue(policy.resumeWhenFocusReturns)
        assertEquals(AudioFocusAction.PLAY, policy.onFocusChange(AudioFocusConstants.GAIN, isPlaying = false))
        assertEquals(AudioFocusState.GAINED, policy.state)
        assertFalse(policy.resumeWhenFocusReturns)
    }

    /** Another app took over; barging back in when focus returns would fight the user. */
    @Test fun permanentLossPausesAndDoesNotResume() {
        policy.onFocusGranted()
        assertEquals(
            AudioFocusAction.PAUSE,
            policy.onFocusChange(AudioFocusConstants.LOSS, isPlaying = true)
        )
        assertEquals(AudioFocusState.PERMANENT_LOSS, policy.state)
        assertFalse(policy.resumeWhenFocusReturns)
        assertEquals(
            AudioFocusAction.NOTHING,
            policy.onFocusChange(AudioFocusConstants.GAIN, isPlaying = false)
        )
    }

    @Test fun duckingKeepsPlayingAndIsUndoneOnGain() {
        policy.onFocusGranted()
        assertEquals(
            AudioFocusAction.DUCK,
            policy.onFocusChange(AudioFocusConstants.LOSS_TRANSIENT_CAN_DUCK, isPlaying = true)
        )
        assertEquals(AudioFocusState.DUCKED, policy.state)
        assertFalse(policy.resumeWhenFocusReturns)
        assertEquals(
            AudioFocusAction.UNDUCK,
            policy.onFocusChange(AudioFocusConstants.GAIN, isPlaying = true)
        )
    }

    @Test fun lossWhileAlreadyPausedDoesNotScheduleAResume() {
        policy.onFocusGranted()
        assertEquals(
            AudioFocusAction.NOTHING,
            policy.onFocusChange(AudioFocusConstants.LOSS_TRANSIENT, isPlaying = false)
        )
        assertFalse(policy.resumeWhenFocusReturns)
        assertEquals(
            AudioFocusAction.NOTHING,
            policy.onFocusChange(AudioFocusConstants.GAIN, isPlaying = false)
        )
    }

    @Test fun abandoningFocusClearsAnyPendingResume() {
        policy.onFocusGranted()
        policy.onFocusChange(AudioFocusConstants.LOSS_TRANSIENT, isPlaying = true)
        policy.onFocusAbandoned()
        assertEquals(AudioFocusState.NONE, policy.state)
        assertFalse(policy.resumeWhenFocusReturns)
    }
}

class AudioRouteTest {
    @Test fun eachDeviceFamilyIsRecognised() {
        assertEquals(AudioRouteKind.CAR, AudioRoute.classify(AudioRoute.TYPE_BUS))
        assertEquals(AudioRouteKind.BLUETOOTH, AudioRoute.classify(AudioRoute.TYPE_BLUETOOTH_A2DP))
        assertEquals(AudioRouteKind.BLUETOOTH, AudioRoute.classify(AudioRoute.TYPE_BLE_HEADSET))
        assertEquals(AudioRouteKind.USB, AudioRoute.classify(AudioRoute.TYPE_USB_HEADSET))
        assertEquals(AudioRouteKind.WIRED, AudioRoute.classify(AudioRoute.TYPE_WIRED_HEADPHONES))
        assertEquals(AudioRouteKind.SPEAKER, AudioRoute.classify(AudioRoute.TYPE_BUILTIN_SPEAKER))
    }

    @Test fun anUnknownTypeIsReportedAsOtherRatherThanGuessed() {
        assertEquals(AudioRouteKind.OTHER, AudioRoute.classify(9999))
    }

    /** The built-in speaker is always listed, so picking the first device would always say Speaker. */
    @Test fun theCarWinsOverEverythingElseThatIsConnected() {
        val devices = listOf(
            AudioRoute.TYPE_BUILTIN_SPEAKER,
            AudioRoute.TYPE_BLUETOOTH_A2DP,
            AudioRoute.TYPE_BUS,
        )
        assertEquals(AudioRouteKind.CAR, AudioRoute.preferredOutput(devices))
    }

    @Test fun bluetoothWinsOverTheBuiltInSpeaker() {
        assertEquals(
            AudioRouteKind.BLUETOOTH,
            AudioRoute.preferredOutput(listOf(AudioRoute.TYPE_BUILTIN_SPEAKER, AudioRoute.TYPE_BLUETOOTH_A2DP))
        )
    }

    @Test fun theSpeakerIsUsedWhenNothingElseIsConnected() {
        assertEquals(
            AudioRouteKind.SPEAKER,
            AudioRoute.preferredOutput(listOf(AudioRoute.TYPE_BUILTIN_SPEAKER))
        )
    }

    @Test fun noDevicesIsUnknownNotSpeaker() {
        assertEquals(AudioRouteKind.UNKNOWN, AudioRoute.preferredOutput(emptyList()))
    }
}

/**
 * A page returns whatever it likes from the bridge's script, so parsing must never throw into the
 * WebView's evaluate callback.
 */
class WebMediaStatusTest {
    @Test fun readsStateAndMediaSessionMetadata() {
        val status = WebMediaStatus.parse(
            """{"playing":true,"duration":215000,"position":42000,"title":"Better Days",
               "artist":"Sam Feldt","album":"Sunrise","artwork":"https://x/a.png"}"""
        )
        assertTrue(status.playing)
        assertEquals(215_000L, status.durationMs)
        assertEquals(42_000L, status.positionMs)
        assertEquals("Better Days", status.title)
        assertTrue(status.hasMetadata)
    }

    @Test fun aPageWithNoMetadataIsStillUsableForState() {
        val status = WebMediaStatus.parse("""{"playing":true,"duration":0,"position":0}""")
        assertTrue(status.playing)
        assertFalse(status.hasMetadata)
    }

    @Test fun malformedOrEmptyOutputYieldsAnEmptyStatus() {
        listOf("", "null", "not json", "{", "[]").forEach { raw ->
            val status = WebMediaStatus.parse(raw)
            assertFalse("parsed $raw as playing", status.playing)
            assertEquals(0L, status.durationMs)
        }
    }

    @Test fun negativeTimesFromAPageAreClampedNotPassedOn() {
        val status = WebMediaStatus.parse("""{"playing":true,"duration":-5,"position":-1}""")
        assertEquals(0L, status.durationMs)
        assertEquals(0L, status.positionMs)
    }
}
