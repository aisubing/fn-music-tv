// SPDX-License-Identifier: GPL-3.0-only
// Pure color science for player artwork ambience: Palette extraction, Oklab conversion, and
// tone-mapping toward a calm poster surface. No Compose state; safe to unit test directly.
package com.fnmusic.tv.ui

import android.graphics.Bitmap
import androidx.palette.graphics.Palette
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.pow
import kotlin.math.sqrt

internal data class ExtractedArtworkColors(
    val ambience: Color,
    val posterSurface: Color,
)

internal data class ArtworkPaletteSwatch(
    val rgb: Int,
    val population: Int,
)

internal fun extractArtworkColors(bitmap: Bitmap): ExtractedArtworkColors = runCatching {
    val globalPalette = Palette.from(bitmap)
        .maximumColorCount(24)
        .generate()
    val edgeLeft = (bitmap.width * 0.62f).toInt().coerceIn(0, bitmap.width - 1)
    val edgePalette = Palette.from(bitmap)
        .maximumColorCount(16)
        .setRegion(edgeLeft, 0, bitmap.width, bitmap.height)
        .generate()
    val globalSwatches = globalPalette.swatches.map { ArtworkPaletteSwatch(it.rgb, it.population) }
    val edgeSwatches = edgePalette.swatches.map { ArtworkPaletteSwatch(it.rgb, it.population) }
    ExtractedArtworkColors(
        ambience = artworkAmbienceColor(globalSwatches),
        posterSurface = artworkPosterSurfaceColor(globalSwatches, edgeSwatches),
    )
}.getOrDefault(fallbackArtworkColors())

internal fun fallbackArtworkColors(): ExtractedArtworkColors {
    val ambience = fallbackAmbienceColor()
    return ExtractedArtworkColors(ambience, posterSurfaceColor(ambience))
}

internal fun artworkAmbienceColor(swatches: List<ArtworkPaletteSwatch>): Color {
    val candidates = swatches.filter { it.population > 0 }
    if (candidates.isEmpty()) return fallbackAmbienceColor()
    var weightedRed = 0f
    var weightedGreen = 0f
    var weightedBlue = 0f
    var totalWeight = 0f
    candidates.forEach { swatch ->
        val red = (swatch.rgb ushr 16 and 0xFF) / 255f
        val green = (swatch.rgb ushr 8 and 0xFF) / 255f
        val blue = (swatch.rgb and 0xFF) / 255f
        val peak = maxOf(red, green, blue)
        val floor = minOf(red, green, blue)
        val saturation = if (peak == 0f) 0f else (peak - floor) / peak
        val lightness = (peak + floor) / 2f
        val usefulLightness = (1f - kotlin.math.abs(lightness - 0.52f) / 0.52f).coerceIn(0f, 1f)
        val extremeWeight = when {
            peak < 0.08f -> 0.05f
            floor > 0.94f -> 0.12f
            else -> 1f
        }
        val weight = swatch.population *
            (0.12f + saturation * 0.88f) *
            (0.35f + usefulLightness * 0.65f) *
            extremeWeight
        weightedRed += red * weight
        weightedGreen += green * weight
        weightedBlue += blue * weight
        totalWeight += weight
    }
    if (totalWeight <= 0f) return fallbackAmbienceColor()
    return normalizedAmbienceColor(
        red = weightedRed / totalWeight,
        green = weightedGreen / totalWeight,
        blue = weightedBlue / totalWeight,
    )
}

internal fun fallbackAmbienceColor(): Color = Color(0xFF29312F)

private const val POSTER_EDGE_MAX_INFLUENCE = 0.35f
private const val POSTER_EDGE_DISTANCE_LIMIT = 0.18f
private const val POSTER_MAX_CHROMA = 0.09f
private const val POSTER_MIN_LIGHTNESS = 0.32f
private const val POSTER_MAX_LIGHTNESS = 0.50f
private const val POSTER_TEXT_CONTRAST = 4.8f

private data class OklabColor(
    val lightness: Float,
    val a: Float,
    val b: Float,
)

internal fun artworkPosterSurfaceColor(
    globalSwatches: List<ArtworkPaletteSwatch>,
    edgeSwatches: List<ArtworkPaletteSwatch>,
): Color {
    val global = weightedOklabColor(globalSwatches)
        ?: return posterSurfaceColor(fallbackAmbienceColor())
    val edge = weightedOklabColor(edgeSwatches) ?: global
    val edgeInfluence = posterEdgeInfluence(global, edge)
    return toneMapPosterColor(lerpOklab(global, edge, edgeInfluence))
}

