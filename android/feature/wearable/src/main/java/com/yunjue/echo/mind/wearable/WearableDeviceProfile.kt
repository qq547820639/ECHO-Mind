package com.yunjue.echo.mind.wearable

/**
 * 设备画像：屏幕/能力事实。
 *
 * Band 10 官方规格：1.72" AMOLED，**212 × 520**（mi.com 官方 specs 页）。
 * 狭长屏：禁止把 Android UI 缩小复制；Vela 端按窄带纵向布局设计。
 */
data class WearableDeviceProfile(
    val deviceId: String,
    val model: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val capabilities: List<WearableCapability>,
) {
    companion object {
        const val BAND10_MODEL = "Xiaomi Band 10"
        const val BAND10_SCREEN_WIDTH = 212
        const val BAND10_SCREEN_HEIGHT = 520

        /** Band 10 官方能力画像（Matrix 为源）。deviceId 由运行时在连接时填充。 */
        fun band10(deviceId: String): WearableDeviceProfile = WearableDeviceProfile(
            deviceId = deviceId,
            model = BAND10_MODEL,
            screenWidth = BAND10_SCREEN_WIDTH,
            screenHeight = BAND10_SCREEN_HEIGHT,
            capabilities = WearableCapability.BAND10_CAPABILITIES,
        )
    }
}
