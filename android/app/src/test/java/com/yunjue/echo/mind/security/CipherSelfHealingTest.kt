package com.yunjue.echo.mind.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ERA 32 R16 — Keystore 自愈决策回归（真机「打开即闪退」修复的核心纯逻辑，JVM）。
 *
 * 设备实测故障：主 alias AndroidKeyStore 键不可用 → fail-closed 裸崩。
 * 三态：全新安装换 alias 自愈 / 后续启动直接用持久化 fallback / 已有秘密 fail-closed。
 */
class CipherSelfHealingTest {

    private fun cipher(keys: KeystoreKeyProvider, storage: DatabaseSecretStorage) =
        AndroidKeystoreFieldCipher(keys = keys, secretStorage = storage)

    @Test
    fun freshInstallWithPoisonedPrimaryFallsBackAndPersistsSuffix() {
        val storage = InMemorySecretStorage()
        var persisted: String? = null
        val built = resolveCipher(
            persistedSuffix = null,
            build = { suffix ->
                if (suffix.isEmpty()) throw IllegalStateException("poisoned primary alias")
                cipher(JceksKeystoreKeyProvider(), storage)
            },
            hasWrappedSecret = { storage.wrapped != null },
            newSuffix = { "-rtest" },
            persistSuffix = { persisted = it },
        )
        assertNotNull("全新安装应自愈成功", built)
        assertEquals("-rtest", persisted)
        assertEquals("x", built.decrypt(built.encrypt("x")))
        // 自愈后受保护秘密已写入（fallback 钥包装）——deriveDatabasePassphrase 触发 provision
        built.deriveDatabasePassphrase()
        assertNotNull(storage.wrapped)
    }

    @Test
    fun subsequentLaunchUsesPersistedFallbackWithoutTryingPrimary() {
        val storage = InMemorySecretStorage()
        var primaryTried = false
        val built = resolveCipher(
            persistedSuffix = "-rtest",
            build = { suffix ->
                if (suffix.isEmpty()) {
                    primaryTried = true
                    throw IllegalStateException("poisoned")
                }
                cipher(JceksKeystoreKeyProvider(), storage)
            },
            hasWrappedSecret = { storage.wrapped != null },
            newSuffix = { "-rother" },
            persistSuffix = { },
        )
        assertFalse("已有 fallback 时不得再尝试主 alias", primaryTried)
        assertNotNull(built)
    }

    @Test
    fun existingWrappedSecretStaysFailClosed() {
        val storage = InMemorySecretStorage()
        // 先正常建立数据（受保护秘密写入）
        cipher(JceksKeystoreKeyProvider(), storage).deriveDatabasePassphrase()
        assertNotNull(storage.wrapped)
        try {
            resolveCipher(
                persistedSuffix = null,
                build = { throw IllegalStateException("all poisoned") },
                hasWrappedSecret = { storage.wrapped != null },
                newSuffix = { "-rnever" },
                persistSuffix = { fail("有数据时不得换 alias") },
            )
            fail("已有受保护秘密时必须 fail-closed")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("poisoned"))
        }
    }
}