private fun weightedOklabColor(swatches: List<ArtworkPaletteSwatch>): OklabColor? {
    var weightedLightness = 0f
    var weightedA = 0f
    var weightedB = 0f
    var totalWeight = 0f
    swatches.filter { it.population > 0 }.forEach { swatch ->
        val color = rgbIntToOklab(swatch.rgb)
        val extremeWeight = when {
            color.lightness < 0.04f -> 0.35f
            color.lightness > 0.97f -> 0.45f
            else -> 1f
        }
        val weight = swatch.population * extremeWeight
        weightedLightness += color.lightness * weight
        weightedA += color.a * weight
        weightedB += color.b * weight
        totalWeight += weight
    }
    if (totalWeight <= 0f) return null
    return OklabColor(
        lightness = weightedLightness / totalWeight,
        a = weightedA / totalWeight,
        b = weightedB / totalWeight,
    )
}

private fun posterEdgeInfluence(global: OklabColor, edge: OklabColor): Float {
    val lightnessDistance = (global.lightness - edge.lightness) * 0.35f
    val aDistance = global.a - edge.a
    val bDistance = global.b - edge.b
    val distance = sqrt(
        lightnessDistance * lightnessDistance +
            aDistance * aDistance +
            bDistance * bDistance,
    )
    val agreement = (1f - distance / POSTER_EDGE_DISTANCE_LIMIT).coerceIn(0f, 1f)
    return POSTER_EDGE_MAX_INFLUENCE * agreement * agreement
}

private fun lerpOklab(start: OklabColor, stop: OklabColor, fraction: Float): OklabColor {
    val safeFraction = fraction.coerceIn(0f, 1f)
    return OklabColor(
        lightness = start.lightness + (stop.lightness - start.lightness) * safeFraction,
        a = start.a + (stop.a - start.a) * safeFraction,
        b = start.b + (stop.b - start.b) * safeFraction,
    )
}

private fun toneMapPosterColor(source: OklabColor): Color {
    val chroma = sqrt(source.a * source.a + source.b * source.b)
    val chromaScale = if (chroma > POSTER_MAX_CHROMA) POSTER_MAX_CHROMA / chroma else 1f
    var mapped = OklabColor(
        lightness = source.lightness.coerceIn(POSTER_MIN_LIGHTNESS, POSTER_MAX_LIGHTNESS),
        a = source.a * chromaScale,
        b = source.b * chromaScale,
    )
    var surface = oklabToColor(mapped)
    while (
        colorContrastRatio(FnColors.Text, surface) < POSTER_TEXT_CONTRAST &&
        mapped.lightness > POSTER_MIN_LIGHTNESS
    ) {
        mapped = mapped.copy(
            lightness = (mapped.lightness - 0.01f).coerceAtLeast(POSTER_MIN_LIGHTNESS),
        )
        surface = oklabToColor(mapped)
    }
    return surface
}

internal fun perceptualChroma(color: Color): Float {
    val oklab = colorToOklab(color)
    return sqrt(oklab.a * oklab.a + oklab.b * oklab.b)
}

internal fun perceptualColorDistance(first: Color, second: Color): Float {
    val firstOklab = colorToOklab(first)
    val secondOklab = colorToOklab(second)
    val lightnessDistance = firstOklab.lightness - secondOklab.lightness
    val aDistance = firstOklab.a - secondOklab.a
    val bDistance = firstOklab.b - secondOklab.b
    return sqrt(
        lightnessDistance * lightnessDistance +
            aDistance * aDistance +
            bDistance * bDistance,
    )
}

private fun normalizedAmbienceColor(red: Float, green: Float, blue: Float): Color {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    val hue = rgbHue(red, green, blue)
    val sourceSaturation = if (max == 0f) 0f else delta / max
    if (sourceSaturation < 0.12f) {
        val average = (red + green + blue) / 3f
        val value = (0.27f + average * 0.06f).coerceIn(0.27f, 0.33f)
        return Color(
            red = (value + (red - average) * 0.08f).coerceIn(0f, 1f),
            green = (value + (green - average) * 0.08f).coerceIn(0f, 1f),
            blue = (value + (blue - average) * 0.08f).coerceIn(0f, 1f),
        )
    }
    val saturation = (sourceSaturation * 0.52f).coerceIn(0.1f, 0.32f)
    return hsvColor(hue, saturation, value = 0.34f)
}

