package com.example.smsforwarder.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ==================== Common ====================

@Serializable
data class BaseRequest(
    val data: JsonElement? = null,
    val timestamp: Long = 0,
    val sign: String = ""
)

@Serializable
data class BaseResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: JsonElement? = null,
    val timestamp: Long = 0,
    val sign: String = ""
)

// ==================== Config ====================

@Serializable
data class ConfigQueryResponse(
    @SerialName("enable_api_battery_query") val enableAPIBatteryQuery: Boolean = false,
    @SerialName("enable_api_call_query") val enableAPICallQuery: Boolean = false,
    @SerialName("enable_api_clone") val enableAPIClone: Boolean = false,
    @SerialName("enable_api_contact_query") val enableAPIContactQuery: Boolean = false,
    @SerialName("enable_api_sms_query") val enableAPISmsQuery: Boolean = false,
    @SerialName("enable_api_sms_send") val enableAPISmsSend: Boolean = false,
    @SerialName("enable_api_wol") val enableAPIWol: Boolean = false,
    @SerialName("extra_device_mark") val extraDeviceMark: String = "",
    @SerialName("extra_sim1") val extraSim1: String = "",
    @SerialName("extra_sim2") val extraSim2: String = "",
    @SerialName("sim_info_list") val simInfoList: Map<String, SimInfo> = emptyMap()
)

@Serializable
data class SimInfo(
    @SerialName("carrier_name") val carrierName: String = "",
    @SerialName("country_iso") val countryIso: String = "",
    @SerialName("icc_id") val iccID: String = "",
    val number: String = "",
    @SerialName("sim_slot_index") val simSlotIndex: Int = 0,
    @SerialName("subscription_id") val subscriptionID: Int = 0
)

// ==================== SMS ====================

@Serializable
data class SmsSendRequest(
    @SerialName("sim_slot") val simSlot: Int,
    @SerialName("phone_numbers") val phoneNumbers: String,
    @SerialName("msg_content") val msgContent: String
)

@Serializable
data class SmsQueryRequest(
    val type: Int,
    @SerialName("page_num") val pageNum: Int,
    @SerialName("page_size") val pageSize: Int,
    val keyword: String = ""
)

@Serializable
data class SmsRecord(
    val content: String = "",
    val number: String = "",
    val name: String = "",
    val type: Int = 0,
    val date: Long = 0,
    @SerialName("sim_id") val simID: Int = 0,
    @SerialName("sub_id") val subID: Int = 0
)

// ==================== Call ====================

@Serializable
data class CallQueryRequest(
    val type: Int = 0,
    @SerialName("page_num") val pageNum: Int,
    @SerialName("page_size") val pageSize: Int,
    @SerialName("phone_number") val phoneNumber: String = ""
)

@Serializable
data class CallRecord(
    val name: String = "",
    val number: String = "",
    val dateLong: Long = 0,
    val duration: Int = 0,
    val type: Int = 0,
    @SerialName("sim_id") val simID: Int = 0
)

// ==================== Contact ====================

@Serializable
data class ContactQueryRequest(
    @SerialName("phone_number") val phoneNumber: String = "",
    val name: String = ""
)

@Serializable
data class ContactRecord(
    val name: String = "",
    @SerialName("phone_number") val phoneNumber: String = ""
)

@Serializable
data class ContactAddRequest(
    @SerialName("phone_number") val phoneNumber: String,
    val name: String = ""
)

// ==================== Battery ====================

@Serializable
data class BatteryResponse(
    val level: String = "",
    val scale: String = "",
    val voltage: String = "",
    val temperature: String = "",
    val status: String = "",
    val health: String = "",
    val plugged: String = ""
)

// ==================== Location ====================

@Serializable
data class LocationResponse(
    val address: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val provider: String = "",
    val time: String = ""
)

// ==================== WOL ====================

@Serializable
data class WolRequest(
    val mac: String,
    val ip: String = "",
    val port: Int = 0
)

// ==================== Clone ====================

@Serializable
data class ClonePullRequest(
    @SerialName("version_code") val versionCode: Int
)
