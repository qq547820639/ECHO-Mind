package com.yunjue.echo.mind.security

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 生产环境字段加密器 + SQLCipher 口令派生（AES-GCM，密钥存 Android Keystore，不可导出）——
 * **fail-closed**。
 *
 * Phase 3.2（Privacy Fail-Closed）：
 * - **Production 语义**：仅使用 AndroidKeyStore。Keystore 不可用（设备/系统异常）时
 *   **fail closed**（初始化即抛 [IllegalStateException]），绝不静默生成进程内随机密钥。
 * - **测试语义**：JVM 测试显式使用 JCEKS 内存密钥库 fake（[KeystoreKeyProvider]），
 *   **不做运行期自动降级**。
 *
 * ERA 17 §88-§91 正式迁移（原 TODO(KDF) 落地）：
 * - **§89 标准 KDF**：数据库口令 = HKDF-SHA256(每安装 256-bit 随机秘密)（[DatabasePassphraseDerivation]），
 *   废弃 fixed IV + AES-GCM + SHA-256 的非标准派生；
 * - **§90 密钥分离**：字段加密用 field alias，DB 秘密包装用独立 db_secret alias/context；
 * - **§91 旧库迁移**：[deriveLegacyDatabasePassphrase] 保留 v0.8/0.9 生产时代的
 *   固定 IV 派生（field key），[deriveAncientDatabasePassphrase] 保留 v0.7 预修复时代
 *   v1 alias 兜底；迁移编排见 [DatabaseOpenOrchestrator]。
 */
class AndroidKeystoreFieldCipher(
    private val keys: KeystoreKeyProvider,
    private val secretStorage: DatabaseSecretStorage,
) : FieldCipher {

    /** 缓存派生的数据库口令，避免重复 Keystore 运算。 */
    @Volatile
    private var cachedDbPassphrase: ByteArray? = null

    /** 缓存已解包的 DB 秘密（仅在内存）。 */
    @Volatile
    private var cachedSecret: ByteArray? = null

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.fieldKey())
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(payload)
    }

    override fun decrypt(encoded: String): String {
        val payload = java.util.Base64.getDecoder().decode(encoded)
        val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keys.fieldKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * §89 — 派生 SQLCipher 数据库口令（32 字节）。
     *
     * 秘密 = 每安装随机 256-bit，经 Keystore AES-GCM（随机 IV）包装后持久化；
     * 口令 = HKDF-SHA256(secret)。跨进程重启稳定可复现（同一存储 + 同一 Keystore 密钥）。
     * 首次调用自动 provision 秘密（fresh install / 旧版无秘密）。
     */
    override fun deriveDatabasePassphrase(): ByteArray {
        cachedDbPassphrase?.let { return it.copyOf() }
        val passphrase = DatabasePassphraseDerivation.derive(ensureSecret())
        cachedDbPassphrase = passphrase
        return passphrase.copyOf()
    }

    /**
     * §91 — legacy 口令（v0.8/0.9 生产时代）：field key + 固定 IV + SHA-256。
     * 仅用于打开旧库后 rekey 迁移；field key 不可用 → null。
     */
    fun deriveLegacyDatabasePassphrase(): ByteArray? =
        runCatching { deriveFixedIvPassphrase(keys.fieldKey()) }.getOrNull()

    /** v0.7 预修复时代兜底（v1 alias；现实设备基本不存在；迁移完成后删除该密钥）。 */
    fun deriveAncientDatabasePassphrase(): ByteArray? =
        keys.ancientKey()?.let { key ->
            runCatching { deriveFixedIvPassphrase(key) }.getOrNull()
        }

    /** §91 — 生成并持久化新的受保护 DB 秘密，返回其 HKDF 口令（旧秘密即刻作废）。 */
    fun rotateDatabaseSecret(): ByteArray {
        val secret = DatabasePassphraseDerivation.generateSecret()
        wrapAndStore(secret)
        cachedSecret = secret
        val passphrase = DatabasePassphraseDerivation.derive(secret)
        cachedDbPassphrase = passphrase
        return passphrase.copyOf()
    }

    /** §91 — 是否已完成迁移（true → legacy 派生退役，见 [DatabaseOpenOrchestrator]）。 */
    fun isDatabaseSecretMigrated(): Boolean = secretStorage.isSecretMigrated()

    /** §91 — 标记迁移完成（rekey + verify 成功后才可调用）。 */
    fun markDatabaseSecretMigrated() {
        secretStorage.markSecretMigrated()
    }

    /** §91 retire — 删除古代 v1 alias（幂等）。 */
    fun retireAncientAlias() {
        keys.deleteAncientAlias()
    }

    /** 读取/生成受保护秘密：存储缺失或解析失败 → 重新生成（fail-closed 不编造）。 */
    private fun ensureSecret(): ByteArray {
        cachedSecret?.let { return it.copyOf() }
        val existing = readAndUnwrapSecret()
        if (existing != null) {
            cachedSecret = existing
            return existing.copyOf()
        }
        val secret = DatabasePassphraseDerivation.generateSecret()
        wrapAndStore(secret)
        cachedSecret = secret
        return secret.copyOf()
    }

    private fun wrapAndStore(secret: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.secretKey())
        val ciphertext = cipher.doFinal(secret)
        secretStorage.writeWrappedSecret(DatabaseSecretFormat.encode(cipher.iv, ciphertext))
    }

    private fun readAndUnwrapSecret(): ByteArray? {
        val encoded = secretStorage.readWrappedSecret() ?: return null
        val (iv, ciphertext) = DatabaseSecretFormat.decode(encoded) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keys.secretKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        }.getOrNull()?.takeIf { it.size == DatabasePassphraseDerivation.SECRET_LENGTH }
    }

    /**
     * legacy 非标准派生（保留只为打开旧库，§88 审计结论：正式迁移后仅此一处存在）：
     * 固定 IV(全零 12B) AES-GCM 加密固定盐 → SHA-256(密文+tag) → 32 字节。
     */
    private fun deriveFixedIvPassphrase(key: SecretKey): ByteArray {
        val salt = "echo_mind_db_passphrase_salt_v1".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val fixedIv = ByteArray(GCM_IV_LENGTH)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, fixedIv))
        val ciphertextWithTag = cipher.doFinal(salt)
        return MessageDigest.getInstance("SHA-256").digest(ciphertextWithTag)
    }

    companion object {
        const val GCM_IV_LENGTH = 12

        /** SQLCipher PRAGMA rekey 语句：口令以 x'hex' blob 字面量传入（byte[] 口令安全）。 */
        fun rekeyPragma(passphrase: ByteArray): String {
            val hex = passphrase.joinToString("") { "%02x".format(it) }
            return "PRAGMA rekey = \"x'$hex'\""
        }
    }
}
