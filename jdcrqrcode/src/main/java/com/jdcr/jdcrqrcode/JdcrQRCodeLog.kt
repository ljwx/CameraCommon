package com.jdcr.jdcrqrcode

import com.jdcr.jdcrlog.JdcrLog

object JdcrQRCodeLog {

    private val logger = JdcrLog

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