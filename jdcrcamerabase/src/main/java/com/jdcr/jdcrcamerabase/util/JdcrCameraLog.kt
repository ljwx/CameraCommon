package com.jdcr.jdcrcamerabase.util

import android.util.Log

open class JdcrCameraLogBase {

    var mTagPrefix = "jdcr"

    var feat = "_camera"
    protected val tag by lazy { mTagPrefix + feat }

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

object JdcrCameraLog : JdcrCameraLogBase() {

}