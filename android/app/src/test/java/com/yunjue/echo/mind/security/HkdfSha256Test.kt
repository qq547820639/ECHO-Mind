package com.yunjue.echo.mind.security

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * ERA 17 §89 — RFC 5869 HKDF-SHA256 官方测试向量（Test Case 1/2/3）。
 *
 * 向量来自 RFC 5869 Appendix A：证明实现是标准 HKDF，不是自造 Crypto。
 */
class HkdfSha256Test {

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    @Test
    fun rfc5869TestCase1() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expectedPrk = hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
        val expectedOkm = hex(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        )
        assertArrayEquals(expectedPrk, HkdfSha256.extract(salt, ikm))
        assertArrayEquals(expectedOkm, HkdfSha256.derive(ikm, salt, info, 42))
    }

    @Test
    fun rfc5869TestCase2() {
        val ikm = hex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f" +
                "404142434445464748494a4b4c4d4e4f"
        )
        val salt = hex(
            "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f" +
                "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f" +
                "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
        )
        val info = hex(
            "b0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                "d0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
        )
        val expectedOkm = hex(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87"
        )
        assertArrayEquals(expectedOkm, HkdfSha256.derive(ikm, salt, info, 82))
    }

    @Test
    fun rfc5869TestCase3ZeroLength() {
        val ikm = hex("0b".repeat(22))
        val salt = ByteArray(0)
        val info = ByteArray(0)
        val expectedOkm = hex(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8"
        )
        assertArrayEquals(expectedOkm, HkdfSha256.derive(ikm, salt, info, 42))
    }

    @Test
    fun expandIsPrefixConsistent() {
        val prk = ByteArray(32) { it.toByte() }
        val info = "context".toByteArray()
        val full = HkdfSha256.expand(prk, info, 64)
        val first = HkdfSha256.expand(prk, info, 32)
        assertArrayEquals(full.copyOfRange(0, 32), first)
    }
}
