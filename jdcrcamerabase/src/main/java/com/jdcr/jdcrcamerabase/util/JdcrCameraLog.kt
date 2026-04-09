package com.jdcr.jdcrcamerabase.util

import android.util.Log

object JdcrCameraLog {

    var mTagPrefix = "jdcr"

    private val feat = "_camera"
    private val tag by lazy { mTagPrefix + feat }

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