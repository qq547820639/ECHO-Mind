package com.yunjue.echo.mind.ui.me

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.AppContainer

/**
 * v3 §28 — Me → Presence：ECHO Presence 控制中心（Master Prompt PART 46/55/58）。
 * 动态壁纸（跳系统选择器，不是「导出壁纸」）/ 充电屏保 / 锁屏隐私说明 /
 * 动态程度 / 夜间模式 / 减少动画 / 应用内建议（L2 opt-in）。
 */
@Composable
fun PresenceSettingsSection(container: AppContainer, context: Context) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ECHO Presence", style = MaterialTheme.typography.titleMedium)
            Text(
                "让 ECHO 留在手机上。壁纸与屏保只消费同一个 ECHO 状态，只渲染视觉，不显示任何文字。",
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("动态壁纸")
                    Text("ECHO 持续存在于主屏与锁屏。不可见时停止渲染，不额外耗电。", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                                android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                android.content.ComponentName(
                                    context,
                                    com.yunjue.echo.mind.presence.EchoWallpaperService::class.java
                                )
                            )
                        )
                    }
                }) { Text("选择 ECHO 壁纸") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("充电屏保")
                    Text("充电放在桌面时，ECHO 成为环境的一部分（部分设备需在系统设置中手动启用）。", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) }
                }) { Text("系统屏保设置") }
            }
            Text("锁屏隐私：动态壁纸仅渲染视觉，不含任何文字——Public Safe 由构造保证。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("动态程度", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val motion = container.preferences.presenceMotionLevel
                listOf("QUIET" to "安静", "DEFAULT" to "默认", "LIVELY" to "明显").forEach { (value, label) ->
                    FilterChip(
                        selected = motion == value,
                        onClick = { container.preferences.presenceMotionLevel = value },
                        label = { Text(label) }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("夜间模式")
                    Text("夜间额外调暗减速（昼夜亮度本身已自动变化）。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = container.preferences.presenceNightMode,
                    onCheckedChange = { container.preferences.presenceNightMode = it }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("减少动画")
                    Text("无障碍支持：视觉保持静止。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = container.preferences.presenceReduceMotion,
                    onCheckedChange = { container.preferences.presenceReduceMotion = it }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("应用内建议")
                    Text("打开时基于高置信的节律状态给温和建议；低置信度时不会出现。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = container.preferences.presenceSuggestionsEnabled,
                    onCheckedChange = { container.preferences.presenceSuggestionsEnabled = it }
                )
            }
        }
    }
}
