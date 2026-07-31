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

class FieldCipher {
    private val alias = "echo_mind_sensitive_fields_v1"
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

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
                .build()
        )
        return generator.generateKey()
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
