package com.example.domain.optimizer

import com.example.data.model.DailyPlanWithStops
import com.example.data.model.HcpWithDetails
import com.example.data.model.PlannedStopEntity
import com.example.data.model.WEEKLY_HCP_RESTRICTION_MESSAGE
import com.example.data.model.WeeklyHcpBlockInfo
import com.example.data.model.WorkSettings
import com.example.domain.parser.AvailabilityScheduleParser
import com.example.domain.parser.TimeInfoType

data class PlanValidationReport(
    val isValid: Boolean,
    val violations: List<String>,
    val stopConflicts: Map<Int, String>, // stopOrder to conflict reason
    val summary: String
)

/**
 * Independent feasibility validator (Section 21.3, 23.18 & Section 8 of Strict Excel Schedule Accuracy).
 * Validates every generated or manually edited plan independently of the optimizer:
 * - Correct HCP and correct clinic record
 * - Weekly visit restriction (max 1 visit per HCP per calendar week regardless of clinic)
 * - Correct weekday against normalized clinic availability windows
 * - Clinic availability time windows (start AND end within permitted window)
 * - Correct appointment type (CLINIC_AVAILABILITY_RANGE only; no booking/call or approximate times)
 * - No use of unconfirmed availability as confirmed availability
 * - Visit durations (full visit duration fits)
 * - Maximum duty span (480 minutes continuous span, including return home when enabled)
 * - No split shifts (no disconnected shift gaps > 150m)
 * - No duplicate qualifying HCP visits on the same day
 * - Verified clinic coordinates
 * - Chronological travel & arrival consistency
 */
object PlanValidator {

