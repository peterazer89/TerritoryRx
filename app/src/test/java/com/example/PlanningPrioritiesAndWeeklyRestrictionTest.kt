package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.AvailabilityWindowEntity
import com.example.data.model.CalendarWeekCalculator
import com.example.data.model.ClinicEntity
import com.example.data.model.ClinicWithAvailability
import com.example.data.model.CoverageRiskLevel
import com.example.data.model.HcpCoverageMetrics
import com.example.data.model.HcpEntity
import com.example.data.model.HcpWithDetails
import com.example.data.model.PlanningPriorityWeights
import com.example.data.model.VisitEntity
import com.example.data.model.WEEKLY_HCP_RESTRICTION_MESSAGE
import com.example.data.model.WorkSettings
import com.example.data.repository.TerritoryRepository
import com.example.domain.optimizer.OptimizationRequest
import com.example.domain.optimizer.PlanValidator
import com.example.domain.optimizer.RouteOptimizerEngine
import com.example.domain.routing.DefaultRoutingService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanningPrioritiesAndWeeklyRestrictionTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: TerritoryRepository
    private lateinit var optimizer: RouteOptimizerEngine

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
        optimizer = RouteOptimizerEngine(DefaultRoutingService())
    }

    @After
    fun tearDown() {
        database.close()
    }

    // =========================================================================
    // 9.1 Priority Weights Tests
    // =========================================================================

    @Test
    fun priorityWeights_defaultsAndRedistributionMaintainExactly100Percent() {
        val defaultSettings = WorkSettings()
        assertEquals(30, defaultSettings.priorityWeightSpecialtyPercent)
        assertEquals(40, defaultSettings.priorityWeightRemainingVisitsPercent)
        assertEquals(30, defaultSettings.priorityWeightTravelDistancePercent)
        assertEquals(
            100,
            defaultSettings.priorityWeightSpecialtyPercent +
                defaultSettings.priorityWeightRemainingVisitsPercent +
                defaultSettings.priorityWeightTravelDistancePercent
        )

        // Adjust Specialty to 60% -> remaining 40% distributed between Remaining (40) and Distance (30)
        val adjusted1 = PlanningPriorityWeights.adjustSpecialtyWeight(defaultSettings, 60)
        assertEquals(60, adjusted1.priorityWeightSpecialtyPercent)
        assertEquals(
            100,
            adjusted1.priorityWeightSpecialtyPercent +
                adjusted1.priorityWeightRemainingVisitsPercent +
                adjusted1.priorityWeightTravelDistancePercent
        )
        assertTrue(adjusted1.priorityWeightRemainingVisitsPercent > adjusted1.priorityWeightTravelDistancePercent)

        // Edge case: set Specialty to 100% -> other two become 0%
        val allSpecialty = PlanningPriorityWeights.adjustSpecialtyWeight(defaultSettings, 100)
        assertEquals(100, allSpecialty.priorityWeightSpecialtyPercent)
        assertEquals(0, allSpecialty.priorityWeightRemainingVisitsPercent)
        assertEquals(0, allSpecialty.priorityWeightTravelDistancePercent)

        // Edge case: when both remaining factors are 0%, reducing Specialty from 100% to 40% distributes remaining 60% equally (30% / 30%)
        val fromZeroPeers = PlanningPriorityWeights.adjustSpecialtyWeight(allSpecialty, 40)
        assertEquals(40, fromZeroPeers.priorityWeightSpecialtyPercent)
        assertEquals(30, fromZeroPeers.priorityWeightRemainingVisitsPercent)
        assertEquals(30, fromZeroPeers.priorityWeightTravelDistancePercent)

        // Restore defaults returns 30%, 40%, 30%
        val restored = PlanningPriorityWeights.restoreDefaults(fromZeroPeers)
        assertEquals(30, restored.priorityWeightSpecialtyPercent)
        assertEquals(40, restored.priorityWeightRemainingVisitsPercent)
        assertEquals(30, restored.priorityWeightTravelDistancePercent)
    }

    @Test
    fun priorityWeights_persistInRepositoryAndAffectPlanGeneration() = runBlocking {
        // Persist custom weights in DataStore via TerritoryRepository
        val customSettings = PlanningPriorityWeights.applyWeights(
            settings = WorkSettings(dailyHcpTarget = 1),
            specialtyPct = 85,
            remainingVisitsPct = 10,
            travelDistancePct = 5
        )
        repository.updateWorkSettings(customSettings)
        val loaded = repository.workSettingsFlow.first()
        assertEquals(85, loaded.priorityWeightSpecialtyPercent)
        assertEquals(10, loaded.priorityWeightRemainingVisitsPercent)
        assertEquals(5, loaded.priorityWeightTravelDistancePercent)

        val homeLat = 31.2001
        val homeLng = 29.9187

        // Create 3 candidate HCPs with identical clinic hours on Monday (2026-11-16, dow=1)
        // HCP 1: Highest Specialty Priority (98), low remaining visits (1), medium distance
        // HCP 2: Highest Remaining Visits (10 remaining), low specialty priority (40), medium distance
        // HCP 3: Closest to Home (0.05 km), low specialty priority (40), low remaining visits (1)
        val hcpSpecialtyStar = buildTestHcp(
            id = 1L,
            targetId = "HCP_SPEC",
            name = "Dr Specialty Star",
            specialty = "Cardiology",
            priorityScore = 98,
            quarterlyTarget = 4,
            remainingVisits = 1,
            clinicId = 101L,
            lat = 31.2250,
            lng = 29.9550,
            dow = 1
        )
        val hcpRemainingStar = buildTestHcp(
            id = 2L,
            targetId = "HCP_REM",
            name = "Dr Remaining Visits Star",
            specialty = "General Practice",
            priorityScore = 40,
            quarterlyTarget = 10,
            remainingVisits = 10,
            clinicId = 102L,
            lat = 31.2260,
            lng = 29.9560,
            dow = 1
        )
        val hcpCloseDistanceStar = buildTestHcp(
            id = 3L,
            targetId = "HCP_DIST",
            name = "Dr Closest Clinic",
            specialty = "General Practice",
            priorityScore = 40,
            quarterlyTarget = 4,
            remainingVisits = 1,
            clinicId = 103L,
            lat = 31.2005,
            lng = 29.9190,
            dow = 1
        )
        val candidates = listOf(hcpSpecialtyStar, hcpRemainingStar, hcpCloseDistanceStar)

        // Case A: 100% Specialty Weight (dailyHcpTarget = 1) -> Plan A selects Dr Specialty Star
        val outcomeSpecialty = optimizer.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-16",
                hcps = candidates,
                settings = WorkSettings(
                    dailyHcpTarget = 1,
                    homeLatitude = homeLat,
                    homeLongitude = homeLng,
                    priorityWeightSpecialtyPercent = 100,
                    priorityWeightRemainingVisitsPercent = 0,
                    priorityWeightTravelDistancePercent = 0
                ),
                targetOverride = 1
            )
        )
        assertEquals(1L, outcomeSpecialty.alternatives.first().stops.first().hcpId)

        // Case B: 100% Remaining Visits Weight (dailyHcpTarget = 1) -> Plan A selects Dr Remaining Visits Star
        val outcomeRemaining = optimizer.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-16",
                hcps = candidates,
                settings = WorkSettings(
                    dailyHcpTarget = 1,
                    homeLatitude = homeLat,
                    homeLongitude = homeLng,
                    priorityWeightSpecialtyPercent = 0,
                    priorityWeightRemainingVisitsPercent = 100,
                    priorityWeightTravelDistancePercent = 0
                ),
                targetOverride = 1
            )
        )
        assertEquals(2L, outcomeRemaining.alternatives.first().stops.first().hcpId)

        // Case C: 100% Travel Distance Weight (dailyHcpTarget = 1) -> Plan A selects Dr Closest Clinic
        val outcomeDistance = optimizer.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-16",
                hcps = candidates,
                settings = WorkSettings(
                    dailyHcpTarget = 1,
                    homeLatitude = homeLat,
                    homeLongitude = homeLng,
                    priorityWeightSpecialtyPercent = 0,
                    priorityWeightRemainingVisitsPercent = 0,
                    priorityWeightTravelDistancePercent = 100
                ),
                targetOverride = 1
            )
        )
        assertEquals(3L, outcomeDistance.alternatives.first().stops.first().hcpId)
    }

    // =========================================================================
    // 9.2 Weekly Visit Restriction Tests
    // =========================================================================

    @Test
    fun calendarWeekCalculator_respectsConfiguredFirstDayOfWeek() {
        // Suppose firstDayOfWeekIso = 7 (Sunday).
        // Sunday 2026-11-15 through Saturday 2026-11-21 is one calendar week.
        val boundsSun = CalendarWeekCalculator.boundsForDate("2026-11-18", firstDayOfWeekIso = 7)
        assertEquals("2026-11-15", boundsSun.weekStartIso)
        assertEquals("2026-11-21", boundsSun.weekEndIso)
        assertTrue(CalendarWeekCalculator.isSameCalendarWeek("2026-11-15", "2026-11-18", firstDayOfWeekIso = 7))
        assertFalse(CalendarWeekCalculator.isSameCalendarWeek("2026-11-14", "2026-11-15", firstDayOfWeekIso = 7))

        // Suppose firstDayOfWeekIso = 6 (Saturday).
        // Saturday 2026-11-14 through Friday 2026-11-20 is one calendar week.
        val boundsSat = CalendarWeekCalculator.boundsForDate("2026-11-15", firstDayOfWeekIso = 6)
        assertEquals("2026-11-14", boundsSat.weekStartIso)
        assertEquals("2026-11-20", boundsSat.weekEndIso)
        assertTrue(CalendarWeekCalculator.isSameCalendarWeek("2026-11-14", "2026-11-15", firstDayOfWeekIso = 6))

        // Suppose firstDayOfWeekIso = 1 (Monday).
        // Monday 2026-11-16 through Sunday 2026-11-22 is one calendar week.
        val boundsMon = CalendarWeekCalculator.boundsForDate("2026-11-16", firstDayOfWeekIso = 1)
        assertEquals("2026-11-16", boundsMon.weekStartIso)
        assertEquals("2026-11-22", boundsMon.weekEndIso)
        assertFalse(CalendarWeekCalculator.isSameCalendarWeek("2026-11-15", "2026-11-16", firstDayOfWeekIso = 1))
    }

    @Test
    fun weeklyRestriction_blocksCompletedScheduledAndMultiClinicVisitsInSameWeek_andResetsNextWeek() = runBlocking {
        // Configure week starting Sunday (7)
        repository.updateWorkSettings(
            WorkSettings(
                dailyHcpTarget = 4,
                firstDayOfWeekIso = 7,
                weekendDaysIso = setOf(5, 6),
                workingDaysIso = setOf(7, 1, 2, 3, 4)
            )
        )

        // Insert Multi-Clinic HCP 1 (high priority, 8 remaining visits, Clinic 1 + Clinic 2) and HCP 2
        val dao = database.territoryDao()
        val hcp1Id = dao.insertHcp(
            HcpEntity(
                targetId = "T_MULTI_1",
                name = "Dr Multi Clinic",
                specialty = "Cardiology",
                quarterlyTarget = 8,
                priorityScore = 99,
                territoryZone = "Smouha"
            )
        )
        val c1Id = dao.insertClinic(
            ClinicEntity(
                hcpId = hcp1Id,
                clinicIndex = 1,
                label = "Clinic 1 (Smouha)",
                zone = "Smouha",
                latitude = 31.2156,
                longitude = 29.9553,
                rawScheduleText = "Every day 2 PM - 8 PM",
                scheduleParseStatus = "CONFIRMED",
                userConfirmedSchedule = true
            )
        )
        val c2Id = dao.insertClinic(
            ClinicEntity(
                hcpId = hcp1Id,
                clinicIndex = 2,
                label = "Clinic 2 (Roushdy)",
                zone = "Roushdy",
                latitude = 31.2290,
                longitude = 29.9520,
                rawScheduleText = "Every day 2 PM - 8 PM",
                scheduleParseStatus = "CONFIRMED",
                userConfirmedSchedule = true
            )
        )
        for (dow in 1..7) {
            dao.insertAvailabilityWindows(
                listOf(
                    AvailabilityWindowEntity(
                        hcpId = hcp1Id,
                        clinicId = c1Id,
                        dayOfWeek = dow,
                        startMinuteOfDay = 14 * 60,
                        endMinuteOfDay = 20 * 60,
                        originalExcelText = "2 PM - 8 PM",
                        isConfirmed = true
                    ),
                    AvailabilityWindowEntity(
                        hcpId = hcp1Id,
                        clinicId = c2Id,
                        dayOfWeek = dow,
                        startMinuteOfDay = 14 * 60,
                        endMinuteOfDay = 20 * 60,
                        originalExcelText = "2 PM - 8 PM",
                        isConfirmed = true
                    )
                )
            )
        }

        val hcp2Id = dao.insertHcp(
            HcpEntity(
                targetId = "T_ELIGIBLE_2",
                name = "Dr Eligible Peer",
                specialty = "Internal Medicine",
                quarterlyTarget = 6,
                priorityScore = 80,
                territoryZone = "Smouha"
            )
        )
        val hcp2ClinicId = dao.insertClinic(
            ClinicEntity(
                hcpId = hcp2Id,
                clinicIndex = 1,
                label = "Clinic 1 (Smouha)",
                zone = "Smouha",
                latitude = 31.2165,
                longitude = 29.9560,
                rawScheduleText = "Every day 2 PM - 8 PM",
                scheduleParseStatus = "CONFIRMED",
                userConfirmedSchedule = true
            )
        )
        for (dow in 1..7) {
            dao.insertAvailabilityWindows(
                listOf(
                    AvailabilityWindowEntity(
                        hcpId = hcp2Id,
                        clinicId = hcp2ClinicId,
                        dayOfWeek = dow,
                        startMinuteOfDay = 14 * 60,
                        endMinuteOfDay = 20 * 60,
                        originalExcelText = "2 PM - 8 PM",
                        isConfirmed = true
                    )
                )
            )
        }

        // 1. Record a COMPLETED visit for Dr Multi Clinic at Clinic 1 on Sunday 2026-11-15
        dao.insertVisit(
            VisitEntity(
                hcpId = hcp1Id,
                hcpTargetId = "T_MULTI_1",
                clinicId = c1Id,
                clinicLabel = "Clinic 1 (Smouha)",
                plannedDateIso = "2026-11-15",
                plannedStartMinute = 15 * 60,
                plannedEndMinute = 15 * 60 + 20,
                status = "COMPLETED",
                qualifiesForCoverage = true,
                quarterLabel = "2026-Q4",
                recordSource = "APP_EXECUTION"
            )
        )

        // 2. Generate daily plan for Wednesday 2026-11-18 (same calendar week: Sun Nov 15..Sat Nov 21)
        // Even though Dr Multi Clinic has priorityScore=99, 7 remaining visits, and Clinic 2 available,
        // neither Clinic 1 nor Clinic 2 may be scheduled!
        val wedOutcome = repository.generateAndSaveDailyPlans(dateIso = "2026-11-18")
        val wedActiveStops = wedOutcome.alternatives.first().stops
        assertTrue(wedActiveStops.none { it.hcpId == hcp1Id })
        assertTrue(wedActiveStops.any { it.hcpId == hcp2Id })
        assertEquals(1, wedOutcome.weeklyRestrictedExcluded)

        // 3. Attempting to manually add Dr Multi Clinic (even at Clinic 2) on Wednesday 2026-11-18 must fail with WEEKLY_HCP_RESTRICTION_MESSAGE
        val hcp1Details = repository.hcpsWithDetailsFlow.first().first { it.hcp.id == hcp1Id }
        val clinic2Entity = hcp1Details.clinics.first { it.clinic.id == c2Id }.clinic
        var caughtMessage: String? = null
        try {
            repository.addHcpStopToPlan(
                dateIso = "2026-11-18",
                hcpWithDetails = hcp1Details,
                preferredClinic = clinic2Entity
            )
        } catch (e: IllegalStateException) {
            caughtMessage = e.message
        }
        assertEquals(WEEKLY_HCP_RESTRICTION_MESSAGE, caughtMessage)

        // 4. Next calendar week (Sunday 2026-11-22) resets weekly eligibility -> Dr Multi Clinic is eligible again!
        val nextWeekOutcome = repository.generateAndSaveDailyPlans(dateIso = "2026-11-22")
        val nextWeekStops = nextWeekOutcome.alternatives.first().stops
        assertTrue(nextWeekStops.any { it.hcpId == hcp1Id })
    }

    @Test
    fun weeklyRestriction_cancelledVisitFreesHcpAndRescheduledVisitReplacesWithoutDuplicate() = runBlocking {
        repository.updateWorkSettings(
            WorkSettings(
                dailyHcpTarget = 4,
                firstDayOfWeekIso = 7
            )
        )
        val dao = database.territoryDao()
        val hcpId = dao.insertHcp(
            HcpEntity(
                targetId = "T_CANCEL_TEST",
                name = "Dr Cancel And Reschedule",
                specialty = "Pediatrics",
                quarterlyTarget = 6,
                priorityScore = 90,
                territoryZone = "Smouha"
            )
        )
        val clinicId = dao.insertClinic(
            ClinicEntity(
                hcpId = hcpId,
                clinicIndex = 1,
                label = "Clinic 1 (Smouha)",
                zone = "Smouha",
                latitude = 31.2156,
                longitude = 29.9553,
                rawScheduleText = "Every day 2 PM - 8 PM",
                scheduleParseStatus = "CONFIRMED",
                userConfirmedSchedule = true
            )
        )
        for (dow in 1..7) {
            dao.insertAvailabilityWindows(
                listOf(
                    AvailabilityWindowEntity(
                        hcpId = hcpId,
                        clinicId = clinicId,
                        dayOfWeek = dow,
                        startMinuteOfDay = 14 * 60,
                        endMinuteOfDay = 20 * 60,
                        originalExcelText = "2 PM - 8 PM",
                        isConfirmed = true
                    )
                )
            )
        }

        // Schedule on Monday 2026-11-16
        val monOutcome = repository.generateAndSaveDailyPlans(dateIso = "2026-11-16")
        val monPlan = monOutcome.alternatives.first()
        val monStop = monPlan.stops.firstOrNull { it.hcpId == hcpId }
        assertNotNull(monStop)

        // While Monday's stop is active (PLANNED), Wednesday 2026-11-18 sees it as scheduled in the same week
        val blockedBeforeCancel = repository.getWeeklyBlockedHcpsForDate("2026-11-18")
        assertTrue(blockedBeforeCancel.containsKey(hcpId))

        // Reschedule Monday's stop to Tuesday 2026-11-17 -> moves stop without duplicate weekly entry
        repository.rescheduleStopToDate(
            planWithStops = monPlan,
            stop = monStop!!,
            targetDateIso = "2026-11-17"
        )
        val tuePlans = dao.getPlansForDate("2026-11-17")
        assertTrue(tuePlans.isNotEmpty())
        val tueStops = dao.getStopsForPlan(tuePlans.first().id)
        assertEquals(1, tueStops.count { it.hcpId == hcpId })

        // Now mark Tuesday's stop as CANCELLED -> frees HCP to be scheduled on Thursday 2026-11-19
        val tuePlanWithStops = com.example.data.model.DailyPlanWithStops(tuePlans.first(), tueStops)
        repository.recordStopExecution(
            planWithStops = tuePlanWithStops,
            stop = tueStops.first { it.hcpId == hcpId },
            newStatus = "CANCELLED",
            reoptimizeRemaining = true
        )

        val blockedAfterCancel = repository.getWeeklyBlockedHcpsForDate("2026-11-19")
        assertFalse(blockedAfterCancel.containsKey(hcpId))

        val thuOutcome = repository.generateAndSaveDailyPlans("2026-11-19")
        assertTrue(thuOutcome.alternatives.first().stops.any { it.hcpId == hcpId })
    }

    // =========================================================================
    // 9.3 Travel Optimization & 2-Opt Improvement Tests
    // =========================================================================

    @Test
    fun travelOptimization_avoidsZigZagAndMinimizesConsecutiveClinicDistance() = runBlocking {
        // Home is at (31.2001, 29.9187)
        // Create 4 clinics along a straight West-to-East corridor, all open 13:00..21:00 on Monday (dow=1):
        // StopNear1 (31.205, 29.925), StopNear2 (31.208, 29.930), StopFar1 (31.250, 29.985), StopFar2 (31.253, 29.990)
        val h1 = buildTestHcp(1L, "H1", "Dr Near A", "Cardiology", 80, 6, 4, 11L, 31.2050, 29.9250, 1, zone = "Brick A")
        val h2 = buildTestHcp(2L, "H2", "Dr Far B1", "Cardiology", 80, 6, 4, 12L, 31.2500, 29.9850, 1, zone = "Brick B")
        val h3 = buildTestHcp(3L, "H3", "Dr Near A2", "Cardiology", 80, 6, 4, 13L, 31.2080, 29.9300, 1, zone = "Brick A")
        val h4 = buildTestHcp(4L, "H4", "Dr Far B2", "Cardiology", 80, 6, 4, 14L, 31.2530, 29.9900, 1, zone = "Brick B")

        val outcome = optimizer.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-16",
                hcps = listOf(h1, h2, h3, h4),
                settings = WorkSettings(
                    dailyHcpTarget = 4,
                    homeLatitude = 31.2001,
                    homeLongitude = 29.9187,
                    priorityWeightSpecialtyPercent = 20,
                    priorityWeightRemainingVisitsPercent = 20,
                    priorityWeightTravelDistancePercent = 60,
                    returnHomeRequired = false
                ),
                targetOverride = 4
            )
        )

        val planAStops = outcome.alternatives.first().stops
        assertEquals(4, planAStops.size)

        // Verify Brick A stops are visited consecutively and Brick B stops are visited consecutively (no Brick A -> Brick B -> Brick A zig-zag)
        val zonesOrder = planAStops.map { it.zone }
        val firstBrickBIdx = zonesOrder.indexOfFirst { it == "Brick B" }
        val lastBrickAIdx = zonesOrder.indexOfLast { it == "Brick A" }
        assertTrue(
            "Expected Brick A clinics to be grouped before Brick B clinics without zig-zagging, got $zonesOrder",
            lastBrickAIdx < firstBrickBIdx || zonesOrder.indexOfFirst { it == "Brick A" } > zonesOrder.indexOfLast { it == "Brick B" }
        )
    }

    @Test
    fun weeklyOptimizer_neverSchedulesSameHcpMoreThanOnceInWeek() = runBlocking {
        val hcps = (1L..10L).map { id ->
            buildTestHcp(
                id = id,
                targetId = "T_$id",
                name = "Dr Weekly $id",
                specialty = "Cardiology",
                priorityScore = 90,
                quarterlyTarget = 12, // High target (12 visits/quarter)
                remainingVisits = 12,
                clinicId = id * 100L,
                lat = 31.2100 + id * 0.002,
                lng = 29.9400 + id * 0.002,
                dow = 0 // all days 1..7 added below
            )
        }

        val weeklyResult = optimizer.optimizeWeek(
            weekStartDateIso = "2026-11-15",
            hcps = hcps,
            settings = WorkSettings(
                dailyHcpTarget = 4,
                homeLatitude = 31.2001,
                homeLongitude = 29.9187,
                firstDayOfWeekIso = 7,
                workingDaysIso = setOf(7, 1, 2, 3, 4),
                weekendDaysIso = setOf(5, 6)
            )
        )

        val allScheduledHcpIds = weeklyResult.dayPlans.flatMap { day ->
            day.selectedPlan.stops.map { it.hcpId }
        }
        // Every HCP scheduled across the entire 5-day workweek must be strictly unique (at most 1 visit per week)
        assertEquals(allScheduledHcpIds.distinct().size, allScheduledHcpIds.size)

        // Also validate each day's plan via PlanValidator
        val seenSoFar = mutableSetOf<Long>()
        for (day in weeklyResult.dayPlans) {
            val report = PlanValidator.validate(
                planWithStops = day.selectedPlan,
                settings = WorkSettings(
                    homeLatitude = 31.2001,
                    homeLongitude = 29.9187
                ),
                returnHomeTravelMinutes = 10,
                alreadyVisitedOrScheduledInWeekHcpIds = seenSoFar
            )
            assertTrue("Expected valid daily plan on ${day.dateIso}: ${report.summary}", report.isValid)
            seenSoFar.addAll(day.selectedPlan.stops.map { it.hcpId })
        }
    }

    // =========================================================================
    // Section 10: Preferred Specialties Selection, Ranking & Strict Eligibility Tests
    // =========================================================================

    @Test
    fun preferredSpecialties_multiSelectionReorderingAndPersistenceWorkCorrectly() = runBlocking {
        // 1. Selecting multiple specialties & toggling
        var selected = emptyList<String>()
        selected = com.example.data.model.SpecialtyNormalizer.toggleSpecialtySelection(selected, "Cardiology")
        selected = com.example.data.model.SpecialtyNormalizer.toggleSpecialtySelection(selected, "Internal Medicine")
        selected = com.example.data.model.SpecialtyNormalizer.toggleSpecialtySelection(selected, "Neurology")
        assertEquals(listOf("Cardiology", "Internal Medicine", "Neurology"), selected)

        // 2. Reordering specialties updates their ranking and normalized rank score
        val reordered = com.example.data.model.SpecialtyNormalizer.moveSpecialtyRank(selected, fromIndex = 2, toIndex = 0)
        assertEquals(listOf("Neurology", "Cardiology", "Internal Medicine"), reordered)
        assertEquals(1, com.example.data.model.SpecialtyNormalizer.rankOfSpecialty("Neurology", reordered))
        assertEquals(2, com.example.data.model.SpecialtyNormalizer.rankOfSpecialty("Cardiology", reordered))
        assertEquals(3, com.example.data.model.SpecialtyNormalizer.rankOfSpecialty("Internal Medicine", reordered))

        val scoreRank1 = com.example.data.model.SpecialtyNormalizer.computeNormalizedSpecialtyRankScore("Neurology", reordered)
        val scoreRank2 = com.example.data.model.SpecialtyNormalizer.computeNormalizedSpecialtyRankScore("Cardiology", reordered)
        val scoreRank3 = com.example.data.model.SpecialtyNormalizer.computeNormalizedSpecialtyRankScore("Internal Medicine", reordered)
        val scoreUnselected = com.example.data.model.SpecialtyNormalizer.computeNormalizedSpecialtyRankScore("Dermatology", reordered)
        assertTrue(scoreRank1 > scoreRank2)
        assertTrue(scoreRank2 > scoreRank3)
        assertTrue(scoreRank3 > 0.0)
        assertEquals(0.0, scoreUnselected, 0.0001)

        // 3. Persist selections and rankings in DataStore and verify they survive reload
        repository.updateWorkSettings(
            WorkSettings(
                preferredSpecialtiesRanked = reordered,
                priorityWeightSpecialtyPercent = 45,
                priorityWeightRemainingVisitsPercent = 30,
                priorityWeightTravelDistancePercent = 25
            )
        )
        val loaded = repository.workSettingsFlow.first()
        assertEquals(reordered, loaded.preferredSpecialtiesRanked)
    }

    @Test
    fun preferredSpecialties_strictEligibilityExcludesUnselectedMultiClinicAndMissingSpecialties() = runBlocking {
        // Select ONLY Cardiology (#1) and Internal Medicine (#2)
        val preferred = listOf("Cardiology", "Internal Medicine")

        // HCP 1: Cardiology (Selected, Rank 1)
        val hcpCardio = buildTestHcp(
            id = 10L,
            targetId = "T_CARDIO",
            name = "Dr Cardio Eligible",
            specialty = "Cardiology",
            priorityScore = 80,
            quarterlyTarget = 6,
            remainingVisits = 4,
            clinicId = 1010L,
            lat = 31.2150,
            lng = 29.9450,
            dow = 1
        )
        // HCP 2: Multi-clinic Dermatology (Unselected specialty, even with 12 remaining visits and closest GPS!)
        val c1Derm = ClinicEntity(
            id = 2010L,
            hcpId = 20L,
            clinicIndex = 1,
            label = "Clinic 1 (Smouha)",
            zone = "Smouha",
            latitude = 31.2002,
            longitude = 29.9188,
            geocodingStatus = "VERIFIED",
            rawScheduleText = "2:00 PM - 8:00 PM",
            scheduleParseStatus = "CONFIRMED",
            userConfirmedSchedule = true
        )
        val c2Derm = ClinicEntity(
            id = 2020L,
            hcpId = 20L,
            clinicIndex = 2,
            label = "Clinic 2 (Roushdy)",
            zone = "Roushdy",
            latitude = 31.2004,
            longitude = 29.9190,
            geocodingStatus = "VERIFIED",
            rawScheduleText = "2:00 PM - 8:00 PM",
            scheduleParseStatus = "CONFIRMED",
            userConfirmedSchedule = true
        )
        val winDerm1 = listOf(
            AvailabilityWindowEntity(
                id = 20101L,
                hcpId = 20L,
                clinicId = 2010L,
                dayOfWeek = 1,
                startMinuteOfDay = 14 * 60,
                endMinuteOfDay = 20 * 60,
                originalExcelText = "2-8 PM",
                isConfirmed = true
            )
        )
        val winDerm2 = listOf(
            AvailabilityWindowEntity(
                id = 20201L,
                hcpId = 20L,
                clinicId = 2020L,
                dayOfWeek = 1,
                startMinuteOfDay = 14 * 60,
                endMinuteOfDay = 20 * 60,
                originalExcelText = "2-8 PM",
                isConfirmed = true
            )
        )
        val hcpUnselectedMultiClinic = HcpWithDetails(
            hcp = HcpEntity(
                id = 20L,
                targetId = "T_DERM_MULTI",
                name = "Dr Unselected MultiClinic",
                specialty = "Dermatology",
                quarterlyTarget = 12,
                priorityScore = 99,
                territoryZone = "Smouha"
            ),
            clinics = listOf(
                ClinicWithAvailability(c1Derm, winDerm1),
                ClinicWithAvailability(c2Derm, winDerm2)
            ),
            visits = emptyList(),
            coverage = HcpCoverageMetrics(
                quarterlyTarget = 12,
                completedVisitsThisQuarter = 0,
                remainingVisits = 12,
                lastCompletedVisitDateIso = null,
                daysSinceLastVisit = null,
                isOverdue = true,
                overdueReason = "12 remaining",
                riskLevel = CoverageRiskLevel.HIGH_RISK,
                riskScore = 150.0,
                riskFormulaExplanation = "High deficit",
                projectedQuarterEndVisits = 12
            )
        )

        // HCP 3: Missing / Unknown specialty ("Unknown") -> must never be classified into a preferred specialty; excluded & flagged
        val hcpMissingSpec = buildTestHcp(
            id = 30L,
            targetId = "T_MISSING_SPEC",
            name = "Dr Missing Specialty",
            specialty = "Unknown",
            priorityScore = 95,
            quarterlyTarget = 8,
            remainingVisits = 8,
            clinicId = 3010L,
            lat = 31.2010,
            lng = 29.9200,
            dow = 1
        )

        val outcome = optimizer.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-16",
                hcps = listOf(hcpCardio, hcpUnselectedMultiClinic, hcpMissingSpec),
                settings = WorkSettings(
                    dailyHcpTarget = 6,
                    preferredSpecialtiesRanked = preferred
                ),
                // Even if mandatoryHcpIds contains unselected or missing specialty, strict eligibility rule excludes them
                mandatoryHcpIds = setOf(20L, 30L)
            )
        )

        val scheduledHcpIds = outcome.alternatives.flatMap { it.stops }.map { it.hcpId }.toSet()
        assertEquals(setOf(10L), scheduledHcpIds)
        assertFalse(scheduledHcpIds.contains(20L))
        assertFalse(scheduledHcpIds.contains(30L))
        assertEquals(1, outcome.specialtyEligibleCount)
        assertEquals(1, outcome.unselectedSpecialtyExcluded)
        assertEquals(1, outcome.missingOrUnmatchedSpecialtyExcluded)
        assertTrue(outcome.missingSpecialtyHcpIds.contains(30L))
        assertTrue(outcome.specialtyFilterSummaryText.contains("#1 Cardiology"))
        assertTrue(outcome.specialtyExclusionSummaryText.contains("Excluded: 1 HCPs from non-selected specialties."))
    }

    @Test
    fun preferredSpecialties_rankingAndSpecialtyWeightControlCandidateSelection() = runBlocking {
        // Two eligible HCPs with identical distance and identical remaining visits, different preferred specialty rank:
        // HCP 1: Cardiology, HCP 2: Neurology
        val hcpCardio = buildTestHcp(
            id = 1L,
            targetId = "T_CARD",
            name = "Dr Cardiology",
            specialty = "Cardiology",
            priorityScore = 80,
            quarterlyTarget = 6,
            remainingVisits = 4,
            clinicId = 101L,
            lat = 31.2150,
            lng = 29.9450,
            dow = 1
        )
        val hcpNeuro = buildTestHcp(
            id = 2L,
            targetId = "T_NEURO",
            name = "Dr Neurology",
            specialty = "Neurology",
            priorityScore = 80,
            quarterlyTarget = 6,
            remainingVisits = 4,
            clinicId = 102L,
            lat = 31.2150,
            lng = 29.9450,
            dow = 1
        )

        // When Cardiology is Rank #1 and Neurology is Rank #2 (with Specialty Weight = 60%), Cardiology is selected first
        val outcomeCardioFirst = optimizer.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-16",
                hcps = listOf(hcpCardio, hcpNeuro),
                settings = WorkSettings(
                    dailyHcpTarget = 1,
                    preferredSpecialtiesRanked = listOf("Cardiology", "Neurology"),
                    priorityWeightSpecialtyPercent = 60,
                    priorityWeightRemainingVisitsPercent = 20,
                    priorityWeightTravelDistancePercent = 20
                ),
                targetOverride = 1
            )
        )
        assertEquals(1L, outcomeCardioFirst.alternatives.first().stops.first().hcpId)

        // Reorder so Neurology is Rank #1 and Cardiology is Rank #2 (without changing the 3 weights) -> Neurology is selected first
        val outcomeNeuroFirst = optimizer.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-16",
                hcps = listOf(hcpCardio, hcpNeuro),
                settings = WorkSettings(
                    dailyHcpTarget = 1,
                    preferredSpecialtiesRanked = listOf("Neurology", "Cardiology"),
                    priorityWeightSpecialtyPercent = 60,
                    priorityWeightRemainingVisitsPercent = 20,
                    priorityWeightTravelDistancePercent = 20
                ),
                targetOverride = 1
            )
        )
        assertEquals(2L, outcomeNeuroFirst.alternatives.first().stops.first().hcpId)
    }

    @Test
    fun preferredSpecialties_emptySelectionBlocksAutomaticPlanGenerationWithRequiredMessage() = runBlocking {
        val hcp = buildTestHcp(
            id = 1L,
            targetId = "T_1",
            name = "Dr Cardio",
            specialty = "Cardiology",
            priorityScore = 90,
            quarterlyTarget = 8,
            remainingVisits = 8,
            clinicId = 101L,
            lat = 31.2150,
            lng = 29.9450,
            dow = 1
        )

        val emptyOutcome = optimizer.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-16",
                hcps = listOf(hcp),
                settings = WorkSettings(
                    dailyHcpTarget = 4,
                    preferredSpecialtiesRanked = emptyList()
                )
            )
        )
        assertTrue(emptyOutcome.emptyPreferredSpecialtiesBlocked)
        assertTrue(emptyOutcome.alternatives.isEmpty())
        assertEquals(
            com.example.data.model.EMPTY_PREFERRED_SPECIALTIES_MESSAGE,
            emptyOutcome.infeasibleReasons.first()
        )

        val emptyWeekly = optimizer.optimizeWeek(
            weekStartDateIso = "2026-11-15",
            hcps = listOf(hcp),
            settings = WorkSettings(
                dailyHcpTarget = 4,
                preferredSpecialtiesRanked = emptyList()
            )
        )
        assertTrue(emptyWeekly.dayPlans.isEmpty())
        assertEquals(
            com.example.data.model.EMPTY_PREFERRED_SPECIALTIES_MESSAGE,
            emptyWeekly.diagnostics.first()
        )
    }

    @Test
    fun preferredSpecialties_excelImportAddsNewSpecialtiesWithoutAutoSelectingAndPreservesRankings_andManualSchedulingWarns() = runBlocking {
        // 1. User configures preferred specialties: #1 Internal Medicine, #2 Cardiology (even if Neurology is not in DB yet)
        val userRanked = listOf("Internal Medicine", "Cardiology", "Neurology")
        repository.updateWorkSettings(
            WorkSettings(
                preferredSpecialtiesRanked = userRanked,
                priorityWeightSpecialtyPercent = 40,
                priorityWeightRemainingVisitsPercent = 30,
                priorityWeightTravelDistancePercent = 30
            )
        )

        // 2. Import a CSV containing extra whitespace/case differences ("  cardiology ") and a brand new specialty ("Oncology")
        val csvContent = """
            Target ID,Doctor Name,Specialty,Brick,Location,Schedule,Frequency
            IMP-1,Dr Cardio Normalized,  cardiology ,Smouha,"31.2156, 29.9553",Monday 2 PM - 7 PM,8
            IMP-2,Dr Oncology New,Oncology,Smouha,"31.2165, 29.9560",Monday 2 PM - 7 PM,8
        """.trimIndent()
        val sheets = com.example.domain.importer.ExcelTerritoryImporter.parseCsv(
            java.io.ByteArrayInputStream(csvContent.toByteArray(Charsets.UTF_8)),
            sheetName = "ImportSheet"
        )
        val report = com.example.domain.importer.ExcelTerritoryImporter.buildPreviewReport(
            fileName = "NewImport.csv",
            sheets = sheets,
            selectedSheetIndex = 0,
            existingHcpsByTargetId = emptyMap()
        )
        repository.executeImport(report, com.example.domain.importer.ImportMode.UPDATE_EXISTING)

        // Verify newly imported specialty ("Oncology") is added to availableSpecialties,
        // existing rankings (#1 Internal Medicine, #2 Cardiology, #3 Neurology) are preserved intact,
        // and "Oncology" was NOT auto-selected!
        val settingsAfterImport = repository.workSettingsFlow.first()
        assertEquals(userRanked, settingsAfterImport.preferredSpecialtiesRanked)
        val availableSpecs = repository.getAvailableSpecialties()
        assertTrue(availableSpecs.contains("Oncology"))
        assertTrue(availableSpecs.contains("Cardiology"))
        assertFalse(settingsAfterImport.preferredSpecialtiesRanked.contains("Oncology"))

        // 3. Direct optimizer plan generation with explicit userRanked filter includes Dr Cardio Normalized and excludes Dr Oncology New
        val importedHcps = repository.hcpsWithDetailsFlow.first()
        val genOutcome = optimizer.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-16",
                hcps = importedHcps,
                settings = settingsAfterImport
            )
        )
        val autoStops = genOutcome.alternatives.first().stops
        assertTrue(autoStops.any { it.hcpTargetId == "IMP-1" })
        assertTrue(autoStops.none { it.hcpTargetId == "IMP-2" })

        // 4. Manual scheduling of Dr Oncology New (non-selected specialty) warns the user, records the manual exception,
        // and does NOT modify the user's saved preferred specialty settings!
        val oncologyHcp = importedHcps.first { it.hcp.targetId == "IMP-2" }
        val manualResult = repository.addHcpStopToPlan(
            dateIso = "2026-11-16",
            hcpWithDetails = oncologyHcp
        )
        assertTrue(manualResult.wasNonPreferredSpecialtyException)
        assertNotNull(manualResult.warningMessage)
        assertTrue(manualResult.warningMessage!!.contains("Oncology"))
        assertEquals(userRanked, repository.workSettingsFlow.first().preferredSpecialtiesRanked)
    }

    private fun buildTestHcp(
        id: Long,
        targetId: String,
        name: String,
        specialty: String,
        priorityScore: Int,
        quarterlyTarget: Int,
        remainingVisits: Int,
        clinicId: Long,
        lat: Double,
        lng: Double,
        dow: Int,
        zone: String = "Smouha"
    ): HcpWithDetails {
        val hcp = HcpEntity(
            id = id,
            targetId = targetId,
            name = name,
            specialty = specialty,
            quarterlyTarget = quarterlyTarget,
            priorityScore = priorityScore,
            territoryZone = zone
        )
        val clinic = ClinicEntity(
            id = clinicId,
            hcpId = id,
            clinicIndex = 1,
            label = "Clinic 1 ($zone)",
            zone = zone,
            latitude = lat,
            longitude = lng,
            geocodingStatus = "VERIFIED",
            rawScheduleText = "2:00 PM - 8:00 PM",
            scheduleParseStatus = "CONFIRMED",
            userConfirmedSchedule = true
        )
        val days = if (dow in 1..7) listOf(dow) else (1..7).toList()
        val windows = days.map { d ->
            AvailabilityWindowEntity(
                id = clinicId * 10 + d,
                hcpId = id,
                clinicId = clinicId,
                dayOfWeek = d,
                startMinuteOfDay = 14 * 60,
                endMinuteOfDay = 20 * 60,
                originalExcelText = "2:00 PM - 8:00 PM",
                isConfirmed = true
            )
        }
        val completed = (quarterlyTarget - remainingVisits).coerceAtLeast(0)
        return HcpWithDetails(
            hcp = hcp,
            clinics = listOf(ClinicWithAvailability(clinic, windows)),
            visits = emptyList(),
            coverage = HcpCoverageMetrics(
                quarterlyTarget = quarterlyTarget,
                completedVisitsThisQuarter = completed,
                remainingVisits = remainingVisits,
                lastCompletedVisitDateIso = null,
                daysSinceLastVisit = null,
                isOverdue = remainingVisits > 0,
                overdueReason = if (remainingVisits > 0) "Remaining $remainingVisits visits" else "",
                riskLevel = if (remainingVisits >= 4) CoverageRiskLevel.HIGH_RISK else CoverageRiskLevel.ON_TRACK,
                riskScore = remainingVisits * 12.0 + priorityScore * 0.4,
                riskFormulaExplanation = "Remaining: $remainingVisits",
                projectedQuarterEndVisits = quarterlyTarget
            )
        )
    }
}
