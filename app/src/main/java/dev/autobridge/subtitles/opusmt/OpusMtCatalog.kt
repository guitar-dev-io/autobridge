package dev.autobridge.subtitles.opusmt

import dev.autobridge.subtitles.SubtitleLanguages

/**
 * The four files one Opus-MT model is made of, under the names this app stores them by.
 *
 * The names are deliberately not the publisher's: upstream calls the graphs
 * `onnx/encoder_model_quantized.onnx` and `onnx/decoder_model_quantized.onnx`, and whether a given
 * repository ships the quantized or the full-precision export varies. Flattening them to
 * `encoder.onnx` and `decoder.onnx` on the way in means the loader does not have to know which
 * variant was downloaded, and a future switch to the full-precision graphs is a change to
 * [OpusMtCatalog.remotePath] alone.
 */
enum class OpusMtFile(val fileName: String) {
    ENCODER("encoder.onnx"),
    DECODER("decoder.onnx"),
    SOURCE_PIECES("source.spm"),
    VOCABULARY("vocab.json");

    companion object {
        val all: List<OpusMtFile> = entries
    }
}

/**
 * How confident this app is that a pair has a published ONNX export.
 *
 * Opus-MT publishes several hundred pairs and the ONNX exports are a community subset of them, so
 * the honest answer for most pairs is "try it". A pair is only [KNOWN] when it is one of the
 * widely mirrored ones; everything else is offered with the caveat rather than hidden, because a
 * hidden pair that would in fact have worked is the worse failure.
 */
enum class OpusMtAvailability { KNOWN, UNVERIFIED }

/** One downloadable Marian model, identified by its language pair. */
data class OpusMtModel(
    val source: String,
    val target: String,
    val availability: OpusMtAvailability
) {
    /** The directory name the files are stored under, and the id the settings screen shows. */
    val id: String get() = "opus-mt-$source-$target"

    /** The upstream repository the files come from. */
    val repository: String get() = "$ONNX_ORGANIZATION/opus-mt-$source-$target"

    val label: String
        get() = SubtitleLanguages.label(source) + " to " + SubtitleLanguages.label(target)

    companion object {
        /**
         * The organization whose transformers.js exports of the Helsinki-NLP models this reads.
         * The upstream Helsinki-NLP repositories carry PyTorch weights only, which an on-device
         * ONNX Runtime cannot load, so the ONNX re-exports are what has to be fetched.
         */
        const val ONNX_ORGANIZATION = "Xenova"
    }
}

/**
 * Which Opus-MT models exist, where their files come from, and what they cost to install.
 *
 * Pure data and string building, so the settings screen's "is this pair available, and how big is
 * it" can be answered - and tested - without a network or a filesystem.
 */
object OpusMtCatalog {
    /** Hugging Face's raw-file endpoint; `resolve/main` follows LFS pointers to the real blob. */
    private const val HOST = "https://huggingface.co"

    /**
     * A rough installed size for one model, used only to warn before a download.
     *
     * The quantized Marian exports land between 70 and 90 MB; the number is deliberately the top
     * of that range, because a size estimate that undershoots is the one that strands someone on
     * a metered connection.
     */
    const val APPROXIMATE_BYTES = 90L * 1024 * 1024

    /**
     * Pairs with a published ONNX export under [OpusMtModel.ONNX_ORGANIZATION], checked against
     * the Hugging Face listing. English-centred, which is what the Opus-MT ONNX exports mostly
     * are. Note what is absent: there is no English-to-Thai, -Japanese, -Korean, -Polish or
     * -Turkish Opus-MT model at all (Japanese exists only as the differently coded `en-jap`).
     */
    private val known: Set<String> = setOf(
        "en-de", "de-en",
        "en-fr", "fr-en",
        "en-es", "es-en",
        "en-it", "it-en",
        "en-nl", "nl-en",
        "en-ru", "ru-en",
        "en-zh", "zh-en",
        "en-ar", "ar-en",
        "en-hi", "hi-en",
        "en-fi", "fi-en",
        "en-sv", "sv-en",
        "en-da", "da-en",
        "en-uk", "uk-en",
        "en-vi", "vi-en",
        "en-id", "id-en",
        "en-cs", "cs-en",
        "en-hu", "hu-en",
        "en-af", "af-en",
        "en-xh", "xh-en",
        "en-ro",
        "ja-en", "ko-en", "th-en", "pl-en", "tr-en", "et-en",
        "de-es", "de-fr", "es-de", "es-fr", "es-it", "es-ru",
        "fr-de", "fr-es", "fr-ro", "fr-ru", "it-es", "it-fr",
        "ru-es", "ru-fr", "ru-uk", "uk-ru", "fi-de", "da-de", "nl-fr"
    )

    /**
     * The model for a pair, or null when the pair is the same language twice or either side is
     * not a language the screen offers.
     */
    fun model(source: String, target: String): OpusMtModel? {
        val from = SubtitleLanguages.find(source)?.code ?: return null
        val to = SubtitleLanguages.find(target)?.code ?: return null
        if (from == to) return null
        return OpusMtModel(
            source = from,
            target = to,
            availability = if (known.contains("$from-$to")) {
                OpusMtAvailability.KNOWN
            } else {
                OpusMtAvailability.UNVERIFIED
            }
        )
    }

    /** The model a config asks for, by the same rule. */
    fun model(id: String): OpusMtModel? {
        val parts = id.removePrefix("opus-mt-").split("-")
        if (parts.size != 2) return null
        return model(parts[0], parts[1])
    }

    /** Where [file] sits inside the upstream repository. */
    fun remotePath(file: OpusMtFile): String = when (file) {
        // The quantized graphs: roughly a quarter of the download and of the resident memory of
        // the float32 exports, for a difference in subtitle wording that is not visible.
        OpusMtFile.ENCODER -> "onnx/encoder_model_quantized.onnx"
        OpusMtFile.DECODER -> "onnx/decoder_model_quantized.onnx"
        OpusMtFile.SOURCE_PIECES -> "source.spm"
        OpusMtFile.VOCABULARY -> "vocab.json"
    }

    /** The URL [file] of [model] is downloaded from. */
    fun downloadUrl(model: OpusMtModel, file: OpusMtFile): String =
        "$HOST/${model.repository}/resolve/main/${remotePath(file)}"

    /** Every pair the settings screen can offer for [source], known ones first. */
    fun modelsFrom(source: String): List<OpusMtModel> = SubtitleLanguages.all
        .mapNotNull { model(source, it.code) }
        .sortedWith(compareBy({ it.availability.ordinal }, { it.label }))
}
