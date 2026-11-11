// SPDX-License-Identifier: GPL-3.0-or-later
//
// Model-to-artwork table and the three listening-mode marks.
//
// Derived from LibrePods, Copyright (C) 2025 LibrePods contributors, GPL-3.0-or-later,
// pinned at 89356c08cea3db0b6b54792d9329d09a87cc85fa:
//   android/app/src/main/java/me/kavishdevar/librepods/data/AirPods.kt  (which picture a model shows)
//   android/app/src/main/res-apple/drawable/airpods_*.png               (40 pictures, byte-identical)
//   android/app/src/main/res/drawable/{adaptive,noise_cancellation,transparency}.png (3 marks, byte-identical)
// The pictures are installed in res/drawable-nodpi under the names listed in
// LICENSES/LibrePods-AirPods-UI.md; the marks are renamed airpods_mode_* so a generic name such as
// "adaptive" cannot collide with another drawable of the app. AirPods and AirPods Pro are trademarks
// of Apple Inc.; the pictures say which paired device a control acts on and nothing else.
package dev.lelonio.square.ui.airpods

import androidx.annotation.DrawableRes
import dev.lelonio.square.R
import dev.lelonio.square.airpods.protocol.AirPodsModels

/** The four pictures of one model: both earbuds, each earbud alone, and the case. */
internal data class AirPodsArtwork(
    @DrawableRes val buds: Int,
    @DrawableRes val left: Int,
    @DrawableRes val right: Int,
    @DrawableRes val case: Int,
)

internal object AirPodsArtworks {
    private val airPods1 = AirPodsArtwork(R.drawable.airpods_1_buds, R.drawable.airpods_1_left,
        R.drawable.airpods_1_right, R.drawable.airpods_1_case)
    private val airPods2 = AirPodsArtwork(R.drawable.airpods_2_buds, R.drawable.airpods_2_left,
        R.drawable.airpods_2_right, R.drawable.airpods_2_case)
    private val airPods3 = AirPodsArtwork(R.drawable.airpods_3_buds, R.drawable.airpods_3_left,
        R.drawable.airpods_3_right, R.drawable.airpods_3_case)
    private val airPods4 = AirPodsArtwork(R.drawable.airpods_4_buds, R.drawable.airpods_4_left,
        R.drawable.airpods_4_right, R.drawable.airpods_4_case)
    private val airPods4Anc = AirPodsArtwork(R.drawable.airpods_4_anc_buds, R.drawable.airpods_4_anc_left,
        R.drawable.airpods_4_anc_right, R.drawable.airpods_4_anc_case)
    private val airPods5 = AirPodsArtwork(R.drawable.airpods_5_buds, R.drawable.airpods_5_left,
        R.drawable.airpods_5_right, R.drawable.airpods_5_case)
    // Upstream's AirPods5WCC points at the airpods_4 pictures and uses these four only in a preview.
    // They are the set named after this model, so this table uses them for it.
    private val airPods5Wireless = AirPodsArtwork(R.drawable.airpods_5_wireless_buds,
        R.drawable.airpods_5_wireless_left, R.drawable.airpods_5_wireless_right,
        R.drawable.airpods_5_wireless_case)
    private val pro1 = AirPodsArtwork(R.drawable.airpods_pro_1_buds, R.drawable.airpods_pro_1_left,
        R.drawable.airpods_pro_1_right, R.drawable.airpods_pro_1_case)
    private val pro2 = AirPodsArtwork(R.drawable.airpods_pro_2_buds, R.drawable.airpods_pro_2_left,
        R.drawable.airpods_pro_2_right, R.drawable.airpods_pro_2_case)
    private val pro3 = AirPodsArtwork(R.drawable.airpods_pro_3_buds, R.drawable.airpods_pro_3_left,
        R.drawable.airpods_pro_3_right, R.drawable.airpods_pro_3_case)

    /** Keyed by the protocol table's own model name, so a model added there fails [find] loudly in tests. */
    private val byModelName: Map<String, AirPodsArtwork> = mapOf(
        "AirPods 1" to airPods1,
        "AirPods 2" to airPods2,
        "AirPods 3" to airPods3,
        "AirPods 4" to airPods4,
        "AirPods 4 (ANC)" to airPods4Anc,
        "AirPods 5" to airPods5,
        "AirPods 5 with Wireless Charging Case" to airPods5Wireless,
        "AirPods Pro 1" to pro1,
        "AirPods Pro 2 with Magsafe Charging Case (Lightning)" to pro2,
        "AirPods Pro 2 with Magsafe Charging Case (USB-C)" to pro2,
        "AirPods Pro 3" to pro3,
    )

    /**
     * What upstream draws before it knows the model (`AirPodsSettingsScreen`: `airpods_pro_2_*`).
     * A placeholder, never a claim: the surfaces draw it dimmed and name no model beside it.
     */
    val placeholder: AirPodsArtwork = pro2

    /** The pictures of a model number the protocol table knows, or null. */
    fun find(modelNumber: String?): AirPodsArtwork? =
        modelNumber?.let(AirPodsModels::find)?.let { byModelName[it.name] }
}

/**
 * Which pictures a surface shows, and whether they stand for a model somebody reported.
 *
 * The model comes from this session's INFORMATION when there is one, otherwise from the pair's
 * stored verification. With neither, [known] is false and the placeholder is used.
 */
internal data class AirPodsArtworkChoice(val artwork: AirPodsArtwork, val known: Boolean, val modelName: String?)

internal fun AirPodsUiState.artworkChoice(): AirPodsArtworkChoice {
    val number = identity?.modelNumber ?: pair.verification?.modelNumber
    val found = AirPodsArtworks.find(number)
    return AirPodsArtworkChoice(found ?: AirPodsArtworks.placeholder, found != null,
        if (found != null) number?.let(AirPodsModels::find)?.name else null)
}

/**
 * LibrePods' mark for a listening mode, by wire value.
 *
 * Off and noise cancellation share one drawing upstream (`NoiseControlSettings`,
 * `PressAndHoldSettingsScreen`); the same fallback as [modeLabel] keeps an unexpected value from
 * being drawn as one thing and named as another.
 */
@DrawableRes
internal fun modeIconRes(wire: Int): Int = when (wire) {
    1, 2 -> R.drawable.airpods_mode_noise_cancellation
    3 -> R.drawable.airpods_mode_transparency
    else -> R.drawable.airpods_mode_adaptive
}
