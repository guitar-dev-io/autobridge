package dev.autobridge.subtitles

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dev.autobridge.display.StructuredLog
import dev.autobridge.subtitles.opusmt.MarianDecoding
import dev.autobridge.subtitles.opusmt.MarianVocabulary
import dev.autobridge.subtitles.opusmt.OpusMtCatalog
import dev.autobridge.subtitles.opusmt.OpusMtFile
import dev.autobridge.subtitles.opusmt.OpusMtModel
import dev.autobridge.subtitles.opusmt.OpusMtModelStore
import dev.autobridge.subtitles.opusmt.SentencePieceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer

/**
 * The Opus-MT side of [SubtitleTranslator]: a Marian encoder-decoder run through ONNX Runtime.
 *
 * This class is only the ONNX Runtime wiring. Every decision - how text is segmented, which token
 * comes next, when a line has run long or started to loop - lives in the pure helpers
 * ([SentencePieceModel], [MarianVocabulary], [MarianDecoding]) that are tested on plain arrays.
 * What is left here is loading the two graphs, moving arrays into tensors and back, and running
 * the greedy generation loop that feeds one on the output of the other. Keeping it that thin is
 * deliberate: the parts that are easy to get subtly wrong are the parts that are tested, and this
 * file is the part that needs a device.
 *
 * The session, the two `.spm` tokenizers and the shared vocabulary are all built once in
 * [prepare] and held until [close]; they are the expensive thing an instance exists to own.
 */
