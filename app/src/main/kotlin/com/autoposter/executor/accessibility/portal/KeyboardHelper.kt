package com.autoposter.executor.accessibility.portal

import android.content.Context
import android.view.inputmethod.InputMethodManager

class KeyboardHelper(private val context: Context) {

    /**
     * Check if the keyboard is currently visible
     */
    fun isKeyboardVisible(): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.isAcceptingText
    }

    /**
     * Get the height of the visible keyboard (if shown)
     * Note: This is an approximation based on common keyboard heights
     */
    fun getEstimatedKeyboardHeight(): Int {
        val displayMetrics = context.resources.displayMetrics
        // Keyboard is typically 40-50% of screen height
        return (displayMetrics.heightPixels * 0.4).toInt()
    }
}
