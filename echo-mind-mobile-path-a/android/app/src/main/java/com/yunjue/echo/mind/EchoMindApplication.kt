package com.yunjue.echo.mind

import android.app.Application

class EchoMindApplication : Application() {
    val container by lazy { AppContainer(this) }
}
