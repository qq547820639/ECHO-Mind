package com.yunjue.echo.mind.data

import androidx.test.core.app.ApplicationProvider
import com.yunjue.echo.mind.security.JvmTestFieldCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ERA 32 R25（无码续期）：/v1/auth/refresh 响应解析 + 刷新令牌加密存取。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthTokenRefresherTest {

    // ===== 响应解析 =====

    @Test
    fun parseResponseAcceptsBothTokens() {
        val parsed = AuthTokenRefresher.parseRefreshResponse(
            """{"user_id":"u1","access_token":"at-new","refresh_token":"rt-new"}"""
        )
        assertEquals("at-new" to "rt-new", parsed)
    }

    @Test
    fun parseResponseMissingAccessReturnsNull() {
        assertNull(AuthTokenRefresher.parseRefreshResponse("""{"user_id":"u1","refresh_token":"rt"}"""))
    }

    @Test
    fun parseResponseMissingRefreshReturnsNull() {
        assertNull(AuthTokenRefresher.parseRefreshResponse("""{"user_id":"u1","access_token":"at"}"""))
    }

    @Test
    fun parseResponseBlankFieldsReturnNull() {
        assertNull(AuthTokenRefresher.parseRefreshResponse("""{"access_token":"","refresh_token":""}"""))
        assertNull(AuthTokenRefresher.parseRefreshResponse("""{"access_token":"at","refresh_token":""}"""))
    }

    // ===== 刷新令牌加密存取（与 accessToken 同等级保护） =====

    @Test
    fun refreshTokenRoundTripsEncryptedAndClearable() {
        val prefs = AppPreferences(ApplicationProvider.getApplicationContext(), JvmTestFieldCipher())
        prefs.refreshToken = "refresh-secret-1"
        assertEquals("refresh-secret-1", prefs.refreshToken)
        prefs.refreshToken = "refresh-secret-2"
        assertEquals("refresh-secret-2", prefs.refreshToken)
        prefs.refreshToken = null
        assertNull(prefs.refreshToken)
    }
}
