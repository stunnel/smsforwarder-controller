package com.example.smsforwarder.data.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesManager(private val context: Context) {

    companion object {
        private val KEY_BOT_TOKEN = stringPreferencesKey("bot_token")
        private val KEY_PORT = intPreferencesKey("sms_forwarder_port")
        private val KEY_SECURITY_MODE = stringPreferencesKey("security_mode")
        private val KEY_SIGN_SECRET = stringPreferencesKey("sign_secret")
        private val KEY_RSA_PRIVATE_KEY = stringPreferencesKey("rsa_private_key")
        private val KEY_RSA_PUBLIC_KEY = stringPreferencesKey("rsa_public_key")
        private val KEY_SM4_KEY = stringPreferencesKey("sm4_key")
        private val KEY_ALLOWED_USERS = stringPreferencesKey("allowed_users")
        private val KEY_LANGUAGE = stringPreferencesKey("language")

        const val SMS_FORWARDER_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 5000
    }

    val botToken: Flow<String> = context.dataStore.data.map { it[KEY_BOT_TOKEN] ?: "" }
    val port: Flow<Int> = context.dataStore.data.map { it[KEY_PORT] ?: DEFAULT_PORT }
    val securityMode: Flow<String> = context.dataStore.data.map { it[KEY_SECURITY_MODE] ?: "none" }
    val signSecret: Flow<String> = context.dataStore.data.map { it[KEY_SIGN_SECRET] ?: "" }
    val rsaPrivateKey: Flow<String> = context.dataStore.data.map { it[KEY_RSA_PRIVATE_KEY] ?: "" }
    val rsaPublicKey: Flow<String> = context.dataStore.data.map { it[KEY_RSA_PUBLIC_KEY] ?: "" }
    val sm4Key: Flow<String> = context.dataStore.data.map { it[KEY_SM4_KEY] ?: "" }
    val allowedUsers: Flow<List<Long>> = context.dataStore.data.map { prefs ->
        prefs[KEY_ALLOWED_USERS]?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?: emptyList()
    }
    val language: Flow<String> = context.dataStore.data.map { it[KEY_LANGUAGE] ?: "zh" }

    suspend fun getBotToken(): String = botToken.first()
    suspend fun getPort(): Int = port.first()
    suspend fun getSecurityMode(): String = securityMode.first()
    suspend fun getSignSecret(): String = signSecret.first()
    suspend fun getRsaPrivateKey(): String = rsaPrivateKey.first()
    suspend fun getRsaPublicKey(): String = rsaPublicKey.first()
    suspend fun getSm4Key(): String = sm4Key.first()
    suspend fun getAllowedUsers(): List<Long> = allowedUsers.first()
    suspend fun getLanguage(): String = language.first()

    suspend fun setBotToken(value: String) =
        context.dataStore.edit { it[KEY_BOT_TOKEN] = value }

    suspend fun setPort(value: Int) =
        context.dataStore.edit { it[KEY_PORT] = value }

    suspend fun setSecurityMode(value: String) =
        context.dataStore.edit { it[KEY_SECURITY_MODE] = value }

    suspend fun setSignSecret(value: String) =
        context.dataStore.edit { it[KEY_SIGN_SECRET] = value }

    suspend fun setRsaPrivateKey(value: String) =
        context.dataStore.edit { it[KEY_RSA_PRIVATE_KEY] = value }

    suspend fun setRsaPublicKey(value: String) =
        context.dataStore.edit { it[KEY_RSA_PUBLIC_KEY] = value }

    suspend fun setSm4Key(value: String) =
        context.dataStore.edit { it[KEY_SM4_KEY] = value }

    suspend fun setAllowedUsers(value: List<Long>) =
        context.dataStore.edit { it[KEY_ALLOWED_USERS] = value.joinToString(",") }

    suspend fun setLanguage(value: String) =
        context.dataStore.edit { it[KEY_LANGUAGE] = value }

    fun getBaseUrl(port: Int): String = "http://$SMS_FORWARDER_HOST:$port"
}
