package com.yunjue.echo.mind.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
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
 * 数据库口令（v0.7.3 信封加密，根除真机闪退）：
 * - **主路径（信封）**：随机生成 32 字节 SQLCipher 口令，用 Keystore 密钥做
 *   **标准 GCM（随机 IV）** 加密后存入 SharedPreferences；每次启动解密还原。
 *   密钥仅使用默认参数（randomizedEncryptionRequired=true），兼容所有 Keymaster，
 *   不再依赖"固定 IV + setRandomizedEncryptionRequired(false)"这一非标准用法
 *   （部分机型生成该参数密钥会抛异常 → 首启闪退，9e23c32 之后仍有残留风险）。
 * - **旧库一次性解锁回退**：[deriveLegacyDatabasePassphrase] 用 v2 alias 的固定 IV
 *   派生旧口令（仅对已存在且支持固定 IV 的旧密钥有效），AppContainer 在信封口令
 *   打开失败时以旧口令解锁并 PRAGMA rekey 迁移到信封口令（数据不丢失）。
 */
class AndroidKeystoreFieldCipher(private val context: Context) : FieldCipher {
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

    private val envelopePrefs = context.getSharedPreferences("echo_mind_crypto", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 默认 randomizedEncryptionRequired=true：标准随机 IV，
                // 兼容所有 Keymaster（不再放宽密钥约束，根除部分机型 generateKey 异常）
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
     * SQLCipher 数据库口令（32 字节，信封加密主路径）：
     * 首次生成随机口令 → 用 Keystore 密钥 GCM 加密 → 存入 SharedPreferences；
     * 之后每次解密还原（跨重启稳定）。Keystore 密钥本身不可导出。
     */
    override fun deriveDatabasePassphrase(): ByteArray {
        cachedDbPassphrase?.let { return it.copyOf() }
        val stored = envelopePrefs.getString(ENVELOPE_KEY, null)
        val passphrase = if (stored != null) {
            runCatching { Base64.decode(decrypt(stored), Base64.NO_WRAP) }.getOrNull()
                ?: generateAndStoreEnvelope()
        } else {
            generateAndStoreEnvelope()
        }
        cachedDbPassphrase = passphrase
        return passphrase.copyOf()
    }

    private fun generateAndStoreEnvelope(): ByteArray {
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encrypted = encrypt(Base64.encodeToString(passphrase, Base64.NO_WRAP))
        envelopePrefs.edit().putString(ENVELOPE_KEY, encrypted).commit()
        return passphrase
    }

    /**
     * 旧库一次性解锁回退（v0.7.2 及更早版本用固定 IV 派生的口令）：
     * 仅对已存在且允许固定 IV 的旧密钥有效；新设备/新密钥上返回 null。
     */
    fun deriveLegacyDatabasePassphrase(): ByteArray? =
        runCatching {
            val salt = "echo_mind_db_passphrase_salt_v1".toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, ByteArray(12)))
            MessageDigest.getInstance("SHA-256").digest(cipher.doFinal(salt))
        }.getOrNull()

    companion object {
        private const val ENVELOPE_KEY = "db_passphrase_envelope"

        /** SQLCipher PRAGMA rekey 语句：口令以 x'hex' blob 字面量传入（byte[] 口令安全）。 */
        fun rekeyPragma(passphrase: ByteArray): String {
            val hex = passphrase.joinToString("") { "%02x".format(it) }
            return "PRAGMA rekey = \"x'$hex'\""
        }
    }
}
