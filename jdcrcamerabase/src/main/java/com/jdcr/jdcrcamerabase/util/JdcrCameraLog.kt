package com.jdcr.jdcrcamerabase.util

import com.jdcr.jdcrlog.JdcrLogBase

object JdcrCameraLog {

    var prefix = "jdcr_"
    var feature = "camera"

    private val logger by lazy { JdcrLogBase(prefix, feature) }

    fun v(msg: String) {
        logger.v(msg)
    }

    fun d(msg: String) {
        logger.d(msg)
    }

    fun i(msg: String) {
        logger.i(msg)
    }

    fun w(msg: String) {
        logger.w(msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        logger.i(msg, t)
    }

}