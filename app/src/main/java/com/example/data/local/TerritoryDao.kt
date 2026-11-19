package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.AvailabilityWindowEntity
import com.example.data.model.BrickEntity
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ClinicEntity
import com.example.data.model.DailyPlanEntity
import com.example.data.model.HcpEntity
import com.example.data.model.ImportLogEntity
import com.example.data.model.ImportMappingProfileEntity
import com.example.data.model.PlannedStopEntity
import com.example.data.model.RouteCacheEntity
import com.example.data.model.VisitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TerritoryDao {

    // Global Brick Registry
    @Query("SELECT * FROM bricks WHERE isActive = 1 ORDER BY name COLLATE NOCASE ASC")
    fun observeActiveBricks(): Flow<List<BrickEntity>>

    @Query("SELECT * FROM bricks WHERE isActive = 1 ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllActiveBricks(): List<BrickEntity>

    @Query("SELECT * FROM bricks WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun getBrickByNormalizedName(normalizedName: String): BrickEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBrick(brick: BrickEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBricks(bricks: List<BrickEntity>)

    @Query("DELETE FROM bricks")
    suspend fun deleteAllBricks()

    // HCPs
    @Query("SELECT * FROM hcps WHERE isDeleted = 0 ORDER BY priorityScore DESC, name ASC")
    fun observeActiveHcps(): Flow<List<HcpEntity>>

    @Query("SELECT * FROM hcps WHERE isDeleted = 0 ORDER BY priorityScore DESC, name ASC")
    suspend fun getAllActiveHcps(): List<HcpEntity>

    @Query("SELECT * FROM hcps WHERE targetId = :targetId LIMIT 1")
    suspend fun getHcpByTargetId(targetId: String): HcpEntity?

    @Query("SELECT * FROM hcps WHERE id = :id LIMIT 1")
    suspend fun getHcpById(id: Long): HcpEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHcp(hcp: HcpEntity): Long

    @Update
    suspend fun updateHcp(hcp: HcpEntity)

    @Query("DELETE FROM hcps WHERE targetId IN (:targetIds)")
    suspend fun deleteHcpsByTargetIds(targetIds: List<String>)

    @Query("DELETE FROM hcps")
    suspend fun deleteAllHcps()

    @Query("DELETE FROM clinics")
    suspend fun deleteAllClinics()

    @Query("DELETE FROM availability_windows")
    suspend fun deleteAllAvailabilityWindows()

    @Query("DELETE FROM planned_stops")
    suspend fun deleteAllPlannedStops()

    @Query("DELETE FROM daily_plans")
    suspend fun deleteAllDailyPlans()

    // Clinics
    @Query("SELECT * FROM clinics ORDER BY hcpId ASC, clinicIndex ASC")
    fun observeAllClinics(): Flow<List<ClinicEntity>>

    @Query("SELECT * FROM clinics ORDER BY hcpId ASC, clinicIndex ASC")
    suspend fun getAllClinics(): List<ClinicEntity>

    @Query("SELECT * FROM clinics WHERE hcpId = :hcpId ORDER BY clinicIndex ASC")
    suspend fun getClinicsForHcp(hcpId: Long): List<ClinicEntity>

    @Query("SELECT * FROM clinics WHERE id = :clinicId LIMIT 1")
    suspend fun getClinicById(clinicId: Long): ClinicEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClinic(clinic: ClinicEntity): Long

    @Update
    suspend fun updateClinic(clinic: ClinicEntity)

    @Query("DELETE FROM clinics WHERE hcpId = :hcpId")
    suspend fun deleteClinicsForHcp(hcpId: Long)

    // Availability Windows
    @Query("SELECT * FROM availability_windows ORDER BY clinicId ASC, dayOfWeek ASC, startMinuteOfDay ASC")
    fun observeAllAvailabilityWindows(): Flow<List<AvailabilityWindowEntity>>

    @Query("SELECT * FROM availability_windows ORDER BY clinicId ASC, dayOfWeek ASC, startMinuteOfDay ASC")
    suspend fun getAllAvailabilityWindows(): List<AvailabilityWindowEntity>

    @Query("SELECT * FROM availability_windows WHERE clinicId = :clinicId ORDER BY dayOfWeek ASC, startMinuteOfDay ASC")
    suspend fun getWindowsForClinic(clinicId: Long): List<AvailabilityWindowEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAvailabilityWindow(window: AvailabilityWindowEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAvailabilityWindows(windows: List<AvailabilityWindowEntity>)

    @Query("DELETE FROM availability_windows WHERE clinicId = :clinicId")
    suspend fun deleteWindowsForClinic(clinicId: Long)

    @Query("DELETE FROM availability_windows WHERE id = :windowId")
    suspend fun deleteWindowById(windowId: Long)

    // Visits
    @Query("SELECT * FROM visits ORDER BY plannedDateIso DESC, plannedStartMinute DESC")
    fun observeAllVisits(): Flow<List<VisitEntity>>

    @Query("SELECT * FROM visits ORDER BY plannedDateIso DESC, plannedStartMinute DESC")
    suspend fun getAllVisits(): List<VisitEntity>

    @Query("SELECT * FROM visits WHERE hcpId = :hcpId ORDER BY plannedDateIso DESC")
    suspend fun getVisitsForHcp(hcpId: Long): List<VisitEntity>

    @Query("SELECT * FROM visits WHERE plannedDateIso >= :startDateIso AND plannedDateIso <= :endDateIso ORDER BY plannedDateIso ASC")
    suspend fun getVisitsBetweenDates(startDateIso: String, endDateIso: String): List<VisitEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVisit(visit: VisitEntity): Long

    @Update
    suspend fun updateVisit(visit: VisitEntity)

    @Query("DELETE FROM visits WHERE hcpId = :hcpId AND plannedDateIso = :dateIso AND status != 'COMPLETED'")
    suspend fun deleteNonCompletedVisitsForHcpOnDate(hcpId: Long, dateIso: String)

    // Daily Plans & Stops
    @Query("SELECT * FROM daily_plans ORDER BY dateIso ASC, variantCode ASC")
    fun observeAllDailyPlans(): Flow<List<DailyPlanEntity>>

    @Query("SELECT * FROM daily_plans WHERE dateIso = :dateIso ORDER BY variantCode ASC")
    suspend fun getPlansForDate(dateIso: String): List<DailyPlanEntity>

    @Query("SELECT * FROM daily_plans WHERE dateIso >= :startDateIso AND dateIso <= :endDateIso AND isSelected = 1 ORDER BY dateIso ASC")
    suspend fun getSelectedPlansBetweenDates(startDateIso: String, endDateIso: String): List<DailyPlanEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyPlan(plan: DailyPlanEntity): Long

    @Update
    suspend fun updateDailyPlan(plan: DailyPlanEntity)

    @Query("UPDATE daily_plans SET isSelected = (id = :selectedPlanId) WHERE dateIso = :dateIso")
    suspend fun selectPlanVariant(dateIso: String, selectedPlanId: Long)

    @Query("DELETE FROM daily_plans WHERE dateIso = :dateIso")
    suspend fun deletePlansForDate(dateIso: String)

    @Query("SELECT * FROM planned_stops ORDER BY planId ASC, stopOrder ASC")
    fun observeAllPlannedStops(): Flow<List<PlannedStopEntity>>

    @Query("SELECT * FROM planned_stops WHERE planId = :planId ORDER BY stopOrder ASC")
    suspend fun getStopsForPlan(planId: Long): List<PlannedStopEntity>

    @Query("SELECT * FROM planned_stops WHERE planId IN (:planIds) ORDER BY planId ASC, stopOrder ASC")
    suspend fun getStopsForPlans(planIds: List<Long>): List<PlannedStopEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlannedStops(stops: List<PlannedStopEntity>)

    @Update
    suspend fun updatePlannedStop(stop: PlannedStopEntity)

    @Query("DELETE FROM planned_stops WHERE planId = :planId")
    suspend fun deleteStopsForPlan(planId: Long)

    @Query("DELETE FROM planned_stops WHERE id = :stopId")
    suspend fun deleteStopById(stopId: Long)

    // Import Logs & Mapping Profiles
    @Query("SELECT * FROM import_logs ORDER BY importedAtEpochMs DESC")
    fun observeImportLogs(): Flow<List<ImportLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImportLog(log: ImportLogEntity): Long

    @Update
    suspend fun updateImportLog(log: ImportLogEntity)

    @Query("SELECT * FROM import_mapping_profiles ORDER BY updatedAtEpochMs DESC")
    fun observeImportMappingProfiles(): Flow<List<ImportMappingProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImportMappingProfile(profile: ImportMappingProfileEntity): Long

    // Route Cache
    @Query("SELECT * FROM route_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun getRouteCache(key: String): RouteCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRouteCache(entry: RouteCacheEntity)

    // Multi-turn Gemini Chat History
    @Query("SELECT * FROM chat_messages ORDER BY timestampEpochMs ASC, id ASC")
    fun observeChatMessages(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY timestampEpochMs ASC, id ASC")
    suspend fun getAllChatMessages(): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessage(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages")
    suspend fun clearChatMessages()
}
