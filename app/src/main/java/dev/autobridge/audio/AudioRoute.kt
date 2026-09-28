package dev.autobridge.audio

/** Where the app's audio is actually coming out, in the terms a user would recognise. */
enum class AudioRouteKind(val label: String) {
    CAR("Car audio"),
    BLUETOOTH("Bluetooth"),
    USB("USB"),
    WIRED("Wired"),
    SPEAKER("Speaker"),
    OTHER("Other"),
    UNKNOWN("Unknown"),
}

/**
 * Classifies `AudioDeviceInfo.TYPE_*` values into [AudioRouteKind].
 *
 * The constants are mirrored rather than imported so the mapping is testable without an Android
 * runtime; they are platform API values and do not change. Anything unrecognised becomes [OTHER]
 * rather than being guessed at, so a new device type shows up honestly in diagnostics instead of
 * being mislabelled as the speaker.
 */
object AudioRoute {
    // android.media.AudioDeviceInfo type constants.
    const val TYPE_BUILTIN_EARPIECE = 1
    const val TYPE_BUILTIN_SPEAKER = 2
    const val TYPE_WIRED_HEADSET = 3
    const val TYPE_WIRED_HEADPHONES = 4
    const val TYPE_BLUETOOTH_SCO = 7
    const val TYPE_BLUETOOTH_A2DP = 8
    const val TYPE_HDMI = 9
    const val TYPE_USB_ACCESSORY = 12
    const val TYPE_USB_DEVICE = 13
    const val TYPE_USB_HEADSET = 22
    const val TYPE_BUS = 21
    const val TYPE_BLE_HEADSET = 26
    const val TYPE_BLE_SPEAKER = 27
    const val TYPE_BLE_BROADCAST = 30

    fun classify(deviceType: Int): AudioRouteKind = when (deviceType) {
        // A head unit exposes itself as an audio bus; on Android Auto over USB the phone still
        // renders locally, so this is the case that means "the car is the sink".
        TYPE_BUS -> AudioRouteKind.CAR
        TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO,
        TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST -> AudioRouteKind.BLUETOOTH
        TYPE_USB_DEVICE, TYPE_USB_HEADSET, TYPE_USB_ACCESSORY -> AudioRouteKind.USB
        TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES -> AudioRouteKind.WIRED
        TYPE_BUILTIN_SPEAKER, TYPE_BUILTIN_EARPIECE -> AudioRouteKind.SPEAKER
        TYPE_HDMI -> AudioRouteKind.OTHER
        else -> AudioRouteKind.OTHER
    }

    /**
     * Picks the device that actually carries media out of a list of output devices.
     *
     * Android lists every reachable sink, and the built-in speaker is always among them, so the
     * first entry is not the answer. Preference follows what a driver would expect to hear from:
     * the car, then a paired headset, then anything wired, and only then the phone's own speaker.
     */
    fun preferredOutput(deviceTypes: List<Int>): AudioRouteKind {
        if (deviceTypes.isEmpty()) return AudioRouteKind.UNKNOWN
        val kinds = deviceTypes.map(::classify)
        return listOf(
            AudioRouteKind.CAR,
            AudioRouteKind.BLUETOOTH,
            AudioRouteKind.USB,
            AudioRouteKind.WIRED,
            AudioRouteKind.SPEAKER,
            AudioRouteKind.OTHER,
        ).firstOrNull { it in kinds } ?: AudioRouteKind.UNKNOWN
    }
}
