package com.yunjue.echo.mind

import android.app.Application

class EchoMindApplication : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // 预热 DataStore（PassiveSensingPrefs）：
        // 触发 PassiveSensingPrefs 实例化及 by preferencesDataStore 委托的 DataStore 引用创建，
        // 实际磁盘 IO 在首次 collect 时异步进行，不阻塞主线程。
        container.passiveSensingPrefs
    }
}
