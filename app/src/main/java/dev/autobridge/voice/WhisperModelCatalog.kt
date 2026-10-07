package dev.autobridge.voice

/** Model size class. The numbers are the encoder's shape, which is how a file says what it is. */
enum class WhisperFamily(val audioState: Int, val audioLayers: Int) {
    TINY(audioState = 384, audioLayers = 4),
    BASE(audioState = 512, audioLayers = 6),
    SMALL(audioState = 768, audioLayers = 12)
}

/**
 * How the weights are stored. [ftype] is ggml's `GGML_FTYPE_*` value, which the model file records
 * in its header (modulo the quantisation-version factor) - so a file can be checked against the
 * entry it claims to be without loading it.
 */
enum class WhisperQuantization(val ftype: Int, val label: String) {
    F16(1, "F16"),
    Q8_0(7, "Q8_0"),
    Q5_1(9, "Q5_1"),
    Q4_K(12, "Q4_K"),
    Q5_K(13, "Q5_K"),
    Q6_K(14, "Q6_K")
}

/** What a model is good for, as the settings screen labels it. */
enum class WhisperModelTier { RECOMMENDED, LOW_END, FAST, BALANCED, ACCURATE, FULL_PRECISION }

/**
 * One Whisper model this app knows how to manage.
 *
 * @param downloadUrl where the file is published, or null when it has no official download and can
 *   only be imported from a file (the k-quant variants; see [WhisperModelCatalog]).
 * @param sizeBytes the published size, used for the free-space check and the size shown before a
 *   download. Exact for every downloadable model; an estimate for import-only ones.
 * @param sha1 the checksum whisper.cpp publishes for the model, where it publishes one. A download
 *   is also checked against the SHA-256 the server reports for the file (see [WhisperModelStore]).
 */
data class WhisperModelInfo(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String?,
    val sizeBytes: Long,
    val multilingual: Boolean,
    val description: String,
    val family: WhisperFamily,
    val quantization: WhisperQuantization,
    val tier: WhisperModelTier,
    val sha1: String? = null
) {
    val downloadable: Boolean get() = downloadUrl != null
}

/**
 * Every Whisper model AutoBridge offers - the one place a model is defined.
 *
 * Multilingual only. The `.en` models cannot transcribe Thai at all, and Thai with English names
 * mixed in ("เปิด YouTube เพลง Bodyslam") is exactly what this feature is for, so they are not
 * listed.
 *
 * Downloads come from whisper.cpp's own model repository on Hugging Face
 * ([HUB]/ggml-<id>.bin), which publishes the full-precision models and their q5_1 and q8_0
 * quantisations. The k-quant variants (q4_k, q5_k, q6_k) are not published there: they are listed
 * so a file made with whisper.cpp's `quantize` tool (`quantize ggml-base.bin ggml-base-q5_k.bin
 * q5_k`) can be imported and recognised, but nothing pretends there is a URL for them.
 *
 * Pure Kotlin: the settings screen, the store and the tests all read the same list.
 */
object WhisperModelCatalog {

    /** whisper.cpp's model repository; `resolve/main` redirects to the file itself. */
    const val HUB = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    /** Base Q8_0: the smallest model that handles Thai with English names in it well. */
    const val RECOMMENDED_ID = "base-q8_0"

    /** Tiny Q8_0: for phones where base is too slow or too large. */
    const val LOW_END_ID = "tiny-q8_0"

    /** Every model whisper.cpp counts as multilingual has this many tokens (large-v3 aside). */
    const val MULTILINGUAL_VOCAB = 51865

    private fun model(
        id: String,
        displayName: String,
        family: WhisperFamily,
        quantization: WhisperQuantization,
        sizeBytes: Long,
        tier: WhisperModelTier,
        description: String,
        downloadable: Boolean = true,
        sha1: String? = null
    ) = WhisperModelInfo(
        id = id,
        displayName = displayName,
        fileName = "ggml-$id.bin",
        downloadUrl = if (downloadable) "$HUB/ggml-$id.bin" else null,
        sizeBytes = sizeBytes,
        multilingual = true,
        description = description,
        family = family,
        quantization = quantization,
        tier = tier,
        sha1 = sha1
    )

