package com.example.data.repository

import com.example.data.local.dao.ServerConfigDao
import com.example.data.local.entity.ServerConfigEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface ServerConfigRepository {
    fun observe(): Flow<ServerConfigEntity?>
    suspend fun get(): ServerConfigEntity?
    suspend fun save(config: ServerConfigEntity)
}

@Singleton
class ServerConfigRepositoryImpl @Inject constructor(
    private val dao: ServerConfigDao
) : ServerConfigRepository {
    override fun observe(): Flow<ServerConfigEntity?> = dao.observe()

    override suspend fun get(): ServerConfigEntity? = dao.get()

    override suspend fun save(config: ServerConfigEntity) {
        dao.upsert(config.copy(id = 1, updatedAt = System.currentTimeMillis()))
    }
}
