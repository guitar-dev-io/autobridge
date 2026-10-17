package dev.autobridge.iptv

import androidx.annotation.StringRes
import dev.autobridge.R

/**
 * A curated (not exhaustive) list of Thai provinces, keyed by the radio-browser `state` spelling the
 * `bystateexact` endpoint expects. The list is static — not fetched — so the province picker opens
 * with no network, labels stay translatable, and the exact `state` spellings are pinned (radio-
 * browser's live spellings are inconsistent in case and script).
 *
 * [isKnown] is the single pure validity predicate for a stored/selected province; it is free of
 * Android types so it is unit-tested on the JVM. Both [RadioProvinceRepository.setSelected] (reject
 * an unknown value before writing) and [RadioProvinceRepository.selected] (treat an unknown stored
 * value as "none chosen") call it, so neither owns the rule itself.
 */
object ThaiProvinces {
    /** One curated province: [state] is the radio-browser key, [labelRes] its translatable label. */
    data class Province(val state: String, @StringRes val labelRes: Int)

    /** Curated provinces, by their radio-browser `state` spelling. */
    val all: List<Province> = listOf(
        Province("Bangkok", R.string.province_bangkok),
        Province("Chiang Mai", R.string.province_chiang_mai),
        Province("Chiang Rai", R.string.province_chiang_rai),
        Province("Nakhon Ratchasima", R.string.province_nakhon_ratchasima),
        Province("Khon Kaen", R.string.province_khon_kaen),
        Province("Udon Thani", R.string.province_udon_thani),
        Province("Ubon Ratchathani", R.string.province_ubon_ratchathani),
        Province("Phuket", R.string.province_phuket),
        Province("Songkhla", R.string.province_songkhla),
        Province("Surat Thani", R.string.province_surat_thani),
        Province("Chon Buri", R.string.province_chon_buri),
        Province("Rayong", R.string.province_rayong),
        Province("Nakhon Si Thammarat", R.string.province_nakhon_si_thammarat),
        Province("Phitsanulok", R.string.province_phitsanulok),
        Province("Nakhon Sawan", R.string.province_nakhon_sawan),
        Province("Lampang", R.string.province_lampang),
    )

    /** True when [state] is one of the curated provinces. Pure; no Android types. */
    fun isKnown(state: String): Boolean = state.isNotBlank() && all.any { it.state == state }
}
