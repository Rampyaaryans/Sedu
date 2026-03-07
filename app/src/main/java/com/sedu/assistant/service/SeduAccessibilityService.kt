package com.sedu.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Deep OS control via Accessibility Service.
 * Allows Sedu to tap buttons, read screen, navigate apps, pull notifications, etc.
 */
class SeduAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SeduA11y"
        var instance: SeduAccessibilityService? = null
            private set
        var isRunning = false
            private set

        fun performGlobalBack() {
            instance?.performGlobalAction(GLOBAL_ACTION_BACK)
        }

        fun performGlobalHome() {
            instance?.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        fun performGlobalRecents() {
            instance?.performGlobalAction(GLOBAL_ACTION_RECENTS)
        }

        fun performGlobalNotifications() {
            instance?.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }

        fun performGlobalQuickSettings() {
            instance?.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        }

        fun performGlobalScreenshot() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                instance?.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            }
        }

        fun performGlobalLockScreen() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }
        }

        /**
         * Find and click a UI element by text on current screen
         */
        fun clickByText(text: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                // Try parent if node isn't clickable
                var parent = node.parent
                var depth = 0
                while (parent != null && depth < 5) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                    parent = parent.parent
                    depth++
                }
            }
            return false
        }

        /**
         * Get all text visible on current screen
         */
        fun getScreenText(): String {
            val root = instance?.rootInActiveWindow ?: return ""
            val sb = StringBuilder()
            traverseNode(root, sb)
            return sb.toString()
        }

        /**
         * Click first playable item on screen (for YouTube/music apps).
         * Looks for video thumbnails, play buttons, or first clickable media item.
         */
        fun clickFirstPlayable(): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            // Try clicking known play-related elements
            val playTexts = listOf("Play", "Watch", "Listen", "Play all", "Shuffle play")
            for (text in playTexts) {
                if (clickByText(text)) return true
            }
            // Find first clickable item that looks like media content
            return clickFirstMediaItem(root, 0)
        }

        private fun clickFirstMediaItem(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 8) return false
            // Skip navigation/header elements
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            // YouTube video items, Spotify tracks, etc.
            if (node.isClickable && (
                viewId.contains("video") || viewId.contains("item") || viewId.contains("thumbnail") ||
                viewId.contains("card") || viewId.contains("track") ||
                desc.contains("video") || desc.contains("song") || desc.contains("play"))) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked media item: viewId=$viewId, desc=$desc")
                return true
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (clickFirstMediaItem(child, depth + 1)) return true
            }
            return false
        }

        private fun traverseNode(node: AccessibilityNodeInfo, sb: StringBuilder) {
            val text = node.text?.toString()
            if (!text.isNullOrBlank()) {
                sb.append(text).append(" ")
            }
            val desc = node.contentDescription?.toString()
            if (!desc.isNullOrBlank()) {
                sb.append(desc).append(" ")
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverseNode(child, sb)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.d(TAG, "Accessibility service connected — deep OS control enabled")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We listen passively — actions are triggered by SeduService/ActionExecutor
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        Log.d(TAG, "Accessibility service destroyed")
    }
}
