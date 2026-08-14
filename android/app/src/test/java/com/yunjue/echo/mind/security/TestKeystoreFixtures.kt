package com.yunjue.echo.mind.security

import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * ERA 17 §92 — 测试用 JCEKS 内存密钥库 KeystoreKeyProvider。
 *
 * 进程内、不落盘、进程退出即失效；生产 [AndroidKeystoreKeyProvider] 的同构替身，
 * 使 [AndroidKeystoreFieldCipher] 的全部迁移逻辑可在纯 JVM 验证。
 */
class JceksKeystoreKeyProvider : KeystoreKeyProvider {

    private val keyStore: KeyStore = KeyStore.getInstance("JCEKS").apply { load(null, PASSWORD) }

    private fun getOrCreate(alias: String): SecretKey {
        val existing = keyStore.getKey(alias, PASSWORD) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also {
            keyStore.setKeyEntry(alias, it, PASSWORD, null)
        }
    }

    override fun fieldKey(): SecretKey = getOrCreate("field")

    override fun secretKey(): SecretKey = getOrCreate("secret")

    /** 与生产一致：只读（删除后即 null，不自动重建）。 */
    override fun ancientKey(): SecretKey? = keyStore.getKey("ancient", PASSWORD) as? SecretKey

    /** 测试准备：预置 ancient 密钥（模拟 v0.7 时代遗留）。 */
    fun provisionAncientKey() {
        getOrCreate("ancient")
    }

    override fun deleteAncientAlias() {
        keyStore.deleteEntry("ancient")
    }

    companion object {
        private val PASSWORD = "echo_mind_jvm_test_keystore".toCharArray()
    }
}

/** ERA 17 §92 — 内存版 DatabaseSecretStorage（测试替身）。 */
class InMemorySecretStorage : DatabaseSecretStorage {
    var wrapped: String? = null
    var migrated: Boolean = false

    override fun readWrappedSecret(): String? = wrapped

    override fun writeWrappedSecret(encoded: String) {
        wrapped = encoded
    }

    override fun isSecretMigrated(): Boolean = migrated

    override fun markSecretMigrated() {
        migrated = true
    }
}
