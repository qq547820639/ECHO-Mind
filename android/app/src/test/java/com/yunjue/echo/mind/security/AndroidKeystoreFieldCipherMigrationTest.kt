package com.yunjue.echo.mind.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 17 §88-§92 — AndroidKeystoreFieldCipher 迁移行为（JCEKS 替身，纯 JVM）：
 *
 * fresh install（秘密自动 provision + 跨实例稳定）/ 旧库 legacy 派生稳定 /
 * 轮换（§91 rotate）/ 字段加密不受 DB 轮换影响（§90 密钥分离）/
 * 损坏存储 fail-closed 重建 / ancient 退役 / 迁移标记。
 */
class AndroidKeystoreFieldCipherMigrationTest {

    private fun cipher(keys: KeystoreKeyProvider, storage: DatabaseSecretStorage) =
        AndroidKeystoreFieldCipher(keys = keys, secretStorage = storage)

    @Test
    fun freshInstallProvisionsSecretAndPassphraseIsStableAcrossInstances() {
        val keys = JceksKeystoreKeyProvider()
        val storage = InMemorySecretStorage()
        val first = cipher(keys, storage).deriveDatabasePassphrase()
        // 秘密已包装落盘
        assertNotNull(storage.wrapped)
        // 新实例（模拟进程重启）：同一 Keystore + 同一存储 → 同一口令
        val second = cipher(keys, storage).deriveDatabasePassphrase()
        assertArrayEquals(first, second)
        assertEquals(32, first.size)
    }

    @Test
    fun rotationChangesPassphraseAndPersists() {
        val keys = JceksKeystoreKeyProvider()
        val storage = InMemorySecretStorage()
        val c = cipher(keys, storage)
        val before = c.deriveDatabasePassphrase()
        val rotated = c.rotateDatabaseSecret()
        assertFalse(before.contentEquals(rotated))
        // 新实例从存储读回旋转后的秘密 → 同口令
        assertArrayEquals(rotated, cipher(keys, storage).deriveDatabasePassphrase())
    }

    @Test
    fun legacyDerivationIsStableAndDifferentFromNewKdf() {
        val keys = JceksKeystoreKeyProvider()
        val storage = InMemorySecretStorage()
        val c = cipher(keys, storage)
        val legacyA = c.deriveLegacyDatabasePassphrase()
        val legacyB = c.deriveLegacyDatabasePassphrase()
        assertNotNull(legacyA)
        assertArrayEquals(legacyA, legacyB)
        // §89：新 HKDF 路径与 legacy 固定 IV 派生不同源（同一 field key 也绝不碰撞）
        assertFalse(legacyA!!.contentEquals(c.deriveDatabasePassphrase()))
    }

    @Test
    fun fieldEncryptionIsUnaffectedByDatabaseSecretRotation() {
        val keys = JceksKeystoreKeyProvider()
        val storage = InMemorySecretStorage()
        val c = cipher(keys, storage)
        val plain = "敏感字段值：测试"
        val encoded = c.encrypt(plain)
        assertEquals(plain, c.decrypt(encoded))
        // §90：DB 秘密轮换后，字段密文仍可解（field key 独立 alias）
        c.rotateDatabaseSecret()
        assertEquals(plain, c.decrypt(encoded))
        // 且新字段加密仍正常
        assertEquals(plain + "2", c.decrypt(c.encrypt(plain + "2")))
    }

    @Test
    fun corruptStoredSecretReprovisionsFailClosed() {
        val keys = JceksKeystoreKeyProvider()
        val storage = InMemorySecretStorage()
        val c = cipher(keys, storage)
        c.deriveDatabasePassphrase()
        val valid = storage.wrapped
        storage.wrapped = "v1|!!!!corrupted"
        // 损坏 → 重新生成秘密（不编造、不抛错，但口令与损坏前不同 = 旧 DB 需走 legacy 迁移链）
        val afterCorruption = c.deriveDatabasePassphrase()
        assertNotNull(afterCorruption)
        assertTrue(storage.wrapped != valid)
    }

    @Test
    fun ancientDerivationWorksAndRetirementDeletesKey() {
        val keys = JceksKeystoreKeyProvider()
        keys.provisionAncientKey()
        val storage = InMemorySecretStorage()
        val c = cipher(keys, storage)
        assertNotNull(c.deriveAncientDatabasePassphrase())
        c.retireAncientAlias()
        assertNull(keys.ancientKey())
        // §91 retire：ancient 派生随密钥删除而退役
        assertNull(c.deriveAncientDatabasePassphrase())
    }

    @Test
    fun migrationFlagRoundTripsThroughStorage() {
        val keys = JceksKeystoreKeyProvider()
        val storage = InMemorySecretStorage()
        val c = cipher(keys, storage)
        assertFalse(c.isDatabaseSecretMigrated())
        c.markDatabaseSecretMigrated()
        assertTrue(c.isDatabaseSecretMigrated())
    }

    @Test
    fun encryptRoundTripIsDeterministicUnderSameKey() {
        val keys = JceksKeystoreKeyProvider()
        val storage = InMemorySecretStorage()
        val c = cipher(keys, storage)
        val plain = "echo_mind_roundtrip"
        assertEquals(plain, c.decrypt(c.encrypt(plain)))
    }
}