    /** In the order the settings screen lists them: by size class, smallest file first. */
    val models: List<WhisperModelInfo> = listOf(
        model(
            "tiny-q5_1", "Tiny Q5_1", WhisperFamily.TINY, WhisperQuantization.Q5_1,
            32_152_673L, WhisperModelTier.FAST, "Smallest download; lowest accuracy"
        ),
        model(
            "tiny-q8_0", "Tiny Q8_0", WhisperFamily.TINY, WhisperQuantization.Q8_0,
            43_537_433L, WhisperModelTier.LOW_END, "Fast, low memory"
        ),
        model(
            "tiny", "Tiny", WhisperFamily.TINY, WhisperQuantization.F16,
            77_691_713L, WhisperModelTier.FULL_PRECISION, "Tiny at full precision",
            sha1 = "bd577a113a864445d4c299885e0cb97d4ba92b5f"
        ),
        model(
            "base-q4_k", "Base Q4_K", WhisperFamily.BASE, WhisperQuantization.Q4_K,
            45_000_000L, WhisperModelTier.FAST, "Import only (whisper.cpp quantize)",
            downloadable = false
        ),
        model(
            "base-q5_k", "Base Q5_K", WhisperFamily.BASE, WhisperQuantization.Q5_K,
            53_000_000L, WhisperModelTier.BALANCED, "Import only (whisper.cpp quantize)",
            downloadable = false
        ),
        model(
            "base-q5_1", "Base Q5_1", WhisperFamily.BASE, WhisperQuantization.Q5_1,
            59_707_625L, WhisperModelTier.BALANCED, "Balanced size and accuracy"
        ),
        model(
            "base-q6_k", "Base Q6_K", WhisperFamily.BASE, WhisperQuantization.Q6_K,
            62_000_000L, WhisperModelTier.BALANCED, "Import only (whisper.cpp quantize)",
            downloadable = false
        ),
        model(
            "base-q8_0", "Base Q8_0", WhisperFamily.BASE, WhisperQuantization.Q8_0,
            81_768_585L, WhisperModelTier.RECOMMENDED, "Recommended for Thai and mixed Thai-English"
        ),
        model(
            "base", "Base", WhisperFamily.BASE, WhisperQuantization.F16,
            147_951_465L, WhisperModelTier.FULL_PRECISION, "Base at full precision",
            sha1 = "465707469ff3a37a2b9b8d8f89f2f99de7299dac"
        ),
        model(
            "small-q4_k", "Small Q4_K", WhisperFamily.SMALL, WhisperQuantization.Q4_K,
            145_000_000L, WhisperModelTier.ACCURATE, "Import only (whisper.cpp quantize)",
            downloadable = false
        ),
        model(
            "small-q5_1", "Small Q5_1", WhisperFamily.SMALL, WhisperQuantization.Q5_1,
            190_085_487L, WhisperModelTier.ACCURATE, "Higher accuracy; needs a fast phone"
        ),
        model(
            "small-q8_0", "Small Q8_0", WhisperFamily.SMALL, WhisperQuantization.Q8_0,
            264_464_607L, WhisperModelTier.ACCURATE, "Highest accuracy offered; slowest"
        )
    )

    fun find(id: String?): WhisperModelInfo? = models.firstOrNull { it.id == id }

    fun byFileName(fileName: String): WhisperModelInfo? = models.firstOrNull { it.fileName == fileName }

    val recommended: WhisperModelInfo get() = find(RECOMMENDED_ID)!!

    /** The short list shown above the full one when nothing is installed yet. */
    val suggested: List<WhisperModelInfo>
        get() = listOf(RECOMMENDED_ID, LOW_END_ID, "base-q5_1", "small-q5_1").mapNotNull(::find)

    /** The catalog entry a model file's header describes, or null for a file this app does not offer. */
    fun match(header: WhisperModelHeader): WhisperModelInfo? = models.firstOrNull {
        WhisperModelHeader.problemFor(it, header) == null
    }
}
