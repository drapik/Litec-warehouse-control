package com.example.hellofly

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class MoySkladApi {

    fun verifyCredentials(authHeader: String) {
        val response = executeGet("$BASE_URL/customerorder?limit=1", authHeader)
        if (response.code in 200..299) {
            return
        }
        throw mapToException(response)
    }

    fun fetchAllCustomerOrders(authHeader: String): List<CustomerOrder> {
        val result = mutableListOf<CustomerOrder>()
        var offset = 0

        while (true) {
            val response = executeGet("$BASE_URL/customerorder?limit=$PAGE_SIZE&offset=$offset", authHeader)
            if (response.code !in 200..299) {
                throw mapToException(response)
            }

            val payload = JSONObject(response.body)
            val rows = payload.optJSONArray("rows") ?: JSONArray()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                result.add(
                    CustomerOrder(
                        name = row.optString("name"),
                        moment = row.optString("moment"),
                        sum = row.optLong("sum", 0L),
                        agentName = row.optJSONObject("agent")?.optString("name")
                    )
                )
            }

            if (rows.length() < PAGE_SIZE) {
                break
            }
            offset += PAGE_SIZE
        }

        return result
    }

    private fun executeGet(url: String, authHeader: String): ApiResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", authHeader)

        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = if (stream != null) {
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            } else {
                ""
            }
            ApiResponse(code = code, body = body)
        } finally {
            connection.disconnect()
        }
    }

    private fun mapToException(response: ApiResponse): Exception {
        val message = parseErrorMessage(response.body)
        return if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED || response.code == HttpURLConnection.HTTP_FORBIDDEN) {
            AuthException(message.ifBlank { "Неверный логин или пароль МойСклад." })
        } else {
            val suffix = if (message.isBlank()) "Ошибка API МойСклад." else message
            ApiException("HTTP ${response.code}. $suffix")
        }
    }

    private fun parseErrorMessage(rawBody: String): String {
        if (rawBody.isBlank()) {
            return ""
        }

        return try {
            val json = JSONObject(rawBody)
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val firstError = errors.optJSONObject(0)
                val errorText = firstError?.optString("error").orEmpty()
                val details = firstError?.optString("moreInfo").orEmpty()
                listOf(errorText, details).filter { it.isNotBlank() }.joinToString(". ")
            } else {
                json.optString("error", json.optString("message", ""))
            }
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private const val BASE_URL = "https://api.moysklad.ru/api/remap/1.2/entity"
        private const val PAGE_SIZE = 100
        private const val TIMEOUT_MS = 15_000

        fun buildBasicAuthHeader(login: String, password: String): String {
            val combined = "$login:$password"
            val encoded = Base64.encodeToString(combined.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            return "Basic $encoded"
        }
    }
}

data class CustomerOrder(
    val name: String,
    val moment: String,
    val sum: Long,
    val agentName: String?
)

data class ApiResponse(
    val code: Int,
    val body: String
)

class AuthException(message: String) : Exception(message)

class ApiException(message: String) : Exception(message)
