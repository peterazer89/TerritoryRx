package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.WorkSettings
import com.example.data.repository.TerritoryRepository
import com.example.domain.importer.ExcelTerritoryImporter
import com.example.domain.importer.ImportMode
import com.example.domain.routing.RoutingProvider
import com.example.domain.routing.TravelEstimate
import com.example.ui.TerritoryViewModel
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression & verification test suite for the "Optimization Failed — Flow exception transparency is violated"
 * bug in TerritoryRepository.workSettingsFlow and the complete AI Route Plan execution path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RouteOptimizationFlowTransparencyTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: TerritoryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TerritoryRepository(
            context = context,
            dao = database.territoryDao(),
            hcpDao = database.hcpDao(),
            cloudSyncRepository = null
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun workSettingsFlow_firstAndTakeOnePreserveFlowExceptionTransparency() = runBlocking {
        // Calling first() repeatedly triggers Flow's internal AbortFlowException after the first emit.
        // Previously, try { emit(...) } catch (_: Throwable) { appSettingsStateFlow.collect { emit(it) } }
        // caught AbortFlowException and emitted again, crashing with "Flow exception transparency is violated".
        val firstRead = repository.workSettingsFlow.first()
        val secondRead = repository.workSettingsFlow.first()
        val taken = repository.workSettingsFlow.take(1).toList()

        assertTrue(firstRead.dailyHcpTarget > 0)
        assertEquals(firstRead.dailyHcpTarget, secondRead.dailyHcpTarget)
        assertEquals(1, taken.size)

        // Persist updated settings and verify first() succeeds cleanly after DataStore edit as well
        repository.updateWorkSettings(
            firstRead.copy(
                dailyHcpTarget = 6,
                homeAddress = "Smouha, Alexandria, Egypt",
                homeLatitude = 31.2156,
                homeLongitude = 29.9553
            )
        )
        val afterUpdate = repository.workSettingsFlow.first()
        assertEquals(6, afterUpdate.dailyHcpTarget)
        assertEquals("Smouha, Alexandria, Egypt", afterUpdate.homeAddress)
    }

    @Test
    fun workSettingsFlow_downstreamExceptionPropagatesOriginalCauseWithoutTransparencyViolation() = runBlocking {
        val sentinelException = IllegalArgumentException("Downstream collector validation error")
        try {
            repository.workSettingsFlow.collect {
                throw sentinelException
            }
            fail("Expected downstream exception to propagate")
        } catch (caught: Throwable) {
            assertTrue(
                "Original downstream exception must propagate directly, got ${caught::class.java.name}: ${caught.message}",
                caught === sentinelException
            )
            assertFalse(
                "Must never throw Flow exception transparency violation",
                caught.message.orEmpty().contains("Flow exception transparency is violated", ignoreCase = true)
            )
        }
    }

    @Test
    fun aiRoutePlan_generateAndSaveDailyPlans_succeedsEndToEndWithPersistedStops() = runBlocking {
        val csv = """
            target_id,HCP Name,Specialty,zone 1,clinic 1 GPS,clinic 1 times,zone 2,clinic 2 GPS,clinic 2 times,Frequency
            HCP-RT1,Dr. Ahmed Farid,Cardiologist,Smouha,"https://maps.google.com/?q=31.2156,29.9553","Saturday, Monday 2:00 PM - 6:00 PM",,,,8
            HCP-RT2,Dr. Abo Bakr Mohamed,Cardiologist,Smouha,"https://maps.google.com/?q=31.2190,29.9580","Monday 2:00 PM - 7:00 PM",Roushdy,"https://maps.google.com/?q=31.2310,29.9520","Wednesday 6:00 PM - 9:00 PM",8
            HCP-RT3,Dr. Mona Hassan,Internal Medicine,Roushdy,"https://maps.google.com/?q=31.2325,29.9535","Monday 3:00 PM - 8:00 PM",,,,6
        """.trimIndent()

        val sheets = ExcelTerritoryImporter.parseCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        val report = ExcelTerritoryImporter.buildPreviewReport("TerritoryAlexandria.csv", sheets)
        repository.executeImport(report, ImportMode.REPLACE_ALL)

        repository.updateWorkSettings(
            WorkSettings(
                dailyHcpTarget = 3,
                homeAddress = "Alexandria Center",
                homeLatitude = 31.2100,
                homeLongitude = 29.9480,
                workingDaysIso = setOf(7, 1, 2, 3, 4),
                weekendDaysIso = setOf(5, 6),
                firstDayOfWeekIso = 7
            )
        )

        // Monday 2026-11-16 (matches all 3 HCPs' Monday clinic windows)
        val outcome = repository.generateAndSaveDailyPlans(
            dateIso = "2026-11-16",
            targetOverride = 3,
            shiftStartMinute = 13 * 60,
            shiftEndMinute = 21 * 60
        )

        assertTrue("Expected at least one generated daily route alternative", outcome.alternatives.isNotEmpty())
        val bestPlan = outcome.alternatives.first()
        assertEquals(3, bestPlan.stops.size)
        assertEquals(3, bestPlan.stops.map { it.hcpId }.distinct().size)

        // Verify persisted plans in Room match the returned plan
        val storedPlans = database.territoryDao().getPlansForDate("2026-11-16")
        assertTrue(storedPlans.isNotEmpty())
        val selectedStored = storedPlans.first { it.isSelected }
        val storedStops = database.territoryDao().getStopsForPlan(selectedStored.id)
        assertEquals(3, storedStops.size)

        // Verify weekly schedule generation also succeeds cleanly without Flow violations
        val weekly = repository.generateWeeklySchedule("2026-11-16")
        assertTrue(weekly.dayPlans.isNotEmpty())
        val allWeeklyHcpIds = weekly.dayPlans.flatMap { it.selectedPlan.stops }.map { it.hcpId }
        assertEquals("Each HCP must be scheduled at most once per week", allWeeklyHcpIds.distinct().size, allWeeklyHcpIds.size)
    }

    @Test
    fun aiRoutePlan_failureScenarios_surfaceOriginalRootCauseAndMeaningfulMessage() = runBlocking {
        // 1. Infeasible day / unmatched brick filter returns clean diagnostics without crashing
        val csv = """
            target_id,HCP Name,Specialty,zone 1,clinic 1 GPS,clinic 1 times,Frequency
            HCP-F1,Dr. Monday Only,Cardiology,Smouha,"https://maps.google.com/?q=31.2156,29.9553","Monday 2:00 PM - 5:00 PM",8
        """.trimIndent()
        val sheets = ExcelTerritoryImporter.parseCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        val report = ExcelTerritoryImporter.buildPreviewReport("SingleDay.csv", sheets)
        repository.executeImport(report, ImportMode.REPLACE_ALL)

        val infeasibleOutcome = repository.generateAndSaveDailyPlans(
            dateIso = "2026-11-16",
            allowedZones = setOf("NonExistentBrick"),
            shiftStartMinute = 13 * 60,
            shiftEndMinute = 21 * 60
        )
        assertTrue(
            "No route stops should match an unrelated brick filter",
            infeasibleOutcome.alternatives.all { it.stops.isEmpty() }
        )
        assertEquals(
            "INFEASIBLE",
            infeasibleOutcome.alternatives.firstOrNull()?.plan?.feasibilityStatus ?: "INFEASIBLE"
        )
        assertTrue("Diagnostics should explain why no stops were scheduled", infeasibleOutcome.infeasibleReasons.isNotEmpty())

        // 2. Real upstream routing failure propagates original exception (never Flow exception transparency error)
        val failingRoutingProvider = object : RoutingProvider {
            override suspend fun estimateTravel(
                originLat: Double,
                originLng: Double,
                destLat: Double,
                destLng: Double,
                departureMinuteOfDay: Int,
                safetyBufferPercent: Int,
                allowNetworkCall: Boolean
            ): TravelEstimate {
                throw IllegalStateException("Routing matrix service returned malformed leg duration")
            }
        }
        val failingRepo = TerritoryRepository(
            context = context,
            dao = database.territoryDao(),
            hcpDao = database.hcpDao(),
            cloudSyncRepository = null,
            routingProvider = failingRoutingProvider
        )

        try {
            failingRepo.generateAndSaveDailyPlans(
                dateIso = "2026-11-16",
                shiftStartMinute = 13 * 60,
                shiftEndMinute = 21 * 60
            )
            fail("Expected routing exception to propagate")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("Routing matrix service returned malformed leg duration"))
            assertFalse(e.message.orEmpty().contains("Flow exception transparency is violated", ignoreCase = true))

            val formatted = TerritoryViewModel.formatOptimizationFailureMessage(e)
            assertTrue(
                "Formatted error should include root cause explanation, got: $formatted",
                formatted.contains("Routing matrix service returned malformed leg duration")
            )
        }

        // 3. Verify nested IOException cause unwrapping produces a meaningful storage/network message
        val wrappedIo = RuntimeException("Plan load failed", IOException("DataStore file corrupted"))
        val ioMessage = TerritoryViewModel.formatOptimizationFailureMessage(wrappedIo)
        assertTrue(ioMessage.contains("Storage or network error"))
        assertTrue(ioMessage.contains("DataStore file corrupted"))
    }
}
