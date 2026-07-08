package com.example.smsforwarder.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.smsforwarder.MainActivity
import com.example.smsforwarder.R
import com.example.smsforwarder.data.model.*
import com.example.smsforwarder.data.network.SmsForwarderApi
import com.example.smsforwarder.data.storage.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Foreground Service that runs the Telegram Bot long-polling loop.
 */
class TelegramBotService : Service() {

    companion object {
        const val CHANNEL_ID = "telegram_bot_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.example.smsforwarder.STOP_BOT"
        const val PAGE_SIZE = 10

        val isRunningState = MutableStateFlow(false)

        @Volatile
        private var instance: TelegramBotService? = null

        fun start(context: Context) {
            val intent = Intent(context, TelegramBotService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TelegramBotService::class.java))
        }

        fun onConfigChanged() {
            instance?.onConfigChangedInternal()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var lastUpdateId = 0L
    private var currentApiClient: SmsForwarderApi? = null

    private lateinit var prefs: PreferencesManager

    // Separate OkHttp client for Telegram API (no security interceptor)
    private val telegramClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS) // slightly longer than Telegram's 60s timeout
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // Per-user conversation state
    private val userStates = mutableMapOf<Long, UserState>()

    data class UserState(
        var waitingForSmsPhone: Boolean = false,
        var waitingForSmsContent: Boolean = false,
        var smsSimSlot: Int = 1,
        var smsPhoneNumbers: String = "",
        var smsContent: String = "",
        var waitingForContactQuery: Boolean = false,
        var waitingForContactAdd: Boolean = false,
        var waitingForWol: Boolean = false,
        var smsQueryType: Int = 0,
        var smsCurrentPage: Int = 1,
        var callQueryType: Int = 0,
        var callCurrentPage: Int = 1
    )

    // ==================== Service Lifecycle ====================

