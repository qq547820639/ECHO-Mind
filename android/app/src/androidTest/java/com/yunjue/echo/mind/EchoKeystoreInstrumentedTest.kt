package com.yunjue.echo.mind

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yunjue.echo.mind.security.AndroidKeystoreFieldCipher
import com.yunjue.echo.mind.security.AndroidKeystoreKeyProvider
import com.yunjue.echo.mind.security.PreferencesDatabaseSecretStorage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机 Keystore 路径测试（ERA 17 §88-§92 更新）：
 *
 * 覆盖曾导致「单测+lint+构建全绿但真机首启闪退」的 AndroidKeyStore 路径——
 * Robolectric 无法覆盖设备侧行为，本类由 CI 模拟器执行。
 *
 * ERA 17 增补：新 HKDF 口令稳定 / legacy 派生可用（旧库迁移链前提）/
 * 秘密轮换跨实例持久（§91 rotate 真机语义）。
 */
@RunWith(AndroidJUnit4::class)
class EchoKeystoreInstrumentedTest {

    private fun cipher(): AndroidKeystoreFieldCipher {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("echo_mind_keystore_test", Context.MODE_PRIVATE)
        return AndroidKeystoreFieldCipher(
            keys = AndroidKeystoreKeyProvider(),
            secretStorage = PreferencesDatabaseSecretStorage(prefs),
        )
    }

    @Test
    fun fieldCipherRoundtripAndStablePassphrase() {
        val cipher = cipher()
        val plain = "敏感字段明文-keystore-roundtrip"
        val encoded = cipher.encrypt(plain)
        assertFalse("密文不得包含明文", encoded.contains("敏感字段明文"))
        assertEquals("解密往返一致", plain, cipher.decrypt(encoded))

        // §89：HKDF 口令跨调用稳定可复现
        val p1 = cipher.deriveDatabasePassphrase()
        val p2 = cipher.deriveDatabasePassphrase()
        assertArrayEquals("派生口令应跨调用稳定", p1, p2)
        assertEquals("SQLCipher 口令应为 32 字节", 32, p1.size)
        assertTrue(
            "rekey PRAGMA 应为 x'hex' 形状",
            AndroidKeystoreFieldCipher.rekeyPragma(p1).startsWith("PRAGMA rekey = \"x'")
        )
    }

    @Test
    fun legacyDerivationAvailableForOldDatabaseMigration() {
        // §91：旧库迁移链依赖 legacy 固定 IV 派生（真机 field key randomized=false 路径）
        val legacy = cipher().deriveLegacyDatabasePassphrase()
        assertNotNull("真机应可派生 legacy 口令", legacy)
        assertEquals(32, legacy!!.size)
    }

    @Test
    fun secretRotationPersistsAcrossInstances() {
        val c = cipher()
        val before = c.deriveDatabasePassphrase()
        val rotated = c.rotateDatabaseSecret()
        assertFalse("轮换后口令必须变化", before.contentEquals(rotated))
        // 新实例读同一 prefs（模拟进程重启）→ 同一轮换后口令
        assertArrayEquals(rotated, cipher().deriveDatabasePassphrase())
    }
}
