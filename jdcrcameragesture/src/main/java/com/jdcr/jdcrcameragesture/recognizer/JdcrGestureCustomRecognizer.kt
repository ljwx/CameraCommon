package com.jdcr.jdcrcameragesture.recognizer

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.jdcr.jdcrcameragesture.util.HandGestureLog
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
        HandGestureLog.r("进入库自定义手势识别")
        if (h.size < 21) return Result.failure(Exception("关节数不够21个"))

        val palmSize = distance2D(h[0], h[9])
        if (palmSize < 1e-6f) return Result.success(JdcrGestureRecognizer.UNKONWN)

        // 1. 基础角度计算（保持不变，确保其他手势稳定）
        val isThumbExt = jointAngle(h[2], h[3], h[4]) > 150f
        val indexAngle = jointAngle(h[5], h[6], h[8])
        val middleAngle = jointAngle(h[9], h[10], h[12])
        val ringAngle = jointAngle(h[13], h[14], h[16])
        val pinkyAngle = jointAngle(h[17], h[18], h[20])

        val isIndexExt = indexAngle > 150f
        val isMiddleExt = middleAngle > 150f
        val isRingExt = ringAngle > 150f
        val isPinkyExt = pinkyAngle > 150f

        // 2. 【关键】判断小指是否“伸出来”了
        // 判定：2D角度伸直 或者 Z轴正对镜头（针对竖着比6时，小指在2D平面缩成一个点的情况）
        val isPinkyActive = isPinkyExt || (h[17].z() - h[20].z() > 0.04f)

        // 判断666的横竖方向
        val dx6 = h[4].x() - h[20].x()
        val dy6 = h[4].y() - h[20].y()
        val isHorizontal6 = abs(dx6) > abs(dy6) * 1.1f

        // 3. 其他辅助判定（用于比心、指向等）
        val middleCurled = middleAngle < 140f
        val isIndexSemiExt = indexAngle > 120f
        val thumbIndexTipDist = distance2D(h[4], h[8])
        val thumbIndexDipDist = distance2D(h[4], h[7])
        val isPinch = thumbIndexTipDist < palmSize * 0.45f || thumbIndexDipDist < palmSize * 0.45f
        val thumbNearIndexTip = thumbIndexTipDist < palmSize * 0.55f || thumbIndexDipDist < palmSize * 0.55f

        // --- 手势识别开始（严格保持原有顺序） ---

        // 比心 🫰
        if (isPinch && isIndexSemiExt && middleCurled && !isRingExt && !isPinkyExt) {
            return Result.success(FingerHeart)
        }

        // OK 👌
        if (isPinch && isMiddleExt && isRingExt && isPinkyExt) {
            return Result.success(Ok)
        }

        // 666 和 点赞 的排他性判断 (核心逻辑修改)
        if (isThumbExt && !isIndexExt && !isMiddleExt && !isRingExt) {
            if (isPinkyActive) {
                // 情况一：只要小指是开的，它就绝对不可能是“点赞”
                if (isHorizontal6) {
                    return Result.success(SixSixSix) // 横向6，识别成功
                } else {
                    // 竖向6，这里直接返回 UNKONWN
                    // 因为我们在这个 block 里，已经排除了它是“点赞”的可能性
                    return Result.success(JdcrGestureRecognizer.UNKONWN)
                }
            } else {
                // 情况二：小指是完全闭合的，这里才是真正的“点赞”判定区
                // 如果你不想要点赞识别，就返回 UNKONWN；
                // 这样竖着的6在上面就会被拦截，永远进不来这里。
                return Result.success(JdcrGestureRecognizer.UNKONWN)
            }
        }

        // 拳头 ✊
        val anglesCurled = indexAngle < 130f && middleAngle < 130f && ringAngle < 130f && pinkyAngle < 130f
        val tipsOverlapMcp = distance2D(h[5], h[8]) < palmSize * 0.35f
                && distance2D(h[9], h[12]) < palmSize * 0.35f
                && distance2D(h[13], h[16]) < palmSize * 0.35f
                && distance2D(h[17], h[20]) < palmSize * 0.35f
        if (anglesCurled || tipsOverlapMcp) {
            return Result.success("fist")
        }

        // 指向 ☝️
        if (isIndexExt && !isMiddleExt && !isRingExt && !isPinkyExt && !thumbNearIndexTip) {
            val dx = h[8].x() - h[0].x()
            val dy = h[8].y() - h[0].y()
            if (abs(dx) > abs(dy) * 1.2f) {
                if (dx < 0) return Result.success(PointLeft)
                if (dx > 0) return Result.success(PointRight)
            } else if (abs(dy) > abs(dx) * 1.2f) {
                if (dy > 0) return Result.success(PointDown)
                if (dy < 0) return Result.success(JdcrGestureRecognizer.PointingUp)
            }
        }

        return Result.success(JdcrGestureRecognizer.UNKONWN)
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