package com.yunjue.echo.mind

import android.content.Context

/**
 * ERA 13.2 §42 — 同步触发组合根缝隙。
 *
 * Ground Truth（sensing/localportrait/model）禁止 import data 实现；
 * 服务组件触发同步经此根级函数（root = composition root，允许接 data 实现）。
 */
fun enqueueSync(context: Context) {
    SyncWorker.enqueue(context)
}
