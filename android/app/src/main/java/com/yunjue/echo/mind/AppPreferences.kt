package com.yunjue.echo.mind

import android.content.Context
import com.yunjue.echo.mind.security.FieldCipher

class AppPreferences(context: Context, private val cipher: FieldCipher) {
    private val prefs = context.getSharedPreferences("echo_mind_app_state", Context.MODE_PRIVATE)

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean("onboarding_completed", false)
        set(value) = prefs.edit().putBoolean("onboarding_completed", value).apply()

    var institutionCode: String
        get() = prefs.getString("institution_code", "") ?: ""
        set(value) = prefs.edit().putString("institution_code", value).apply()

    var userId: String
        get() = prefs.getString("user_id", "u_demo") ?: "u_demo"
        set(value) = prefs.edit().putString("user_id", value).apply()

    var accessToken: String?
        get() = prefs.getString("access_token_ciphertext", null)?.let { runCatching { cipher.decrypt(it) }.getOrNull() }
        set(value) = prefs.edit().apply {
            if (value.isNullOrBlank()) remove("access_token_ciphertext")
            else putString("access_token_ciphertext", cipher.encrypt(value))
        }.apply()

    fun clearServiceState() {
        prefs.edit().clear().apply()
    }
}
