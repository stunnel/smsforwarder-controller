package com.example.smsforwarder.data.network

import android.util.Base64
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OkHttp Interceptor that applies SmsForwarder security transformations.
 *
 * Security modes:
 * - "none": No transformation
 * - "sign": HMAC-SHA256 signature with timestamp (DingTalk robot sign algorithm)
 * - "rsa": RSA PKCS1v15 encryption/decryption
 * - "sm4": Not implemented (placeholder)
 */
class SecurityInterceptor(
    private val securityMode: String,
    private val signSecret: String,
    private val rsaPrivateKey: String,
    private val rsaPublicKey: String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        if (securityMode == "none") {
            return chain.proceed(originalRequest)
        }

        // Read the original request body
        val originalBody = originalRequest.body ?: return chain.proceed(originalRequest)
        val buffer = okio.Buffer()
        originalBody.writeTo(buffer)
        val bodyBytes = buffer.readByteArray()
        val bodyString = String(bodyBytes, Charsets.UTF_8)

        val timestamp = System.currentTimeMillis()

        return when (securityMode) {
            "sign" -> {
                // Build signed JSON and send as application/json
                val signedJson = buildSignedJson(bodyString, timestamp)
                val newBody = signedJson.toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val newRequest = originalRequest.newBuilder().post(newBody).build()
                chain.proceed(newRequest)
            }
            "rsa" -> {
                // Encrypt request body with RSA public key
                val encryptedBytes = rsaEncrypt(bodyString, timestamp)
                val newBody = encryptedBytes.toRequestBody("application/octet-stream".toMediaType())
                val newRequest = originalRequest.newBuilder().post(newBody).build()
                val response = chain.proceed(newRequest)

                // Decrypt response body with RSA private key
                val responseBodyBytes = response.body?.bytes() ?: return response
                val decryptedJson = rsaDecrypt(responseBodyBytes)
                val newResponseBody = decryptedJson.toResponseBody("application/json".toMediaType())
                response.newBuilder().body(newResponseBody).build()
            }
            else -> chain.proceed(originalRequest)
        }
    }

    /**
     * Build HMAC-SHA256 signed JSON.
     * Algorithm (DingTalk robot sign):
     *   stringToSign = timestamp + "\n" + secret
     *   sign = Base64(HMAC-SHA256(stringToSign, secret))
     *   urlEncode(sign)
     */
    private fun buildSignedJson(bodyJson: String, timestamp: Long): String {
        val stringToSign = "$timestamp\n$signSecret"
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(signSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        val signBytes = mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8))
        val signB64 = Base64.encodeToString(signBytes, Base64.NO_WRAP)
        val urlEncodedSign = urlEncode(signB64)
        return """{"data":$bodyJson,"timestamp":$timestamp,"sign":"$urlEncodedSign"}"""
    }

    /**
     * URL encode: keep alphanumeric and -_.~, percent-encode everything else.
     */
    private fun urlEncode(s: String): String = buildString {
        for (c in s) {
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                        c == '-' || c == '_' || c == '.' || c == '~' -> append(c)
                else -> append("%%%02X".format(c.code))
            }
        }
    }

    /**
     * RSA encrypt:
     *   1. Build full JSON: {"data": <body>, "timestamp": <ts>}
     *   2. Base64 encode the JSON bytes (StdEncoding)
     *   3. RSA PKCS1v15 encrypt the Base64 string bytes with public key
     *   4. Return raw encrypted bytes (sent as binary body)
     */
    private fun rsaEncrypt(bodyJson: String, timestamp: Long): ByteArray {
        val fullJson = """{"data":$bodyJson,"timestamp":$timestamp}"""
        val b64Body = Base64.encodeToString(fullJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val publicKey = parseRSAPublicKey(rsaPublicKey)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(b64Body.toByteArray(Charsets.UTF_8))
    }

    /**
     * RSA decrypt:
     *   1. RSA PKCS1v15 decrypt the raw bytes with private key
     *   2. Base64 decode the decrypted bytes
     *   3. Return the decoded JSON string
     */
    private fun rsaDecrypt(encryptedBytes: ByteArray): String {
        val privateKey = parseRSAPrivateKey(rsaPrivateKey)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val decrypted = cipher.doFinal(encryptedBytes)
        val decoded = Base64.decode(String(decrypted, Charsets.UTF_8), Base64.DEFAULT)
        return String(decoded, Charsets.UTF_8)
    }

    companion object {
        /**
         * Parse PEM RSA public key.
         * Supports X.509/PKIX (-----BEGIN PUBLIC KEY-----) and
         * PKCS1 (-----BEGIN RSA PUBLIC KEY-----) formats.
         */
        fun parseRSAPublicKey(pem: String): java.security.PublicKey {
            val keyBytes = pem.lines()
                .filter { !it.startsWith("-----") && it.isNotBlank() }
                .joinToString("")
            val decoded = Base64.decode(keyBytes, Base64.DEFAULT)
            val keyFactory = KeyFactory.getInstance("RSA")
            return try {
                keyFactory.generatePublic(X509EncodedKeySpec(decoded))
            } catch (e: Exception) {
                // PKCS1: wrap in SubjectPublicKeyInfo DER structure
                val pkcs1Header = byteArrayOf(
                    0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48,
                    0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01,
                    0x05, 0x00
                )
                val bitStringHeader = byteArrayOf(0x03.toByte())
                val innerLen = decoded.size + 1
                val bitStringLen = encodeDerLength(innerLen)
                val wrapped = byteArrayOf(0x30.toByte()) +
                        encodeDerLength(pkcs1Header.size + bitStringLen.size + 1 + innerLen) +
                        pkcs1Header +
                        bitStringHeader +
                        bitStringLen +
                        byteArrayOf(0x00) +
                        decoded
                keyFactory.generatePublic(X509EncodedKeySpec(wrapped))
            }
        }

        /**
         * Parse PEM RSA private key.
         * Supports PKCS8 (-----BEGIN PRIVATE KEY-----) and
         * PKCS1 (-----BEGIN RSA PRIVATE KEY-----) formats.
         */
        fun parseRSAPrivateKey(pem: String): java.security.PrivateKey {
            val keyBytes = pem.lines()
                .filter { !it.startsWith("-----") && it.isNotBlank() }
                .joinToString("")
            val decoded = Base64.decode(keyBytes, Base64.DEFAULT)
            val keyFactory = KeyFactory.getInstance("RSA")
            return try {
                keyFactory.generatePrivate(PKCS8EncodedKeySpec(decoded))
            } catch (e: Exception) {
                // PKCS1: wrap in PKCS8 structure
                val pkcs8Header = byteArrayOf(
                    0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48,
                    0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01,
                    0x05, 0x00
                )
                val octetStringHeader = byteArrayOf(0x04.toByte())
                val octetLen = encodeDerLength(decoded.size)
                val innerContent = pkcs8Header + octetStringHeader + octetLen + decoded
                val wrapped = byteArrayOf(0x30.toByte()) +
                        encodeDerLength(innerContent.size) +
                        innerContent
                keyFactory.generatePrivate(PKCS8EncodedKeySpec(wrapped))
            }
        }

        private fun encodeDerLength(length: Int): ByteArray {
            return when {
                length < 0x80 -> byteArrayOf(length.toByte())
                length < 0x100 -> byteArrayOf(0x81.toByte(), length.toByte())
                else -> byteArrayOf(
                    0x82.toByte(),
                    (length shr 8).toByte(),
                    (length and 0xFF).toByte()
                )
            }
        }
    }
}
