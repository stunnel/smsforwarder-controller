package com.example.smsforwarder.data.storage

import android.content.Context
import androidx.core.os.UserManagerCompat
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/**
 * Settings are kept in device-protected storage so the bot can read them right after a reboot,
 * before the user unlocks the device (Direct Boot); credential-protected storage is unreadable
 * until the first unlock. Trade-off: the file is protected by the device key only, not by the
 * lock screen credential (SmsForwarder stores its own config the same way).
 */
private object SettingsStore {
    private const val FILE_PATH = "datastore/settings.preferences_pb"

    @Volatile private var instance: DataStore<Preferences>? = null
    private val migrationMutex = Mutex()
    @Volatile private var migrated = false

    fun get(appContext: Context): DataStore<Preferences> =
        instance ?: synchronized(this) {
            instance ?: PreferenceDataStoreFactory.create(
                // Not preferencesDataStoreFile(): it resolves through applicationContext,
                // which is credential-protected again.
                produceFile = {
                    File(appContext.createDeviceProtectedStorageContext().filesDir, FILE_PATH)
                }
            ).also { instance = it }
        }

    /**
     * Copy settings written by older versions to credential-protected storage. That storage
     * is only readable once the user has unlocked, so until then this is retried on each access.
     * Keys already present in the new store win (they were written later).
     */
    suspend fun migrateIfNeeded(appContext: Context) {
        if (migrated || !UserManagerCompat.isUserUnlocked(appContext)) return
        migrationMutex.withLock {
            if (migrated) return
            val legacyFile = File(appContext.filesDir, FILE_PATH)
            if (legacyFile.exists()) {
                val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                try {
                    val legacy = PreferenceDataStoreFactory.create(scope = scope) { legacyFile }
                    val old = legacy.data.first()
                    get(appContext).edit { current ->
                        for ((key, value) in old.asMap()) {
                            if (!current.contains(key)) current.putUnchecked(key, value)
                        }
                    }
                    legacyFile.delete()
                } catch (_: CorruptionException) {
                    // Unreadable legacy file: give up rather than retrying on every access.
                } catch (_: IOException) {
                    // Transient I/O error: keep the legacy file and retry on the next access.
                    return
                } finally {
                    // Cancellation propagates without marking the migration done.
                    scope.cancel()
                }
            }
            migrated = true
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutablePreferences.putUnchecked(key: Preferences.Key<*>, value: Any) {
        this[key as Preferences.Key<Any>] = value
    }
}

class PreferencesManager(context: Context) {

    private val appContext = context.applicationContext
    private val store = SettingsStore.get(appContext)
    private val data: Flow<Preferences> = flow {
        SettingsStore.migrateIfNeeded(appContext)
        emitAll(store.data)
    }

    private suspend fun edit(transform: suspend (MutablePreferences) -> Unit) {
        SettingsStore.migrateIfNeeded(appContext)
        store.edit(transform)
    }

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
        private val KEY_BOT_ENABLED = booleanPreferencesKey("bot_enabled")

        const val SMS_FORWARDER_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 5000
    }

    val botToken: Flow<String> = data.map { it[KEY_BOT_TOKEN] ?: "" }
    val port: Flow<Int> = data.map { it[KEY_PORT] ?: DEFAULT_PORT }
    val securityMode: Flow<String> = data.map { it[KEY_SECURITY_MODE] ?: "none" }
    val signSecret: Flow<String> = data.map { it[KEY_SIGN_SECRET] ?: "" }
    val rsaPrivateKey: Flow<String> = data.map { it[KEY_RSA_PRIVATE_KEY] ?: "" }
    val rsaPublicKey: Flow<String> = data.map { it[KEY_RSA_PUBLIC_KEY] ?: "" }
    val sm4Key: Flow<String> = data.map { it[KEY_SM4_KEY] ?: "" }
    val allowedUsers: Flow<List<Long>> = data.map { prefs ->
        prefs[KEY_ALLOWED_USERS]?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?: emptyList()
    }
    val language: Flow<String> = data.map { it[KEY_LANGUAGE] ?: "zh" }

    // Whether the user wants the bot running (survives reboot / process death).
    // Defaults to true so installs that predate this flag keep auto-starting on boot.
    val botEnabled: Flow<Boolean> = data.map { it[KEY_BOT_ENABLED] ?: true }

    suspend fun getBotToken(): String = botToken.first()
    suspend fun getPort(): Int = port.first()
    suspend fun getSecurityMode(): String = securityMode.first()
    suspend fun getSignSecret(): String = signSecret.first()
    suspend fun getRsaPrivateKey(): String = rsaPrivateKey.first()
    suspend fun getRsaPublicKey(): String = rsaPublicKey.first()
    suspend fun getSm4Key(): String = sm4Key.first()
    suspend fun getAllowedUsers(): List<Long> = allowedUsers.first()
    suspend fun getLanguage(): String = language.first()
    suspend fun isBotEnabled(): Boolean = botEnabled.first()

    suspend fun setBotToken(value: String) =
        edit { it[KEY_BOT_TOKEN] = value }

    suspend fun setPort(value: Int) =
        edit { it[KEY_PORT] = value }

    suspend fun setSecurityMode(value: String) =
        edit { it[KEY_SECURITY_MODE] = value }

    suspend fun setSignSecret(value: String) =
        edit { it[KEY_SIGN_SECRET] = value }

    suspend fun setRsaPrivateKey(value: String) =
        edit { it[KEY_RSA_PRIVATE_KEY] = value }

    suspend fun setRsaPublicKey(value: String) =
        edit { it[KEY_RSA_PUBLIC_KEY] = value }

    suspend fun setSm4Key(value: String) =
        edit { it[KEY_SM4_KEY] = value }

    suspend fun setAllowedUsers(value: List<Long>) =
        edit { it[KEY_ALLOWED_USERS] = value.joinToString(",") }

    suspend fun setLanguage(value: String) =
        edit { it[KEY_LANGUAGE] = value }

    suspend fun setBotEnabled(value: Boolean) =
        edit { it[KEY_BOT_ENABLED] = value }

    fun getBaseUrl(port: Int): String = "http://$SMS_FORWARDER_HOST:$port"
}
