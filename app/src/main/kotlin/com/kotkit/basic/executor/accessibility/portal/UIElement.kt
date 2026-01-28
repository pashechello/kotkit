package com.kotkit.basic.executor.accessibility.portal

import android.graphics.Rect
import com.kotkit.basic.data.remote.api.models.BoundsModel
import com.kotkit.basic.data.remote.api.models.UIElementModel

data class UIElement(
    val index: Int,
    val className: String,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEnabled: Boolean,
    val isVisible: Boolean,
    val children: List<UIElement> = emptyList()
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    fun toApiModel(): UIElementModel {
        return UIElementModel(
            index = index,
            className = className,
            resourceId = resourceId,
            text = text,
            contentDescription = contentDescription,
            bounds = BoundsModel(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom
            ),
            clickable = isClickable,
            enabled = isEnabled,
            visible = isVisible
        )
    }
}

data class UITree(
    val packageName: String,
    val activityName: String?,
    val elements: List<UIElement>,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toApiModel(): com.kotkit.basic.data.remote.api.models.UITreeModel {
        return com.kotkit.basic.data.remote.api.models.UITreeModel(
            packageName = packageName,
            activity = activityName,
            elements = elements.map { it.toApiModel() }
        )
    }
}
