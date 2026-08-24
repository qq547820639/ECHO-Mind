package com.yunjue.echo.mind.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * ERA 17 §90 — Keystore 密钥提供（key separation：字段加密与 DB KDF 分设 alias）。
 *
 * 生产实现 [AndroidKeystoreKeyProvider] fail-closed（AndroidKeyStore 不可用即抛）；
 * 测试实现用 JCEKS 内存密钥库（进程内、不落盘），使全部迁移逻辑可在 JVM 验证。
 */
interface KeystoreKeyProvider {
    /** 字段加密密钥（alias echo_mind_sensitive_fields_v2；randomized=false 兼容 legacy 固定 IV 派生）。 */
    fun fieldKey(): SecretKey

    /** DB 秘密包装密钥（alias echo_mind_db_secret_v1；randomized=true 标准随机 IV，与字段密钥分离）。 */
    fun secretKey(): SecretKey

    /** v0.7 预修复时代 v1 alias 密钥（无 → null；仅 ancient 兜底派生用）。 */
    fun ancientKey(): SecretKey?

    /** 退役古代 v1 alias（迁移完成后删除；幂等）。 */
    fun deleteAncientAlias()
}

/**
 * 生产 KeystoreKeyProvider（AndroidKeyStore，密钥不可导出）。
 *
 * - [fieldKey]：沿用 v2 alias（randomizedEncryptionRequired=false）——legacy 固定 IV
 *   派生（旧库口令）与字段加密继续可用；
 * - [secretKey]：全新独立 alias（randomizedEncryptionRequired=true 默认值，随机 IV
 *   标准 GCM）——§89/§90 正式迁移后的 DB 秘密包装；
 * - [deleteAncientAlias]：v0.7 预修复时代 v1 alias（现实设备从未建成库）迁移后删除。
 */
class AndroidKeystoreKeyProvider(
    /**
     * ERA 32 R16 自愈：主 alias 在设备上不可用（如 OEM 卸载残留旧签名 Keystore 条目）时，
     * 用带后缀的新 alias 重建密钥——alias 内嵌安装身份，不再被历史残留污染。
     * 主 alias = ""（既有安装不变）；fallback = "-r" + 随机 hex（持久化于 prefs）。
     */
    aliasSuffix: String = "",
) : KeystoreKeyProvider {

    private val fieldAlias = "echo_mind_sensitive_fields_v2$aliasSuffix"
    private val dbSecretAlias = "echo_mind_db_secret_v1$aliasSuffix"
    private val ancientAlias = "echo_mind_sensitive_fields_v1"

    private val keyStore: KeyStore = runCatching {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }.getOrElse {
        // Phase 3.2：生产环境 AndroidKeyStore 不可用 → fail closed，绝不静默降级。
        throw IllegalStateException(
            "AndroidKeyStore unavailable in production: ${it.javaClass.simpleName}: ${it.message}",
            it,
        )
    }

    override fun fieldKey(): SecretKey =
        key(fieldAlias, randomizedEncryptionRequired = false)

    override fun secretKey(): SecretKey =
        key(dbSecretAlias, randomizedEncryptionRequired = true)

    override fun ancientKey(): SecretKey? =
        keyStore.getKey(ancientAlias, null) as? SecretKey

    override fun deleteAncientAlias() {
        runCatching { keyStore.deleteEntry(ancientAlias) }
    }

    /** 单例级锁：防止并发调用时 keyStore.getKey() 与 generateKey() 之间的竞态。 */
    private val keyLock = Any()

    private fun key(aliasName: String, randomizedEncryptionRequired: Boolean): SecretKey {
        synchronized(keyLock) {
            val existing = keyStore.getKey(aliasName, null) as? SecretKey
            if (existing != null) return existing
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(aliasName, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // field key：允许调用方传入固定 IV（legacy 派生需要）；secret key：默认 true（随机 IV）
                .setRandomizedEncryptionRequired(randomizedEncryptionRequired)
                .build()
        )
            return generator.generateKey()
        }
    }
}
