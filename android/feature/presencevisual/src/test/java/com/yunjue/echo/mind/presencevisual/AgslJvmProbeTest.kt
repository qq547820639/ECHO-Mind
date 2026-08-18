package com.yunjue.echo.mind.presencevisual

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM backend-truth probe：Robolectric NATIVE graphics 是否能执行真实 RuntimeShader（AGSL）。
 *
 * 结论写入 stdout（QA 证据）；不做 pass/fail 断言——两种结果都是合法事实：
 * - true  → JVM 离屏可渲染真实 AGSL/ADVANCED 帧；
 * - false → AGSL raster 证据必须来自真机（BLOCKED_EXTERNAL_DEVICE）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgslJvmProbeTest {

    @Test
    fun probeRuntimeShaderAvailability() {
        val agsl = AgslEchoBackend.isAvailable()
        val advanced = AgslEchoBackend.isAdvancedAvailable()
        println("AGSL_PROBE runtimeShader=$agsl advanced=$advanced sdk=${android.os.Build.VERSION.SDK_INT}")
    }
}
