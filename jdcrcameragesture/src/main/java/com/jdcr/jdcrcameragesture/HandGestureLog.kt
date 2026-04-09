package com.jdcr.jdcrcameragesture

import android.util.Log
import com.jdcr.jdcrcamerabase.util.JdcrCameraLog

object HandGestureLog {

    private val feat = "_camera_g"
    private var tag = JdcrCameraLog.mTagPrefix + feat

    var enableRunLog = false

    fun r(content: String) {
        if (enableRunLog) {
            Log.i(tag, content)
        }
    }

    fun i(content: String) {
        Log.i(tag, content)
    }

    fun d(content: String) {
        Log.d(tag, content)
    }

    fun e(content: String) {
        Log.e(tag, content)
    }

}