private fun rgbHue(red: Float, green: Float, blue: Float): Float {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    return when {
        delta == 0f -> 0f
        max == red -> 60f * (((green - blue) / delta) % 6f)
        max == green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }.let { if (it < 0f) it + 360f else it }
}

private fun colorContrastRatio(first: Color, second: Color): Float {
    val lighter = maxOf(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun rgbIntToOklab(rgb: Int): OklabColor = colorToOklab(
    Color(
        red = (rgb ushr 16 and 0xFF) / 255f,
        green = (rgb ushr 8 and 0xFF) / 255f,
        blue = (rgb and 0xFF) / 255f,
    ),
)

private fun colorToOklab(color: Color): OklabColor {
    val red = srgbToLinear(color.red)
    val green = srgbToLinear(color.green)
    val blue = srgbToLinear(color.blue)
    val l = 0.41222146f * red + 0.53633255f * green + 0.051445995f * blue
    val m = 0.2119035f * red + 0.6806995f * green + 0.10739696f * blue
    val s = 0.08830246f * red + 0.28171885f * green + 0.6299787f * blue
    val lRoot = Math.cbrt(l.toDouble()).toFloat()
    val mRoot = Math.cbrt(m.toDouble()).toFloat()
    val sRoot = Math.cbrt(s.toDouble()).toFloat()
    return OklabColor(
        lightness = 0.21045426f * lRoot + 0.7936178f * mRoot - 0.004072047f * sRoot,
        a = 1.9779985f * lRoot - 2.4285922f * mRoot + 0.4505937f * sRoot,
        b = 0.025904037f * lRoot + 0.78277177f * mRoot - 0.80867577f * sRoot,
    )
}

private fun oklabToColor(color: OklabColor): Color {
    val lRoot = color.lightness + 0.39633778f * color.a + 0.21580376f * color.b
    val mRoot = color.lightness - 0.105561346f * color.a - 0.06385417f * color.b
    val sRoot = color.lightness - 0.08948418f * color.a - 1.2914855f * color.b
    val l = lRoot * lRoot * lRoot
    val m = mRoot * mRoot * mRoot
    val s = sRoot * sRoot * sRoot
    return Color(
        red = linearToSrgb(4.0767417f * l - 3.3077116f * m + 0.23096994f * s),
        green = linearToSrgb(-1.268438f * l + 2.6097574f * m - 0.34131938f * s),
        blue = linearToSrgb(-0.0041960863f * l - 0.7034186f * m + 1.7076147f * s),
    )
}

private fun srgbToLinear(component: Float): Float {
    val safeComponent = component.coerceIn(0f, 1f)
    return if (safeComponent <= 0.04045f) {
        safeComponent / 12.92f
    } else {
        ((safeComponent + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }
}

private fun linearToSrgb(component: Float): Float {
    val srgb = if (component <= 0.0031308f) {
        component * 12.92f
    } else {
        1.055f * component.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
    }
    return srgb.coerceIn(0f, 1f)
}

private fun hsvColor(hue: Float, saturation: Float, value: Float): Color {
    val chroma = value * saturation
    val segment = hue / 60f
    val x = chroma * (1f - kotlin.math.abs(segment % 2f - 1f))
    val (red, green, blue) = when (segment.toInt().coerceIn(0, 5)) {
        0 -> Triple(chroma, x, 0f)
        1 -> Triple(x, chroma, 0f)
        2 -> Triple(0f, chroma, x)
        3 -> Triple(0f, x, chroma)
        4 -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    val match = value - chroma
    return Color(red + match, green + match, blue + match)
}

internal fun posterSurfaceColor(color: Color): Color {
    return toneMapPosterColor(colorToOklab(color))
}

internal fun interpolatedLyricPosition(
    anchorPositionMs: Long,
    anchorObservedAtMs: Long,
    frameObservedAtMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
): Long {
    val basePositionMs = anchorPositionMs.coerceAtLeast(0L)
    val elapsedMs = if (isPlaying) {
        (frameObservedAtMs - anchorObservedAtMs).coerceAtLeast(0L)
    } else {
        0L
    }
    val positionMs = basePositionMs + elapsedMs
    return if (durationMs > 0L) positionMs.coerceAtMost(durationMs) else positionMs
}

internal fun playerProgressFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
