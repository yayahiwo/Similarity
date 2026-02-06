package com.yayahiwo.similarity.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ImageEmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addImageEmbedding(imageEmbedding: ImageEmbedding)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addImageEmbeddings(imageEmbeddings: List<ImageEmbedding>)

    @Query("SELECT * FROM image_embeddings WHERE id = :id LIMIT 1")
    suspend fun getRecord(id: Long): ImageEmbedding?

    @Query("SELECT * FROM image_embeddings WHERE id IN (:ids)")
    suspend fun getRecordsByIds(ids: List<Long>): List<ImageEmbedding>

    @Query("DELETE FROM image_embeddings WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT id FROM image_embeddings")
    suspend fun getAllIds(): List<Long>

    @Query("SELECT COUNT(*) FROM image_embeddings")
    suspend fun countRecords(): Long

    @Query("DELETE FROM image_embeddings")
    suspend fun clearAll()
}