    fun validate(
        planWithStops: DailyPlanWithStops,
        settings: WorkSettings,
        returnHomeTravelMinutes: Int = 0,
        hcpsById: Map<Long, HcpWithDetails> = emptyMap(),
        alreadyVisitedOrScheduledInWeekHcpIds: Set<Long> = emptySet(),
        weeklyBlockedByHcpId: Map<Long, WeeklyHcpBlockInfo> = emptyMap()
    ): PlanValidationReport {
        val plan = planWithStops.plan
        val stops = planWithStops.stops.sortedBy { it.stopOrder }
        val violations = mutableListOf<String>()
        val stopConflicts = mutableMapOf<Int, String>()

        if (stops.isEmpty()) {
            return PlanValidationReport(
                isValid = plan.feasibilityStatus == "INFEASIBLE",
                violations = if (plan.feasibilityStatus == "INFEASIBLE") emptyList() else listOf("Plan contains zero stops."),
                stopConflicts = emptyMap(),
                summary = if (plan.feasibilityStatus == "INFEASIBLE") {
                    "No feasible stops scheduled: ${plan.infeasibilityReasonsText}"
                } else {
                    "Plan has no stops."
                }
            )
        }

        val planDayOfWeek = RouteOptimizerEngine.parseIsoDayOfWeek(plan.dateIso)

        // 1. Check duplicate HCPs on the same day and weekly visit restriction (Section 3: max 1 visit per HCP per calendar week)
        val seenHcpIds = mutableSetOf<Long>()
        for (stop in stops) {
            if (!seenHcpIds.add(stop.hcpId)) {
                val msg = "Duplicate visit to ${stop.hcpName} (${stop.hcpTargetId}) on the same day."
                violations.add(msg)
                stopConflicts[stop.stopOrder] = msg
            }
            if (settings.enforceWeeklyHcpVisitLimit &&
                (stop.hcpId in alreadyVisitedOrScheduledInWeekHcpIds || stop.hcpId in weeklyBlockedByHcpId) &&
                stop.executionStatus != "COMPLETED"
            ) {
                val blockInfo = weeklyBlockedByHcpId[stop.hcpId]
                val detailSuffix = if (blockInfo != null) {
                    " (${blockInfo.blockingDateIso} at ${blockInfo.blockingClinicLabel})"
                } else {
                    ""
                }
                val msg = "$WEEKLY_HCP_RESTRICTION_MESSAGE$detailSuffix"
                violations.add(msg)
                stopConflicts[stop.stopOrder] = (stopConflicts[stop.stopOrder]?.let { "$it " } ?: "") + msg
            }
        }

        // 1b. Global Pairwise No-Overlap Rule (Part 7.1):
        // Under no circumstances may two HCP visits overlap in time, even if they are in the same clinic/hospital.
        for (i in stops.indices) {
            for (j in i + 1 until stops.size) {
                val a = stops[i]
                val b = stops[j]
                if (a.visitStartMinute < b.visitEndMinute && b.visitStartMinute < a.visitEndMinute) {
                    val overlapMsg = "Overlapping visits prohibited: ${a.hcpName} (${formatMin(a.visitStartMinute)}–${formatMin(a.visitEndMinute)}) overlaps with ${b.hcpName} (${formatMin(b.visitStartMinute)}–${formatMin(b.visitEndMinute)})."
                    violations.add(overlapMsg)
                    stopConflicts[a.stopOrder] = (stopConflicts[a.stopOrder]?.let { "$it " } ?: "") + overlapMsg
                    stopConflicts[b.stopOrder] = (stopConflicts[b.stopOrder]?.let { "$it " } ?: "") + overlapMsg
                }
            }
        }

        // 2. Check chronological flow, coordinates, visit duration, same-location sequential buffer, and clinic time windows
        var prevEndMinute = plan.startMinuteOfDay
        for ((idx, stop) in stops.withIndex()) {
            val stopIssues = mutableListOf<String>()

            if (!stop.locationVerified || stop.latitude == null || stop.longitude == null) {
                stopIssues.add("Unverified clinic coordinates for ${stop.clinicLabel}.")
            }

            if (stop.visitDurationMinutes <= 0) {
                stopIssues.add("Invalid visit duration (${stop.visitDurationMinutes}m) at ${stop.hcpName}.")
            }

            val expectedDeparture = if (idx == 0) plan.startMinuteOfDay else prevEndMinute
            if (stop.departureFromPrevMinute < expectedDeparture) {
                stopIssues.add(
                    "Stop #${stop.stopOrder} departs at ${stop.departureFromPrevMinute}m before previous activity ends ($expectedDeparture m)."
                )
            }

            // Same-location sequential rule: if consecutive stops share the same physical building (< 50m),
            // next.visitStart >= current.visitEnd + sameLocationTransitionBuffer
            if (idx > 0) {
                val prevStop = stops[idx - 1]
                val isSamePhysicalLocation = stop.distanceKmFromPrev < 0.05 ||
                    stop.trafficSource == "SAME_LOCATION_SEQUENTIAL"
                val minRequiredTransition = if (isSamePhysicalLocation) {
                    settings.sameLocationTransitionMinutes.coerceAtLeast(0)
                } else {
                    stop.travelMinutesFromPrev.coerceAtLeast(0)
                }
                if (stop.visitStartMinute < prevStop.visitEndMinute + minRequiredTransition) {
                    stopIssues.add(
                        "Stop #${stop.stopOrder} starts at ${formatMin(stop.visitStartMinute)} before previous visit ends (${formatMin(prevStop.visitEndMinute)}) + ${minRequiredTransition}m transition."
                    )
                }
            }

            val expectedArrival = stop.departureFromPrevMinute + stop.travelMinutesFromPrev
            if (stop.arrivalMinute < expectedArrival) {
                stopIssues.add("Stop #${stop.stopOrder} arrival time precedes departure + travel time.")
            }

            if (stop.visitStartMinute < stop.arrivalMinute) {
                stopIssues.add("Stop #${stop.stopOrder} visit starts before arrival.")
            }

            if (stop.visitEndMinute != stop.visitStartMinute + stop.visitDurationMinutes) {
                stopIssues.add("Stop #${stop.stopOrder} visit end does not match start + duration.")
            }

            // Clinic window check: must BOTH start and finish within confirmed window (handle overnight windows via 1440 cap on same-day route)
            val isOvernightStopWindow = stop.windowEndMinute < stop.windowStartMinute
            val safeStopWinEnd = if (isOvernightStopWindow) 1440 else stop.windowEndMinute
            if (stop.visitStartMinute < stop.windowStartMinute || stop.visitEndMinute > safeStopWinEnd) {
                stopIssues.add(
                    "Visit at ${stop.hcpName} (${formatMin(stop.visitStartMinute)}–${formatMin(stop.visitEndMinute)}) falls outside clinic window (${formatMin(stop.windowStartMinute)}–${formatMin(stop.windowEndMinute)}${if (isOvernightStopWindow) " +1d" else ""})."
                )
            }

            // Section 8: Cross-check against source HCP & clinic availability records when provided
            if (hcpsById.isNotEmpty()) {
                val hcpDetails = hcpsById[stop.hcpId]
                if (hcpDetails == null) {
                    stopIssues.add("Stop #${stop.stopOrder} references unknown HCP ID ${stop.hcpId} (${stop.hcpName}).")
                } else {
                    val matchedClinicWithAvail = if (stop.clinicId > 0L) {
                        hcpDetails.clinics.firstOrNull { it.clinic.id == stop.clinicId }
                    } else {
                        hcpDetails.clinics.firstOrNull { it.clinic.label.equals(stop.clinicLabel, ignoreCase = true) }
                    }
                    if (matchedClinicWithAvail == null) {
                        stopIssues.add(
                            "Stop #${stop.stopOrder} (${stop.hcpName}) references clinic '${stop.clinicLabel}' (ID ${stop.clinicId}) which does not belong to this HCP."
                        )
                    } else {
                        val clinic = matchedClinicWithAvail.clinic
                        val isConfirmedSchedule = clinic.userConfirmedSchedule ||
                            clinic.scheduleParseStatus == "CONFIRMED" ||
                            clinic.scheduleParseStatus == "PARSED_HIGH_CONFIDENCE"

                        if (!isConfirmedSchedule) {
                            stopIssues.add(
                                "Stop #${stop.stopOrder} (${stop.hcpName} - ${clinic.label}) uses unconfirmed schedule (status=${clinic.scheduleParseStatus})."
                            )
                        }

                        if (clinic.timeInfoType.isNotBlank() &&
                            clinic.timeInfoType != TimeInfoType.CLINIC_AVAILABILITY_RANGE.name
                        ) {
                            stopIssues.add(
                                "Stop #${stop.stopOrder} (${stop.hcpName} - ${clinic.label}) has non-range schedule type (${clinic.timeInfoType}) instead of confirmed clinic availability."
                            )
                        }

                        if (clinic.validationStatus == "INVALID") {
                            stopIssues.add(
                                "Stop #${stop.stopOrder} (${stop.hcpName} - ${clinic.label}) has invalid schedule validation status."
                            )
                        }

                        val dayWindows = matchedClinicWithAvail.windows.filter {
                            it.clinicId == clinic.id &&
                                (it.hcpId == 0L || it.hcpId == stop.hcpId) &&
                                it.dayOfWeek == planDayOfWeek &&
                                (it.isConfirmed || isConfirmedSchedule)
                        }
                        if (dayWindows.isEmpty()) {
                            stopIssues.add(
                                "Stop #${stop.stopOrder} (${stop.hcpName} - ${clinic.label}) is scheduled on ${AvailabilityScheduleParser.dayName(planDayOfWeek)}, which is not in the clinic's confirmed weekdays."
                            )
                        } else {
                            val fitsAnyConfirmedWindow = dayWindows.any { win ->
                                val sameDayEnd = win.effectiveSameDayEndMinute()
                                if (win.endsNextDay && win.durationMinutes() !in 15..720) {
                                    false
                                } else {
                                    stop.visitStartMinute >= win.startMinuteOfDay &&
                                        stop.visitEndMinute <= sameDayEnd &&
                                        (stop.visitEndMinute - stop.visitStartMinute) >= stop.visitDurationMinutes
                                }
                            }
                            if (!fitsAnyConfirmedWindow) {
                                val winDesc = dayWindows.joinToString(", ") {
                                    val suffix = if (it.endsNextDay) " (+1d)" else ""
                                    "${formatMin(it.startMinuteOfDay)}–${formatMin(it.endMinuteOfDay)}$suffix"
                                }
                                stopIssues.add(
                                    "Visit at ${stop.hcpName} (${formatMin(stop.visitStartMinute)}–${formatMin(stop.visitEndMinute)}) falls outside clinic window ($winDesc) on ${AvailabilityScheduleParser.dayName(planDayOfWeek)}."
                                )
                            }
                        }
                    }
                }
            }

            // No split-shift check (excessive idle gap > 150 minutes between stops)
            if (stop.waitingMinutes > 150) {
                stopIssues.add(
                    "Excessive waiting gap (${stop.waitingMinutes}m) before ${stop.hcpName} violates continuous single-shift policy."
                )
            }

            if (stopIssues.isNotEmpty()) {
                val joined = stopIssues.joinToString(" ")
                violations.addAll(stopIssues)
                stopConflicts[stop.stopOrder] = (stopConflicts[stop.stopOrder]?.let { "$it " } ?: "") + joined
            }

            prevEndMinute = stop.visitEndMinute
        }

        // 3. Check total duty span (including return home if enabled)
        val firstDeparture = stops.first().departureFromPrevMinute
        val lastActivityEnd = stops.last().visitEndMinute + (if (settings.returnHomeRequired) returnHomeTravelMinutes else 0)
        val actualDutySpan = lastActivityEnd - firstDeparture

        if (actualDutySpan > settings.maxDutySpanMinutes) {
            val msg = "Total duty span (${actualDutySpan}m) exceeds maximum limit of ${settings.maxDutySpanMinutes}m."
            violations.add(msg)
            stopConflicts[stops.last().stopOrder] = (stopConflicts[stops.last().stopOrder]?.let { "$it " } ?: "") + msg
        }

        val isValid = violations.isEmpty()
        val summary = if (isValid) {
            "Verified by Independent Validator: ${stops.size} stops, ${actualDutySpan}m duty span (<= ${settings.maxDutySpanMinutes}m limit), zero overlapping visits, all clinic windows and travel constraints satisfied."
        } else {
            "Validation Failed (${violations.size} issue(s)): ${violations.first()}"
        }

        return PlanValidationReport(
            isValid = isValid,
            violations = violations,
            stopConflicts = stopConflicts,
            summary = summary
        )
    }

    private fun formatMin(min: Int): String {
        val h = (min / 60).coerceIn(0, 23)
        val m = (min % 60).coerceIn(0, 59)
        return String.format("%02d:%02d", h, m)
    }
}
