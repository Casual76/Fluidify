// SPDX-License-Identifier: GPL-3.0-or-later
//
// The pictures of the earbuds and the case with a charge ring under each.
//
// Ported from LibrePods, Copyright (C) 2025 LibrePods contributors, GPL-3.0-or-later, pinned at
// 89356c08cea3db0b6b54792d9329d09a87cc85fa:
//   android/app/src/main/java/me/kavishdevar/librepods/presentation/components/BatteryView.kt
//   android/app/src/main/java/me/kavishdevar/librepods/presentation/components/BatteryIndicator.kt
//
// Kept from upstream: the layout (two equal columns no wider than 350 dp together, the picture
// centred over its indicator, 12 dp between them), the single indicator when both earbuds are
// within three points and in the same charging state, the 34 dp ring with a 4 dp round-capped
// stroke starting at twelve o'clock, the red fill at 25 % and under, the optimised-charging ring
// that stops at 80 % with its marker, and the bolt that scales in while charging.
//
// Changed here, and only this:
//  - colours come from the theme (accent and error instead of fixed greens and reds, the content
//    colour at low alpha for the track), and the bolt's motion from FluidMotion;
//  - the bolt and the L / R marks are not SF Pro glyphs: a Phosphor bolt, and the two letters the
//    app already translates, in a small disc. The case has no mark; it stands under its picture;
//  - a component that has not reported is "--" with no ring. Upstream draws 0 % for it, and an
//    empty ring is a reading of zero that nobody has taken;
//  - a value older than the freshness window is not drawn here at all (see BatteryHistory);
//  - the pictures are nodpi resources laid out by their own aspect ratio.
package dev.lelonio.square.ui.airpods

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.Lightning
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.lelonio.square.R
import dev.lelonio.square.airpods.BatteryValue
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** AACP battery components, as the device numbers them. */
internal const val BatteryLeft = 4
internal const val BatteryRight = 2
internal const val BatteryCase = 8

/** AACP charging states that mean current is flowing (LibrePods `BatteryStatus`). */
private const val StatusCharging = 1
private const val StatusOptimizedCharging = 5

/** Dimming of a picture that stands for a device nobody is hearing from right now. */
internal const val AirPodsArtworkIdleAlpha = 0.38f

/** Both earbuds read alike enough to be said once: upstream's rule, on received values only. */
internal fun earbudsReadAsOne(left: BatteryValue?, right: BatteryValue?): Boolean {
    val l = left?.percent ?: return false
    val r = right?.percent ?: return false
    return left.status == right.status && (l - r) in -3..3
}

/** One picture, as wide as it is given and as tall as its own proportions make it. */
@Composable
internal fun AirPodsPicture(resource: Int, modifier: Modifier = Modifier, live: Boolean = true) {
    val painter = painterResource(resource)
    val size = painter.intrinsicSize
    val ratio = if (size.width > 0f && size.height > 0f) size.width / size.height else 1f
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.aspectRatio(ratio).alpha(if (live) 1f else AirPodsArtworkIdleAlpha),
    )
}

/**
 * The ring, the bolt and the percentage of one received reading.
 *
 * Called only with a value the device reported in this session and inside the freshness window:
 * there is no default percentage, and no call site that passes one.
 */
