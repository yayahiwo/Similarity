package com.yayahiwo.similarity.data

class ImageEmbeddingRepository(private val imageEmbeddingDao: ImageEmbeddingDao) {
    suspend fun addImageEmbedding(imageEmbedding: ImageEmbedding) {
        imageEmbeddingDao.addImageEmbedding(imageEmbedding)
    }

    suspend fun addImageEmbeddings(imageEmbeddings: List<ImageEmbedding>) {
        imageEmbeddingDao.addImageEmbeddings(imageEmbeddings)
    }

    suspend fun getRecord(id: Long): ImageEmbedding? {
        return imageEmbeddingDao.getRecord(id)
    }

    suspend fun getRecordsByIds(ids: List<Long>): List<ImageEmbedding> {
        return imageEmbeddingDao.getRecordsByIds(ids)
    }

    suspend fun deleteByIds(ids: List<Long>) {
        imageEmbeddingDao.deleteByIds(ids)
    }

    suspend fun getAllIds(): List<Long> {
        return imageEmbeddingDao.getAllIds()
    }

    suspend fun countRecords(): Long {
        return imageEmbeddingDao.countRecords()
    }

    suspend fun clearAll() {
        imageEmbeddingDao.clearAll()
    }
}
