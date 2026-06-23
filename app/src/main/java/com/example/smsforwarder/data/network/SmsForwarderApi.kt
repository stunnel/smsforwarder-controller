package com.example.smsforwarder.data.network

import com.example.smsforwarder.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP API client for SmsForwarder.
 * All requests are POST to http://127.0.0.1:{port}/{endpoint}.
 * Security transformations are applied by SecurityInterceptor.
 */
class SmsForwarderApi(
    private val baseUrl: String,
    private val securityMode: String = "none",
    private val signSecret: String = "",
    private val rsaPrivateKey: String = "",
    private val rsaPublicKey: String = ""
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(
                SecurityInterceptor(
                    securityMode = securityMode,
                    signSecret = signSecret,
                    rsaPrivateKey = rsaPrivateKey,
                    rsaPublicKey = rsaPublicKey
                )
            )
            .build()
    }

    // ==================== Config ====================

    suspend fun configQuery(): Result<ConfigQueryResponse> = safeCall {
        val resp = post("/config/query", JsonObject(emptyMap()))
        extractData(resp)
    }

    // ==================== SMS ====================

    suspend fun smsSend(request: SmsSendRequest): Result<Unit> = safeCall {
        val resp = post("/sms/send", json.encodeToJsonElement(request))
        checkSuccess(resp)
    }

    suspend fun smsQuery(request: SmsQueryRequest): Result<List<SmsRecord>> = safeCall {
        val resp = post("/sms/query", json.encodeToJsonElement(request))
        extractList(resp)
    }

    // ==================== Call ====================

    suspend fun callQuery(request: CallQueryRequest): Result<List<CallRecord>> = safeCall {
        val resp = post("/call/query", json.encodeToJsonElement(request))
        extractList(resp)
    }

    // ==================== Contact ====================

    suspend fun contactQuery(request: ContactQueryRequest): Result<List<ContactRecord>> = safeCall {
        val resp = post("/contact/query", json.encodeToJsonElement(request))
        extractList(resp)
    }

    suspend fun contactAdd(request: ContactAddRequest): Result<Unit> = safeCall {
        val resp = post("/contact/add", json.encodeToJsonElement(request))
        checkSuccess(resp)
    }

    // ==================== Battery ====================

    suspend fun batteryQuery(): Result<BatteryResponse> = safeCall {
        val resp = post("/battery/query", JsonObject(emptyMap()))
        extractData(resp)
    }

    // ==================== Location ====================

    suspend fun locationQuery(): Result<LocationResponse> = safeCall {
        val resp = post("/location/query", JsonObject(emptyMap()))
        extractData(resp)
    }

    // ==================== WOL ====================

    suspend fun wolSend(request: WolRequest): Result<Unit> = safeCall {
        val resp = post("/wol/send", json.encodeToJsonElement(request))
        checkSuccess(resp)
    }

    // ==================== Clone ====================

    suspend fun clonePull(versionCode: Int): Result<Map<String, String>> = safeCall {
        val request = ClonePullRequest(versionCode = versionCode)
        val resp = post("/clone/pull", json.encodeToJsonElement(request))
        if (resp.code != 200) throw ApiException(resp.msg)
        val dataElement = resp.data ?: return@safeCall emptyMap()
        json.decodeFromJsonElement<Map<String, String>>(dataElement)
    }

    // ==================== Helpers ====================

    private suspend fun <T> safeCall(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun checkSuccess(resp: BaseResponse) {
        if (resp.code != 200) throw ApiException(resp.msg)
    }

    private inline fun <reified T> extractData(resp: BaseResponse): T {
        if (resp.code != 200) throw ApiException(resp.msg)
        val data = resp.data ?: throw ApiException("empty data")
        return json.decodeFromJsonElement(data)
    }

    private inline fun <reified T> extractList(resp: BaseResponse): List<T> {
        if (resp.code != 200) throw ApiException(resp.msg)
        val data = resp.data ?: return emptyList()
        return json.decodeFromJsonElement(data)
    }

    /**
     * Perform a POST request. The SecurityInterceptor handles body transformation.
     * Must be called from a coroutine (uses withContext(Dispatchers.IO)).
     */
    private suspend fun post(
        path: String,
        body: kotlinx.serialization.json.JsonElement
    ): BaseResponse = withContext(Dispatchers.IO) {
        val jsonString = json.encodeToString(body)
        val requestBody = jsonString.toByteArray(Charsets.UTF_8)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(requestBody)
            .build()
        val response = client.newCall(request).execute()
        val responseBodyStr = response.body?.string()
            ?: throw ApiException("empty response body")
        if (!response.isSuccessful) {
            throw ApiException("HTTP ${response.code}: $responseBodyStr")
        }
        json.decodeFromString(responseBodyStr)
    }
}

class ApiException(message: String) : Exception(message)
