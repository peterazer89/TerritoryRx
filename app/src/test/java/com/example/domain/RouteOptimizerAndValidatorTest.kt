package com.example.domain

import com.example.data.model.AvailabilityWindowEntity
import com.example.data.model.ClinicEntity
import com.example.data.model.ClinicWithAvailability
import com.example.data.model.DailyPlanEntity
import com.example.data.model.DailyPlanWithStops
import com.example.data.model.HcpEntity
import com.example.data.model.HcpWithDetails
import com.example.data.model.PlannedStopEntity
import com.example.data.model.WorkSettings
import com.example.domain.coverage.CoverageCalculator
import com.example.domain.optimizer.OptimizationRequest
import com.example.domain.optimizer.PlanValidator
import com.example.domain.optimizer.RouteOptimizerEngine
import com.example.domain.routing.RoutingProvider
import com.example.domain.routing.TrafficEstimateSource
import com.example.domain.routing.TravelEstimate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteOptimizerAndValidatorTest {

    private val deterministicRouting = object : RoutingProvider {
        override suspend fun estimateTravel(
            originLat: Double,
            originLng: Double,
            destLat: Double,
            destLng: Double,
            departureMinuteOfDay: Int,
            safetyBufferPercent: Int,
            allowNetworkCall: Boolean
        ): TravelEstimate {
            return TravelEstimate(
                baseDurationMinutes = 10,
                bufferedDurationMinutes = 11,
                distanceKm = 3.2,
                source = TrafficEstimateSource.TYPICAL_ROAD,
                lastUpdatedLabel = "Deterministic Test"
            )
        }
    }

    private fun buildTestHcp(
        id: Long,
        targetId: String,
        name: String,
        quarterlyTarget: Int = 6,
        baselineVisits: Int = 0,
        multiClinic: Boolean = false,
        windowStartMin: Int = 14 * 60, // 2:00 PM
        windowEndMin: Int = 22 * 60    // 10:00 PM
    ): HcpWithDetails {
        val settings = WorkSettings()
        val hcp = HcpEntity(
            id = id,
            targetId = targetId,
            name = name,
            specialty = "Internal Medicine",
            quarterlyTarget = quarterlyTarget,
            importedBaselineVisits = baselineVisits,
            priorityScore = 80,
            territoryZone = "El-Sheikh Zayed 1"
        )
        val clinics = mutableListOf(
            ClinicWithAvailability(
                clinic = ClinicEntity(
                    id = id * 10 + 1,
                    hcpId = id,
                    clinicIndex = 1,
                    label = "Clinic 1",
                    zone = "El-Sheikh Zayed 1",
                    latitude = 30.0444 + (id * 0.002),
                    longitude = 30.9760 + (id * 0.002),
                    geocodingStatus = "VERIFIED",
                    rawScheduleText = "Monday 2-10 PM",
                    scheduleParseStatus = "CONFIRMED",
                    userConfirmedSchedule = true
                ),
                windows = listOf(
                    AvailabilityWindowEntity(
                        id = id * 100 + 1,
                        clinicId = id * 10 + 1,
                        dayOfWeek = 1, // Monday
                        startMinuteOfDay = windowStartMin,
                        endMinuteOfDay = windowEndMin,
                        isConfirmed = true
                    )
                )
            )
        )
        if (multiClinic) {
            clinics.add(
                ClinicWithAvailability(
                    clinic = ClinicEntity(
                        id = id * 10 + 2,
                        hcpId = id,
                        clinicIndex = 2,
                        label = "Clinic 2",
                        zone = "El-Sheikh Zayed 2",
                        latitude = 30.0380 + (id * 0.002),
                        longitude = 30.9580 + (id * 0.002),
                        geocodingStatus = "VERIFIED",
                        rawScheduleText = "Monday 2-10 PM",
                        scheduleParseStatus = "CONFIRMED",
                        userConfirmedSchedule = true
                    ),
                    windows = listOf(
                        AvailabilityWindowEntity(
                            id = id * 100 + 2,
                            clinicId = id * 10 + 2,
                            dayOfWeek = 1, // Monday
                            startMinuteOfDay = windowStartMin,
                            endMinuteOfDay = windowEndMin,
                            isConfirmed = true
                        )
                    )
                )
            )
        }
        val coverage = CoverageCalculator.calculateMetrics(hcp, clinics, emptyList(), settings)
        return HcpWithDetails(hcp, clinics, emptyList(), coverage)
    }

    @Test
    fun optimizeDay_reaches8UniqueHcpsWithin480MinContinuousDutySpan_andSingleCountsMultiClinicHcp() = runBlocking {
        val engine = RouteOptimizerEngine(deterministicRouting)
        val settings = WorkSettings(dailyHcpTarget = 8, maxDutySpanMinutes = 480)

        val hcps = (1L..10L).map { id ->
            buildTestHcp(
                id = id,
                targetId = "${30620 + id}",
                name = "Dr. Test $id",
                multiClinic = (id <= 3L)
            )
        }

        val outcome = engine.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-02", // Monday
                hcps = hcps,
                settings = settings
            )
        )

        assertTrue(outcome.alternatives.isNotEmpty())
        val best = outcome.alternatives.first()
        assertEquals(8, best.plan.uniqueHcpsCount)
        assertTrue(best.plan.totalDutySpanMinutes <= 480)
        assertTrue(best.plan.isValidatorVerified)

        val distinctHcpIds = best.stops.map { it.hcpId }.toSet()
        assertEquals(best.stops.size, distinctHcpIds.size)
    }

    @Test
    fun optimizeDay_whenFewerThan8Available_returnsBestEffortWithShortfallExplanation() = runBlocking {
        val engine = RouteOptimizerEngine(deterministicRouting)
        val settings = WorkSettings(dailyHcpTarget = 8, maxDutySpanMinutes = 480)

        val hcps = (1L..3L).map { id ->
            buildTestHcp(id = id, targetId = "${30700 + id}", name = "Dr. Sparse $id")
        }

        val outcome = engine.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-02",
                hcps = hcps,
                settings = settings
            )
        )

        val best = outcome.alternatives.first()
        assertEquals(3, best.plan.uniqueHcpsCount)
        assertTrue(best.plan.infeasibilityReasonsText.contains("Scheduled 3/8 target visits"))
    }

    @Test
    fun independentPlanValidator_rejectsDutySpanOverflowAndClinicWindowViolations() {
        val settings = WorkSettings(dailyHcpTarget = 8, maxDutySpanMinutes = 480)

        val invalidStop = PlannedStopEntity(
            id = 1L,
            planId = 1L,
            stopOrder = 1,
            hcpId = 10L,
            hcpTargetId = "30622",
            hcpName = "Dr. Ahmed",
            specialty = "Internal Medicine",
            clinicId = 101L,
            clinicLabel = "Clinic 1",
            zone = "El-Sheikh Zayed 1",
            latitude = 30.0444,
            longitude = 30.9760,
            locationVerified = true,
            departureFromPrevMinute = 8 * 60,
            travelMinutesFromPrev = 20,
            distanceKmFromPrev = 5.0,
            trafficSource = "TYPICAL_ROAD",
            arrivalMinute = 8 * 60 + 20,
            waitingMinutes = 0,
            visitStartMinute = 18 * 60 - 5,
            visitDurationMinutes = 20,
            visitEndMinute = 18 * 60 + 15, // exceeds 6:00 PM clinic close!
            windowStartMinute = 14 * 60,
            windowEndMinute = 18 * 60,
            priorityScore = 80,
            remainingQuarterlyVisits = 4
        )

        val invalidPlan = DailyPlanEntity(
            id = 1L,
            dateIso = "2026-11-02",
            variantCode = "PLAN_A",
            variantLabel = "Invalid Test Plan",
            isSelected = true,
            startMinuteOfDay = 8 * 60,
            endMinuteOfDay = 18 * 60 + 30,
            totalDutySpanMinutes = 630,
            totalDrivingMinutes = 35,
            totalWaitingMinutes = 0,
            plannedVisitsCount = 1,
            uniqueHcpsCount = 1
        )

        val validation = PlanValidator.validate(
            planWithStops = DailyPlanWithStops(invalidPlan, listOf(invalidStop)),
            settings = settings,
            returnHomeTravelMinutes = 15
        )

        assertFalse(validation.isValid)
        assertTrue(validation.violations.any { it.contains("Total duty span") })
        assertTrue(validation.violations.any { it.contains("outside clinic window") })
    }

    @Test
    fun optimizeDay_prioritizesRemainingVisits_minimizesDistance_andCompressesTimeGaps() = runBlocking {
        val distanceAwareRouting = object : RoutingProvider {
            override suspend fun estimateTravel(
                originLat: Double,
                originLng: Double,
                destLat: Double,
                destLng: Double,
                departureMinuteOfDay: Int,
                safetyBufferPercent: Int,
                allowNetworkCall: Boolean
            ): TravelEstimate {
                val dLat = (destLat - originLat) * 111.0
                val dLng = (destLng - originLng) * 96.0
                val distKm = kotlin.math.sqrt(dLat * dLat + dLng * dLng).coerceAtLeast(0.4)
                val mins = (distKm * 2.5).toInt().coerceIn(4, 45)
                return TravelEstimate(
                    baseDurationMinutes = mins,
                    bufferedDurationMinutes = mins + 1,
                    distanceKm = distKm,
                    source = TrafficEstimateSource.TYPICAL_ROAD,
                    lastUpdatedLabel = "Distance Test"
                )
            }
        }

        val engine = RouteOptimizerEngine(distanceAwareRouting)
        val settings = WorkSettings(dailyHcpTarget = 3, maxDutySpanMinutes = 480)

        // Create 5 doctors: 3 with high remaining visits (8 remaining) and 2 with low remaining visits (1 remaining)
        val highRem1 = buildTestHcp(id = 1L, targetId = "H1", name = "Dr. High Rem 1", quarterlyTarget = 8, baselineVisits = 0, windowStartMin = 14 * 60, windowEndMin = 19 * 60)
        val highRem2 = buildTestHcp(id = 2L, targetId = "H2", name = "Dr. High Rem 2", quarterlyTarget = 8, baselineVisits = 0, windowStartMin = 15 * 60, windowEndMin = 19 * 60)
        val highRem3 = buildTestHcp(id = 3L, targetId = "H3", name = "Dr. High Rem 3", quarterlyTarget = 8, baselineVisits = 0, windowStartMin = 16 * 60, windowEndMin = 20 * 60)
        val lowRem4 = buildTestHcp(id = 4L, targetId = "L4", name = "Dr. Low Rem 4", quarterlyTarget = 8, baselineVisits = 7, windowStartMin = 14 * 60, windowEndMin = 20 * 60)
        val lowRem5 = buildTestHcp(id = 5L, targetId = "L5", name = "Dr. Low Rem 5", quarterlyTarget = 8, baselineVisits = 7, windowStartMin = 14 * 60, windowEndMin = 20 * 60)

        val outcome = engine.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-02",
                hcps = listOf(lowRem4, highRem1, lowRem5, highRem2, highRem3),
                settings = settings
            )
        )

        val best = outcome.alternatives.first()
        assertEquals(3, best.stops.size)
        // All 3 selected stops should be the doctors with 8 remaining visits
        assertTrue(best.stops.all { it.remainingQuarterlyVisits == 8 })
        // Forward slack compression should eliminate unnecessary idle waiting gaps between staggered windows (2pm, 3pm, 4pm)
        assertTrue("Expected minimal waiting time after gap compression, got ${best.plan.totalWaitingMinutes}", best.plan.totalWaitingMinutes <= 15)
        assertTrue(best.plan.isValidatorVerified)
    }

    @Test
    fun planValidator_rejectsOverlappingVisitsEvenAtSamePhysicalLocation() {
        val settings = WorkSettings(
            dailyHcpTarget = 8,
            maxDutySpanMinutes = 480,
            defaultVisitDurationMinutes = 15,
            sameLocationTransitionMinutes = 0
        )

        val stop1 = PlannedStopEntity(
            id = 1L,
            planId = 1L,
            stopOrder = 1,
            hcpId = 101L,
            hcpTargetId = "T101",
            hcpName = "Dr. Same Tower 1",
            specialty = "Cardiology",
            clinicId = 1001L,
            clinicLabel = "Clinic 1",
            zone = "Smouha",
            latitude = 31.2156,
            longitude = 29.9553,
            locationVerified = true,
            departureFromPrevMinute = 14 * 60 - 10,
            travelMinutesFromPrev = 10,
            distanceKmFromPrev = 3.0,
            trafficSource = "TYPICAL_ROAD",
            arrivalMinute = 14 * 60,
            waitingMinutes = 0,
            visitStartMinute = 14 * 60,      // 2:00 PM
            visitDurationMinutes = 15,
            visitEndMinute = 14 * 60 + 15,   // 2:15 PM
            windowStartMinute = 14 * 60,
            windowEndMinute = 18 * 60,
            priorityScore = 85,
            remainingQuarterlyVisits = 6
        )

        // Overlaps with stop1 (starts at 2:10 PM before 2:15 PM end)
        val overlappingStop2 = stop1.copy(
            id = 2L,
            stopOrder = 2,
            hcpId = 102L,
            hcpTargetId = "T102",
            hcpName = "Dr. Same Tower 2",
            clinicId = 1002L,
            departureFromPrevMinute = 14 * 60 + 10,
            travelMinutesFromPrev = 0,
            distanceKmFromPrev = 0.0,
            arrivalMinute = 14 * 60 + 10,
            visitStartMinute = 14 * 60 + 10, // 2:10 PM (OVERLAP!)
            visitDurationMinutes = 15,
            visitEndMinute = 14 * 60 + 25
        )

        val plan = DailyPlanEntity(
            id = 1L,
            dateIso = "2026-11-02",
            variantCode = "PLAN_A",
            variantLabel = "Overlap Test",
            isSelected = true,
            startMinuteOfDay = 14 * 60 - 10,
            endMinuteOfDay = 14 * 60 + 40,
            totalDutySpanMinutes = 50,
            totalDrivingMinutes = 25,
            totalWaitingMinutes = 0,
            plannedVisitsCount = 2,
            uniqueHcpsCount = 2
        )

        val overlapValidation = PlanValidator.validate(
            planWithStops = DailyPlanWithStops(plan, listOf(stop1, overlappingStop2)),
            settings = settings,
            returnHomeTravelMinutes = 15
        )
        assertFalse("Overlapping stops must be rejected", overlapValidation.isValid)
        assertTrue(overlapValidation.violations.any { it.contains("Overlapping", ignoreCase = true) || it.contains("before", ignoreCase = true) })

        // Sequential back-to-back at 2:15 PM with 0m transition at identical verified coordinates MUST be valid
        val sequentialStop2 = overlappingStop2.copy(
            departureFromPrevMinute = 14 * 60 + 15,
            arrivalMinute = 14 * 60 + 15,
            visitStartMinute = 14 * 60 + 15, // 2:15 PM — exact sequential start after 2:15 PM end
            visitEndMinute = 14 * 60 + 30
        )
        val sequentialValidation = PlanValidator.validate(
            planWithStops = DailyPlanWithStops(plan, listOf(stop1, sequentialStop2)),
            settings = settings,
            returnHomeTravelMinutes = 15
        )
        assertTrue("Back-to-back 0m transition at same verified location should pass: ${sequentialValidation.violations}", sequentialValidation.isValid)
    }

    @Test
    fun optimizeDay_uses15MinDefaultDuration_andZeroMinuteTransitionOnlyForSameVerifiedCoordinates() = runBlocking {
        val engine = RouteOptimizerEngine(com.example.domain.routing.DefaultRoutingService())
        val settings = WorkSettings(
            dailyHcpTarget = 2,
            defaultVisitDurationMinutes = 15,
            sameLocationTransitionMinutes = 0
        )

        // Two doctors in the exact same medical tower (identical verified GPS coordinates)
        val base1 = buildTestHcp(id = 1L, targetId = "B1", name = "Dr. Tower A", windowStartMin = 15 * 60, windowEndMin = 17 * 60)
        val towerLat = 30.0500
        val towerLng = 31.0100
        val doc1 = base1.copy(
            clinics = listOf(
                base1.clinics.first().copy(
                    clinic = base1.clinics.first().clinic.copy(latitude = towerLat, longitude = towerLng, geocodingStatus = "VERIFIED")
                )
            )
        )
        val base2 = buildTestHcp(id = 2L, targetId = "B2", name = "Dr. Tower B", windowStartMin = 15 * 60, windowEndMin = 17 * 60)
        val doc2 = base2.copy(
            clinics = listOf(
                base2.clinics.first().copy(
                    clinic = base2.clinics.first().clinic.copy(latitude = towerLat, longitude = towerLng, geocodingStatus = "VERIFIED")
                )
            )
        )

        val outcome = engine.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-02", // Monday (working day)
                hcps = listOf(doc1, doc2),
                settings = settings
            )
        )

        val best = outcome.alternatives.first()
        assertEquals(2, best.stops.size)
        val firstStop = best.stops[0]
        val secondStop = best.stops[1]
        assertEquals(15, firstStop.visitDurationMinutes)
        assertEquals(15, secondStop.visitDurationMinutes)
        assertEquals(0, secondStop.travelMinutesFromPrev)
        assertEquals(0.0, secondStop.distanceKmFromPrev, 0.001)
        assertEquals(firstStop.visitEndMinute, secondStop.visitStartMinute)
    }

    @Test
    fun workSettings_defaultsToThursdayFridaySaturdayWeekend_andRejectsWeekendPlanningByDefault() = runBlocking {
        val engine = RouteOptimizerEngine(deterministicRouting)
        val defaultSettings = WorkSettings()

        // Default weekend days: Thursday (4), Friday (5), Saturday (6)
        assertEquals(setOf(4, 5, 6), defaultSettings.weekendDaysIso)
        // Default working days: Sunday (7), Monday (1), Tuesday (2), Wednesday (3)
        assertEquals(setOf(7, 1, 2, 3), defaultSettings.workingDaysIso)
        assertEquals(15, defaultSettings.defaultVisitDurationMinutes)
        assertEquals(0, defaultSettings.sameLocationTransitionMinutes)

        val hcp = buildTestHcp(id = 1L, targetId = "W1", name = "Dr. Weekend")
        // 2026-11-05 is Thursday (ISO 4)
        val thursdayOutcome = engine.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-05",
                hcps = listOf(hcp),
                settings = defaultSettings
            )
        )
        assertTrue(thursdayOutcome.alternatives.all { it.stops.isEmpty() })
        assertTrue(thursdayOutcome.infeasibleReasons.any { it.contains("weekend", ignoreCase = true) })
    }

    @Test
    fun brickFiltering_schedulesHcpAtClinicMatchingSelectedBrick_andNeverDoubleCountsMultiClinicHcp() = runBlocking {
        val engine = RouteOptimizerEngine(deterministicRouting)
        val settings = WorkSettings(dailyHcpTarget = 4, maxDutySpanMinutes = 480)

        // HCP with Clinic 1 in "Brick A" and Clinic 2 in "Brick B"
        val hcpMultiBrick = buildTestHcp(
            id = 10L,
            targetId = "MB-10",
            name = "Dr. Multi Brick",
            multiClinic = true
        ).let { base ->
            val updatedClinics = listOf(
                base.clinics[0].copy(
                    clinic = base.clinics[0].clinic.copy(
                        zone = "Brick A",
                        latitude = 31.1100,
                        longitude = 29.7700
                    )
                ),
                base.clinics[1].copy(
                    clinic = base.clinics[1].clinic.copy(
                        zone = "Brick B",
                        latitude = 31.0100,
                        longitude = 29.8100
                    )
                )
            )
            base.copy(
                hcp = base.hcp.copy(territoryZone = "Brick A"),
                clinics = updatedClinics
            )
        }

        // 1. Selecting only "Brick A" -> must schedule at Clinic 1 (Brick A, lat 31.1100)
        val outcomeBrickA = engine.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-02",
                hcps = listOf(hcpMultiBrick),
                settings = settings,
                allowedZones = setOf("Brick A")
            )
        )
        val stopA = outcomeBrickA.alternatives.first().stops.single()
        assertEquals(hcpMultiBrick.clinics[0].clinic.id, stopA.clinicId)
        assertEquals("Brick A", stopA.zone)
        assertEquals(31.1100, stopA.latitude!!, 0.0001)

        // 2. Selecting only "Brick B" -> must schedule at Clinic 2 (Brick B, lat 31.0100), NOT Clinic 1
        val outcomeBrickB = engine.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-02",
                hcps = listOf(hcpMultiBrick),
                settings = settings,
                allowedZones = setOf("Brick B")
            )
        )
        val stopB = outcomeBrickB.alternatives.first().stops.single()
        assertEquals(hcpMultiBrick.clinics[1].clinic.id, stopB.clinicId)
        assertEquals("Brick B", stopB.zone)
        assertEquals(31.0100, stopB.latitude!!, 0.0001)

        // 3. Selecting both "Brick A" and "Brick B" -> both clinics eligible, but HCP is scheduled at most once
        val outcomeBoth = engine.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-02",
                hcps = listOf(hcpMultiBrick),
                settings = settings,
                allowedZones = setOf("Brick A", "Brick B")
            )
        )
        val bestBoth = outcomeBoth.alternatives.first()
        assertEquals(1, bestBoth.stops.size)
        assertEquals(1, bestBoth.plan.uniqueHcpsCount)
    }

    @Test
    fun strictExcelOptimizerAndValidator_enforcesConfirmedRangeOnly_andRejectsVisitsExceedingClinicWindowOrUnconfirmed() = runBlocking {
        val engine = RouteOptimizerEngine(deterministicRouting)
        val settings = WorkSettings(dailyHcpTarget = 4, defaultVisitDurationMinutes = 15)

        // 1. HCP with confirmed 3:00 PM - 6:00 PM Monday window -> Eligible
        val validHcp = buildTestHcp(
            id = 1L,
            targetId = "V-01",
            name = "Dr. Confirmed Range",
            windowStartMin = 15 * 60,
            windowEndMin = 18 * 60
        )

        // 2. HCP with BOOKING_OR_CALL_TIME ("Call at 7 PM to book") -> Must be excluded by optimizer
        val bookingCallHcp = buildTestHcp(
            id = 2L,
            targetId = "B-02",
            name = "Dr. Call To Book",
            windowStartMin = 19 * 60,
            windowEndMin = 22 * 60
        ).let { base ->
            base.copy(
                clinics = listOf(
                    base.clinics.first().copy(
                        clinic = base.clinics.first().clinic.copy(
                            rawScheduleText = "Call at 7 PM to book",
                            originalExcelScheduleText = "Call at 7 PM to book",
                            timeInfoType = "BOOKING_OR_CALL_TIME",
                            scheduleParseStatus = "AMBIGUOUS",
                            userConfirmedSchedule = false
                        )
                    )
                )
            )
        }

        // 3. HCP with a 10-minute window (3:00 PM - 3:10 PM) where 15-minute visit duration cannot fit -> Must be excluded
        val tooShortWindowHcp = buildTestHcp(
            id = 3L,
            targetId = "S-03",
            name = "Dr. Short Window",
            windowStartMin = 15 * 60,
            windowEndMin = 15 * 60 + 10
        )

        val outcome = engine.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-02", // Monday
                hcps = listOf(validHcp, bookingCallHcp, tooShortWindowHcp),
                settings = settings
            )
        )

        val best = outcome.alternatives.first()
        assertEquals(1, best.stops.size)
        assertEquals(validHcp.hcp.id, best.stops.single().hcpId)
        assertTrue(best.stops.single().visitStartMinute >= 15 * 60)
        assertTrue(best.stops.single().visitEndMinute <= 18 * 60)
        assertTrue(outcome.infeasibleReasons.any { it.contains("Dr. Call To Book") })

        // Verify PlanValidator independently rejects a stop that uses an unconfirmed/booking-only clinic
        val illegalStop = best.stops.single().copy(
            hcpId = bookingCallHcp.hcp.id,
            hcpTargetId = bookingCallHcp.hcp.targetId,
            hcpName = bookingCallHcp.hcp.name,
            clinicId = bookingCallHcp.clinics.first().clinic.id
        )
        val validation = PlanValidator.validate(
            planWithStops = DailyPlanWithStops(best.plan, listOf(illegalStop)),
            settings = settings,
            returnHomeTravelMinutes = 10,
            hcpsById = mapOf(
                validHcp.hcp.id to validHcp,
                bookingCallHcp.hcp.id to bookingCallHcp
            )
        )
        assertFalse("PlanValidator must reject unconfirmed or booking-only clinic schedules", validation.isValid)
        assertTrue(validation.violations.any { it.contains("unconfirmed") || it.contains("BOOKING_OR_CALL_TIME") })
    }

    @Test
    fun optimizer_safelyHandlesOvernightWindowsAndRejectsInvalidStatus() = runBlocking {
        val engine = RouteOptimizerEngine(deterministicRouting)
        val settings = WorkSettings(dailyHcpTarget = 4, defaultVisitDurationMinutes = 15)

        val validHcp = buildTestHcp(
            id = 1L,
            targetId = "V-10",
            name = "Dr. Daytime Valid",
            windowStartMin = 15 * 60,
            windowEndMin = 18 * 60
        )

        // Overnight clinic: 11 PM (1380) to 1 AM (60) next day -> endsNextDay = true
        val overnightHcp = buildTestHcp(
            id = 2L,
            targetId = "OV-11",
            name = "Dr. Overnight Clinic",
            windowStartMin = 23 * 60,
            windowEndMin = 60
        ).let { base ->
            val ovWin = base.clinics.first().windows.first().copy(endsNextDay = true)
            base.copy(clinics = listOf(base.clinics.first().copy(windows = listOf(ovWin))))
        }

        // INVALID validation status clinic
        val invalidHcp = buildTestHcp(
            id = 3L,
            targetId = "INV-12",
            name = "Dr. Invalid Status"
        ).let { base ->
            base.copy(
                clinics = listOf(
                    base.clinics.first().copy(
                        clinic = base.clinics.first().clinic.copy(validationStatus = "INVALID")
                    )
                )
            )
        }

        val outcome = engine.optimizeDay(
            OptimizationRequest(
                dateIso = "2026-11-02", // Monday
                hcps = listOf(validHcp, overnightHcp, invalidHcp),
                settings = settings,
                shiftStartMinute = 13 * 60,
                shiftEndMinute = 21 * 60
            )
        )

        val best = outcome.alternatives.first()
        assertEquals(1, best.stops.size)
        assertEquals(validHcp.hcp.id, best.stops.single().hcpId)
        assertTrue(outcome.infeasibleReasons.any { it.contains("Dr. Overnight Clinic") && it.contains("overnight", ignoreCase = true) })
        assertTrue(outcome.infeasibleReasons.any { it.contains("Dr. Invalid Status") })
    }
}



