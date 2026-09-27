package com.example.dianzicheng.data.garmin

import android.content.Context
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.local.ScaleDao
import com.example.dianzicheng.domain.BodyMeasurement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private const val TAG = "GarminSync"

/**
 * Garmin 数据上传服务。
 *
 * 将测量记录使用 [GarminFitEncoder] 编码为标准 FIT 文件，
 * 并通过 OkHttp 上传至 Garmin Connect API (`/upload-service/upload`)。
 */
class GarminSyncService(
    private val context: Context,
    private val authManager: GarminAuthManager,
    private val dao: ScaleDao
) {
    private val httpClient = OkHttpClient.Builder().build()
    private val UPLOAD_URL = "https://connectapi.garmin.com/upload-service/upload"

    /**
     * 上传单条称重记录至 Garmin Connect。
     *
     * @param measurement 要上传的测量记录
     * @return Result<Unit> 成功或失败异常
     */
    suspend fun uploadMeasurement(measurement: BodyMeasurement): Result<Unit> = withContext(Dispatchers.IO) {
        val token = authManager.getValidAccessToken()
            ?: return@withContext Result.failure(IllegalStateException("未登录 Garmin 账号，请先在设置中登录"))

        try {
            AppLogger.i(TAG, "正在生成 FIT 文件并上传至 Garmin: weight=${measurement.weightKg}kg, fat=${measurement.bodyFatPct}%...")

            // 1. 生成 FIT 文件二进制流
            val fitBytes = GarminFitEncoder.encodeToBytes(measurement)
            val fileName = "weight_${measurement.measuredAtEpochMs}.fit"

            // 2. 构建 Multipart 请求
            val fileBody = fitBytes.toRequestBody("application/octet-stream".toMediaType())
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, fileBody)
                .build()

            val request = Request.Builder()
                .url(UPLOAD_URL)
                .post(multipartBody)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("origin", "https://sso.garmin.com")
                .header("nk", "NT")
                .build()

            val response = httpClient.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: ""

            // 201/202 成功，409 重复（Garmin 已存在该数据）也视为已同步
            if (code in 200..202 || code == 409) {
                val markMsg = if (code == 409) "Garmin 检测到重复记录，已标记为已同步" else "Garmin 上传成功"
                AppLogger.i(TAG, "$markMsg: $fileName")
                // 更新本地数据库中的同步状态
                dao.markMeasurementSynced(measurement.id, System.currentTimeMillis())
                Result.success(Unit)
            } else {
                AppLogger.e(TAG, "Garmin 上传失败 (HTTP $code): $body")
                Result.failure(IOException("Garmin 上传失败 HTTP $code: $body"))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Garmin 上传发生异常: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 批量上传所有本地尚未同步至 Garmin 的历史数据。
     *
     * @return 成功同步的条数
     */
    suspend fun syncAllUnsynced(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val unsyncedEntities = dao.getUnsyncedMeasurements()
            if (unsyncedEntities.isEmpty()) {
                AppLogger.i(TAG, "没有未同步到 Garmin 的记录")
                return@withContext Result.success(0)
            }

            var successCount = 0
            for (entity in unsyncedEntities) {
                val measurement = entity.toDomain()
                val r = uploadMeasurement(measurement)
                if (r.isSuccess) {
                    successCount++
                }
            }
            AppLogger.i(TAG, "批量同步完成，成功 $successCount/${unsyncedEntities.size} 条")
            Result.success(successCount)
        } catch (e: Exception) {
            AppLogger.e(TAG, "批量同步异常: ${e.message}")
            Result.failure(e)
        }
    }
}
