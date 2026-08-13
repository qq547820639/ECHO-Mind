package com.yunjue.echo.mind

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yunjue.echo.mind.security.AndroidKeystoreFieldCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机 Keystore 路径测试（v0.7.2）：
 * 覆盖曾导致「单测+lint+构建全绿但真机首启闪退」的 AndroidKeyStore 派生路径
 * （fixed-IV GCM 派生 SQLCipher 口令）——Robolectric 无法覆盖设备侧行为。
 */
@RunWith(AndroidJUnit4::class)
class EchoKeystoreInstrumentedTest {

    @Test
    fun fieldCipherRoundtripAndStablePassphrase() {
        val cipher = AndroidKeystoreFieldCipher()
        val plain = "敏感字段明文-keystore-roundtrip"
        val encoded = cipher.encrypt(plain)
        assertFalse("密文不得包含明文", encoded.contains("敏感字段明文"))
        assertEquals("解密往返一致", plain, cipher.decrypt(encoded))

        // 数据库口令派生：真机固定 IV 路径（闪退根因路径），跨调用稳定可复现
        val p1 = cipher.deriveDatabasePassphrase()
        val p2 = cipher.deriveDatabasePassphrase()
        assertArrayEquals("派生口令应跨调用稳定", p1, p2)
        assertEquals("SQLCipher 口令应为 32 字节", 32, p1.size)
        assertTrue(
            "rekey PRAGMA 应为 x'hex' 形状",
            AndroidKeystoreFieldCipher.rekeyPragma(p1).startsWith("PRAGMA rekey = \"x'")
        )
    }
}
