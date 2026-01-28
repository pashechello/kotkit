package com.kotkit.basic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest

@Composable
fun VideoThumbnail(
    videoPath: String,
    modifier: Modifier = Modifier,
    thumbnailPath: String? = null,
    showPlayIcon: Boolean = true
) {
    val context = LocalContext.current
    var loadState by remember { mutableStateOf<AsyncImagePainter.State?>(null) }

    // Use saved thumbnail if available, otherwise extract from video
    val imageSource = thumbnailPath ?: videoPath
    val useSavedThumbnail = thumbnailPath != null

    Box(
        modifier = modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // Only show AsyncImage if not in error state (prevents reload flickering)
        val isError = loadState is AsyncImagePainter.State.Error

        if (!isError) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageSource)
                    .apply {
                        if (!useSavedThumbnail) {
                            decoderFactory { result, options, _ ->
                                VideoFrameDecoder(result.source, options)
                            }
                        }
                    }
                    .memoryCacheKey(imageSource)
                    .diskCacheKey(imageSource)
                    .build(),
                contentDescription = "Video thumbnail",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onState = { state -> loadState = state }
            )
        }

        // Static placeholder when file not found
        if (isError) {
            Icon(
                imageVector = Icons.Default.VideoFile,
                contentDescription = "Video",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )
        } else if (showPlayIcon && loadState is AsyncImagePainter.State.Success) {
            // Play icon overlay - only show when image loaded successfully
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun VideoThumbnailPlaceholder(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.VideoFile,
            contentDescription = "Video",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
    }
}
