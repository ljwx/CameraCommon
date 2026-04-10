package com.jdcr.jdcrcameragesture.util

import android.util.Log
import com.jdcr.jdcrcamerabase.util.JdcrCameraLog
import com.jdcr.jdcrcamerabase.util.JdcrCameraLogBase

object HandGestureLog: JdcrCameraLogBase() {

    init {
        feat = "_gesture"
    }

    var enableRunLog = false

    fun r(content: String) {
        if (enableRunLog) {
            Log.i(tag, content)
        }
    }

}