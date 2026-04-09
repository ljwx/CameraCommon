package com.jdcr.jdcrcameragesture

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * 自定义手势，完全基于 2D（忽略 z 轴）。
 * z 轴是 MediaPipe 估算的深度，手掌旋转时噪声大导致角度跳变；
 * 2D 角度随旋转平缓退化，对阈值判定更稳定。
 * 拳头额外用 MCP-TIP 2D 距离兜底手背朝屏幕时 z 方向弯曲的情况。
 */
object JdcrGestureCustomRecognizer {

    //扩展
    const val Ok = "ok"
    const val SixSixSix = "sixSixSix"
    const val FingerHeart = "fingerHeart" //比心
    const val PointLeft = "pointLeft"
    const val PointRight = "pointRight"
    const val PointDown = "pointDown"

    fun custom(h: List<NormalizedLandmark>): Result<String> {
        if (h.size < 21) return Result.failure(Exception("关节数不够21个"))

        val palmSize = distance2D(h[0], h[9])
        if (palmSize < 1e-6f) return Result.success(JdcrGestureRecognizerHelper.UNKONWN)

        // 纯 2D 关节角度：MCP→PIP→TIP，伸直≈180°，弯曲<120°
        val isThumbExt = jointAngle(h[2], h[3], h[4]) > 150f
        val indexAngle = jointAngle(h[5], h[6], h[8])
        val middleAngle = jointAngle(h[9], h[10], h[12])
        val ringAngle = jointAngle(h[13], h[14], h[16])
        val pinkyAngle = jointAngle(h[17], h[18], h[20])

        val isIndexExt = indexAngle > 150f
        val isMiddleExt = middleAngle > 150f
        val isRingExt = ringAngle > 150f
        val isPinkyExt = pinkyAngle > 150f

        val middleCurled = middleAngle < 140f
        val isIndexSemiExt = indexAngle > 120f

        // 捏合：拇指尖靠近食指 TIP/DIP（不含 PIP，向下指时拇指自然靠近 PIP 会误判）
        val thumbIndexTipDist = distance2D(h[4], h[8])
        val thumbIndexDipDist = distance2D(h[4], h[7])

        val isPinch = thumbIndexTipDist < palmSize * 0.45f
                || thumbIndexDipDist < palmSize * 0.45f

        // 拇指在食指上半段附近（阈值大于 isPinch，形成缓冲带：比心边界失败时也不会滑到指向）
        val thumbNearIndexTip = thumbIndexTipDist < palmSize * 0.55f
                || thumbIndexDipDist < palmSize * 0.55f

        // --- 手势识别（越严格的越靠前） ---

        // 比心 🫰：捏合 + 食指半伸 + 中指弯曲 + 无名小指非伸直
        if (isPinch && isIndexSemiExt && middleCurled && !isRingExt && !isPinkyExt) {
            return Result.success(FingerHeart)
        }

        // OK 👌：拇指食指捏合 + 中无名小指伸出
        if (isPinch && isMiddleExt && isRingExt && isPinkyExt) {
            return Result.success(Ok)
        }

        // 666 🤙：大拇指小指伸出，中间三指弯曲
        if (isThumbExt && !isIndexExt && !isMiddleExt && !isRingExt && isPinkyExt) {
            return Result.success(SixSixSix)
        }

        // 拳头 ✊：2D 角度弯曲 OR 指尖与 MCP 在 2D 上重叠（手背朝屏幕 z 方向弯曲）
        val anglesCurled = indexAngle < 130f && middleAngle < 130f
                && ringAngle < 130f && pinkyAngle < 130f
        val tipsOverlapMcp = distance2D(h[5], h[8]) < palmSize * 0.35f
                && distance2D(h[9], h[12]) < palmSize * 0.35f
                && distance2D(h[13], h[16]) < palmSize * 0.35f
                && distance2D(h[17], h[20]) < palmSize * 0.35f
        if (anglesCurled || tipsOverlapMcp) {
            return Result.success("fist")
        }

        // 单指指向 ☝️👈👉👇👆（拇指靠近食指尖时排除，防止比心误判为指向）
        if (isIndexExt && !isMiddleExt && !isRingExt && !isPinkyExt && !thumbNearIndexTip) {
            val dx = h[8].x() - h[0].x()
            val dy = h[8].y() - h[0].y()

            if (abs(dx) > abs(dy) * 1.2f) {
                if (dx < 0) return Result.success(PointLeft)
                if (dx > 0) return Result.success(PointRight)
            } else if (abs(dy) > abs(dx) * 1.2f) {
                if (dy > 0) return Result.success(PointDown)
                if (dy < 0) return Result.success("pointUp")
            }
        }

        return Result.success(JdcrGestureRecognizerHelper.UNKONWN)
    }

    private fun distance2D(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        return sqrt(dx * dx + dy * dy)
    }

    /** 纯 2D 关节角度（度），忽略 z 轴，随手掌旋转平缓退化而非跳变 */
    private fun jointAngle(
        a: NormalizedLandmark,
        mid: NormalizedLandmark,
        b: NormalizedLandmark
    ): Float {
        val vax = a.x() - mid.x()
        val vay = a.y() - mid.y()
        val vbx = b.x() - mid.x()
        val vby = b.y() - mid.y()
        val dot = vax * vbx + vay * vby
        val magA = sqrt(vax * vax + vay * vay)
        val magB = sqrt(vbx * vbx + vby * vby)
        if (magA < 1e-6f || magB < 1e-6f) return 180f
        val cosAngle = (dot / (magA * magB)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosAngle).toDouble()).toFloat()
    }
}
