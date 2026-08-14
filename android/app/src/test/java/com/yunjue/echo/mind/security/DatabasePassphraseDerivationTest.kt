package com.yunjue.echo.mind.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERA 17 §89/§90 — SQLCipher 口令派生与 DB 秘密格式：
 * 确定性 / 域分离 / 长度 / 格式 fail-closed。
 */
class DatabasePassphraseDerivationTest {

    @Test
    fun derivationIsDeterministic() {
        val secret = DatabasePassphraseDerivation.generateSecret()
        assertArrayEquals(
            DatabasePassphraseDerivation.derive(secret),
            DatabasePassphraseDerivation.derive(secret),
        )
    }

    @Test
    fun derivationOutputs32Bytes() {
        val passphrase = DatabasePassphraseDerivation.derive(DatabasePassphraseDerivation.generateSecret())
        assertTrue(passphrase.size == 32)
    }

    @Test
    fun differentSecretsProduceDifferentPassphrases() {
        val a = DatabasePassphraseDerivation.generateSecret()
        val b = DatabasePassphraseDerivation.generateSecret()
        assertFalse(DatabasePassphraseDerivation.derive(a).contentEquals(DatabasePassphraseDerivation.derive(b)))
    }

    @Test
    fun domainSeparationIsolation() {
        // §90：不同 context/info 派生互不相关（字段加密与 DB KDF 绝不共用）
        val secret = DatabasePassphraseDerivation.generateSecret()
        val db = DatabasePassphraseDerivation.derive(secret)
        val other = HkdfSha256.derive(secret, DatabasePassphraseDerivation.SALT, "other-context".toByteArray(), 32)
        assertFalse(db.contentEquals(other))
    }

    @Test
    fun secretLengthIsEnforced() {
        runCatching { DatabasePassphraseDerivation.derive(ByteArray(16)) }
            .onSuccess { throw AssertionError("16 字节秘密必须被拒绝") }
    }

    @Test
    fun secretFormatRoundTrips() {
        val iv = ByteArray(12) { (it * 3).toByte() }
        val ciphertext = ByteArray(48) { (it * 5).toByte() }
        val encoded = DatabaseSecretFormat.encode(iv, ciphertext)
        val decoded = DatabaseSecretFormat.decode(encoded)
        assertTrue(decoded != null)
        assertArrayEquals(iv, decoded!!.first)
        assertArrayEquals(ciphertext, decoded.second)
    }

    @Test
    fun secretFormatFailsClosed() {
        assertNull(DatabaseSecretFormat.decode(null))
        assertNull(DatabaseSecretFormat.decode(""))
        assertNull(DatabaseSecretFormat.decode("garbage"))
        assertNull(DatabaseSecretFormat.decode("v2|AAAA"))
        assertNull(DatabaseSecretFormat.decode("v1|!!!!not-base64"))
        // 长度不足（只有 iv + tag，无密文）
        val tooShort = DatabaseSecretFormat.encode(ByteArray(12), ByteArray(16))
        assertNull(DatabaseSecretFormat.decode(tooShort))
    }

    @Test
    fun generatedSecretsAreHighEntropy() {
        val a = DatabasePassphraseDerivation.generateSecret()
        val b = DatabasePassphraseDerivation.generateSecret()
        assertFalse(a.contentEquals(b))
        assertTrue(a.size == 32)
    }
}
