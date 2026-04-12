package com.jdcr.jdcrcameragesture.data

import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer

object JdcrGestureName {

    const val MODEL_OpenPalm = "Open_Palm"
    const val MODEL_ClosedFist = "Closed_Fist"
    const val MODEL_PointingUp = "Pointing_Up"
    const val MODEL_Victory = "Victory"
    const val MODEL_ThumbUp = "Thumb_Up"
    const val MODEL_ThumbDown = "Thumb_Down"
    const val MODEL_ILoveYou = "ILoveYou"

    const val CUSTOM_Ok = "ok"
    const val CUSTOM_SixSixSix = "sixSixSix"
    const val CUSTOM_FingerHeart = "fingerHeart" //比心
    const val CUSTOM_PointLeft = "pointLeft"
    const val CUSTOM_PointRight = "pointRight"
    const val CUSTOM_PointDown = "pointDown"

    const val UNKNOWN = "unknown"

    val DEFAULTS =
        setOf(
            MODEL_OpenPalm,
            MODEL_ClosedFist,
            MODEL_Victory,
            MODEL_PointingUp,
            MODEL_ThumbUp,
            MODEL_ThumbDown,
            MODEL_ILoveYou
        )

    val CMT = setOf(
        MODEL_OpenPalm,
        MODEL_ClosedFist,
        MODEL_Victory,
        MODEL_ThumbUp,
        MODEL_PointingUp,
        CUSTOM_Ok,
        CUSTOM_SixSixSix,
        CUSTOM_FingerHeart,
        CUSTOM_PointLeft,
        CUSTOM_PointRight,
        CUSTOM_PointDown,
    )

}