package com.example.dianzicheng.data.repository

import com.example.dianzicheng.data.local.ScaleDao
import com.example.dianzicheng.data.local.toDomain
import com.example.dianzicheng.data.local.toEntity
import com.example.dianzicheng.domain.BodyMeasurement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 测量记录数据仓库，封装 Room DAO 操作。
 */
class ScaleRepository(private val dao: ScaleDao) {

    /** 获取所有历史记录流（按时间倒序） */
    fun getHistory(): Flow<List<BodyMeasurement>> =
        dao.getAllMeasurements().map { list -> list.map { it.toDomain() } }

    /** 一次性获取所有历史测量记录 */
    suspend fun getAllMeasurements(): List<BodyMeasurement> =
        dao.getAllMeasurementsList().map { it.toDomain() }

    /** 保存或更新一条测量记录（相同 ID 自动覆盖最新值） */
    suspend fun saveMeasurement(measurement: BodyMeasurement): BodyMeasurement {
        val finalRecord = if (measurement.id.isBlank()) {
            measurement.copy(id = UUID.randomUUID().toString())
        } else {
            measurement
        }
        dao.insertMeasurement(finalRecord.toEntity())
        return finalRecord
    }

    /** 标记记录为已同步 Garmin */
    suspend fun markSynced(id: String, syncedAt: Long) {
        dao.markMeasurementSynced(id, syncedAt)
    }

    /** 删除一条测量记录 */
    suspend fun deleteMeasurement(measurement: BodyMeasurement) {
        dao.deleteMeasurement(measurement.toEntity())
    }

    /** 按 ID 删除测量记录 */
    suspend fun deleteMeasurementById(id: String) {
        dao.deleteMeasurementById(id)
    }

    /** 清空全部历史记录 */
    suspend fun clearAll() {
        dao.clearAllMeasurements()
    }
}
