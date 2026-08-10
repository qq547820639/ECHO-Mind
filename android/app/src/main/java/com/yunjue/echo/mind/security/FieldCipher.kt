package com.yunjue.echo.mind.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感字段加密器（AES-GCM，密钥存 Android Keystore，不可导出）。
 *
 * v0.6.2（Batch A，测试基建修复）：增加 JVM-only 降级分支——
 * Robolectric/JVM 单测环境没有 AndroidKeyStore provider（KeyStore.getInstance 抛
 * "AndroidKeyStore not found"），此前所有依赖 FieldCipher 的 Robolectric 测试无法运行。
 *
 * 本降级分支**仅在 AndroidKeyStore 不可用**时激活：使用进程内内存 KeyStore（JCEKS）
 * + 随机 AES-256 密钥，密钥不落盘、进程退出即失效。因此：
 * - 生产环境（真机 Android）恒走 AndroidKeyStore 分支，安全语义完全不变；
 * - 测试环境获得确定性可用的 encrypt / decrypt / deriveDatabasePassphrase。
 */
class FieldCipher {
    private val alias = "echo_mind_sensitive_fields_v1"

    private val keyStore: KeyStore
    private val useAndroidKeyStore: Boolean

    /** JVM 降级分支的内存 KeyStore 口令（仅内存使用，不落盘）。 */
    private val jceksPassword = "echo_mind_jvm_test_keystore".toCharArray()

    init {
        val androidKeyStore = runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        }.getOrNull()
        if (androidKeyStore != null) {
            keyStore = androidKeyStore
            useAndroidKeyStore = true
        } else {
            // JVM 单测降级：内存 JCEKS + 随机密钥，绝不持久化（生产恒走 AndroidKeyStore 分支）
            keyStore = KeyStore.getInstance("JCEKS").apply { load(null, jceksPassword) }
            useAndroidKeyStore = false
        }
    }

    /** 缓存派生的数据库口令，避免重复 Keystore 运算。 */
    @Volatile
    private var cachedDbPassphrase: ByteArray? = null

    private fun key(): SecretKey {
        val existing = keyStore.getKey(alias, if (useAndroidKeyStore) null else jceksPassword) as? SecretKey
        if (existing != null) return existing
        return if (useAndroidKeyStore) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        } else {
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also {
                // 内存 KeyStore 需显式 setKeyEntry 才能在后续 getKey 中取回
                keyStore.setKeyEntry(alias, it, jceksPassword, null)
            }
        }
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * 派生 SQLCipher 数据库口令（32 字节）。
     *
     * 方法：用 Android Keystore 中的 AES-GCM 密钥加密固定盐值，对密文取 SHA-256 输出 32 字节。
     *
     * 使用固定 IV（全零 12 字节）确保派生结果跨进程重启稳定可复现：
     * - 盐值本身是公开常量，固定 IV 不泄露任何秘密
     * - 输出口令的机密性完全依赖 Keystore 密钥不可导出
     * - 这实质上将 AES-GCM 作为基于密钥的 PRF / KDF 使用
     *
     * 首次派生后缓存在内存中，后续直接返回同一口令。
     */
    fun deriveDatabasePassphrase(): ByteArray {
        cachedDbPassphrase?.let { return it.copyOf() }
        val salt = "echo_mind_db_passphrase_salt_v1".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // 固定 IV（盐值公开，IV 复用不引入安全风险）
        val fixedIv = ByteArray(12)
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, fixedIv))
        val ciphertextWithTag = cipher.doFinal(salt)
        val passphrase = MessageDigest.getInstance("SHA-256").digest(ciphertextWithTag)
        cachedDbPassphrase = passphrase
        return passphrase.copyOf()
    }
}
