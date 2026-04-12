package com.jdcr.jdcrcameragesture.exception

open class JdcrGestureException(open val code: Int, open val error: String) : Exception(error)

class JdcrUnrecognizedException(override val code: Int, override val error: String) :
    JdcrGestureException(code, error)

class JdcrNoneHandException(override val code: Int, override val error: String) :
    JdcrGestureException(code, error)