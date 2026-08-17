package com.yunjue.echo.mind.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * V3 §AG — 行动降级入口：「想做点什么？」不再是 Why/Ask 的同级 peer，
 * 而是 Ask 之下的 quiet 内联展开区（槽位内容由 [com.yunjue.echo.mind.ui.echo.actions.EchoActionLayer]
 * 提供：quiet TextButton + 展开；最后一个 scene sheet 就此移除）。
 * actions 为空（空槽位）→ 本区不渲染任何内容。
 */
@Composable
internal fun EchoActionEntry(
    modifier: Modifier = Modifier,
    actionLayer: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .testTag("echo_scene_action_entry"),
    ) {
        actionLayer()
    }
}
