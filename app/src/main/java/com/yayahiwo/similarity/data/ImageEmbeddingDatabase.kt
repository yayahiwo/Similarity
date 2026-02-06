package com.yayahiwo.similarity.data

import android.content.Context
import androidx.room.migration.Migration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

@Database(entities = [ImageEmbedding::class], version = 2, exportSchema = false)
abstract class ImageEmbeddingDatabase : RoomDatabase() {
    abstract fun imageEmbeddingDao(): ImageEmbeddingDao

    companion object {
        private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.beginTransaction()
                try {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS image_embeddings_new (
                            id INTEGER NOT NULL,
                            date INTEGER NOT NULL,
                            embedding BLOB NOT NULL,
                            PRIMARY KEY(id)
                        )
                        """.trimIndent()
                    )

                    val cursor = db.query("SELECT id, date, embedding FROM image_embeddings")
                    val insert =
                        db.compileStatement("INSERT OR REPLACE INTO image_embeddings_new (id, date, embedding) VALUES (?, ?, ?)")

                    val listType: Type = object : TypeToken<FloatArray>() {}.type
                    val gson = Gson()

                    cursor.use {
                        val idIdx = it.getColumnIndexOrThrow("id")
                        val dateIdx = it.getColumnIndexOrThrow("date")
                        val embeddingIdx = it.getColumnIndexOrThrow("embedding")

                        while (it.moveToNext()) {
                            val id = it.getLong(idIdx)
                            val date = it.getLong(dateIdx)
                            val json = it.getString(embeddingIdx)

                            val floats: FloatArray = try {
                                gson.fromJson(json, listType) ?: floatArrayOf()
                            } catch (_: Exception) {
                                floatArrayOf()
                            }
                            val blob = Converters.fromFloatArray(floats)

                            insert.clearBindings()
                            insert.bindLong(1, id)
                            insert.bindLong(2, date)
                            insert.bindBlob(3, blob)
                            insert.executeInsert()
                        }
                    }

                    db.execSQL("DROP TABLE image_embeddings")
                    db.execSQL("ALTER TABLE image_embeddings_new RENAME TO image_embeddings")

                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        @Volatile
        private var INSTANCE: ImageEmbeddingDatabase? = null

        fun getDatabase(context: Context): ImageEmbeddingDatabase {
            val tempInstance = INSTANCE
            if (tempInstance != null) return tempInstance
            synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ImageEmbeddingDatabase::class.java,
                    "image_embedding_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                return instance
            }
        }

        fun closeDatabase() {
            synchronized(this) {
                val db = INSTANCE ?: return
                try {
                    db.close()
                } catch (_: Exception) {
                } finally {
                    INSTANCE = null
                }
            }
        }
    }
}
