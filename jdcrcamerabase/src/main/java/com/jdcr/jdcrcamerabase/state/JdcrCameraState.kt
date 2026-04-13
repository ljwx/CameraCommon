package com.jdcr.jdcrcamerabase.state

sealed class JdcrCameraOperation {
    object Open : JdcrCameraOperation()
    object Switch : JdcrCameraOperation()
    object Close : JdcrCameraOperation()
}

sealed class JdcrCameraState(val desc: String) {
    object IDLE : JdcrCameraState("闲置")
    object Ready : JdcrCameraState("准备就绪")
    object Opening : JdcrCameraState("启动中")
    object Opened : JdcrCameraState("已启动")
    object Closing : JdcrCameraState("关闭中")
    object Closed : JdcrCameraState("已关闭")
    data class Error(val error: JdcrCameraStateError) : JdcrCameraState("报错")

    override fun toString(): String {
        return desc
    }

}

sealed class JdcrCameraStateError(open val message: String) {
    data class StreamConfigError(override val message: String = "流配置错误") :
        JdcrCameraStateError(message)

    data class CameraInUse(override val message: String = "相机已被占用") :
        JdcrCameraStateError(message)

    data class MaxCamerasInUse(override val message: String = "已达到最大相机使用量") :
        JdcrCameraStateError(message)

    data class OtherRecoverableError(override val message: String = "其他可恢复错误") :
        JdcrCameraStateError(message)

    data class CameraDisabled(override val message: String = "相机被禁用") :
        JdcrCameraStateError(message)

    data class CameraFatalError(override val message: String = "相机致命错误") :
        JdcrCameraStateError(message)

    data class DoNotDisturbModeEnabled(override val message: String = "请勿打扰模式已启用") :
        JdcrCameraStateError(message)

    data class Unknown(override val message: String = "未知错误") :
        JdcrCameraStateError(message)
}