@Composable
internal fun AirPodsBatteryIndicator(
    percent: Int,
    status: Int,
    modifier: Modifier = Modifier,
    side: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (percent > 25) scheme.primary else scheme.error
    val track = LocalContentColor.current.copy(alpha = 0.14f)
    val charging = status == StatusCharging || status == StatusOptimizedCharging
    val bolt by animateFloatAsState(
        targetValue = if (charging) 1f else 0f,
        animationSpec = FluidMotion.standard(),
        label = "airpods battery bolt",
    )

    Column(modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
            val strokeWidthPx = with(LocalDensity.current) { 4.dp.toPx() }
            val gapFromCenterPx = with(LocalDensity.current) { 8.sp.toPx() }
            val optimizedLimit = 0.8f
            val progress = percent / 100f

            Canvas(Modifier.size(BatteryRingSize)) {
                val startAngle = -90f
                val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                val radius = size.minDimension / 2

                if (status == StatusOptimizedCharging) {
                    drawArc(track, startAngle, 360f * optimizedLimit, useCenter = false, style = stroke)
                    drawArc(fill, startAngle, 360f * min(progress, optimizedLimit), useCenter = false, style = stroke)

                    // The marker at 80 %, from the ring toward the centre.
                    val angleRad = Math.toRadians((startAngle + 360f * optimizedLimit).toDouble())
                    val arcRadius = radius - strokeWidthPx
                    val outerX = center.x + arcRadius * cos(angleRad).toFloat()
                    val outerY = center.y + arcRadius * sin(angleRad).toFloat()
                    val dirX = center.x - outerX
                    val dirY = center.y - outerY
                    val length = sqrt(dirX * dirX + dirY * dirY)
                    val normX = dirX / length
                    val normY = dirY / length
                    drawLine(
                        color = if (percent >= 80) fill else track,
                        start = Offset(outerX - normX * strokeWidthPx / 2, outerY - normY * strokeWidthPx / 2),
                        end = Offset(center.x - normX * gapFromCenterPx, center.y - normY * gapFromCenterPx),
                        strokeWidth = strokeWidthPx,
                        cap = StrokeCap.Round,
                    )
                } else {
                    drawArc(track, 0f, 360f, useCenter = false, style = stroke)
                    drawArc(fill, startAngle, 360f * progress, useCenter = false, style = stroke)
                }
            }

            Icon(
                PhosphorIcons.Fill.Lightning,
                contentDescription = null,
                tint = fill,
                modifier = Modifier.size(14.dp).graphicsLayer { scaleX = bolt; scaleY = bolt },
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (side != null) SideMark(side)
            Text(
                stringResource(R.string.airpods_feature_percent, percent),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** The same footprint as an indicator, for a component that has not reported: no ring, "--". */
@Composable
internal fun AirPodsBatteryUnknown(modifier: Modifier = Modifier, side: String? = null) {
    Column(modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.padding(bottom = 4.dp).size(BatteryRingSize))
        Spacer(Modifier.height(4.dp))
        Row(Modifier.alpha(0.5f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (side != null) SideMark(side)
            Text(stringResource(R.string.airpods_ui_missing), style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

private val BatteryRingSize: Dp = 34.dp

/** "L" or "R" in a disc, where upstream prints a circled SF Pro letter. */
@Composable
private fun SideMark(letter: String) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(16.dp).background(scheme.inverseSurface, CircleShape), contentAlignment = Alignment.Center) {
        Text(letter, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            color = scheme.inverseOnSurface, maxLines = 1,
            modifier = Modifier.clearAndSetSemantics { })
    }
}

/**
 * LibrePods' `BatteryView` on the facts of [s].
 *
 * Read aloud as one sentence per component; the pictures are decoration. While nothing in this
 * session vouches for the device (not connected, or a model nobody reported) the pictures are
 * dimmed, and they are the placeholder when the model is unknown.
 */
@Composable
internal fun AirPodsBatteryView(s: AirPodsUiState, modifier: Modifier = Modifier) {
    val choice = s.artworkChoice()
    val live = choice.known && s.homeVisible
    val left = s.freshBattery(BatteryLeft)
    val right = s.freshBattery(BatteryRight)
    val case = s.freshBattery(BatteryCase)
    val single = earbudsReadAsOne(left, right)
    val leftLetter = stringResource(R.string.airpods_ui_left_short)
    val rightLetter = stringResource(R.string.airpods_ui_right_short)
    val spoken = listOf(
        R.string.airpods_feature_left to left, R.string.airpods_feature_right to right,
        R.string.airpods_feature_case to case,
    ).map { (name, value) ->
        value?.percent?.let { stringResource(R.string.airpods_feature_battery_accessibility, stringResource(name), it) }
            ?: (stringResource(name) + " " + stringResource(R.string.airpods_ui_missing))
    }.joinToString(". ")

    Box(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken },
        contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 350.dp).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AirPodsPicture(choice.artwork.buds, Modifier.fillMaxWidth(), live)
                }
                Box(Modifier.fillMaxWidth().testTag("airpods-battery-earbuds"), contentAlignment = Alignment.Center) {
                    if (single && left != null && right != null) {
                        // The lower of the two, as upstream: the pair lasts as long as its weaker side.
                        AirPodsBatteryIndicator(min(left.percent ?: 0, right.percent ?: 0), left.status,
                            Modifier.testTag("airpods-battery-pair"))
                    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        BatteryCell(left, leftLetter, Modifier.testTag("airpods-battery-$BatteryLeft"))
                        Spacer(Modifier.width(16.dp))
                        BatteryCell(right, rightLetter, Modifier.testTag("airpods-battery-$BatteryRight"))
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AirPodsPicture(choice.artwork.case, Modifier.fillMaxWidth(), live)
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BatteryCell(case, null, Modifier.testTag("airpods-battery-$BatteryCase"))
                }
            }
        }
    }
}

@Composable
private fun BatteryCell(value: BatteryValue?, side: String?, modifier: Modifier = Modifier) {
    val percent = value?.percent
    if (value != null && percent != null) AirPodsBatteryIndicator(percent, value.status, modifier, side)
    else AirPodsBatteryUnknown(modifier, side)
}
