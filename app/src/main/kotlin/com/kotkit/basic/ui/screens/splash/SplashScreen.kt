package com.kotkit.basic.ui.screens.splash

import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kotkit.basic.R
import com.kotkit.basic.ui.theme.AeonikFontFamily
import com.kotkit.basic.ui.theme.GradientCyan
import com.kotkit.basic.ui.theme.GradientPink
import com.kotkit.basic.ui.theme.TikTokBlack
import kotlinx.coroutines.delay
import kotlin.random.Random

@OptIn(UnstableApi::class)
@Composable
fun SplashScreen(
    onSplashFinished: () -> Unit
) {
    val context = LocalContext.current

    // Animation states
    var showVideo by remember { mutableStateOf(false) }
    var showTitle by remember { mutableStateOf(false) }
    var glitchActive by remember { mutableStateOf(false) }
    var fadeOut by remember { mutableStateOf(false) }

    // Glitch intensity (10 = max chaos, 0 = calm)
    var glitchStep by remember { mutableIntStateOf(0) }

    // Glitch animation values - only slices (no shake that reveals video bounds)
    var glitchSlices by remember { mutableStateOf(listOf<GlitchSlice>()) }

    // Video fade in - overlay fades OUT to reveal video
    val overlayAlpha by animateFloatAsState(
        targetValue = if (showVideo) 0f else 1f,
        animationSpec = tween(1500, easing = LinearOutSlowInEasing),
        label = "overlayAlpha"
    )

    // Title animation
    val titleAlpha by animateFloatAsState(
        targetValue = if (showTitle && !glitchActive && !fadeOut) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (glitchActive || fadeOut) 100 else 800,
            easing = if (glitchActive || fadeOut) LinearEasing else LinearOutSlowInEasing
        ),
        label = "titleAlpha"
    )

    val titleScale by animateFloatAsState(
        targetValue = if (showTitle && !glitchActive) 1f else 0.8f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "titleScale"
    )

    val titleOffsetY by animateFloatAsState(
        targetValue = if (showTitle && !glitchActive) 0f else 30f,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "titleOffsetY"
    )

    // Glow pulsation
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    // Screen fade out
    val screenAlpha by animateFloatAsState(
        targetValue = if (fadeOut) 0f else 1f,
        animationSpec = tween(250, easing = LinearEasing),
        finishedListener = { if (fadeOut) onSplashFinished() },
        label = "screenAlpha"
    )

    // Glitch animation loop - ONLY SLICES (no shake/RGB that reveals video bounds)
    LaunchedEffect(glitchActive) {
        if (glitchActive) {
            // Quick glitch ~400ms - only horizontal slices
            for (step in 8 downTo 0) {
                glitchStep = step
                val intensity = step / 8f

                repeat(2) {
                    // Only color slices - cyan/pink/white
                    val sliceCount = (Random.nextInt(4, 10) * intensity).toInt().coerceAtLeast(2)
                    glitchSlices = if (Random.nextFloat() < 0.8f) {
                        List(sliceCount) {
                            GlitchSlice(
                                yOffset = Random.nextFloat(),
                                height = Random.nextFloat() * 0.15f + 0.01f,
                                xShift = (Random.nextFloat() - 0.5f) * 80f,
                                color = when (Random.nextInt(3)) {
                                    0 -> GradientCyan
                                    1 -> GradientPink
                                    else -> Color.White
                                },
                                alpha = Random.nextFloat() * 0.6f + 0.2f
                            )
                        }
                    } else emptyList()

                    delay(30L)
                }
            }

            // Quick reset and fade
            glitchSlices = emptyList()
            fadeOut = true
        }
    }

    // ExoPlayer setup
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val videoUri = Uri.parse("android.resource://${context.packageName}/${R.raw.splash_video}")
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
        }
    }

    // Meow sound state
    var meowPlayed by remember { mutableStateOf(false) }

    // Listen for video playback events
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                // First frame is rendered - start fade-in
                showVideo = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    glitchActive = true
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Track video position to play meow near the end
    LaunchedEffect(exoPlayer) {
        // Wait for video to be ready and get duration
        while (exoPlayer.duration <= 0) {
            delay(50)
        }

        val totalDuration = exoPlayer.duration
        Log.d("SplashScreen", "Video duration: $totalDuration ms")

        // Calculate when to play meow (1.5 seconds before end, or at 50% for short videos)
        val meowTriggerTime = if (totalDuration > 3000) {
            totalDuration - 1500
        } else {
            totalDuration / 2  // For short videos, play at 50%
        }
        Log.d("SplashScreen", "Will play meow at position: $meowTriggerTime ms")

        while (!meowPlayed && exoPlayer.playbackState != Player.STATE_ENDED) {
            delay(50)
            val position = exoPlayer.currentPosition

            if (position >= meowTriggerTime) {
                Log.d("SplashScreen", "Playing meow at position: $position ms")
                try {
                    // Create and play sound directly
                    MediaPlayer.create(context, R.raw.meow_ui)?.apply {
                        setVolume(0.8f, 0.8f)
                        setOnCompletionListener { mp ->
                            Log.d("SplashScreen", "Meow completed")
                            mp.release()
                        }
                        start()
                        Log.d("SplashScreen", "Meow started successfully!")
                    } ?: Log.e("SplashScreen", "Failed to create MediaPlayer!")
                } catch (e: Exception) {
                    Log.e("SplashScreen", "Error playing meow", e)
                }
                meowPlayed = true
            }
        }
    }

    // Show title after delay
    LaunchedEffect(Unit) {
        delay(1800)
        showTitle = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TikTokBlack)
            .alpha(screenAlpha),
        contentAlignment = Alignment.Center
    ) {
        // Glow effect behind video
        Box(
            modifier = Modifier
                .size(400.dp)
                .blur(100.dp)
                .alpha(glowAlpha * 0.4f)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            GradientCyan.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Video player
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Black overlay that fades out to reveal video
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(overlayAlpha)
                    .background(TikTokBlack)
            )
        }

        // Glitch slices overlay - full screen
        if (glitchActive && glitchSlices.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                glitchSlices.forEach { slice ->
                    // Main slice
                    drawRect(
                        color = slice.color.copy(alpha = slice.alpha),
                        topLeft = Offset(slice.xShift.dp.toPx(), size.height * slice.yOffset),
                        size = Size(size.width + 100f, size.height * slice.height)
                    )
                    // Secondary offset slice for depth
                    if (Random.nextFloat() > 0.5f) {
                        drawRect(
                            color = slice.color.copy(alpha = slice.alpha * 0.5f),
                            topLeft = Offset(
                                -slice.xShift.dp.toPx() * 0.5f,
                                size.height * slice.yOffset + 5.dp.toPx()
                            ),
                            size = Size(size.width, size.height * slice.height * 0.5f)
                        )
                    }
                }
            }
        }

        // Scanlines overlay during glitch (subtle)
        if (glitchActive && glitchStep > 3) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.08f)
            ) {
                val lineHeight = 1.dp.toPx()
                val gap = 4.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(0f, y),
                        size = Size(size.width, lineHeight)
                    )
                    y += gap + lineHeight
                }
            }
        }

        // App title at bottom
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Gradient text "KotKit"
            Text(
                text = "KotKit",
                fontSize = 42.sp,
                fontFamily = AeonikFontFamily,
                fontWeight = FontWeight.Bold,
                style = androidx.compose.ui.text.TextStyle(
                    brush = Brush.linearGradient(
                        colors = listOf(GradientCyan, GradientPink)
                    )
                ),
                modifier = Modifier
                    .alpha(titleAlpha)
                    .scale(titleScale)
                    .offset(y = titleOffsetY.dp)
            )

            // Subtle tagline
            Text(
                text = "Smart TikTok Autoposter",
                fontSize = 14.sp,
                fontFamily = AeonikFontFamily,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .alpha(titleAlpha * 0.8f)
                    .offset(y = titleOffsetY.dp)
                    .padding(top = 8.dp)
            )
        }
    }
}

private data class GlitchSlice(
    val yOffset: Float,
    val height: Float,
    val xShift: Float,
    val color: Color,
    val alpha: Float = 0.4f
)