    override fun onCreate() {
        super.onCreate()
        isRunningState.value = true
        instance = this
        prefs = PreferencesManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_starting)))
        startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunningState.value = false
        instance = null
        pollingJob?.cancel()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ==================== Notification ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, TelegramBotService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_stop), stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ==================== Polling Loop ====================

    private fun startPolling() {
        pollingJob = serviceScope.launch {
            var retryDelay = 5_000L
            while (isActive) {
                try {
                    val botToken = prefs.getBotToken()
                    if (botToken.isBlank()) {
                        updateNotification(getString(R.string.bot_not_configured))
                        delay(30_000)
                        continue
                    }
                    if (currentApiClient == null) {
                        currentApiClient = createApiClient()
                    }
                    retryDelay = 5_000L
                    updateNotification(getString(R.string.bot_running_notif))
                    pollOnce(botToken, currentApiClient!!, prefs.getAllowedUsers())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = e.message?.take(60) ?: getString(R.string.unknown_error)
                    updateNotification(getString(R.string.bot_error, msg))
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(120_000L)
                }
            }
        }
    }

    private fun onConfigChangedInternal() {
        if (!isRunningState.value) return
        serviceScope.launch {
            try {
                currentApiClient = createApiClient()
            } catch (_: Exception) { }
        }
    }

    private suspend fun createApiClient(): SmsForwarderApi = SmsForwarderApi(
        baseUrl = "http://127.0.0.1:${prefs.getPort()}",
        securityMode = prefs.getSecurityMode(),
        signSecret = prefs.getSignSecret(),
        rsaPrivateKey = prefs.getRsaPrivateKey(),
        rsaPublicKey = prefs.getRsaPublicKey()
    )

    private suspend fun pollOnce(
        botToken: String,
        apiClient: SmsForwarderApi,
        allowedUsers: List<Long>
    ) {
        val params = JSONObject().apply {
            put("offset", lastUpdateId + 1)
            put("timeout", 30)
        }
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$botToken/getUpdates")
            .post(params.toString().toRequestBody(jsonMediaType))
            .build()
        val response = withContext(Dispatchers.IO) { telegramClient.newCall(request).execute() }
        if (!response.isSuccessful) return
        val body = response.body?.string() ?: return
        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) return
        val results = json.getJSONArray("result")
        for (i in 0 until results.length()) {
            val update = results.getJSONObject(i)
            lastUpdateId = update.getLong("update_id")
            handleUpdate(update, botToken, apiClient, allowedUsers)
        }
    }

    // ==================== Update Router ====================

    private suspend fun handleUpdate(
        update: JSONObject,
        botToken: String,
        apiClient: SmsForwarderApi,
        allowedUsers: List<Long>
    ) {
        if (update.has("callback_query")) {
            handleCallbackQuery(update.getJSONObject("callback_query"), botToken, apiClient, allowedUsers)
            return
        }
        val message = update.optJSONObject("message") ?: return
        val text = message.optString("text").ifBlank { return }
        val chatId = message.getJSONObject("chat").getLong("id")
        val userId = message.getJSONObject("from").getLong("id")

        if (!isAllowed(userId, allowedUsers)) {
            sendMessage(botToken, chatId, getString(R.string.unauthorized))
            return
        }

        when (text) {
            "/start", "/help" -> handleStart(botToken, chatId)
            "/config" -> handleConfig(botToken, chatId, apiClient)
            "/battery" -> handleBattery(botToken, chatId, apiClient)
            "/location" -> handleLocation(botToken, chatId, apiClient)
            "/sms_send" -> handleSmsSend(botToken, chatId, userId, apiClient)
            "/sms_query" -> handleSmsQuery(botToken, chatId)
            "/call_query" -> handleCallQuery(botToken, chatId)
            "/contact_query" -> handleContactQuery(botToken, chatId, userId)
            "/contact_add" -> handleContactAdd(botToken, chatId, userId)
            "/wol" -> handleWol(botToken, chatId, userId)
            "/clone" -> handleClone(botToken, chatId, apiClient)
            "/check" -> handleCheck(botToken, chatId, apiClient)
            "/cancel" -> handleCancel(botToken, chatId, userId)
            else -> handleTextInput(botToken, chatId, userId, text, apiClient)
        }
    }

    private fun isAllowed(userId: Long, allowedUsers: List<Long>): Boolean =
        allowedUsers.isEmpty() || userId in allowedUsers

    // ==================== Command Handlers ====================

    private suspend fun handleStart(botToken: String, chatId: Long) {
        val lang = prefs.getLanguage()
        val msg = if (lang == "zh") {
            """📱 <b>SmsForwarder 远程控制 Bot</b>

通过 Telegram 远程控制你的 SmsForwarder 手机。

<b>可用命令：</b>

/config - 查看远程配置
/battery - 查询电池状态
/location - 查询定位信息
/sms_send - 发送短信（交互式）
/sms_query - 查询短信记录
/call_query - 查询通话记录
/contact_query - 查询通讯录
/contact_add - 添加联系人
/wol - 发送 WOL 唤醒包
/clone - 拉取克隆配置
/check - 检查 SmsForwarder 连接
/help - 显示帮助信息
/cancel - 取消当前操作"""
        } else {
            """📱 <b>SmsForwarder Remote Control Bot</b>

Control your SmsForwarder device via Telegram.

<b>Available Commands:</b>

/config - View remote configuration
/battery - Query battery status
/location - Query location info
/sms_send - Send SMS (interactive)
/sms_query - Query SMS records
/call_query - Query call records
/contact_query - Search contacts
/contact_add - Add contact
/wol - Send WOL packet
/clone - Pull clone config
/check - Check SmsForwarder connection
/help - Show this help
/cancel - Cancel current operation"""
        }
        sendMessage(botToken, chatId, msg)
    }

    private suspend fun handleConfig(botToken: String, chatId: Long, apiClient: SmsForwarderApi) {
        val loadingId = sendMessageGetId(botToken, chatId, s(R.string.querying_config))
        val result = apiClient.configQuery()
        val cfg = result.getOrNull()
        val err = result.exceptionOrNull()
        if (cfg != null) {
            val simInfo = cfg.simInfoList.values.joinToString("\n") { sim ->
                val slot = if (sim.simSlotIndex == 0) 1 else sim.simSlotIndex
                "  📱 SIM$slot: ${esc(sim.carrierName)} (${esc(sim.number)})"
            }.ifEmpty { "  ${s(R.string.no_sim_info)}" }
            val deviceMark = cfg.extraDeviceMark.ifEmpty { s(R.string.not_set) }
            val features = buildString {
                appendLine("  ${boolEmoji(cfg.enableAPISmsSend)} ${s(R.string.feature_sms_send)}")
                appendLine("  ${boolEmoji(cfg.enableAPISmsQuery)} ${s(R.string.feature_sms_query)}")
                appendLine("  ${boolEmoji(cfg.enableAPICallQuery)} ${s(R.string.feature_call_query)}")
                appendLine("  ${boolEmoji(cfg.enableAPIContactQuery)} ${s(R.string.feature_contact_query)}")
                appendLine("  ${boolEmoji(cfg.enableAPIBatteryQuery)} ${s(R.string.feature_battery_query)}")
                appendLine("  ${boolEmoji(cfg.enableAPIClone)} ${s(R.string.feature_location_query)}")
                append("  ${boolEmoji(cfg.enableAPIWol)} WOL")
            }
            val msg = "📋 <b>${s(R.string.remote_config_title)}</b>\n\n" +
                "<b>${s(R.string.device_label)}:</b> ${esc(deviceMark)}\n\n" +
                "<b>${s(R.string.sim_info_label)}:</b>\n$simInfo\n\n" +
                "<b>${s(R.string.enabled_features_label)}:</b>\n$features"
            editMessage(botToken, chatId, loadingId, msg)
        } else {
            editMessage(botToken, chatId, loadingId, "${s(R.string.query_failed)}: ${esc(err?.message ?: s(R.string.unknown_error))}")
        }
    }

    private suspend fun handleBattery(botToken: String, chatId: Long, apiClient: SmsForwarderApi) {
        val loadingId = sendMessageGetId(botToken, chatId, s(R.string.querying_battery))
        val result = apiClient.batteryQuery()
        val battery = result.getOrNull()
        val err = result.exceptionOrNull()
        if (battery != null) {
            val unk = s(R.string.unknown)
            val msg = "🔋 <b>${s(R.string.battery_status_title)}</b>\n\n" +
                "<b>${s(R.string.battery_level)}:</b> ${esc(battery.level)}\n" +
                "<b>${s(R.string.battery_scale)}:</b> ${esc(battery.scale.ifEmpty { unk })}\n" +
                "<b>${s(R.string.battery_voltage)}:</b> ${esc(battery.voltage.ifEmpty { unk })}\n" +
                "<b>${s(R.string.battery_temperature)}:</b> ${esc(battery.temperature.ifEmpty { unk })}\n" +
                "<b>${s(R.string.battery_status)}:</b> ${esc(battery.status)}\n" +
                "<b>${s(R.string.battery_health)}:</b> ${esc(battery.health)}\n" +
                "<b>${s(R.string.battery_plugged)}:</b> ${esc(battery.plugged)}"
            editMessage(botToken, chatId, loadingId, msg)
        } else {
            editMessage(botToken, chatId, loadingId, "${s(R.string.query_failed)}: ${esc(err?.message ?: s(R.string.unknown_error))}")
        }
    }

    private suspend fun handleLocation(botToken: String, chatId: Long, apiClient: SmsForwarderApi) {
        val loadingId = sendMessageGetId(botToken, chatId, s(R.string.querying_location))
        val result = apiClient.locationQuery()
        val loc = result.getOrNull()
        val err = result.exceptionOrNull()
        if (loc != null) {
            val address = loc.address.ifEmpty { s(R.string.no_address) }
            val unk = s(R.string.unknown)
            val msg = "📍 <b>${s(R.string.location_info_title)}</b>\n\n" +
                "<b>${s(R.string.location_address)}:</b> ${esc(address)}\n" +
                "<b>${s(R.string.location_latitude)}:</b> ${loc.latitude}\n" +
                "<b>${s(R.string.location_longitude)}:</b> ${loc.longitude}\n" +
                "<b>${s(R.string.location_provider)}:</b> ${esc(loc.provider.ifEmpty { unk })}\n" +
                "<b>${s(R.string.location_time)}:</b> ${esc(loc.time)}"
            editMessage(botToken, chatId, loadingId, msg)
            // Also send a map location pin
            sendLocation(botToken, chatId, loc.latitude, loc.longitude)
        } else {
            editMessage(botToken, chatId, loadingId, "${s(R.string.query_failed)}: ${esc(err?.message ?: s(R.string.unknown_error))}")
        }
    }

    private suspend fun handleSmsSend(
        botToken: String, chatId: Long, userId: Long, apiClient: SmsForwarderApi
    ) {
        val loadingId = sendMessageGetId(botToken, chatId, s(R.string.querying_sim_info))
        val result = apiClient.configQuery()
        val cfg = result.getOrNull()
        val err = result.exceptionOrNull()
        if (cfg == null) {
            editMessage(botToken, chatId, loadingId, "${s(R.string.get_sim_failed)}: ${esc(err?.message ?: s(R.string.unknown_error))}")
            return
        }
        if (!cfg.enableAPISmsSend) {
            editMessage(botToken, chatId, loadingId, s(R.string.sms_send_disabled))
            return
        }
        val sims = cfg.simInfoList.values.toList()
        if (sims.isEmpty()) {
            editMessage(botToken, chatId, loadingId, s(R.string.no_sim_detected))
            return
        }
        val keyboard = JSONArray()
        sims.forEach { sim ->
            val slot = if (sim.simSlotIndex == 0) 1 else sim.simSlotIndex
            val row = JSONArray()
            row.put(JSONObject().apply {
                put("text", "SIM$slot: ${sim.carrierName} (${sim.number})")
                put("callback_data", "sim_$slot")
            })
            keyboard.put(row)
        }
        keyboard.put(JSONArray().apply {
            put(JSONObject().apply { put("text", s(R.string.cancel)); put("callback_data", "cancel") })
        })
        editMessageWithKeyboard(
            botToken, chatId, loadingId,
            "📱 <b>${s(R.string.sms_send_select_sim)}</b>\n\n${s(R.string.sms_send_select_sim_prompt)}",
            keyboard
        )
    }

    private suspend fun handleSmsQuery(botToken: String, chatId: Long, @Suppress("UNUSED_PARAMETER") userId: Long = 0L) {
        val keyboard = JSONArray().apply {
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", "📩 ${s(R.string.sms_inbox)}"); put("callback_data", "sms_type_1") })
                put(JSONObject().apply { put("text", "📤 ${s(R.string.sms_outbox)}"); put("callback_data", "sms_type_2") })
            })
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", s(R.string.cancel)); put("callback_data", "cancel") })
            })
        }
        sendKeyboard(botToken, chatId, "📋 <b>${s(R.string.sms_query_title)}</b>\n\n${s(R.string.sms_query_select_type)}", keyboard)
    }

    private suspend fun handleCallQuery(botToken: String, chatId: Long) {
        val keyboard = JSONArray().apply {
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", "📞 ${s(R.string.call_incoming)}"); put("callback_data", "call_type_1") })
                put(JSONObject().apply { put("text", "📞 ${s(R.string.call_outgoing)}"); put("callback_data", "call_type_2") })
                put(JSONObject().apply { put("text", "📞 ${s(R.string.call_missed)}"); put("callback_data", "call_type_3") })
            })
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", "📋 ${s(R.string.call_all)}"); put("callback_data", "call_type_0") })
            })
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", s(R.string.cancel)); put("callback_data", "cancel") })
            })
        }
        sendKeyboard(botToken, chatId, "📋 <b>${s(R.string.call_query_title)}</b>\n\n${s(R.string.call_query_select_type)}", keyboard)
    }

    private suspend fun handleContactQuery(botToken: String, chatId: Long, userId: Long) {
        getUserState(userId).waitingForContactQuery = true
        sendMessage(botToken, chatId,
            "📋 <b>${s(R.string.contact_query_title)}</b>\n\n${s(R.string.contact_query_prompt)}\n\n${s(R.string.cancel_hint)}")
    }

    private suspend fun handleContactAdd(botToken: String, chatId: Long, userId: Long) {
        getUserState(userId).waitingForContactAdd = true
        sendMessage(botToken, chatId,
            "📋 <b>${s(R.string.contact_add_title)}</b>\n\n${s(R.string.contact_add_prompt)}\n\n${s(R.string.cancel_hint)}")
    }

    private suspend fun handleWol(botToken: String, chatId: Long, userId: Long) {
        getUserState(userId).waitingForWol = true
        sendMessage(botToken, chatId,
            "🔌 <b>${s(R.string.wol_title)}</b>\n\n${s(R.string.wol_prompt)}\n\n${s(R.string.cancel_hint)}")
    }

    private suspend fun handleClone(botToken: String, chatId: Long, apiClient: SmsForwarderApi) {
        val loadingId = sendMessageGetId(botToken, chatId, s(R.string.querying_clone))
        val result = apiClient.clonePull(0)
        val data = result.getOrNull()
        val err = result.exceptionOrNull()
        if (data != null) {
            val content = if (data.isEmpty()) {
                s(R.string.clone_empty)
            } else {
                data.entries.joinToString("\n") { (k, v) -> "<b>${esc(k)}:</b> ${esc(v)}" }
            }
            editMessage(botToken, chatId, loadingId, "📋 <b>${s(R.string.clone_title)}</b>\n\n$content")
        } else {
            editMessage(botToken, chatId, loadingId, "${s(R.string.clone_failed)}: ${esc(err?.message ?: s(R.string.unknown_error))}")
        }
    }

    private suspend fun handleCheck(botToken: String, chatId: Long, apiClient: SmsForwarderApi) {
        val loadingId = sendMessageGetId(botToken, chatId, s(R.string.checking_connection))
        val result = apiClient.configQuery()
        val cfg = result.getOrNull()
        val err = result.exceptionOrNull()
        if (cfg != null) {
            val deviceMark = cfg.extraDeviceMark.ifEmpty { s(R.string.not_set) }
            val simCount = cfg.simInfoList.size
            val enabledFeatures = buildList {
                if (cfg.enableAPISmsSend) add(s(R.string.feature_sms_send))
                if (cfg.enableAPISmsQuery) add(s(R.string.feature_sms_query))
                if (cfg.enableAPICallQuery) add(s(R.string.feature_call_query))
                if (cfg.enableAPIContactQuery) add(s(R.string.feature_contact_query))
                if (cfg.enableAPIBatteryQuery) add(s(R.string.feature_battery_query))
                if (cfg.enableAPIClone) add(s(R.string.feature_location_query))
                if (cfg.enableAPIWol) add("WOL")
            }.joinToString(", ").ifEmpty { s(R.string.none) }
            val msg = "✅ <b>${s(R.string.check_ok)}</b>\n\n" +
                "<b>${s(R.string.device_label)}:</b> ${esc(deviceMark)}\n" +
                "<b>SIM:</b> $simCount\n" +
                "<b>${s(R.string.enabled_features_label)}:</b> $enabledFeatures"
            editMessage(botToken, chatId, loadingId, msg)
        } else {
            val errorMsg = "❌ <b>${s(R.string.check_fail)}</b>\n\n" +
                "${s(R.string.query_failed)}: ${esc(err?.message ?: s(R.string.unknown_error))}\n\n" +
                "${s(R.string.possible_reasons)}\n" +
                "${s(R.string.reason_not_running)}\n" +
                "${s(R.string.reason_wrong_port)}\n" +
                "${s(R.string.reason_key_mismatch)}\n" +
                s(R.string.reason_api_disabled)
            editMessage(botToken, chatId, loadingId, errorMsg)
        }
    }

    private suspend fun handleCancel(botToken: String, chatId: Long, userId: Long) {
        userStates.remove(userId)
        sendMessage(botToken, chatId, s(R.string.operation_cancelled))
    }

    // ==================== Text Input Handler ====================

    private suspend fun handleTextInput(
        botToken: String, chatId: Long, userId: Long,
        text: String, apiClient: SmsForwarderApi
    ) {
        val state = getUserState(userId)
        when {
            state.waitingForSmsPhone -> {
                state.waitingForSmsPhone = false
                state.waitingForSmsContent = true
                state.smsPhoneNumbers = text
                sendMessage(botToken, chatId,
                    "📱 <b>${s(R.string.sms_send_input_content)}</b>\n\n" +
                    "${s(R.string.sms_phone_label)}: ${esc(text)}\n\n" +
                    "${s(R.string.sms_send_content_prompt)}\n\n${s(R.string.cancel_hint)}")
            }
            state.waitingForSmsContent -> {
                state.waitingForSmsContent = false
                state.smsContent = text
                val keyboard = JSONArray().apply {
                    put(JSONArray().apply {
                        put(JSONObject().apply { put("text", s(R.string.confirm_send)); put("callback_data", "confirm_send_yes") })
                        put(JSONObject().apply { put("text", s(R.string.cancel)); put("callback_data", "cancel") })
                    })
                }
                val msg = "📱 <b>${s(R.string.sms_confirm_title)}</b>\n\n" +
                    "SIM: SIM${state.smsSimSlot}\n" +
                    "${s(R.string.sms_phone_label)}: ${esc(state.smsPhoneNumbers)}\n" +
                    "${s(R.string.sms_content_label)}: ${esc(text)}\n\n" +
                    s(R.string.sms_confirm_prompt)
                sendKeyboard(botToken, chatId, msg, keyboard)
            }
            state.waitingForContactQuery -> {
                state.waitingForContactQuery = false
                val loadingId = sendMessageGetId(botToken, chatId, s(R.string.querying_contacts))
                val result = apiClient.contactQuery(ContactQueryRequest(name = text, phoneNumber = text))
                val records = result.getOrNull()
                val err = result.exceptionOrNull()
                if (records != null) {
                    if (records.isEmpty()) {
                        editMessage(botToken, chatId, loadingId, s(R.string.no_contacts_found))
                    } else {
                        val body = records.withIndex().joinToString("\n\n") { (i, r) ->
                            "${i + 1}. ${esc(r.name)}\n    📞 ${esc(r.phoneNumber)}"
                        }
                        editMessage(botToken, chatId, loadingId,
                            "📋 <b>${s(R.string.contact_query_title)}</b> (${s(R.string.found_count, records.size)})\n\n$body")
                    }
                } else {
                    editMessage(botToken, chatId, loadingId, "${s(R.string.query_failed)}: ${esc(err?.message ?: s(R.string.unknown_error))}")
                }
            }
            state.waitingForContactAdd -> {
                state.waitingForContactAdd = false
                val parts = text.trim().split("\\s+".toRegex(), 2)
                if (parts.size < 2) {
                    sendMessage(botToken, chatId, s(R.string.contact_format_error))
                    return
                }
                val name = parts[0]
                val phone = parts[1]
                val loadingId = sendMessageGetId(botToken, chatId, s(R.string.adding_contact))
                val result = apiClient.contactAdd(ContactAddRequest(name = name, phoneNumber = phone))
                if (result.isSuccess) {
                    editMessage(botToken, chatId, loadingId,
                        "✅ <b>${s(R.string.contact_added)}</b>\n\n${s(R.string.contact_name_label)}: ${esc(name)}\n${s(R.string.contact_phone_label)}: ${esc(phone)}")
                } else {
                    val err = result.exceptionOrNull()
                    editMessage(botToken, chatId, loadingId, "${s(R.string.add_contact_failed)}: ${esc(err?.message ?: s(R.string.unknown_error))}")
                }
            }
            state.waitingForWol -> {
                state.waitingForWol = false
                val parts = text.trim().split("\\s+".toRegex())
                if (parts.isEmpty() || parts[0].isBlank()) {
                    sendMessage(botToken, chatId, s(R.string.wol_format_error))
                    return
                }
                val wolReq = WolRequest(
                    mac = parts[0],
                    ip = if (parts.size >= 2) parts[1] else "",
                    port = if (parts.size >= 3) parts[2].toIntOrNull() ?: 0 else 0
                )
                val loadingId = sendMessageGetId(botToken, chatId, s(R.string.sending_wol))
                val result = apiClient.wolSend(wolReq)
                if (result.isSuccess) {
                    val ipDisplay = wolReq.ip.ifEmpty { s(R.string.wol_broadcast) }
                    editMessage(botToken, chatId, loadingId,
                        "✅ <b>${s(R.string.wol_sent)}</b>\n\nMAC: ${esc(wolReq.mac)}\nIP: ${esc(ipDisplay)}\nPort: ${wolReq.port}")
                } else {
                    val err = result.exceptionOrNull()
                    editMessage(botToken, chatId, loadingId, "${s(R.string.send_failed)}: ${esc(err?.message ?: s(R.string.unknown_error))}")
                }
            }
            else -> sendMessage(botToken, chatId, s(R.string.unknown_command))
        }
    }

    // ==================== Callback Query Handler ====================

    private suspend fun handleCallbackQuery(
        callback: JSONObject,
        botToken: String,
        apiClient: SmsForwarderApi,
        allowedUsers: List<Long>
    ) {
        val data = callback.getString("data")
        val userId = callback.getJSONObject("from").getLong("id")
        val msg = callback.getJSONObject("message")
        val chatId = msg.getJSONObject("chat").getLong("id")
        val messageId = msg.getLong("message_id")
        val callbackId = callback.getString("id")

        if (!isAllowed(userId, allowedUsers)) {
            answerCallback(botToken, callbackId, s(R.string.no_permission))
            return
        }

        when {
            data == "cancel" -> {
                userStates.remove(userId)
                editMessageText(botToken, chatId, messageId, s(R.string.operation_cancelled))
                answerCallback(botToken, callbackId, s(R.string.cancelled))
            }
            data.startsWith("sim_") -> {
                val slot = data.removePrefix("sim_").toIntOrNull() ?: return
                val state = getUserState(userId)
                state.smsSimSlot = slot
                state.waitingForSmsPhone = true
                editMessageText(botToken, chatId, messageId,
                    "📱 <b>${s(R.string.sms_send_input_phone)}</b>\n\n" +
                    "${s(R.string.selected_sim)}: SIM$slot\n\n" +
                    "${s(R.string.sms_send_phone_prompt)}\n\n${s(R.string.cancel_hint)}")
                answerCallback(botToken, callbackId, "${s(R.string.selected_sim)}: SIM$slot")
            }
            data.startsWith("sms_type_") -> {
                val type = data.removePrefix("sms_type_").toIntOrNull() ?: return
                val state = getUserState(userId)
                state.smsQueryType = type
                state.smsCurrentPage = 1
                editMessageText(botToken, chatId, messageId, s(R.string.querying_sms))
                val result = apiClient.smsQuery(SmsQueryRequest(type = type, pageNum = 1, pageSize = PAGE_SIZE))
                val records = result.getOrNull()
                val err = result.exceptionOrNull()
                if (records != null) {
                    if (records.isEmpty()) editMessageText(botToken, chatId, messageId, s(R.string.no_sms_records))
                    else displaySmsRecords(botToken, chatId, messageId, records, 1, type)
                } else {
                    editMessageText(botToken, chatId, messageId, "${s(R.string.query_failed)}: ${esc(err?.message ?: s(R.string.unknown_error))}")
                }
                answerCallback(botToken, callbackId, s(R.string.querying))
            }
            data.startsWith("sms_page_") -> {
                val parts = data.removePrefix("sms_page_").split("_")
                if (parts.size != 2) return
                val type = parts[0].toIntOrNull() ?: return
                val page = parts[1].toIntOrNull() ?: return
                val state = getUserState(userId)
                state.smsQueryType = type
                state.smsCurrentPage = page
                editMessageText(botToken, chatId, messageId, s(R.string.querying_sms))
                val result = apiClient.smsQuery(SmsQueryRequest(type = type, pageNum = page, pageSize = PAGE_SIZE))
                val records = result.getOrNull()
                val err = result.exceptionOrNull()
                if (records != null) {
                    if (records.isEmpty()) editMessageText(botToken, chatId, messageId, s(R.string.no_more_records))
                    else displaySmsRecords(botToken, chatId, messageId, records, page, type)
                } else {
                    editMessageText(botToken, chatId, messageId, "${s(R.string.query_failed)}: ${esc(err?.message ?: s(R.string.unknown_error))}")
                }
                answerCallback(botToken, callbackId, "${s(R.string.page_label)} $page")
            }
            data.startsWith("call_type_") -> {
                val type = data.removePrefix("call_type_").toIntOrNull() ?: return
                val state = getUserState(userId)
                state.callQueryType = type
                state.callCurrentPage = 1
                editMessageText(botToken, chatId, messageId, s(R.string.querying_calls))
                val result = apiClient.callQuery(CallQueryRequest(type = type, pageNum = 1, pageSize = PAGE_SIZE))
                val records = result.getOrNull()
                val err = result.exceptionOrNull()
                if (records != null) {
                    if (records.isEmpty()) editMessageText(botToken, chatId, messageId, s(R.string.no_call_records))
                    else displayCallRecords(botToken, chatId, messageId, records, 1, type)
                } else {
                    editMessageText(botToken, chatId, messageId, "${s(R.string.query_failed)}: ${esc(err?.message ?: s(R.string.unknown_error))}")
                }
                answerCallback(botToken, callbackId, s(R.string.querying))
            }
            data.startsWith("call_page_") -> {
                val parts = data.removePrefix("call_page_").split("_")
                if (parts.size != 2) return
                val type = parts[0].toIntOrNull() ?: return
                val page = parts[1].toIntOrNull() ?: return
                val state = getUserState(userId)
                state.callQueryType = type
                state.callCurrentPage = page
                editMessageText(botToken, chatId, messageId, s(R.string.querying_calls))
                val result = apiClient.callQuery(CallQueryRequest(type = type, pageNum = page, pageSize = PAGE_SIZE))
                val records = result.getOrNull()
                val err = result.exceptionOrNull()
                if (records != null) {
                    if (records.isEmpty()) editMessageText(botToken, chatId, messageId, s(R.string.no_more_records))
                    else displayCallRecords(botToken, chatId, messageId, records, page, type)
                } else {
                    editMessageText(botToken, chatId, messageId, "${s(R.string.query_failed)}: ${esc(err?.message ?: s(R.string.unknown_error))}")
                }
                answerCallback(botToken, callbackId, "${s(R.string.page_label)} $page")
            }
            data == "confirm_send_yes" -> {
                val state = getUserState(userId)
                editMessageText(botToken, chatId, messageId, s(R.string.sending_sms))
                val result = apiClient.smsSend(
                    SmsSendRequest(
                        simSlot = state.smsSimSlot,
                        phoneNumbers = state.smsPhoneNumbers,
                        msgContent = state.smsContent
                    )
                )
                userStates.remove(userId)
                if (result.isSuccess) {
                    editMessageText(botToken, chatId, messageId,
                        "✅ <b>${s(R.string.sms_sent_success)}</b>\n\n" +
                        "SIM: SIM${state.smsSimSlot}\n" +
                        "${s(R.string.sms_phone_label)}: ${esc(state.smsPhoneNumbers)}\n" +
                        "${s(R.string.sms_content_label)}: ${esc(state.smsContent)}")
                    answerCallback(botToken, callbackId, s(R.string.sms_sent_success))
                } else {
                    val err = result.exceptionOrNull()
                    editMessageText(botToken, chatId, messageId,
                        "❌ <b>${s(R.string.sms_send_failed)}</b>\n\n${esc(err?.message ?: s(R.string.unknown_error))}")
                    answerCallback(botToken, callbackId, s(R.string.sms_send_failed))
                }
            }
        }
    }

    // ==================== Display Helpers ====================

    private suspend fun displaySmsRecords(
        botToken: String, chatId: Long, messageId: Long,
        records: List<SmsRecord>, page: Int, type: Int
    ) {
        val title = s(R.string.sms_records_title)
        val pageLabel = s(R.string.page_label)
        val contactLabel = s(R.string.contact_label)
        val contentLabel = s(R.string.content_label)
        val timeLabel = s(R.string.time_label)
        val slotLabel = s(R.string.slot_label)
        val unk = s(R.string.unknown)

        val msgText = buildString {
            appendLine("📋 <b>$title</b> ($pageLabel $page)\n")
            records.forEachIndexed { i, record ->
                val index = i + 1 + PAGE_SIZE * (page - 1)
                appendLine("$index. 📞 ${esc(record.number)}")
                if (record.name.isNotEmpty() && record.name != "Unknown Number") {
                    appendLine("   $contactLabel: ${esc(record.name)}")
                }
                appendLine("   $contentLabel: ${esc(record.content)}")
                appendLine("   $timeLabel: ${dateFormat.format(Date(record.date))}")
                val simLabel = if (record.simID == -1) unk else "SIM${record.simID + 1}"
                appendLine("   $slotLabel: $simLabel\n")
            }
        }
        val keyboard = buildPaginationKeyboard("sms_page", type, page)
        editMessageWithKeyboard(botToken, chatId, messageId, msgText, keyboard)
    }

    private suspend fun displayCallRecords(
        botToken: String, chatId: Long, messageId: Long,
        records: List<CallRecord>, page: Int, type: Int
    ) {
        val title = s(R.string.call_records_title)
        val pageLabel = s(R.string.page_label)
        val contactLabel = s(R.string.contact_label)
        val timeLabel = s(R.string.time_label)
        val durationLabel = s(R.string.duration_label)
        val slotLabel = s(R.string.slot_label)
        val unk = s(R.string.unknown)

        val msgText = buildString {
            appendLine("📋 <b>$title</b> ($pageLabel $page)\n")
            records.forEachIndexed { i, record ->
                val index = i + 1 + PAGE_SIZE * (page - 1)
                val typeLabel = when (record.type) {
                    1 -> s(R.string.call_incoming)
                    2 -> s(R.string.call_outgoing)
                    3 -> s(R.string.call_missed)
                    else -> unk
                }
                appendLine("$index. ${esc(record.number)} [$typeLabel]")
                if (record.name.isNotEmpty()) {
                    appendLine("   $contactLabel: ${esc(record.name)}")
                }
                appendLine("   $timeLabel: ${dateFormat.format(Date(record.dateLong))}")
                appendLine("   $durationLabel: ${record.duration}s")
                val simLabel = if (record.simID == -1) unk else "SIM${record.simID + 1}"
                appendLine("   $slotLabel: $simLabel\n")
            }
        }
        val keyboard = buildPaginationKeyboard("call_page", type, page)
        editMessageWithKeyboard(botToken, chatId, messageId, msgText, keyboard)
    }

    private suspend fun buildPaginationKeyboard(prefix: String, type: Int, page: Int): JSONArray {
        val prevLabel = s(R.string.previous_page)
        val nextLabel = s(R.string.next_page)
        val returnLabel = s(R.string.return_btn)
        return JSONArray().apply {
            val navRow = JSONArray()
            if (page > 1) {
                navRow.put(JSONObject().apply {
                    put("text", prevLabel)
                    put("callback_data", "${prefix}_${type}_${page - 1}")
                })
            }
            // Always show next page button (consistent with Go version)
            navRow.put(JSONObject().apply {
                put("text", nextLabel)
                put("callback_data", "${prefix}_${type}_${page + 1}")
            })
            put(navRow)
            put(JSONArray().apply {
                put(JSONObject().apply { put("text", returnLabel); put("callback_data", "cancel") })
            })
        }
    }

    // ==================== Telegram API Helpers ====================

    private suspend fun sendMessage(botToken: String, chatId: Long, text: String) {
        val params = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            put("parse_mode", "HTML")
            put("disable_web_page_preview", true)
        }
        postTelegram("https://api.telegram.org/bot$botToken/sendMessage", params)
    }

    private suspend fun sendMessageGetId(botToken: String, chatId: Long, text: String): Long {
        val params = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            put("parse_mode", "HTML")
            put("disable_web_page_preview", true)
        }
        return try {
            val response = postTelegramWithResponse("https://api.telegram.org/bot$botToken/sendMessage", params)
            response?.getJSONObject("result")?.getLong("message_id") ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    private suspend fun sendKeyboard(botToken: String, chatId: Long, text: String, keyboard: JSONArray) {
        val params = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            put("parse_mode", "HTML")
            put("disable_web_page_preview", true)
            put("reply_markup", JSONObject().apply { put("inline_keyboard", keyboard) })
        }
        postTelegram("https://api.telegram.org/bot$botToken/sendMessage", params)
    }

    private suspend fun editMessage(botToken: String, chatId: Long, messageId: Long, text: String) {
        if (messageId <= 0) {
            sendMessage(botToken, chatId, text)
            return
        }
        editMessageText(botToken, chatId, messageId, text)
    }

    private suspend fun editMessageText(botToken: String, chatId: Long, messageId: Long, text: String) {
        val params = JSONObject().apply {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("text", text)
            put("parse_mode", "HTML")
            put("disable_web_page_preview", true)
        }
        postTelegram("https://api.telegram.org/bot$botToken/editMessageText", params)
    }

    private suspend fun editMessageWithKeyboard(
        botToken: String, chatId: Long, messageId: Long,
        text: String, keyboard: JSONArray
    ) {
        val params = JSONObject().apply {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("text", text)
            put("parse_mode", "HTML")
            put("disable_web_page_preview", true)
            put("reply_markup", JSONObject().apply { put("inline_keyboard", keyboard) })
        }
        postTelegram("https://api.telegram.org/bot$botToken/editMessageText", params)
    }

    private suspend fun sendLocation(botToken: String, chatId: Long, lat: Double, lon: Double) {
        val params = JSONObject().apply {
            put("chat_id", chatId)
            put("latitude", lat)
            put("longitude", lon)
        }
        postTelegram("https://api.telegram.org/bot$botToken/sendLocation", params)
    }

    private suspend fun answerCallback(botToken: String, callbackId: String, text: String) {
        val params = JSONObject().apply {
            put("callback_query_id", callbackId)
            put("text", text)
            put("show_alert", false)
        }
        postTelegram("https://api.telegram.org/bot$botToken/answerCallbackQuery", params)
    }

    private suspend fun postTelegram(url: String, params: JSONObject) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(params.toString().toRequestBody(jsonMediaType))
                    .build()
                telegramClient.newCall(request).execute().close()
            } catch (_: Exception) {}
        }
    }

    private suspend fun postTelegramWithResponse(url: String, params: JSONObject): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(params.toString().toRequestBody(jsonMediaType))
                    .build()
                val response = telegramClient.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext null
                JSONObject(body)
            } catch (_: Exception) {
                null
            }
        }
    }

    // ==================== Utilities ====================

    private fun getUserState(userId: Long): UserState =
        userStates.getOrPut(userId) { UserState() }

    /** Escape HTML special characters for Telegram HTML parse mode. */
    private fun esc(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun boolEmoji(v: Boolean): String = if (v) "✅" else "❌"

    /** Get localized string based on language preference. */
    private suspend fun s(resId: Int): String {
        val lang = prefs.getLanguage()
        return if (lang == "zh") {
            val config = android.content.res.Configuration(resources.configuration)
            config.setLocale(java.util.Locale.CHINESE)
            createConfigurationContext(config).getString(resId)
        } else {
            getString(resId)
        }
    }

    private suspend fun s(resId: Int, vararg args: Any?): String {
        val lang = prefs.getLanguage()
        return if (lang == "zh") {
            val config = android.content.res.Configuration(resources.configuration)
            config.setLocale(java.util.Locale.CHINESE)
            createConfigurationContext(config).getString(resId, *args)
        } else {
            getString(resId, *args)
        }
    }
}
