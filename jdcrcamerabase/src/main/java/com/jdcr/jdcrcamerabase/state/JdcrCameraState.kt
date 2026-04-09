package com.jdcr.jdcrcamerabase.state

sealed class JdcrCameraOperation {
    data object Open : JdcrCameraOperation()
    data object Switch : JdcrCameraOperation()
    data object Close : JdcrCameraOperation()
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

sealed class JdcrCameraStateError {
    data class StreamConfigError(val message: String = "流配置错误") : JdcrCameraStateError()
    data class CameraInUse(val message: String = "相机已被占用") : JdcrCameraStateError()
    data class MaxCamerasInUse(val message: String = "已达到最大相机使用量") :
        JdcrCameraStateError()

    data class OtherRecoverableError(val message: String = "其他可恢复错误") :
        JdcrCameraStateError()

    data class CameraDisabled(val message: String = "相机被禁用") : JdcrCameraStateError()
    data class CameraFatalError(val message: String = "相机致命错误") : JdcrCameraStateError()
    data class DoNotDisturbModeEnabled(val message: String = "请勿打扰模式已启用") :
        JdcrCameraStateError()

    data class Unknown(val message: String = "未知错误") :
        JdcrCameraStateError()
}