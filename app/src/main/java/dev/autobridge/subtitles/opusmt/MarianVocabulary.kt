package dev.autobridge.subtitles.opusmt

import org.json.JSONObject

/**
 * The `vocab.json` of an Opus-MT export: the piece-to-id map the encoder takes and the id-to-piece
 * map the decoder's output is read back through.
 *
 * Marian shares one vocabulary between source and target - the two `.spm` models segment text, but
 * both sides index into this single table - which is why there is one of these per model rather
 * than one per direction.
 */
class MarianVocabulary private constructor(
    private val ids: Map<String, Int>,
    private val pieces: Array<String>,
    val unknownId: Int,
    val endId: Int,
    /**
     * The token a Marian decoder is seeded with. Marian uses the padding token for this rather
     * than a dedicated start token, which is the one detail of its generation loop that cannot be
     * guessed from the graph.
     */
    val decoderStartId: Int
) {
    val size: Int get() = pieces.size

    /** The id for [piece], or [unknownId] when the vocabulary has no entry. */
    fun idOf(piece: String): Int = ids[piece] ?: unknownId

    /** The piece for [id], or an empty string when the id is outside the table. */
    fun pieceOf(id: Int): String = pieces.getOrElse(id) { "" }

    /**
     * Encodes the pieces of one subtitle line, with the end-of-sequence token Marian expects.
     *
     * Marian's encoder has no beginning-of-sequence token; the sequence is the pieces followed by
     * `</s>`, and adding anything else in front shifts every position embedding.
     */
    fun encode(pieces: List<String>): IntArray =
        IntArray(pieces.size + 1) { index ->
            if (index == pieces.size) endId else idOf(pieces[index])
        }

    /** Decoded ids back to text, stopping at `</s>` and dropping the special tokens. */
    fun decode(ids: List<Int>): String {
        val text = ids
            .takeWhile { it != endId }
            .map { pieceOf(it) }
            .filter { it.isNotEmpty() && !isSpecial(it) }
        return SentencePieceModel.detokenize(text)
    }

    private fun isSpecial(piece: String): Boolean =
        piece.length > 2 && piece.startsWith("<") && piece.endsWith(">")

    companion object {
        const val END_TOKEN = "</s>"
        const val UNKNOWN_TOKEN = "<unk>"
        const val PADDING_TOKEN = "<pad>"

        fun parse(json: String): MarianVocabulary {
            val root = JSONObject(json)
            val ids = HashMap<String, Int>(root.length() * 2)
            var highest = -1
            for (piece in root.keys()) {
                val id = root.optInt(piece, -1)
                if (id < 0) continue
                ids[piece] = id
                if (id > highest) highest = id
            }
            require(highest >= 0) { "Marian vocabulary is empty" }

            val pieces = Array(highest + 1) { "" }
            // A duplicated id would otherwise decode to whichever entry the map iterated last;
            // keeping the first keeps the table deterministic across runs.
            for ((piece, id) in ids) if (pieces[id].isEmpty()) pieces[id] = piece

            val end = ids[END_TOKEN] ?: 0
            return MarianVocabulary(
                ids = ids,
                pieces = pieces,
                unknownId = ids[UNKNOWN_TOKEN] ?: end,
                endId = end,
                // Falling back to `</s>` keeps the loop runnable on an export whose vocabulary
                // omits the padding token: Marian treats both as "nothing has been said yet".
                decoderStartId = ids[PADDING_TOKEN] ?: end
            )
        }
    }
}
