package com.yunjue.echo.mind.me

/**
 * ERA 13.1 §31/§33-§36 — Me 事件面（Screen → ViewModel 唯一交互入口）。
 */
sealed interface MeEvent {
    /** 人工支持：点「请求支持」→ 二次确认对话框。 */
    data object RequestSupportClicked : MeEvent

    /** 确认提交支持请求。 */
    data object SupportConfirmed : MeEvent

    data object SupportDismissed : MeEvent

    /** 消费根页面消息。 */
    data object ConsumeMessage : MeEvent
}

sealed interface DataAndSensingEvent {
    /** 被动感知总开关（pause/resume）。 */
    data class ToggleSensing(val enabled: Boolean) : DataAndSensingEvent

    /** 麦克风开关：开启需二次确认（opt-in），关闭直接生效。 */
    data class ToggleMic(val enabled: Boolean) : DataAndSensingEvent

    /** 麦克风确认对话框：同意并继续（UI 侧发起系统权限请求后回调 [MicPermissionResult]）。 */
    data object MicConfirmRequested : DataAndSensingEvent

    data object MicConfirmDismissed : DataAndSensingEvent

    /** 系统录音权限结果（permission truth → consent 证据闭环）。 */
    data class MicPermissionResult(val granted: Boolean) : DataAndSensingEvent

    /** 每晚小结提醒开关。 */
    data class SetEveningReminder(val enabled: Boolean) : DataAndSensingEvent

    /** 数据权利：导出（本地模式 → 返回 JSON 由 UI 分享；云端 → 创建导出请求）。 */
    data object RequestExport : DataAndSensingEvent

    /** 数据权利：删除（本地模式 → 二次确认；云端 → 创建删除请求）。 */
    data object RequestDelete : DataAndSensingEvent

    data object ConfirmLocalDelete : DataAndSensingEvent

    data object DismissLocalDelete : DataAndSensingEvent

    /** 撤回同意并停止服务（system repair）。 */
    data object RevokeConsent : DataAndSensingEvent

    /** 立即同步。 */
    data object SyncNow : DataAndSensingEvent

    data object ConsumeMessage : DataAndSensingEvent
}

sealed interface IntelligenceSettingsEvent {
    data class UpdateDraftBaseUrl(val value: String) : IntelligenceSettingsEvent
    data class UpdateDraftModel(val value: String) : IntelligenceSettingsEvent
    data class UpdateDraftApiKey(val value: String) : IntelligenceSettingsEvent
    data object ToggleChangeExpanded : IntelligenceSettingsEvent

    /** 测试连接（未展开设置时测已存配置；展开时测草稿）。 */
    data object TestConnection : IntelligenceSettingsEvent

    /** 验证并保存草稿配置（READY 才落盘）。 */
    data object SaveAndConnect : IntelligenceSettingsEvent

    data object Disconnect : IntelligenceSettingsEvent
}

sealed interface PresenceSettingsEvent {
    data class SetMotionLevel(val level: String) : PresenceSettingsEvent
    data class SetNightMode(val enabled: Boolean) : PresenceSettingsEvent
    data class SetReduceMotion(val enabled: Boolean) : PresenceSettingsEvent
    data class SetSuggestionsEnabled(val enabled: Boolean) : PresenceSettingsEvent
}

sealed interface MemoryManagementEvent {
    data class SetFilter(val type: com.yunjue.echo.mind.memory.MemoryType?) : MemoryManagementEvent
    data class Confirm(val id: String) : MemoryManagementEvent
    data class Edit(val id: String, val content: String) : MemoryManagementEvent
    data class Forget(val id: String) : MemoryManagementEvent

    /** ERA 15.5 §78/§79：用户解释优先（添加特殊时期，用户自述最高置信）。 */
    data class AddContextException(val kind: String, val note: String) : MemoryManagementEvent
}

/** ERA 33 — 可选订阅开通事件面（SubscriptionViewModel 唯一交互入口）。 */
sealed interface SubscriptionEvent {
    data class UpdateBindCode(val value: String) : SubscriptionEvent
    data object Bind : SubscriptionEvent
}
