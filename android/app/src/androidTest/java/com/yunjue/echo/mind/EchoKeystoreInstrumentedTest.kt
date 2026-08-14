package com.yunjue.echo.mind

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yunjue.echo.mind.security.AndroidKeystoreFieldCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机 Keystore 路径测试（v0.7.3）：
 * 覆盖真机首启闪退的两代根因路径——v0.7.2 fixed-IV GCM 派生在部分 Keymaster 上
 * generateKey 异常（已由信封加密根除）；本测试验证信封主路径：
 * 标准 GCM（默认参数）密钥 + 随机 32 字节口令的信封加密跨重启稳定可复现。
 * Robolectric 无法覆盖设备侧行为，本测试由 CI 模拟器执行。
 */
@RunWith(AndroidJUnit4::class)
class EchoKeystoreInstrumentedTest {

    @Test
    fun fieldCipherRoundtripAndStableEnvelopePassphrase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cipher = AndroidKeystoreFieldCipher(context)
        val plain = "敏感字段明文-keystore-roundtrip"
        val encoded = cipher.encrypt(plain)
        assertFalse("密文不得包含明文", encoded.contains("敏感字段明文"))
        assertEquals("解密往返一致", plain, cipher.decrypt(encoded))

        // 数据库口令（信封主路径）：跨调用稳定可复现，32 字节
        val p1 = cipher.deriveDatabasePassphrase()
        val p2 = cipher.deriveDatabasePassphrase()
        assertArrayEquals("信封口令应跨调用稳定", p1, p2)
        assertEquals("SQLCipher 口令应为 32 字节", 32, p1.size)

        // 新实例（模拟进程重启）应还原同一口令
        val reopened = AndroidKeystoreFieldCipher(context)
        assertArrayEquals("重启后应还原同一口令", p1, reopened.deriveDatabasePassphrase())

        assertTrue(
            "rekey PRAGMA 应为 x'hex' 形状",
            AndroidKeystoreFieldCipher.rekeyPragma(p1).startsWith("PRAGMA rekey = \"x'")
        )
    }
}
