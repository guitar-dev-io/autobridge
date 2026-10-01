package dev.autobridge.subtitles.opusmt

/**
 * The decisions a Marian generation loop makes, separated from the ONNX Runtime calls that feed
 * it so they can be tested on plain arrays.
 *
 * The strategy is greedy - the highest-scoring token at every step - rather than beam search.
 * Beams multiply the decoder runs by the beam width, and a subtitle has to be on screen while the
 * dialogue it belongs to is still being spoken; the wording a beam would improve is not worth the
 * line arriving after the scene. What greedy decoding does need is the two guards below, because
 * without them a Marian decoder that loses the thread will repeat a phrase until the token limit.
 */
object MarianDecoding {
    /**
     * The hard ceiling on one line's output, whatever the input length suggests.
     *
     * A subtitle cue is one or two sentences; anything past this is the decoder having lost the
     * thread, and each extra token is another full decoder run.
     */
    const val MAX_OUTPUT_TOKENS = 96

    /** The size of the repeated block that counts as a loop. */
    private const val LOOP_BLOCK = 4

    /**
     * How many tokens to allow for an input of [inputTokens].
     *
     * Translation length varies by pair - German compounds shrink the token count, Finnish
     * inflection grows it - so the allowance is generous rather than tight, and the loop guard is
     * what actually stops a runaway.
     */
    fun maxOutputTokens(inputTokens: Int): Int =
        (inputTokens * 2 + 8).coerceAtMost(MAX_OUTPUT_TOKENS)

    /**
     * The highest-scoring token in [logits], skipping [banned].
     *
     * The unknown token is banned by every caller: emitting it puts a literal `<unk>` in the
     * middle of a subtitle, and the second-best token is always a better answer than that.
     */
    fun nextToken(logits: FloatArray, banned: Set<Int> = emptySet()): Int {
        var best = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (id in logits.indices) {
            if (id in banned) continue
            val score = logits[id]
            if (score > bestScore) {
                bestScore = score
                best = id
            }
        }
        return best
    }

    /**
     * True when the tail of [generated] is the same block of tokens twice over.
     *
     * The cheap version of a repetition penalty: it does not change what the model scores, it only
     * notices that the output has started to cycle and ends the line there. A translation cut
     * short still matches the dialogue; one that repeats a clause eight times does not.
     */
    fun isLooping(generated: List<Int>, block: Int = LOOP_BLOCK): Boolean {
        if (block < 1 || generated.size < block * 2) return false
        val end = generated.size
        for (offset in 0 until block) {
            if (generated[end - 1 - offset] != generated[end - 1 - block - offset]) return false
        }
        return true
    }

    /**
     * The row of per-token scores for the last position, out of a decoder's
     * `[batch, sequence, vocabulary]` logits.
     *
     * Only the last position is ever used - the earlier ones re-score tokens that have already
     * been chosen - and reading it out by hand avoids materializing the whole nested array the
     * ONNX Runtime value API would hand back.
     */
    fun lastPositionLogits(flat: FloatArray, sequenceLength: Int, vocabularySize: Int): FloatArray {
        require(sequenceLength > 0 && vocabularySize > 0) { "empty logits" }
        require(flat.size >= sequenceLength * vocabularySize) {
            "logits hold ${flat.size} values, expected ${sequenceLength * vocabularySize}"
        }
        val start = (sequenceLength - 1) * vocabularySize
        return FloatArray(vocabularySize) { flat[start + it] }
    }
}
