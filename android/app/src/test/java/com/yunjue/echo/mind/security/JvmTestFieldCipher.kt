package com.yunjue.echo.mind.security

import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * JVM / Robolectric 测试专用 FieldCipher（Phase 3.2：显式 Test 实现）。
 *
 * - 与生产 [AndroidKeystoreFieldCipher] 实现同一 [FieldCipher] 接口；
 * - 密钥保存在**进程内内存 JCEKS**，随机生成，不落盘、进程退出即失效；
 * - **仅测试 sourceSet 可见**（本文件位于 app/src/test），生产代码绝不引用；
 * - 生产实现已 fail-closed（AndroidKeyStore 不可用即抛异常），不再有"静默 JVM 降级"分支。
 */
class JvmTestFieldCipher : FieldCipher {
    private val alias = "echo_mind_jvm_test_key"
    private val jceksPassword = "echo_mind_jvm_test_keystore".toCharArray()

    private val keyStore: KeyStore = KeyStore.getInstance("JCEKS").apply { load(null, jceksPassword) }

    @Volatile
    private var cachedDbPassphrase: ByteArray? = null

    private fun key(): SecretKey {
        val existing = keyStore.getKey(alias, jceksPassword) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also {
            keyStore.setKeyEntry(alias, it, jceksPassword, null)
        }
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    override fun deriveDatabasePassphrase(): ByteArray {
        cachedDbPassphrase?.let { return it.copyOf() }
        val salt = "echo_mind_db_passphrase_salt_v1".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val fixedIv = ByteArray(12)
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, fixedIv))
        val ciphertextWithTag = cipher.doFinal(salt)
        val passphrase = MessageDigest.getInstance("SHA-256").digest(ciphertextWithTag)
        cachedDbPassphrase = passphrase
        return passphrase.copyOf()
    }
}