class OpusMtSubtitleTranslator(
    private val context: Context,
    override val signature: String,
    private val model: OpusMtModel,
    private val wifiOnly: Boolean
) : SubtitleTranslator {

    override val engine: TranslationEngine = TranslationEngine.OPUS_MT

    private var environment: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var sourcePieces: SentencePieceModel? = null
    private var vocabulary: MarianVocabulary? = null

    @Volatile
    private var ready = false

    /**
     * Downloads the model if it is missing and allowed, then loads the two graphs and the
     * tokenizer into memory. Idempotent: a second call once [ready] returns immediately, which is
     * what lets the pipeline call it on every line without paying for it twice.
     */
    override suspend fun prepare() {
        if (ready) return
        withContext(Dispatchers.IO) {
            if (ready) return@withContext
            ensureDownloaded()
            load()
            ready = true
            StructuredLog.i("SUBTITLE", "opus-mt engine ready: ${model.id}")
        }
    }

    private fun ensureDownloaded() {
        if (OpusMtModelStore.isInstalled(context, model)) return
        if (!OpusMtModelStore.isDownloadAllowed(context, wifiOnly)) {
            throw IllegalStateException("Opus-MT model ${model.id} is not downloaded and the network policy forbids fetching it")
        }
        OpusMtModelStore.download(context, model)
    }

    private fun load() {
        val directory = OpusMtModelStore.directory(context, model)
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            // A head unit is not a workstation; one or two threads keeps a decoder step off every
            // core and leaves the main thread responsive while a line is being produced.
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }
        environment = env
        encoder = env.createSession(fileOf(directory, OpusMtFile.ENCODER).absolutePath, options)
        decoder = env.createSession(fileOf(directory, OpusMtFile.DECODER).absolutePath, options)
        sourcePieces = SentencePieceModel.parse(fileOf(directory, OpusMtFile.SOURCE_PIECES).readBytes())
        vocabulary = MarianVocabulary.parse(
            fileOf(directory, OpusMtFile.VOCABULARY).readText(Charsets.UTF_8)
        )
    }

    private fun fileOf(directory: File, file: OpusMtFile): File {
        val target = File(directory, file.fileName)
        if (!target.isFile) throw IllegalStateException("Opus-MT model ${model.id} is missing ${file.fileName}")
        return target
    }

    override suspend fun translate(text: String): String {
        if (!ready) prepare()
        return withContext(Dispatchers.IO) { run(text) }
    }

    /**
     * One line, encoded once and decoded greedily until Marian emits `</s>`, the length allowance
     * runs out, or the output starts to loop. The original text is the answer when the model has
     * nothing better - an empty segmentation, or a decode that produced only special tokens.
     */
    private fun run(text: String): String {
        val env = environment ?: return text
        val encoderSession = encoder ?: return text
        val decoderSession = decoder ?: return text
        val pieces = sourcePieces ?: return text
        val vocab = vocabulary ?: return text

        val tokens = pieces.encode(text)
        if (tokens.isEmpty()) return text
        val inputIds = vocab.encode(tokens)
        val inputLength = inputIds.size

        val encoderStates: OnnxTensor
        val attentionMask: OnnxTensor
        val inputTensor = longTensor(env, LongArray(inputLength) { inputIds[it].toLong() })
        val maskTensor = longTensor(env, LongArray(inputLength) { 1L })
        try {
            val encoderOut = encoderSession.run(
                mapOf(
                    ENCODER_INPUT_IDS to inputTensor,
                    ENCODER_ATTENTION_MASK to maskTensor
                )
            )
            // Held open for the whole decode: every decoder step reads these same encoder states,
            // so closing them with the encoder output would pull the ground out from under the loop.
            encoderStates = encoderOut.get(ENCODER_OUTPUT).get() as OnnxTensor
            attentionMask = maskTensor
        } finally {
            inputTensor.close()
        }

        try {
            return decode(env, decoderSession, vocab, encoderStates, attentionMask, inputLength)
                .ifEmpty { text }
        } finally {
            encoderStates.close()
            attentionMask.close()
        }
    }

    private fun decode(
        env: OrtEnvironment,
        decoderSession: OrtSession,
        vocab: MarianVocabulary,
        encoderStates: OnnxTensor,
        attentionMask: OnnxTensor,
        inputLength: Int
    ): String {
        val banned = setOf(vocab.unknownId)
        val limit = MarianDecoding.maxOutputTokens(inputLength)
        val generated = ArrayList<Int>(limit)
        // Marian seeds the decoder with the padding token, then re-feeds the whole sequence so far
        // on every step; the graph here is the non-cached export, so there is no past-key-values
        // to thread through.
        val sequence = ArrayList<Long>(limit + 1)
        sequence.add(vocab.decoderStartId.toLong())

        while (generated.size < limit) {
            val decoderIds = longTensor2d(env, sequence)
            val logits: FloatArray
            val sequenceLength: Int
            val vocabularySize: Int
            try {
                val out = decoderSession.run(
                    mapOf(
                        DECODER_INPUT_IDS to decoderIds,
                        DECODER_ENCODER_ATTENTION_MASK to attentionMask,
                        DECODER_ENCODER_STATES to encoderStates
                    )
                )
                val tensor = out.get(DECODER_OUTPUT).get() as OnnxTensor
                val shape = tensor.info.shape
                sequenceLength = shape[1].toInt()
                vocabularySize = shape[2].toInt()
                logits = flatten(tensor)
                out.close()
            } finally {
                decoderIds.close()
            }

            val lastRow = MarianDecoding.lastPositionLogits(logits, sequenceLength, vocabularySize)
            val next = MarianDecoding.nextToken(lastRow, banned)
            if (next < 0 || next == vocab.endId) break
            generated.add(next)
            sequence.add(next.toLong())
            if (MarianDecoding.isLooping(generated)) break
        }

        return vocab.decode(generated)
    }

    private fun longTensor(env: OrtEnvironment, values: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(values), longArrayOf(1, values.size.toLong()))

    private fun longTensor2d(env: OrtEnvironment, values: List<Long>): OnnxTensor =
        OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(values.toLongArray()),
            longArrayOf(1, values.size.toLong())
        )

    private fun flatten(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    override fun close() {
        ready = false
        runCatching { decoder?.close() }
        runCatching { encoder?.close() }
        decoder = null
        encoder = null
        sourcePieces = null
        vocabulary = null
        environment = null
    }

    private companion object {
        // The transformers.js Marian export's graph I/O names. The encoder takes the token ids and
        // their mask; the decoder is the non-merged variant that re-reads the encoder states on
        // every step rather than threading cached key/values through.
        const val ENCODER_INPUT_IDS = "input_ids"
        const val ENCODER_ATTENTION_MASK = "attention_mask"
        const val ENCODER_OUTPUT = "last_hidden_state"

        const val DECODER_INPUT_IDS = "input_ids"
        const val DECODER_ENCODER_ATTENTION_MASK = "encoder_attention_mask"
        const val DECODER_ENCODER_STATES = "encoder_hidden_states"
        const val DECODER_OUTPUT = "logits"
    }
}

/** Kept beside the translator because the catalog is where a config's pair becomes a model. */
internal fun opusMtModelFor(config: SubtitleTranslationConfig): OpusMtModel? =
    OpusMtCatalog.model(config.sourceLanguage, config.targetLanguage)
