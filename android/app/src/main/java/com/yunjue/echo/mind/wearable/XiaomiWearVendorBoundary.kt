package com.yunjue.echo.mind.wearable

/**
 * Xiaomi Wear vendor boundary —— SDK 接入的**唯一合法位置**。
 *
 * BLOCKED_EXTERNAL_XIAOMI_SDK：
 * - 缺失资源：小米穿戴第三方 App 能力开放 SDK 本体（官方文档 v1.4 已核实，SDK AAR 未获得）；
 * - 已完成：wearable domain / 协议 / Vela / Noop + Fake / 全部单元测试与静态测试；
 * - 确切的下一步人工动作：从官方开发者平台获取 SDK，在 vendor-enabled build
 *   （Gradle flavor 或可选依赖）中实现 [com.yunjue.echo.mind.wearable.WearablePlatformPort]：
 *     Wearable.getNodeApi/getAuthApi/getMessageApi/…（以 SDK 实际签名为准，禁止凭文档猜写）；
 * - 禁止的宣称：在 SDK 集成并真机验证前，不得宣称 "Android SDK 已集成验证"。
 *
 * 纪律：
 * - SDK AAR 不进入 git（proprietary）；文档说明获取路径；
 * - OSS 构建永远走 [NoopWearablePlatformAdapter] / [FakeWearablePlatformAdapter]，不因此失败；
 * - 生产签名材料不进仓库 → BLOCKED_EXTERNAL_PRODUCTION_SIGNING。
 */
object XiaomiWearVendorBoundary {
    const val BLOCKED_MARKER = "BLOCKED_EXTERNAL_XIAOMI_SDK"

    /** 当前构建使用的平台适配器（SDK 未集成 → Noop，fail-safe 不失败）。 */
    fun currentPlatformAdapter(): WearablePlatformPort = NoopWearablePlatformAdapter()

    /** 文档化的接入说明（Me 高级诊断里如实显示，不暴露 SDK 细节）。 */
    const val INTEGRATION_NOTE =
        "Xiaomi 穿戴 SDK 尚未集成（BLOCKED_EXTERNAL_XIAOMI_SDK）；" +
            "当前为 Noop 适配器。软件侧协议/隐私/连接测试已全部完成。"
}
