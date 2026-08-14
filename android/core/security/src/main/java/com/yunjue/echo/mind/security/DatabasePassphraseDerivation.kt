package com.yunjue.echo.mind.security

import java.security.SecureRandom
import java.util.Base64

/**
 * ERA 17 §89/§90 — SQLCipher 口令派生与 DB 秘密（标准 KDF + 域分离）。
 *
 * - 秘密 = 256-bit SecureRandom（每个安装唯一）；
 * - 口令 = HKDF-SHA256(ikm=secret, salt=公开域分离盐, info=域分离上下文)，
 *   32 字节 —— **不再使用** fixed IV + AES-GCM + SHA-256 的非标准派生；
 * - 字段加密与数据库口令派生使用**不同 alias/context**（§90 key separation）。
 */

/** RFC 5869 HKDF + 域分离：SQLCipher 口令派生（纯函数，确定性）。 */
object DatabasePassphraseDerivation {

    /** DB 秘密长度（256-bit）。 */
    const val SECRET_LENGTH = 32

    /** 口令长度（SQLCipher 256-bit key）。 */
    const val PASSPHRASE_LENGTH = 32

    /** 公开域分离盐（非机密；IKM 为每安装独立的 256-bit 随机秘密）。 */
    val SALT: ByteArray = "echo-mind:sqlcipher-kdf:v1:public-salt".toByteArray(Charsets.UTF_8)

    /** 域分离上下文（字段加密与 DB KDF 绝不共用；别名亦分离，§90）。 */
    val INFO: ByteArray = "echo-mind:sqlcipher-passphrase:v1".toByteArray(Charsets.UTF_8)

    /** 秘密 → 32 字节 SQLCipher 口令（确定性；同秘密同口令，跨进程稳定）。 */
    fun derive(secret: ByteArray): ByteArray {
        require(secret.size == SECRET_LENGTH) { "db secret must be $SECRET_LENGTH bytes" }
        return HkdfSha256.derive(
            ikm = secret,
            salt = SALT,
            info = INFO,
            length = PASSPHRASE_LENGTH,
        )
    }

    /** 生成新的每安装 DB 秘密（SecureRandom，256-bit）。 */
    fun generateSecret(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(SECRET_LENGTH).also { random.nextBytes(it) }
}

/**
 * 受保护 DB 秘密的存储格式（Keystore AES-GCM 包装，随机 IV —— 与字段密文同构）。
 *
 * `v1|base64(iv || ciphertext+tag)`；解析失败一律 null（fail-closed，重新生成秘密）。
 */
object DatabaseSecretFormat {

    const val VERSION = "v1"

    fun encode(iv: ByteArray, ciphertext: ByteArray): String =
        VERSION + "|" + Base64.getEncoder().encodeToString(iv + ciphertext)

    /** 解析失败（版本不符 / 长度非法 / 非 base64）→ null。 */
    fun decode(encoded: String?): Pair<ByteArray, ByteArray>? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            val separator = encoded.indexOf('|')
            if (separator <= 0 || encoded.substring(0, separator) != VERSION) return null
            val payload = Base64.getDecoder().decode(encoded.substring(separator + 1))
            if (payload.size <= GCM_IV_LENGTH + GCM_TAG_LENGTH) return null
            val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
            iv to ciphertext
        }.getOrNull()
    }

    const val GCM_IV_LENGTH = 12
    const val GCM_TAG_LENGTH = 16
}
