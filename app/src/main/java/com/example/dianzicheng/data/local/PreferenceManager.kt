package com.example.dianzicheng.data.local

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.dianzicheng.data.garmin.GarminAuthManager
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.domain.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 应用偏好设置管理器，负责设备配对、单人身体档案、Garmin Connect 认证与自动同步设置。
 */
class PreferenceManager(private val context: Context) {

    // ── 配对设置 ────────────────────────────────────────────────────────────
    private val PAIRING_COMPLETE = booleanPreferencesKey("pairing_complete")
    private val PAIRED_MAC = stringPreferencesKey("paired_mac")
    private val PAIRED_DEVICE_NAME = stringPreferencesKey("paired_device_name")

    // ── 单人身体档案（取代多成员，彻底根除匹配失败与指标清零问题） ─────────
    private val USER_SEX = stringPreferencesKey("user_sex")
    private val USER_HEIGHT_CM = doublePreferencesKey("user_height_cm")
    private val USER_BIRTH_DATE_EPOCH_MS = longPreferencesKey("user_birth_date_epoch_ms")

    // ── Garmin 同步配置 ─────────────────────────────────────────────────────
    private val GARMIN_OAUTH1_TOKEN = stringPreferencesKey("garmin_oauth1_token")
    private val GARMIN_OAUTH1_SECRET = stringPreferencesKey("garmin_oauth1_secret")
    private val GARMIN_OAUTH1_MFA = stringPreferencesKey("garmin_oauth1_mfa")

    private val GARMIN_ACCESS_TOKEN = stringPreferencesKey("garmin_access_token")
    private val GARMIN_REFRESH_TOKEN = stringPreferencesKey("garmin_refresh_token")
    private val GARMIN_EXPIRES_AT = longPreferencesKey("garmin_expires_at")
    private val GARMIN_USERNAME = stringPreferencesKey("garmin_username")
    private val GARMIN_AUTO_SYNC = booleanPreferencesKey("garmin_auto_sync")

    // ── 系统健康连接 (Health Connect) ───────────────────────────────────────
    private val HEALTH_CONNECT_ENABLED = booleanPreferencesKey("health_connect_enabled")

    // ── 读取 Flow ───────────────────────────────────────────────────────────
    val isPairingComplete: Flow<Boolean> = context.dataStore.data
        .map { it[PAIRING_COMPLETE] ?: false }

    val pairedMac: Flow<String?> = context.dataStore.data
        .map { it[PAIRED_MAC] }

    val pairedDeviceName: Flow<String?> = context.dataStore.data
        .map { it[PAIRED_DEVICE_NAME] }

    val userProfile: Flow<UserProfile> = context.dataStore.data
        .map { prefs ->
            val sexStr = prefs[USER_SEX] ?: "MALE"
            val sex = if (sexStr == "FEMALE") Sex.FEMALE else Sex.MALE
            val height = prefs[USER_HEIGHT_CM] ?: 175.0
            val birthDate = prefs[USER_BIRTH_DATE_EPOCH_MS] ?: 946684800000L // 2000-01-01
            UserProfile(sex = sex, heightCm = height, birthDateEpochMs = birthDate)
        }

    val garminUsername: Flow<String?> = context.dataStore.data
        .map { it[GARMIN_USERNAME] }

    val isGarminLoggedIn: Flow<Boolean> = context.dataStore.data
        .map { !it[GARMIN_ACCESS_TOKEN].isNullOrEmpty() }

    val garminAutoSync: Flow<Boolean> = context.dataStore.data
        .map { it[GARMIN_AUTO_SYNC] ?: true }

    val healthConnectEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[HEALTH_CONNECT_ENABLED] ?: false }

    fun getGarminOAuth2(): Flow<GarminAuthManager.OAuth2Token?> = context.dataStore.data
        .map { prefs ->
            val token = prefs[GARMIN_ACCESS_TOKEN] ?: return@map null
            val refresh = prefs[GARMIN_REFRESH_TOKEN] ?: ""
            val expiresAt = prefs[GARMIN_EXPIRES_AT] ?: 0L
            GarminAuthManager.OAuth2Token(
                accessToken = token,
                tokenType = "Bearer",
                refreshToken = refresh,
                expiresAtEpochMs = expiresAt
            )
        }

    fun getGarminOAuth1(): Flow<GarminAuthManager.OAuth1Token?> = context.dataStore.data
        .map { prefs ->
            val token = prefs[GARMIN_OAUTH1_TOKEN] ?: return@map null
            val secret = prefs[GARMIN_OAUTH1_SECRET] ?: ""
            val mfa = prefs[GARMIN_OAUTH1_MFA]
            GarminAuthManager.OAuth1Token(token, secret, mfa)
        }

    // ── 写入操作 ─────────────────────────────────────────────────────────────
    suspend fun savePairedDevice(mac: String, name: String) {
        context.dataStore.edit {
            it[PAIRED_MAC] = mac
            it[PAIRED_DEVICE_NAME] = name
            it[PAIRING_COMPLETE] = true
        }
    }

    suspend fun savePairedMac(mac: String) {
        context.dataStore.edit {
            it[PAIRED_MAC] = mac
            it[PAIRING_COMPLETE] = true
        }
    }

    suspend fun setPairingComplete(complete: Boolean) {
        context.dataStore.edit {
            it[PAIRING_COMPLETE] = complete
        }
    }

    suspend fun clearPairedMac() {
        context.dataStore.edit {
            it.remove(PAIRED_MAC)
            it.remove(PAIRED_DEVICE_NAME)
            it[PAIRING_COMPLETE] = false
        }
    }

    suspend fun saveUserProfile(sex: Sex, heightCm: Double, birthDateEpochMs: Long) {
        context.dataStore.edit {
            it[USER_SEX] = sex.name
            it[USER_HEIGHT_CM] = heightCm
            it[USER_BIRTH_DATE_EPOCH_MS] = birthDateEpochMs
        }
    }

    suspend fun saveGarminOAuth1(token: String, tokenSecret: String, mfaToken: String?) {
        context.dataStore.edit {
            it[GARMIN_OAUTH1_TOKEN] = token
            it[GARMIN_OAUTH1_SECRET] = tokenSecret
            if (mfaToken != null) it[GARMIN_OAUTH1_MFA] = mfaToken else it.remove(GARMIN_OAUTH1_MFA)
        }
    }

    suspend fun saveGarminOAuth2(accessToken: String, refreshToken: String, expiresAt: Long) {
        context.dataStore.edit {
            it[GARMIN_ACCESS_TOKEN] = accessToken
            it[GARMIN_REFRESH_TOKEN] = refreshToken
            it[GARMIN_EXPIRES_AT] = expiresAt
        }
    }

    suspend fun saveGarminUsername(username: String) {
        context.dataStore.edit {
            it[GARMIN_USERNAME] = username
        }
    }

    suspend fun setGarminAutoSync(enabled: Boolean) {
        context.dataStore.edit {
            it[GARMIN_AUTO_SYNC] = enabled
        }
    }

    suspend fun clearGarminAuth() {
        context.dataStore.edit {
            it.remove(GARMIN_OAUTH1_TOKEN)
            it.remove(GARMIN_OAUTH1_SECRET)
            it.remove(GARMIN_OAUTH1_MFA)
            it.remove(GARMIN_ACCESS_TOKEN)
            it.remove(GARMIN_REFRESH_TOKEN)
            it.remove(GARMIN_EXPIRES_AT)
            it.remove(GARMIN_USERNAME)
        }
    }

    suspend fun setHealthConnectEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[HEALTH_CONNECT_ENABLED] = enabled
        }
    }
}
