package com.autoposter.executor.accessibility.portal

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

class UITreeParser {

    fun parse(rootNode: AccessibilityNodeInfo?): UITree {
        if (rootNode == null) {
            return UITree("", null, emptyList())
        }

        val elements = mutableListOf<UIElement>()
        var index = 0

        fun traverse(node: AccessibilityNodeInfo, depth: Int = 0) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Only include visible elements with actual size
            if (bounds.width() > 0 && bounds.height() > 0 && node.isVisibleToUser) {
                elements.add(
                    UIElement(
                        index = index++,
                        className = node.className?.toString() ?: "",
                        resourceId = node.viewIdResourceName,
                        text = node.text?.toString(),
                        contentDescription = node.contentDescription?.toString(),
                        bounds = bounds,
                        isClickable = node.isClickable,
                        isEnabled = node.isEnabled,
                        isVisible = node.isVisibleToUser
                    )
                )
            }

            // Recursively traverse children
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    traverse(child, depth + 1)
                    child.recycle()
                }
            }
        }

        traverse(rootNode)

        return UITree(
            packageName = rootNode.packageName?.toString() ?: "",
            activityName = null, // Activity name obtained separately if needed
            elements = elements
        )
    }

    /**
     * Find element by text content
     */
    fun findElementByText(tree: UITree, text: String, ignoreCase: Boolean = true): UIElement? {
        return tree.elements.find { element ->
            element.text?.contains(text, ignoreCase) == true ||
            element.contentDescription?.contains(text, ignoreCase) == true
        }
    }

    /**
     * Find element by resource ID
     */
    fun findElementByResourceId(tree: UITree, resourceId: String): UIElement? {
        return tree.elements.find { element ->
            element.resourceId?.contains(resourceId) == true
        }
    }

    /**
     * Find clickable elements
     */
    fun findClickableElements(tree: UITree): List<UIElement> {
        return tree.elements.filter { it.isClickable && it.isEnabled }
    }

    /**
     * Find elements by class name
     */
    fun findElementsByClassName(tree: UITree, className: String): List<UIElement> {
        return tree.elements.filter { element ->
            element.className.contains(className, ignoreCase = true)
        }
    }

    /**
     * Find input fields (EditText)
     */
    fun findInputFields(tree: UITree): List<UIElement> {
        return findElementsByClassName(tree, "EditText").filter { it.isEnabled }
    }

    /**
     * Find buttons
     */
    fun findButtons(tree: UITree): List<UIElement> {
        return tree.elements.filter { element ->
            element.isClickable &&
            element.isEnabled &&
            (element.className.contains("Button", ignoreCase = true) ||
             element.className.contains("ImageView", ignoreCase = true))
        }
    }
}
