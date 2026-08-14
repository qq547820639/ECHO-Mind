package com.yunjue.echo.mind.ui.echo.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yunjue.echo.mind.R
import com.yunjue.echo.mind.data.SkillRepository
import com.yunjue.echo.mind.ui.SkillCardHost
import com.yunjue.echo.mind.ui.SkillSessionCoordinator
import com.yunjue.echo.mind.ui.coldStartHint
import com.yunjue.echo.mind.ui.rememberSkillList

/**
 * v3 §20 — 订阅能力卡片区（ECHO Scene「更多能力（订阅）」分区）。
 * 复用 [rememberSkillList] 三态（加载中/失败/冷启动空态/列表）；
 * 每个「开始」按钮走 [SkillCardHost] 真实执行行为（白名单外 fail closed）。
 */
@Composable
fun SkillListSection(skillRepository: SkillRepository, coordinator: SkillSessionCoordinator) {
    val (skillState, retry) = rememberSkillList(skillRepository)
    when {
        skillState.loadFailed -> Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.cold_start_load_failed))
            Spacer(Modifier.height(12.dp))
            Button(onClick = retry) { Text(stringResource(R.string.cold_start_retry)) }
        }
        skillState.skills == null -> Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
        }
        skillState.skills.isEmpty() -> {
            val stage = skillState.coldStartHint ?: "stage_0"
            val resId = coldStartHint(stage, skillState.observationDays)
            Text(
                if (stage == "stage_1_3") stringResource(resId, skillState.observationDays)
                else stringResource(resId)
            )
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            skillState.skills.forEach { skill ->
                SkillCardHost(skill, coordinator)
            }
        }
    }
}
