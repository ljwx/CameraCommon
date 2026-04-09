package com.jdcr.jdcrcamerabase.util

import android.util.Log

object JdcrCameraLog {

    private val tag = "jdcr_camera"

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