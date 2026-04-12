package com.jdcr.jdcrcameragesture

import android.graphics.Bitmap
import com.jdcr.jdcrcameragesture.data.JdcrHandGestureResult

interface GestureRecognizerHelper {

    fun recognizeAsyncBitmap(bitmap: Bitmap)

    suspend fun recognizeBitmap(bitmap: Bitmap): Result<JdcrHandGestureResult>

}