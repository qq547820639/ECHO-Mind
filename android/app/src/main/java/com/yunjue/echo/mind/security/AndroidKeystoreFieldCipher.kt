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
 *   **fail closed**（初始化即抛 [IllegalStateException]），绝不静默生成进程内随机密钥。
 * - **测试语义**：JVM/Robolectric 环境显式使用 [JvmTestFieldCipher]，**不做运行期自动降级**。
 *
 * 数据库派生口令（v0.7.2 双 alias 兼容）：
 * - 主 alias = v2（setRandomizedEncryptionRequired(false)，固定 IV 派生可用）；
 * - [deriveLegacyDatabasePassphrase] 尝试用 v1 alias 派生口令：**仅当** v1 密钥存在且
 *   允许固定 IV 派生时成功（v0.7 预修复版在真机首启即崩、从未建成加密库，故现实设备
 *   上基本不存在 v1 库；此回退为防御性兜底）。AppContainer 在 v2 打开失败时
 *   以 v1 口令解锁并用 PRAGMA rekey 迁移到 v2。
 * - TODO(KDF)：固定 IV GCM + SHA-256 属非标准 KDF。建议改为 Keystore 密钥作
 *   HKDF/HMAC-SHA256 的 IKM 派生 SQLCipher 口令，并为「字段加密」「口令派生」
 *   分设独立 alias（key rotation 时互不影响）。
 */
class AndroidKeystoreFieldCipher : FieldCipher {
    // v2：修复真机启动闪退——v1 密钥默认 randomizedEncryptionRequired=true，
    // 而 deriveDatabasePassphrase 用固定 IV 加密，会在真机抛 InvalidAlgorithmParameterException。
    // 升 alias 强制重建密钥，避免已崩溃设备上残留参数错误的 v1 密钥。
    private val alias = "echo_mind_sensitive_fields_v2"
    private val legacyAlias = "echo_mind_sensitive_fields_v1"

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

    private fun key(): SecretKey = key(alias)

    private fun key(aliasName: String): SecretKey {
        val existing = keyStore.getKey(aliasName, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(aliasName, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
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
     * 派生 SQLCipher 数据库口令（32 字节，v2 alias）。
     *
     * 方法：用 Android Keystore 中的 AES-GCM 密钥加密固定盐值，对密文取 SHA-256 输出 32 字节。
     * 使用固定 IV（全零 12 字节）确保派生结果跨进程重启稳定可复现。
     * 首次派生后缓存在内存中，后续直接返回同一口令。
     */
    override fun deriveDatabasePassphrase(): ByteArray {
        cachedDbPassphrase?.let { return it.copyOf() }
        val passphrase = derivePassphraseFor(alias)
        cachedDbPassphrase = passphrase
        return passphrase.copyOf()
    }

    /**
     * v1→v2 兼容回退：用 v1 alias 派生旧口令；v1 密钥不存在或固定 IV 派生
     * 不可用（预修复版残留的 randomizedEncryptionRequired=true 密钥）→ 返回 null。
     */
    fun deriveLegacyDatabasePassphrase(): ByteArray? =
        runCatching { derivePassphraseFor(legacyAlias) }.getOrNull()

    private fun derivePassphraseFor(aliasName: String): ByteArray {
        val salt = "echo_mind_db_passphrase_salt_v1".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val fixedIv = ByteArray(12)
        cipher.init(Cipher.ENCRYPT_MODE, key(aliasName), GCMParameterSpec(128, fixedIv))
        val ciphertextWithTag = cipher.doFinal(salt)
        return MessageDigest.getInstance("SHA-256").digest(ciphertextWithTag)
    }

    companion object {
        /** SQLCipher PRAGMA rekey 语句：口令以 x'hex' blob 字面量传入（byte[] 口令安全）。 */
        fun rekeyPragma(passphrase: ByteArray): String {
            val hex = passphrase.joinToString("") { "%02x".format(it) }
            return "PRAGMA rekey = \"x'$hex'\""
        }
    }
}
