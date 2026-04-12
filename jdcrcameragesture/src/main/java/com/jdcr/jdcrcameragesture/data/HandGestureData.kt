package com.jdcr.jdcrcameragesture.data

data class JdcrHandGestureResult(val name: GestureName, val position: GesturePosition?)

@JvmInline
value class GestureName(val name: String)

data class GesturePosition(
    val x: Float,
    val y: Float,
)