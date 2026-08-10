package com.yunjue.echo.mind

import android.app.Application
import androidx.work.Configuration

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
        // 触发 PassiveSensingPrefs 实例化及 by preferencesDataStore 委托的 DataStore 引用创建，
        // 实际磁盘 IO 在首次 collect 时异步进行，不阻塞主线程。
        container.passiveSensingPrefs
    }
}
