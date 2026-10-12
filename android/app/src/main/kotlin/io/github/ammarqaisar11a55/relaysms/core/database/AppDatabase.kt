package io.github.ammarqaisar11a55.relaysms.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [OutboxEntity::class, SenderFilterEntity::class, SeenSenderEntity::class, MessageEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun senderFilterDao(): SenderFilterDao
    abstract fun seenSenderDao(): SeenSenderDao
    abstract fun messageDao(): MessageDao

    companion object {
        const val NAME = "relaysms.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}
