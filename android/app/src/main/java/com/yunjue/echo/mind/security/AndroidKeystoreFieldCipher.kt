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
 * 生产环境字段加密器（AES-GCM，密钥存 Android Keystore，不可导出）—— **fail-closed**。
 *
 * Phase 3.2（Privacy Fail-Closed）：
 * - **Production 语义**：仅使用 AndroidKeyStore。Keystore 不可用（设备/系统异常）时
 *   **fail closed**（初始化即抛 [IllegalStateException]），绝不静默生成进程内随机密钥——
 *   防止敏感字段被用无法复现的密钥加密后数据永久不可读，也防止"加密密钥每次进程都不同"
 *   导致的不可解密。
 * - **测试语义**：JVM/Robolectric 环境显式使用 [JvmTestFieldCipher]
 *   （见 `app/src/test/.../security/JvmTestFieldCipher.kt`），**不做运行期自动降级**。
 *
 * 与 v0.6.2 旧行为的差异：
 * - 旧：AndroidKeyStore 不可用 → 自动降级内存 JCEKS + 随机密钥（静默，进程内有效）。
 * - 新：AndroidKeyStore 不可用 → 抛异常（生产 fail closed）；测试用显式 JVM 实现。
 *
 * 数据库派生口令兼容性：`deriveDatabasePassphrase` 算法与旧版完全一致
 * （Keystore 密钥 + 固定盐 + 固定 IV + SHA-256），现有用户数据库口令不变。
 */
class AndroidKeystoreFieldCipher : FieldCipher {
    // v2：修复真机启动闪退——v1 密钥默认 randomizedEncryptionRequired=true，
    // 而 deriveDatabasePassphrase 用固定 IV 加密，会在真机抛 InvalidAlgorithmParameterException。
    // 升 alias 强制重建密钥，避免已崩溃设备上残留参数错误的 v1 密钥。
    private val alias = "echo_mind_sensitive_fields_v2"

    private val keyStore: KeyStore = runCatching {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }.getOrElse {
        // Phase 3.2：生产环境 AndroidKeyStore 不可用 → fail closed，绝不静默降级。
        throw IllegalStateException(
            "AndroidKeyStore unavailable in production: ${it.javaClass.simpleName}: ${it.message}",
            it,
        )
    }

    /** 缓存派生的数据库口令，避免重复 Keystore 运算。 */
    @Volatile
    private var cachedDbPassphrase: ByteArray? = null

    private fun key(): SecretKey {
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 允许调用方传入固定 IV：deriveDatabasePassphrase 用固定 IV 加密固定盐
                // 派生稳定口令。默认 true 时传固定 IV 会抛 InvalidAlgorithmParameterException（真机闪退根因）。
                .setRandomizedEncryptionRequired(false)
                .build()
        )
        return generator.generateKey()
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

    /**
     * 派生 SQLCipher 数据库口令（32 字节）。
     *
     * 方法：用 Android Keystore 中的 AES-GCM 密钥加密固定盐值，对密文取 SHA-256 输出 32 字节。
     * 使用固定 IV（全零 12 字节）确保派生结果跨进程重启稳定可复现。
     * 首次派生后缓存在内存中，后续直接返回同一口令。
     */
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
