// SPDX-License-Identifier: GPL-3.0-only
// Library cover rendering: remote artwork loading, fallback placeholders, and the hand-drawn
// collection/home artwork tiles. Pure leaf composables with no routing or list state.
package com.fnmusic.tv.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import androidx.tv.material3.Border
import androidx.tv.material3.Button as TvMaterialButton
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.fnmusic.tv.AuthenticatedAppDependencies
import com.fnmusic.tv.NowPlayingPresentation
import com.fnmusic.tv.NowPlayingResourceState
import com.fnmusic.tv.core.data.repository.CurrentLyrics
import com.fnmusic.tv.core.data.repository.SessionState
import com.fnmusic.tv.core.model.Album
import com.fnmusic.tv.core.model.AppError
import com.fnmusic.tv.core.model.AppException
import com.fnmusic.tv.core.model.Artist
import com.fnmusic.tv.core.model.CoverVariant
import com.fnmusic.tv.core.model.Page
import com.fnmusic.tv.core.model.PlayerStyle
import com.fnmusic.tv.core.model.Playlist
import com.fnmusic.tv.core.model.Track
import com.fnmusic.tv.core.model.playback.MAX_ACTIVE_QUEUE_ITEMS
import com.fnmusic.tv.core.model.playback.NowPlayingIdentity
import com.fnmusic.tv.core.model.playback.PlayMode
import com.fnmusic.tv.core.model.playback.PlaybackQueueItem
import com.fnmusic.tv.core.model.playback.QueueKind
import com.fnmusic.tv.core.model.playback.QueuePageItem
import com.fnmusic.tv.core.model.playback.QueuePageSegment
import com.fnmusic.tv.core.model.playback.QueueSource
import com.fnmusic.tv.core.model.playback.boundedQueueWindow
import com.fnmusic.tv.core.playback.PlaybackProgressState
import com.fnmusic.tv.core.playback.PlaybackUiState
import com.fnmusic.tv.decodeArtwork
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

@Composable
internal fun CollectionArtwork(
    container: AuthenticatedAppDependencies,
    title: String,
    coverId: String?,
    fallback: CollectionArtworkFallback,
    modifier: Modifier,
    shape: Shape,
) {
    val resolvedShape = if (fallback == CollectionArtworkFallback.Artist) CircleShape else shape
    if (coverId != null) {
        RemoteArtwork(
            container = container,
            coverId = coverId,
            variant = CoverVariant.Grid,
            modifier = modifier,
            shape = resolvedShape,
            contentScale = ContentScale.Crop,
            placeholderContent = {
                CollectionArtworkFallbackContent(title, fallback, Modifier.fillMaxSize(), resolvedShape)
            },
        )
    } else {
        CollectionArtworkFallbackContent(title, fallback, modifier, resolvedShape)
    }
}

@Composable
internal fun CollectionArtworkFallbackContent(
    title: String,
    fallback: CollectionArtworkFallback,
    modifier: Modifier,
    shape: Shape,
) {
    when (fallback) {
        CollectionArtworkFallback.Artist -> ArtistAvatarPlaceholder(
            title = title,
            modifier = modifier,
            fontSize = 58.sp,
        )
        CollectionArtworkFallback.Favorites -> Box(modifier.clip(shape)) {
            HomeFeatureArtwork(HomeArtworkKind.Favorites, Modifier.fillMaxSize())
        }
        CollectionArtworkFallback.Collection -> Box(modifier.clip(shape)) {
            HomeFeatureArtwork(HomeArtworkKind.Collection, Modifier.fillMaxSize())
        }
        CollectionArtworkFallback.Initial -> InitialArtworkPlaceholder(
            text = title,
            accent = FnColors.Coral,
            modifier = modifier,
            shape = shape,
        )
    }
}


