package com.jdcr.jdcrcameragesture.recognizer

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.jdcr.jdcrcameragesture.data.GestureName

interface CustomRecognizer {

    fun recognize(h: List<NormalizedLandmark>): Result<GestureName>

}