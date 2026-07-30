package com.yunjue.echo.mind.data

import com.yunjue.echo.mind.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ApiClient(private val tokenProvider: () -> String?) {
    fun post(path: String, jsonBody: String): Int {
        val connection = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Request-ID", "mobile_${UUID.randomUUID()}")
            tokenProvider()?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.doOutput = true
            connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
