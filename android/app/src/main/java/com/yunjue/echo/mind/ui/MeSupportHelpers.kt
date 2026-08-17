package com.yunjue.echo.mind.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.yunjue.echo.mind.data.AppPreferences
import com.yunjue.echo.mind.data.ConsentRepository
import com.yunjue.echo.mind.ServiceRevocationCoordinator

/**
 * Me 子领域共享的领域辅助函数（v3 §32：SupportScreen 拆分后保留在 ui 包，
 * 供 ui/me 各子领域与单测锚点（ConsentLifecycleTest / TrendDataSourceTest）复用）。
 */

/** 使用情况访问系统设置页（PACKAGE_USAGE_STATS 授权入口，Phase 6.1 权限恢复）。 */
internal fun usageAccessSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

/** 通知使用权系统设置页（NotificationListenerService 授权入口，Phase 6.1 权限恢复）。 */
internal fun notificationListenerSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

/**
 * 关闭被动感知总开关的**原子本地流程**（网络不可用不阻塞）。
 *
 * v0.6.1（P0-3）：委托 [ServiceRevocationCoordinator.disablePassiveSensingOnly]，
 * 保证 Me 页/数据权利/Onboarding 走同一条领域逻辑（不再各自实现一半）。
 * 保留本函数作为单测锚点（ConsentLifecycleTest 依赖）。
 */
internal suspend fun performPassiveSensingStop(
    context: Context,
    preferences: AppPreferences,
    consentRepository: ConsentRepository
) {
    ServiceRevocationCoordinator.disablePassiveSensingOnly(context, preferences, consentRepository)
}
