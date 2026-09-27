package com.example.dianzicheng.data.garmin

import android.content.Context
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.local.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

private const val TAG = "GarminAuth"

/**
 * Garmin Connect 认证管理器（国际区专用，纯原生实现）。
 *
 * 实现了无需内置私钥的 Garmin SSO 换票流程：
 * 1. 在嵌入式 WebView 登录 Garmin 官方页面后，获取 serviceTicketId；
 * 2. 使用官方公开发布的 Garmin Connect Mobile Android Consumer Key 进行 OAuth 1.0a 签名；
 * 3. 换取 OAuth 2.0 Bearer Token 并持久化存储；
 * 4. 支持令牌过期自动静默刷新。
 */
class GarminAuthManager(
    private val context: Context,
    private val preferenceManager: PreferenceManager
) {
    // 官方 GCM Android 公共凭据（公开透明，非私有私钥）
    private val CONSUMER_KEY = "fc3e99d2-118c-44b8-8ae3-03370dde24c0"
    private val CONSUMER_SECRET = "E08WAR897WEy2knn7aFBrvegVAf0AFdWBBF"
    private val DOMAIN = "garmin.com"

    private val httpClient = OkHttpClient.Builder().build()

    companion object {
        /** Garmin 官方 SSO 手机端登录入口 URL */
        const val SSO_LOGIN_URL =
            "https://sso.garmin.com/portal/sso/en_US/sign-in?clientId=GCM_ANDROID_DARK&service=https%3A%2F%2Fmobile.integration.garmin.com%2Fgcm%2Fandroid"

        /** 重定向拦截前缀 */
        const val REDIRECT_URL_PREFIX = "https://mobile.integration.garmin.com/gcm/android"
    }

    data class OAuth1Token(
        val token: String,
        val tokenSecret: String,
        val mfaToken: String? = null
    )

    data class OAuth2Token(
        val accessToken: String,
        val tokenType: String,
        val refreshToken: String,
        val expiresAtEpochMs: Long
    ) {
        val isExpired: Boolean
            get() = System.currentTimeMillis() >= (expiresAtEpochMs - 60_000L) // 提前 1 分钟判定过期
    }

    /**
     * 收到 WebView 重定向的 ticket 后，执行 OAuth1 与 OAuth2 换票流程。
     */
    suspend fun handleServiceTicket(ticket: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            AppLogger.i(TAG, "正在使用 Ticket 换取 Garmin 授权凭据...")

            // 1. 换取 OAuth1 Token
            val oauth1 = getOAuth1Token(ticket)
            preferenceManager.saveGarminOAuth1(oauth1.token, oauth1.tokenSecret, oauth1.mfaToken)

            // 2. 换取 OAuth2 Token
            val oauth2 = exchangeOAuth2(oauth1)
            preferenceManager.saveGarminOAuth2(
                accessToken = oauth2.accessToken,
                refreshToken = oauth2.refreshToken,
                expiresAt = oauth2.expiresAtEpochMs
            )

            // 3. 获取用户 Profile 显示名称
            val username = fetchUserProfile(oauth2.accessToken) ?: "Garmin User"
            preferenceManager.saveGarminUsername(username)

            AppLogger.i(TAG, "Garmin 登录成功: $username")
            Result.success(username)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Garmin Ticket 换票失败: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 获取当前可用的 AccessToken（若过期会自动刷新）。
     */
    suspend fun getValidAccessToken(): String? = withContext(Dispatchers.IO) {
        val oauth2Info = preferenceManager.getGarminOAuth2().first() ?: return@withContext null
        if (!oauth2Info.isExpired) {
            return@withContext oauth2Info.accessToken
        }

        // 尝试使用 OAuth1 重新换取新的 OAuth2 Token
        val oauth1Info = preferenceManager.getGarminOAuth1().first() ?: return@withContext null
        try {
            AppLogger.i(TAG, "OAuth2 令牌已过期，正在刷新...")
            val newOAuth2 = exchangeOAuth2(oauth1Info)
            preferenceManager.saveGarminOAuth2(
                accessToken = newOAuth2.accessToken,
                refreshToken = newOAuth2.refreshToken,
                expiresAt = newOAuth2.expiresAtEpochMs
            )
            newOAuth2.accessToken
        } catch (e: Exception) {
            AppLogger.e(TAG, "刷新 Garmin 令牌失败: ${e.message}")
            null
        }
    }

    suspend fun logout() {
        preferenceManager.clearGarminAuth()
    }

    /**
     * 使用 serviceTicketId 获取 OAuth1 Token
     */
    private fun getOAuth1Token(ticket: String): OAuth1Token {
        val baseUrl = "https://connectapi.$DOMAIN/oauth-service/oauth/preauthorized"
        val loginUrl = "https://mobile.integration.$DOMAIN/gcm/android"
        val queryParams = linkedMapOf(
            "ticket" to ticket,
            "login-url" to loginUrl,
            "accepts-mfa-tokens" to "true"
        )

        val fullUrl = "$baseUrl?ticket=${urlEncode(ticket)}&login-url=${urlEncode(loginUrl)}&accepts-mfa-tokens=true"

        val authHeader = generateOAuth1Header(
            method = "GET",
            url = baseUrl,
            queryParams = queryParams,
            consumerKey = CONSUMER_KEY,
            consumerSecret = CONSUMER_SECRET,
            tokenSecret = ""
        )

        val request = Request.Builder()
            .url(fullUrl)
            .get()
            .header("User-Agent", "com.garmin.android.apps.connectmobile")
            .header("Authorization", authHeader)
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            throw RuntimeException("OAuth1 preauthorized failed (${response.code}): $responseBody")
        }

        val parsed = parseQueryString(responseBody)
        val token = parsed["oauth_token"] ?: throw RuntimeException("No oauth_token in response")
        val tokenSecret = parsed["oauth_token_secret"] ?: throw RuntimeException("No oauth_token_secret in response")
        val mfaToken = parsed["mfa_token"]

        return OAuth1Token(token, tokenSecret, mfaToken)
    }

    /**
     * 使用 OAuth1 Token 换取 OAuth2 Token
     */
    private fun exchangeOAuth2(oauth1: OAuth1Token): OAuth2Token {
        val url = "https://connectapi.$DOMAIN/oauth-service/oauth/exchange/user/2.0"
        val formParams = linkedMapOf<String, String>()
        formParams["audience"] = "GARMIN_CONNECT_MOBILE_ANDROID_DI"
        if (!oauth1.mfaToken.isNullOrEmpty()) {
            formParams["mfa_token"] = oauth1.mfaToken
        }

        val authHeader = generateOAuth1Header(
            method = "POST",
            url = url,
            queryParams = emptyMap(),
            formParams = formParams,
            consumerKey = CONSUMER_KEY,
            consumerSecret = CONSUMER_SECRET,
            token = oauth1.token,
            tokenSecret = oauth1.tokenSecret
        )

        val formBody = FormBody.Builder()
        for ((k, v) in formParams) {
            formBody.add(k, v)
        }

        val request = Request.Builder()
            .url(url)
            .post(formBody.build())
            .header("User-Agent", "com.garmin.android.apps.connectmobile")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", authHeader)
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            throw RuntimeException("OAuth2 exchange failed (${response.code}): $responseBody")
        }

        val json = JSONObject(responseBody)
        val accessToken = json.getString("access_token")
        val tokenType = json.optString("token_type", "Bearer")
        val refreshToken = json.optString("refresh_token", "")
        val expiresIn = json.optLong("expires_in", 86400L)
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000L

        return OAuth2Token(accessToken, tokenType, refreshToken, expiresAt)
    }

    private fun fetchUserProfile(accessToken: String): String? {
        return try {
            val request = Request.Builder()
                .url("https://connectapi.$DOMAIN/userprofile-service/socialProfile")
                .get()
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                json.optString("displayName", json.optString("userName", null))
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 生成标准 RFC 5849 OAuth 1.0a HMAC-SHA1 Authorization Header
     */
    private fun generateOAuth1Header(
        method: String,
        url: String,
        queryParams: Map<String, String> = emptyMap(),
        formParams: Map<String, String> = emptyMap(),
        consumerKey: String,
        consumerSecret: String,
        token: String? = null,
        tokenSecret: String = ""
    ): String {
        val oauthParams = sortedMapOf<String, String>()
        oauthParams["oauth_consumer_key"] = consumerKey
        oauthParams["oauth_nonce"] = UUID.randomUUID().toString().replace("-", "")
        oauthParams["oauth_signature_method"] = "HMAC-SHA1"
        oauthParams["oauth_timestamp"] = (System.currentTimeMillis() / 1000L).toString()
        oauthParams["oauth_version"] = "1.0"
        if (!token.isNullOrEmpty()) {
            oauthParams["oauth_token"] = token
        }

        // 合并所有参数（OAuth参数 + Query参数 + Form参数）并按字典序排序
        val allParams = sortedMapOf<String, String>()
        allParams.putAll(oauthParams)
        allParams.putAll(queryParams)
        allParams.putAll(formParams)

        val paramString = allParams.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }

        // Base String: METHOD & encode(URL) & encode(PARAMS)
        val baseString = "${method.uppercase()}&${urlEncode(url)}&${urlEncode(paramString)}"

        // Key: encode(consumerSecret) & encode(tokenSecret)
        val signingKey = "${urlEncode(consumerSecret)}&${urlEncode(tokenSecret)}"

        // HMAC-SHA1
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(signingKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA1"))
        val signatureBytes = mac.doFinal(baseString.toByteArray(StandardCharsets.UTF_8))
        val signature = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)

        oauthParams["oauth_signature"] = signature

        return "OAuth " + oauthParams.entries.joinToString(", ") { (k, v) ->
            "${urlEncode(k)}=\"${urlEncode(v)}\""
        }
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    private fun parseQueryString(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                result[key] = value
            }
        }
        return result
    }
}
