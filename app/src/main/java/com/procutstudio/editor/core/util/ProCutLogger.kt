package com.procutstudio.editor.core.util

import android.util.Log

/**
 * Thin logging facade. Export and playback are the two areas where device-specific failures are
 * common, so those tags are kept stable to make bug reports actionable.
 */
object ProCutLogger {
    const val TAG_PROJECT = "ProCut/Project"
    const val TAG_MEDIA = "ProCut/Media"
    const val TAG_PLAYBACK = "ProCut/Playback"
    const val TAG_EXPORT = "ProCut/Export"
    const val TAG_TIMELINE = "ProCut/Timeline"
    const val TAG_UI = "ProCut/UI"

    private const val VERBOSE = true

    fun d(tag: String, message: String) {
        if (VERBOSE) Log.d(tag, message)
    }

    fun i(tag: String, message: String) = Log.i(tag, message)

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }
}
