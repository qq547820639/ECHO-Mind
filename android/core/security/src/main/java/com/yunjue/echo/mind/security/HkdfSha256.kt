package com.yunjue.echo.mind.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ERA 17 §89 — RFC 5869 HKDF-SHA256（标准 KDF，不发明 Crypto）。
 *
 * 纯 Kotlin（javax.crypto.Mac），JVM 可测；[HkdfSha256Test] 以 RFC 5869
 * Test Case 1 官方向量验证实现正确性。
 */
object HkdfSha256 {

    /** HMAC-SHA256 输出长度（字节）。 */
    const val HASH_LENGTH = 32

    /** 每轮 expand 计数器上限（RFC 5869：length ≤ 255 × HashLen）。 */
    const val MAX_EXPAND_LENGTH = 255 * HASH_LENGTH

    /** RFC 5869 §2.2 Extract：PRK = HMAC-Hash(salt, IKM)。空 salt 按 RFC 语义补 HashLen 个零字节。 */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        // HMAC 对短密钥补零至块长：空密钥 ≡ 32 字节全零密钥（RFC 5869 Test Case 3 依赖此语义）
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt
        return hmac(effectiveSalt, ikm)
    }

    /** RFC 5869 §2.3 Expand：OKM = T(1) | T(2) | ...（截断至 length）。 */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..MAX_EXPAND_LENGTH) { "length must be in 1..$MAX_EXPAND_LENGTH" }
        val output = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val block = ByteArray(t.size + info.size + 1)
            System.arraycopy(t, 0, block, 0, t.size)
            System.arraycopy(info, 0, block, t.size, info.size)
            block[block.size - 1] = counter.toByte()
            t = hmac(prk, block)
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, output, offset, copyLen)
            offset += copyLen
            counter++
        }
        return output
    }

    /** RFC 5869 §2：derive = extract(salt, ikm) → expand(info, length)。 */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
