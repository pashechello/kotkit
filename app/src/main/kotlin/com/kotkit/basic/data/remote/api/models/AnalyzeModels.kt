package com.kotkit.basic.data.remote.api.models

import com.google.gson.annotations.SerializedName

// Request to analyze screen and get next action
data class AnalyzeRequest(
    val screenshot: String,  // Base64 encoded JPEG
    @SerializedName("ui_tree") val uiTree: UITreeModel,
    val context: AnalyzeContext
)

data class UITreeModel(
    @SerializedName("package") val packageName: String,
    val activity: String?,
    val elements: List<UIElementModel>
)

data class UIElementModel(
    val index: Int,
    @SerializedName("class") val className: String,
    @SerializedName("resource_id") val resourceId: String?,
    val text: String?,
    @SerializedName("content_desc") val contentDescription: String?,
    val bounds: BoundsModel,
    val clickable: Boolean,
    val enabled: Boolean,
    val visible: Boolean
)

data class BoundsModel(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class AnalyzeContext(
    val task: String,  // "post_video" or "post_video_share"
    @SerializedName("session_id") val sessionId: String,
    val step: Int,
    @SerializedName("video_filename") val videoFilename: String,
    val caption: String,
    @SerializedName("previous_actions") val previousActions: List<String>,
    @SerializedName("launch_method") val launchMethod: String? = null  // Always "SHARE_INTENT"
)

// Response with action to execute
data class AnalyzeResponse(
    val action: String,
    val x: Int? = null,
    val y: Int? = null,
    // Element size for humanizer - used to calculate adaptive jitter
    @SerializedName("element_width") val elementWidth: Int? = null,
    @SerializedName("element_height") val elementHeight: Int? = null,
    // Element index hint for semantic grounding
    @SerializedName("element_index") val elementIndex: Int? = null,
    // Swipe coordinates
    @SerializedName("start_x") val startX: Int? = null,
    @SerializedName("start_y") val startY: Int? = null,
    @SerializedName("end_x") val endX: Int? = null,
    @SerializedName("end_y") val endY: Int? = null,
    val duration: Int? = null,
    val text: String? = null,
    @SerializedName("package_name") val packageName: String? = null,
    @SerializedName("wait_after") val waitAfter: Int? = null,
    val confidence: Float? = null,
    val reason: String? = null,
    val message: String? = null,
    val recoverable: Boolean? = null,
    // Flag indicating this is the final publish button tap
    @SerializedName("isPublishAction") val isPublishAction: Boolean = false
)

// Supported action types - must match Python backend
object ActionType {
    const val TAP = "tap"
    const val SWIPE = "swipe"
    const val TYPE_TEXT = "type_text"
    const val WAIT = "wait"
    const val BACK = "back"
    // NOTE: LAUNCH_TIKTOK removed - Share Intent is the only supported flow
    const val DISMISS_POPUP = "dismiss_popup"
    const val NAVIGATE_TO_FEED = "navigate_to_feed"
    const val FINISH = "finish"

    // Legacy aliases for compatibility
    const val TYPE = "type_text"
    const val DONE = "finish"
    const val ERROR = "error"
}

// Caption generation request
data class GenerateCaptionRequest(
    @SerializedName("trackName") val trackName: String = "",
    @SerializedName("videoDescription") val videoDescription: String = "",
    @SerializedName("tonePrompt") val tonePrompt: String? = null
)

// Caption generation response
data class GenerateCaptionResponse(
    val caption: String,
    val success: Boolean = true,
    val error: String? = null
)

// Request to verify feed screen (used after publish tap)
data class VerifyFeedRequest(
    val screenshot: String  // Base64 encoded PNG
)

// Response with feed verification result
data class VerifyFeedResponse(
    @SerializedName("is_feed") val isFeed: Boolean,
    @SerializedName("has_popup") val hasPopup: Boolean = false,
    @SerializedName("popup_type") val popupType: String? = null
)