@Composable
internal fun PlaylistTileArtwork(
    title: String,
    coverId: String?,
    accent: Color,
    modifier: Modifier = Modifier,
    featureArtwork: HomeArtworkKind? = null,
) {
    val container = LocalAuthenticatedDependencies.current
    val shape = RectangleShape
    if (featureArtwork != null) {
        HomeFeatureArtwork(featureArtwork, modifier)
    } else if (coverId != null) {
        RemoteArtwork(
            container = container,
            coverId = coverId,
            variant = CoverVariant.Grid,
            modifier = modifier,
            shape = shape,
            contentScale = ContentScale.Crop,
            placeholderContent = {
                InitialArtworkPlaceholder(title, accent, Modifier.fillMaxSize(), shape)
            },
        )
    } else {
        InitialArtworkPlaceholder(title, accent, modifier, shape)
    }
}

internal enum class HomeArtworkKind { Roam, Favorites, Collection, PlaylistGrid }


@Composable
internal fun HomeFeatureArtwork(kind: HomeArtworkKind, modifier: Modifier) {
    Canvas(modifier) {
        val shortEdge = minOf(size.width, size.height)
        fun point(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(size.width * x, size.height * y)
        fun drawVinyl(center: androidx.compose.ui.geometry.Offset, radius: Float, label: Color) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF38393D),
                        Color(0xFF0B0C0E),
                        Color(0xFF27282C),
                        Color(0xFF08090A),
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
            listOf(0.58f, 0.72f, 0.86f).forEach { scale ->
                drawCircle(
                    Color.White.copy(alpha = 0.08f),
                    radius * scale,
                    center,
                    style = Stroke(shortEdge * 0.006f),
                )
            }
            drawCircle(label, radius * 0.24f, center)
            drawCircle(Color(0xFFF8F2E7), radius * 0.055f, center)
        }

        when (kind) {
            HomeArtworkKind.Roam -> {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFEAE6DF), Color(0xFFC5CDCB)),
                        start = point(0.08f, 0f),
                        end = point(0.94f, 1f),
                    ),
                )
                drawVinyl(point(0.69f, 0.51f), shortEdge * 0.43f, FnColors.Warning)

                val sleeveEdge = size.height * 0.84f
                val sleeveTopLeft = point(0.12f, 0.08f)
                val sleeveSize = androidx.compose.ui.geometry.Size(sleeveEdge, sleeveEdge)
                val sleeveCorner = androidx.compose.ui.geometry.CornerRadius(shortEdge * 0.065f)
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.20f),
                    topLeft = sleeveTopLeft + androidx.compose.ui.geometry.Offset(shortEdge * 0.035f, shortEdge * 0.045f),
                    size = sleeveSize,
                    cornerRadius = sleeveCorner,
                )
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFF4938),
                            FnColors.Coral,
                            Color(0xFFEC477E),
                            Color(0xFF7569D8),
                        ),
                        start = sleeveTopLeft,
                        end = sleeveTopLeft + androidx.compose.ui.geometry.Offset(sleeveEdge, sleeveEdge),
                    ),
                    topLeft = sleeveTopLeft,
                    size = sleeveSize,
                    cornerRadius = sleeveCorner,
                )
                val orangeWave = Path().apply {
                    moveTo(sleeveTopLeft.x, sleeveTopLeft.y + sleeveEdge * 0.68f)
                    cubicTo(
                        sleeveTopLeft.x + sleeveEdge * 0.18f, sleeveTopLeft.y + sleeveEdge * 0.48f,
                        sleeveTopLeft.x + sleeveEdge * 0.32f, sleeveTopLeft.y + sleeveEdge * 0.56f,
                        sleeveTopLeft.x + sleeveEdge * 0.48f, sleeveTopLeft.y + sleeveEdge * 0.42f,
                    )
                    cubicTo(
                        sleeveTopLeft.x + sleeveEdge * 0.66f, sleeveTopLeft.y + sleeveEdge * 0.24f,
                        sleeveTopLeft.x + sleeveEdge * 0.78f, sleeveTopLeft.y + sleeveEdge * 0.24f,
                        sleeveTopLeft.x + sleeveEdge, sleeveTopLeft.y + sleeveEdge * 0.05f,
                    )
                    lineTo(sleeveTopLeft.x + sleeveEdge, sleeveTopLeft.y + sleeveEdge)
                    lineTo(sleeveTopLeft.x, sleeveTopLeft.y + sleeveEdge)
                    close()
                }
                drawPath(orangeWave, Color(0xFFFFA538).copy(alpha = 0.84f))
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                        start = sleeveTopLeft,
                        end = sleeveTopLeft + androidx.compose.ui.geometry.Offset(sleeveEdge * 0.72f, sleeveEdge),
                    ),
                    topLeft = sleeveTopLeft,
                    size = sleeveSize,
                    cornerRadius = sleeveCorner,
                )
            }

            HomeArtworkKind.Favorites -> {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFF304D),
                            Color(0xFFFF563F),
                            Color(0xFFFF9D37),
                            Color(0xFFFFD45A),
                        ),
                        start = point(0.08f, 0.06f),
                        end = point(0.94f, 0.92f),
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFD91442).copy(alpha = 0.72f), Color.Transparent),
                        center = point(0.20f, 0.20f),
                        radius = shortEdge * 0.78f,
                    ),
                    radius = shortEdge * 0.78f,
                    center = point(0.20f, 0.20f),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFFDA70).copy(alpha = 0.65f), Color.Transparent),
                        center = point(0.89f, 0.89f),
                        radius = shortEdge * 0.62f,
                    ),
                    radius = shortEdge * 0.62f,
                    center = point(0.89f, 0.89f),
                )
                val heartSize = androidx.compose.ui.geometry.Size(shortEdge * 0.56f, shortEdge * 0.56f)
                val heartOrigin = androidx.compose.ui.geometry.Offset(
                    (size.width - heartSize.width) / 2f,
                    (size.height - heartSize.height) / 2f - shortEdge * 0.015f,
                )
                withTransform({ translate(heartOrigin.x + shortEdge * 0.035f, heartOrigin.y + shortEdge * 0.055f) }) {
                    drawPath(heartPath(heartSize), Color(0xFF9E1731).copy(alpha = 0.18f))
                }
                withTransform({ translate(heartOrigin.x, heartOrigin.y) }) {
                    drawPath(heartPath(heartSize), Color(0xFFFFDDD8).copy(alpha = 0.78f))
                }
            }

            HomeArtworkKind.Collection -> {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF124A3A), Color(0xFF267A60), FnColors.Teal),
                        start = point(0f, 0.10f),
                        end = point(1f, 0.90f),
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFB4ECD7).copy(alpha = 0.30f), Color.Transparent),
                        center = point(0.88f, 0.12f),
                        radius = shortEdge * 0.72f,
                    ),
                    radius = shortEdge * 0.72f,
                    center = point(0.88f, 0.12f),
                )
                val discCenter = point(0.70f, 0.50f)
                drawCircle(Color.Black.copy(alpha = 0.18f), shortEdge * 0.36f, discCenter + point(0.015f, 0.035f))
                drawVinyl(discCenter, shortEdge * 0.35f, FnColors.Coral)

                val coverEdge = size.height * 0.70f
                val corner = androidx.compose.ui.geometry.CornerRadius(shortEdge * 0.045f)
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.18f),
                    topLeft = point(0.16f, 0.20f),
                    size = androidx.compose.ui.geometry.Size(coverEdge, coverEdge),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = Color(0xFFE7C95D),
                    topLeft = point(0.12f, 0.13f),
                    size = androidx.compose.ui.geometry.Size(coverEdge, coverEdge),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFF4EFE5), Color(0xFFD5E0DB)),
                        start = point(0.19f, 0.17f),
                        end = point(0.53f, 0.84f),
                    ),
                    topLeft = point(0.19f, 0.17f),
                    size = androidx.compose.ui.geometry.Size(coverEdge, coverEdge),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = FnColors.Coral,
                    topLeft = point(0.24f, 0.24f),
                    size = androidx.compose.ui.geometry.Size(coverEdge * 0.23f, coverEdge * 0.62f),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = Color(0xFF202528),
                    topLeft = point(0.35f, 0.24f),
                    size = androidx.compose.ui.geometry.Size(coverEdge * 0.14f, coverEdge * 0.62f),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = FnColors.Teal,
                    topLeft = point(0.43f, 0.24f),
                    size = androidx.compose.ui.geometry.Size(coverEdge * 0.09f, coverEdge * 0.62f),
                    cornerRadius = corner,
                )
            }

            HomeArtworkKind.PlaylistGrid -> {
                drawRect(Color(0xFF171C1F))
                val borderInset = shortEdge * 0.012f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.13f),
                    topLeft = androidx.compose.ui.geometry.Offset(borderInset, borderInset),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - borderInset * 2f,
                        size.height - borderInset * 2f,
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(shortEdge * 0.07f),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                        ),
                    ),
                )
                val cellSize = shortEdge * 0.15f
                val gap = shortEdge * 0.055f
                val gridWidth = cellSize * 2f + gap
                val gridHeight = cellSize * 2f + gap
                val gridOrigin = androidx.compose.ui.geometry.Offset(
                    (size.width - gridWidth) / 2f,
                    (size.height - gridHeight) / 2f,
                )
                repeat(2) { row ->
                    repeat(2) { column ->
                        drawRoundRect(
                            color = Color(0xFF858B8E),
                            topLeft = androidx.compose.ui.geometry.Offset(
                                gridOrigin.x + column * (cellSize + gap),
                                gridOrigin.y + row * (cellSize + gap),
                            ),
                            size = androidx.compose.ui.geometry.Size(cellSize, cellSize),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(shortEdge * 0.035f),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
        }
    }
}


