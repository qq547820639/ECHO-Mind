package com.yunjue.echo.mind.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.R

/** 全国心理援助热线（危机拨打入口；号码保持不变，仅消除魔法值）。 */
private const val CRISIS_HOTLINE_12356 = "12356"

/** 危机支持未确认送达时的默认提示（Onboarding 与 shell 紧急 FAB/入口共用）。 */
internal const val SAFETY_DELIVERY_UNCONFIRMED = "尚未确认送达，请优先使用电话入口。"

/**
 * 全屏安全屏（§AT：紧急 FAB / Me「紧急支持」入口一键直达）。
 * 拨打按钮文案与语义描述统一来自 crisis 字符串资源（冻结：110/120/12356 不变）。
 */
@Composable
fun SafetyScreen(deliveryState: String, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val call110Desc = stringResource(R.string.crisis_call_110_desc)
    val call120Desc = stringResource(R.string.crisis_call_120_desc)
    val call12356Desc = stringResource(R.string.crisis_call_12356_desc)
    Page("现在优先确保安全") {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.crisis_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text("我很重视你现在的安全。普通对话已停止。", style = MaterialTheme.typography.titleMedium)
                Text("请尽量移动到更安全、有人在场的地方，并远离可能造成伤害的物品。")
                Text(deliveryState)
            }
        }
        Button(
            onClick = { context.startActivity(dialIntent("110")) },
            modifier = Modifier.fillMaxWidth().testTag("safety_dial_110").semantics {
                contentDescription = call110Desc
            },
        ) { Text(stringResource(R.string.crisis_call_110)) }
        Button(
            onClick = { context.startActivity(dialIntent("120")) },
            modifier = Modifier.fillMaxWidth().testTag("safety_dial_120").semantics {
                contentDescription = call120Desc
            },
        ) { Text(stringResource(R.string.crisis_call_120)) }
        OutlinedButton(
            onClick = { context.startActivity(dialIntent(CRISIS_HOTLINE_12356)) },
            modifier = Modifier.fillMaxWidth().testTag("safety_dial_12356").semantics {
                contentDescription = call12356Desc
            },
        ) { Text(stringResource(R.string.crisis_call_12356)) }
        Text("迫近危险时优先联系紧急服务和身边可信任的人。12356 不应被理解为所有地区 7×24 的唯一兜底。")
        onBack?.let { TextButton(onClick = it) { Text("返回") } }
    }
}
