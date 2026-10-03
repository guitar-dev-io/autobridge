package dev.autobridge.remotestream

/**
 * Annex-B bitstream inspection, with no Android types in sight.
 *
 * Separate from [H264Decoder] because the two things it answers — "can a decoder be configured
 * from this buffer" and "is this a point playback can start at" — are the parts of the remote
 * stream path most worth testing, and they are testable only if reading them does not drag
 * `MediaCodec` and `MediaFormat` into a JVM test.
 */
object H264Nal {

    /** The NAL type carrying a sequence parameter set. */
    const val TYPE_SPS = 7

    /** The NAL type carrying a picture parameter set. */
    const val TYPE_PPS = 8

    /** An IDR slice: a picture a decoder can start from with no earlier frames. */
    const val TYPE_IDR = 5

    /**
     * True when [data] carries a sequence parameter set, i.e. is enough to configure a decoder.
     *
     * Walks every start code rather than looking at the first NAL only: an access unit from a
     * typical encoder is `SPS | PPS | IDR` concatenated, but the order is the encoder's choice
     * and an access-unit delimiter or SEI in front of it is perfectly legal.
     */
    fun containsParameterSet(data: ByteArray): Boolean = types(data).contains(TYPE_SPS)

    fun isKeyFrame(data: ByteArray): Boolean = types(data).contains(TYPE_IDR)

    /**
     * Every NAL unit type in an Annex-B buffer, in order.
     *
     * Both start-code forms are recognised. A byte that begins neither is skipped rather than
     * treated as an error: a stream joined mid-flight starts with the tail of whatever frame was
     * in flight, and refusing to parse that would mean refusing the first frame of every
     * reconnect.
     */
    fun types(data: ByteArray): List<Int> {
        val found = ArrayList<Int>(4)
        var index = 0
        while (index + 3 < data.size) {
            val longCode = data[index] == ZERO && data[index + 1] == ZERO &&
                data[index + 2] == ZERO && data[index + 3] == ONE
            val shortCode = data[index] == ZERO && data[index + 1] == ZERO && data[index + 2] == ONE
            when {
                longCode -> {
                    found.add(data[index + 4].toInt() and 0x1F)
                    index += 5
                }
                shortCode -> {
                    found.add(data[index + 3].toInt() and 0x1F)
                    index += 4
                }
                else -> index++
            }
        }
        return found
    }

    private const val ZERO: Byte = 0
    private const val ONE: Byte = 1
}