@Composable
internal fun ArtistAvatarPlaceholder(
    title: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 23.sp,
) {
    Box(
        modifier.background(Color(0xFF2D4A46), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.trim().take(1).ifBlank { "音" }.uppercase(),
            color = FnColors.Teal,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
        )
    }
}


@Composable
internal fun RemoteArtwork(
    container: AuthenticatedAppDependencies,
    coverId: String,
    variant: CoverVariant,
    modifier: Modifier = Modifier,
    fallbackVariant: CoverVariant? = null,
    shape: Shape = RoundedCornerShape(8.dp),
    contentScale: ContentScale = ContentScale.Fit,
    placeholderContent: (@Composable () -> Unit)? = null,
) {
    val bitmap = rememberRemoteArtworkBitmap(container, coverId, variant, fallbackVariant)
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), null, modifier.clip(shape), contentScale = contentScale)
    } else {
        Box(modifier.background(FnColors.Surface, shape), contentAlignment = Alignment.Center) {
            if (placeholderContent != null) {
                placeholderContent()
            } else {
                InitialArtworkPlaceholder(
                    text = "",
                    accent = FnColors.Teal,
                    modifier = Modifier.fillMaxSize(),
                    shape = shape,
                )
            }
        }
    }
}

@Composable
internal fun rememberRemoteArtworkBitmap(
    container: AuthenticatedAppDependencies,
    coverId: String?,
    variant: CoverVariant,
    fallbackVariant: CoverVariant? = null,
): Bitmap? {
    val initialBitmap = remember(container, coverId, variant, fallbackVariant) {
        coverId?.let { id ->
            container.artworkBitmapCache.peek(id, variant)
                ?: fallbackVariant?.let { container.artworkBitmapCache.peek(id, it) }
        }
    }
    val bitmap by produceState(initialBitmap, container, coverId, variant, fallbackVariant) {
        value = coverId?.let { id ->
            container.artworkBitmapCache.getProgressively(
                coverId = id,
                variant = variant,
                fallbackVariant = fallbackVariant,
                onIntermediate = { value = it },
            )
        }
    }
    return if (coverId == null) null else bitmap
}

