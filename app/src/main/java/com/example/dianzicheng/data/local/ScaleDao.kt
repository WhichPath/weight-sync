package com.example.dianzicheng.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room 数据访问对象（DAO），提供对测量记录表的增删改查及 Garmin 同步状态标记。
 */
@Dao
interface ScaleDao {

    /** 获取所有测量记录（Flow 实时流，按时间倒序） */
    @Query("SELECT * FROM measurements ORDER BY measuredAtEpochMs DESC")
    fun getAllMeasurements(): Flow<List<MeasurementEntity>>

    /** 一次性获取所有测量记录列表（按时间倒序） */
    @Query("SELECT * FROM measurements ORDER BY measuredAtEpochMs DESC")
    suspend fun getAllMeasurementsList(): List<MeasurementEntity>

    /** 获取所有尚未同步到 Garmin 的测量记录 */
    @Query("SELECT * FROM measurements WHERE syncedToGarmin = 0 ORDER BY measuredAtEpochMs ASC")
    suspend fun getUnsyncedMeasurements(): List<MeasurementEntity>

    /** 插入或覆盖更新测量记录（相同 sessionId 自动覆盖更新） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeasurement(measurement: MeasurementEntity)

    /** 删除指定测量记录 */
    @Delete
    suspend fun deleteMeasurement(measurement: MeasurementEntity)

    /** 按 ID 删除测量记录 */
    @Query("DELETE FROM measurements WHERE id = :id")
    suspend fun deleteMeasurementById(id: String)

    /** 标记某条记录为已同步到 Garmin */
    @Query("UPDATE measurements SET syncedToGarmin = 1, syncedToGarminAtEpochMs = :syncedAt WHERE id = :id")
    suspend fun markMeasurementSynced(id: String, syncedAt: Long)

    /** 清空所有测量记录 */
    @Query("DELETE FROM measurements")
    suspend fun clearAllMeasurements()
}
