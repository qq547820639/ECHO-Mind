package com.yunjue.echo.mind.security

/**
 * 敏感字段加密接口（Phase 3.2：Production / Test 显式分离）。
 *
 * 生产环境唯一实现： [AndroidKeystoreFieldCipher]（fail-closed）。
 * JVM/Robolectric 测试显式实现： [JvmTestFieldCipher]（test sourceSet）。
 *
 * 接口统一：
 * - [encrypt] / [decrypt]：AES-GCM 字段级加解密；
 * - [deriveDatabasePassphrase]：SQLCipher 数据库口令派生（32 字节）。
 */
interface FieldCipher {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String
    fun deriveDatabasePassphrase(): ByteArray
}
