package com.jdcr.jdcrcameragesture

data class JdcrHandGestureResult(val gesture: String, val position: JdcrHandGesturePosition?)

data class JdcrHandGesturePosition(
    val x: Float,
    val y: Float,
)