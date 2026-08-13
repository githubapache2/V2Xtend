package org.opentrafficmap.receiver

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Shared Ktor client for overlay APIs. Gzip Content-Encoding is enabled so
 * DWD's compressed feed works without platform-specific GZIPInputStream.
 */
internal object SharedHttp {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val client: HttpClient = HttpClient {
        expectSuccess = false
        install(ContentEncoding) {
            gzip()
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 25_000
            socketTimeoutMillis = 25_000
        }
    }

    suspend fun getText(url: String, connectMs: Long = 8_000, readMs: Long = 8_000): String {
        val response = client.get(url) {
            timeout {
                connectTimeoutMillis = connectMs
                requestTimeoutMillis = readMs
                socketTimeoutMillis = readMs
            }
        }
        return response.bodyAsText()
    }

    suspend fun postForm(url: String, formBody: String, connectMs: Long = 10_000, readMs: Long = 20_000): String {
        val response = client.post(url) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formBody)
            timeout {
                connectTimeoutMillis = connectMs
                requestTimeoutMillis = readMs
                socketTimeoutMillis = readMs
            }
            header("Accept", "application/json")
        }
        return response.bodyAsText()
    }
}

internal fun JsonElement.asObject(): JsonObject? = this as? JsonObject
internal fun JsonElement.asArray(): JsonArray? = this as? JsonArray

internal fun JsonObject.optObject(key: String): JsonObject? =
    this[key]?.asObject()

internal fun JsonObject.optArray(key: String): JsonArray? =
    this[key]?.asArray()

internal fun JsonObject.optString(key: String, default: String = ""): String {
    val p = this[key]?.jsonPrimitive ?: return default
    return p.contentOrNull ?: default
}

internal fun JsonObject.optInt(key: String, default: Int = 0): Int {
    val p = this[key]?.jsonPrimitive ?: return default
    return p.intOrNull ?: p.contentOrNull?.toIntOrNull() ?: default
}

internal fun JsonObject.optLong(key: String, default: Long = 0L): Long {
    val p = this[key]?.jsonPrimitive ?: return default
    return p.longOrNull ?: p.contentOrNull?.toLongOrNull() ?: default
}

/** Accepts JSON number or string (Autobahn parking/charging quirk). */
internal fun JsonObject.optDouble(key: String): Double? {
    val el = this[key] ?: return null
    val p = el as? JsonPrimitive ?: return null
    return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
}

internal fun JsonArray.optDoubleAt(index: Int): Double? {
    val p = getOrNull(index) as? JsonPrimitive ?: return null
    return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
}
