package com.jdcr.jdcrcameragesture.util

import android.util.Log
import com.jdcr.jdcrcamerabase.util.JdcrCameraLog
import com.jdcr.jdcrcamerabase.util.JdcrCameraLogBase

object HandGestureLog : JdcrCameraLogBase("_camera_gesture") {

    var enableRunLog = true

    fun r(content: String) {
        if (enableRunLog) {
            Log.i(tag, content)
        }
    }

}