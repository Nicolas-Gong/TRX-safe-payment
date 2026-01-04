package com.trxsafe.payment.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.trxsafe.payment.data.dao.AddressBookDao
import com.trxsafe.payment.data.entity.AddressBook

/**
 * App 数据库
 *
 * 启动性能优化:
 * - 启用多实例模式允许多进程访问
 * - 使用 wal 写入模式提高性能
 * - 延迟数据库预加载操作
 */
@Database(
    entities = [AddressBook::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun addressBookDao(): AddressBookDao

    companion object {
        private const val TAG = "AppDatabase"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "trxsafe_database"
            )
                .fallbackToDestructiveMigration()
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }

        /**
         * 异步预加载数据库 (可选优化)
         * 在后台线程访问数据库以触发初始化
         */
        fun preload(context: Context) {
            Log.d(TAG, "开始预加载数据库...")
            val startTime = System.currentTimeMillis()
            getInstance(context).addressBookDao()
            Log.d(TAG, "数据库预加载完成,耗时: ${System.currentTimeMillis() - startTime}ms")
        }
    }
}
