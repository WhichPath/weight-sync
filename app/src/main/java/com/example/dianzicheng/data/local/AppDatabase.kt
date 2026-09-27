package com.example.dianzicheng.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room 数据库主类，管理测量记录表。
 */
@Database(entities = [MeasurementEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scaleDao(): ScaleDao
}
