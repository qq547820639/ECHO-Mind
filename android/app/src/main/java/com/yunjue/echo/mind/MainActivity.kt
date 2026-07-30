package com.yunjue.echo.mind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.yunjue.echo.mind.ui.EchoMindApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as EchoMindApplication).container
        setContent {
            MaterialTheme {
                Surface { EchoMindApp(container) }
            }
        }
    }
}
