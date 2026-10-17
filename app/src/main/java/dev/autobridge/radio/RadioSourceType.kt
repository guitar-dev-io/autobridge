package dev.autobridge.radio

/**
 * The three honest kinds of radio this app distinguishes. Exactly one is the *active* source (owns
 * the shared Media3 queue and audio) at a time; the user's *selected* source may differ when the
 * selection is one this device cannot play.
 *
 *  - [INTERNET_RADIO]: a streamed station, played through the existing Media3 session.
 *  - [HARDWARE_FM]: a physical FM tuner. Never playable on this target — see [HardwareFmProbe] —
 *    and never persisted anywhere, because there is no stream URL to replay.
 *  - [OFFLINE_AUDIO]: local audio files. Modelled for completeness; unwired this pass.
 */
enum class RadioSourceType { INTERNET_RADIO, HARDWARE_FM, OFFLINE_AUDIO }
