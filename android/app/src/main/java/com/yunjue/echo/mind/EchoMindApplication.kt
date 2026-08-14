package com.yunjue.echo.mind

import android.app.Application
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.yunjue.echo.mind.data.EveningReminderWorker
import com.yunjue.echo.mind.data.MessageCheckWorker
import java.util.concurrent.TimeUnit

class EchoMindApplication : Application(), Configuration.Provider {
    val container by lazy { AppContainer(this) }

    /**
     * v0.6.2（Batch A，测试基建 + 启动优化）：实现 WorkManager Configuration.Provider，
     * 使 WorkManager 惰性初始化（首次 getInstance 时按需创建）——
     * - Robolectric 单测中无需手动 initialize，SyncWorker.enqueue 可正常工作；
     * - 生产环境语义不变（默认配置，无自定义 Executor/Network）。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        // 预热 DataStore（PassiveSensingPrefs）：
        // 直接实例化 PassiveSensingPrefs，触发 by preferencesDataStore 委托的 DataStore 引用创建
        // （DataStore 全局唯一，所有实例共享同一实例），实际磁盘 IO 在首次 collect 时异步进行，
        // 不阻塞主线程。
        // 注意：不通过 container 预热——container 会级联构造 AndroidKeystoreFieldCipher
        // （生产 fail-closed 的 Keystore 初始化），应延迟到真正需要加密/建库时再触发；
        // 同时避免 Robolectric 单测在 Application.onCreate 阶段就构造生产 Keystore 导致失败。
        PassiveSensingPrefs(this)

        // v0.7 订阅：分析消息周期检查（24h）——拉取式推送过渡的定时化。
        // Worker 内部对本地模式直接短路，不产生任何网络请求。
        val request = PeriodicWorkRequestBuilder<MessageCheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "echo-message-check",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )

        // v0.7.4 UX：每晚小结提醒（21:00 自续期一次性任务；开关见支持页）
        EveningReminderWorker.scheduleNext(this)
    }
}
