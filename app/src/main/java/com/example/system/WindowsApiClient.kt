package com.example.system

import android.os.Build
import org.json.JSONObject
import com.example.data.local.entity.ClientEntity
import java.net.HttpURLConnection
import java.net.URL

data class DirectoLoginResult(val token: String, val userName: String)
data class DirectoSnapshot(val customers: List<ClientEntity>, val pricePerKg: Double, val folioSeries: String, val folios: List<Int>)

object WindowsApiClient {
    fun loginAndRegisterDevice(baseUrl: String, email: String, password: String, deviceId: String): DirectoLoginResult {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val parsed = URL(cleanBase)
        require(parsed.protocol == "http" || parsed.protocol == "https") { "La dirección debe comenzar con http:// o https://" }
        require(parsed.host.isNotBlank() && parsed.userInfo == null && parsed.query == null) { "La dirección del servidor no es válida" }
        val login = post("$cleanBase/api/auth/login", JSONObject().put("email", email.trim()).put("password", password))
        val user = login.optJSONObject("user") ?: throw IllegalStateException("El servidor no devolvió el perfil de usuario")
        require(user.optString("role") == "vendedor") { "Esta variante acepta cuentas de vendedor. Pide al dueño que cree y autorice tu cuenta." }
        val token = login.optString("token")
        require(token.isNotBlank()) { "El servidor no devolvió una sesión válida" }
        val model = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").take(100).ifBlank { "Android" }
        post(
            "$cleanBase/api/devices/register",
            JSONObject().put("deviceId", deviceId).put("deviceName", model),
            token,
        )
        return DirectoLoginResult(token, user.optString("name", email))
    }

    fun downloadSnapshot(baseUrl: String, token: String, deviceId: String): DirectoSnapshot {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val pull = get("$cleanBase/api/sync/pull?deviceId=$deviceId", token)
        val remoteCustomers = mutableListOf<ClientEntity>()
        val customerArray = pull.optJSONArray("customers")
        if (customerArray != null) {
            for (index in 0 until customerArray.length()) {
                val customer = customerArray.optJSONObject(index) ?: continue
                val windowsId = customer.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = customer.optString("businessName").trim()
                if (name.isBlank()) continue
                remoteCustomers += ClientEntity(
                    windowsId = windowsId,
                    name = name,
                    phone = customer.optString("phone"),
                    address = customer.optString("address"),
                    specialPricePerKg = if (customer.isNull("specialPriceCentavosPerKg")) null else customer.optDouble("specialPriceCentavosPerKg") / 100.0,
                    creditTermDays = if (customer.isNull("creditTermDays")) null else customer.optInt("creditTermDays"),
                )
            }
        }
        val price = pull.optJSONObject("dailyPrice")?.optDouble("priceCentavosPerKg", 0.0)?.div(100.0) ?: 0.0
        val series = pull.optString("folioSeries")
        require(series.isNotBlank()) { "Windows no devolvió una serie de folios para esta cuenta" }
        val reservation = post(
            "$cleanBase/api/sync/folios/reserve",
            JSONObject().put("deviceId", deviceId).put("count", 100),
            token,
        )
        val folios = reservation.optJSONArray("folios")
        val numbers = if (folios == null) emptyList() else (0 until folios.length()).map { folios.optInt(it) }.filter { it > 0 }
        require(numbers.isNotEmpty()) { "Windows no reservó folios para este teléfono" }
        return DirectoSnapshot(remoteCustomers, price, series, numbers)
    }

    fun pushAndroidBatch(baseUrl: String, token: String, deviceId: String, payload: JSONObject): JSONObject =
        post("${baseUrl.trim().trimEnd('/')}/api/sync/push", payload.put("deviceId", deviceId), token)

    private fun post(url: String, body: JSONObject, token: String? = null): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(response) }.getOrElse { JSONObject() }
            if (status !in 200..299) throw IllegalStateException(json.optString("error").ifBlank { "Windows respondió con error HTTP $status" })
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String, token: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(response) }.getOrElse { JSONObject() }
            if (status !in 200..299) throw IllegalStateException(json.optString("error").ifBlank { "Windows respondió con error HTTP $status" })
            return json
        } finally {
            connection.disconnect()
        }
    }
}
