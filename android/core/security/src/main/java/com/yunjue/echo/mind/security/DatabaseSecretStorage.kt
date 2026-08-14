package com.yunjue.echo.mind.security

/**
 * ERA 17 §91 — 受保护 DB 秘密与迁移标记的持久化端口。
 *
 * 实现（:app）：SharedPreferences 存储（内容为 Keystore AES-GCM 包装密文，
 * 不含任何明文密钥材料）。
 */
interface DatabaseSecretStorage {
    /** 读取包装后的 DB 秘密（v1|base64(iv||ct)）；无 → null。 */
    fun readWrappedSecret(): String?

    /** 覆盖写入包装后的 DB 秘密。 */
    fun writeWrappedSecret(encoded: String)

    /** 是否已完成 legacy → 新 KDF 迁移（true 后 legacy 派生退役，§91 retire）。 */
    fun isSecretMigrated(): Boolean

    /** 标记迁移完成（rekey + verify 成功后才可调用）。 */
    fun markSecretMigrated()
}
