package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.HcpEntity
import kotlinx.coroutines.flow.Flow

/**
 * Dedicated Room Data Access Object (DAO) for managing medical representative HCP targets ([HcpEntity]).
 * All queries returning lists expose reactive [Flow] streams, and all write operations are `suspend` functions.
 */
@Dao
interface HcpDao {

    @Query("SELECT * FROM hcps WHERE isDeleted = 0 AND isActive = 1 ORDER BY priorityScore DESC, name ASC")
    fun observeActiveTargets(): Flow<List<HcpEntity>>

    @Query("SELECT COUNT(*) FROM hcps WHERE isDeleted = 0 AND isActive = 1")
    fun observeActiveTargetCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM hcps
        WHERE isDeleted = 0
          AND (
            name LIKE '%' || :query || '%'
            OR targetId LIKE '%' || :query || '%'
            OR specialty LIKE '%' || :query || '%'
            OR territoryZone LIKE '%' || :query || '%'
          )
        ORDER BY priorityScore DESC, name ASC
        """
    )
    fun searchTargets(query: String): Flow<List<HcpEntity>>

    @Query("SELECT * FROM hcps WHERE isDeleted = 0 AND LOWER(specialty) = LOWER(:specialty) ORDER BY priorityScore DESC, name ASC")
    fun observeTargetsBySpecialty(specialty: String): Flow<List<HcpEntity>>

    @Query("SELECT * FROM hcps WHERE isDeleted = 0 AND LOWER(territoryZone) = LOWER(:zone) ORDER BY priorityScore DESC, name ASC")
    fun observeTargetsByZone(zone: String): Flow<List<HcpEntity>>

    @Query("SELECT * FROM hcps WHERE id = :id LIMIT 1")
    suspend fun getTargetById(id: Long): HcpEntity?

    @Query("SELECT * FROM hcps WHERE targetId = :targetId LIMIT 1")
    suspend fun getTargetByTargetId(targetId: String): HcpEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTarget(hcp: HcpEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTargets(hcps: List<HcpEntity>): List<Long>

    @Update
    suspend fun updateTarget(hcp: HcpEntity)

    @Query(
        """
        UPDATE hcps
        SET quarterlyTarget = :quarterlyTarget,
            priorityScore = :priorityScore,
            version = version + 1,
            syncStatus = 'PENDING',
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE targetId = :targetId
        """
    )
    suspend fun updateQuarterlyTargetGoal(
        targetId: String,
        quarterlyTarget: Int,
        priorityScore: Int,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE hcps
        SET isDeleted = 1,
            isActive = 0,
            version = version + 1,
            syncStatus = 'PENDING',
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE id = :hcpId
        """
    )
    suspend fun archiveTargetById(
        hcpId: Long,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    )

    @Delete
    suspend fun deleteTarget(hcp: HcpEntity)

    @Query("DELETE FROM hcps WHERE id = :hcpId")
    suspend fun deleteTargetById(hcpId: Long)
}
