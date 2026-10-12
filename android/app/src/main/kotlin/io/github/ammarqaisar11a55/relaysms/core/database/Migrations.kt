package io.github.ammarqaisar11a55.relaysms.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history. Every released version must be reachable by migration: user data is never
 * dropped to make an upgrade easier. Covered by MigrationTest against the exported schemas.
 */
object Migrations {
    /** v2 adds the inbox. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `address_enc` BLOB, `body_enc` BLOB, " +
                    "`address_key` TEXT NOT NULL, `received_at` INTEGER NOT NULL, `stored_at` INTEGER NOT NULL, " +
                    "`direction` INTEGER NOT NULL, `origin` TEXT NOT NULL, `status` TEXT NOT NULL, `is_read` INTEGER NOT NULL, " +
                    "`has_otp` INTEGER NOT NULL, `part_count` INTEGER NOT NULL, `subscription_id` INTEGER NOT NULL, " +
                    "`provider_id` INTEGER, `deleted_at` INTEGER, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_received_at` ON `messages` (`received_at`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_address_key_received_at` ON `messages` (`address_key`, `received_at`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_deleted_at` ON `messages` (`deleted_at`)")
        }
    }

    val ALL = arrayOf(MIGRATION_1_2)
}
