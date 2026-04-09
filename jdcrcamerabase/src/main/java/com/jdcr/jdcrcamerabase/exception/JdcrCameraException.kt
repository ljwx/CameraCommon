package com.jdcr.jdcrcamerabase.exception

class JdcrCameraException(val msg: String, val code: Int = 0) : Exception(msg)