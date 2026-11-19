package com.example.domain.coverage

import com.example.data.model.ClinicWithAvailability
import com.example.data.model.CoverageRiskLevel
import com.example.data.model.HcpCoverageMetrics
import com.example.data.model.HcpEntity
import com.example.data.model.VisitEntity
import com.example.data.model.WorkSettings
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Deterministic quarterly coverage and risk calculator (Section 8).
 * Formula:
 *   RemainingVisits = max(0, QuarterlyTarget - QualifyingCompletedVisitsInQuarter)
 *   RequiredPacePerWorkday = RemainingVisits / max(1, RemainingQuarterWorkdays)
 *   WeeklyWindowCapacity = count of confirmed weekly availability windows across active clinics
 *   RiskScore (0..100) = clamp(
 *      (RequiredPacePerWorkday / (DailyTarget / 12.0)) * 55 +
 *      (if (WeeklyWindowCapacity <= 1) 25 else if (WeeklyWindowCapacity == 2) 10 else 0) +
 *      (if (isOverdue) 20 else 0)
 *   )
 */
object CoverageCalculator {

    fun calculateMetrics(
        hcp: HcpEntity,
        clinics: List<ClinicWithAvailability>,
        visits: List<VisitEntity>,
        settings: WorkSettings,
        referenceDateIso: String = "2026-10-29"
    ): HcpCoverageMetrics {
        val qualifyingCompletedLedgerVisits = visits.count {
            it.status == "COMPLETED" &&
                it.qualifiesForCoverage &&
                it.quarterLabel == settings.currentQuarterLabel
        }

        val baselineAddition = if (hcp.baselineQuarterLabel == settings.currentQuarterLabel) {
            max(0, hcp.importedBaselineVisits)
        } else {
            0
        }

        val totalCompletedThisQuarter = qualifyingCompletedLedgerVisits + baselineAddition
        val remainingVisits = max(0, hcp.quarterlyTarget - totalCompletedThisQuarter)

        val lastCompletedIso = visits
            .filter { it.status == "COMPLETED" }
            .maxByOrNull { it.plannedDateIso }
            ?.plannedDateIso

        val daysSinceLast = if (lastCompletedIso != null) {
            try {
                val lastDate = LocalDate.parse(lastCompletedIso)
                val refDate = LocalDate.parse(referenceDateIso)
                ChronoUnit.DAYS.between(lastDate, refDate).toInt().coerceAtLeast(0)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        // Expected ideal spacing across a 90-day quarter:
        val idealIntervalDays = if (hcp.quarterlyTarget > 0) {
            (90.0 / hcp.quarterlyTarget).roundToInt().coerceAtLeast(7)
        } else {
            90
        }
        val effectiveOverdueDays = min(settings.overdueThresholdDays, idealIntervalDays)

        val isOverdue: Boolean
        val overdueReason: String
        when {
            remainingVisits == 0 -> {
                isOverdue = false
                overdueReason = "Quarterly coverage target (${hcp.quarterlyTarget}) already met."
            }
            daysSinceLast != null && daysSinceLast >= effectiveOverdueDays -> {
                isOverdue = true
                overdueReason = "$daysSinceLast days since last visit (threshold: $effectiveOverdueDays days)."
            }
            daysSinceLast == null && totalCompletedThisQuarter == 0 && remainingVisits > 0 -> {
                isOverdue = true
                overdueReason = "No completed visit recorded yet this quarter ($remainingVisits required)."
            }
            else -> {
                isOverdue = false
                overdueReason = "Visited ${daysSinceLast ?: 0} days ago (within $effectiveOverdueDays-day threshold)."
            }
        }

        val confirmedWeeklyWindows = clinics.sumOf { c ->
            c.windows.count { w -> w.dayOfWeek in settings.workingDaysIso }
        }
        val verifiedLocationsCount = clinics.count { c ->
            c.clinic.latitude != null && c.clinic.longitude != null &&
                (c.clinic.geocodingStatus == "VERIFIED" || c.clinic.geocodingStatus == "EXTRACTED_URL")
        }

        val remWorkdays = max(1, settings.remainingQuarterWorkdays)
        val elapsedWorkdays = max(1, 65 - remWorkdays)
        val currentPacePerDay = totalCompletedThisQuarter.toDouble() / elapsedWorkdays.toDouble()
        val projectedAdditional = if (confirmedWeeklyWindows > 0 && verifiedLocationsCount > 0) {
            max((currentPacePerDay * remWorkdays).roundToInt(), min(remainingVisits, remWorkdays / 6))
        } else {
            0
        }
        val projectedQuarterEnd = min(hcp.quarterlyTarget, totalCompletedThisQuarter + projectedAdditional)

        if (remainingVisits == 0) {
            return HcpCoverageMetrics(
                quarterlyTarget = hcp.quarterlyTarget,
                completedVisitsThisQuarter = totalCompletedThisQuarter,
                remainingVisits = 0,
                lastCompletedVisitDateIso = lastCompletedIso,
                daysSinceLastVisit = daysSinceLast,
                isOverdue = false,
                overdueReason = overdueReason,
                riskLevel = CoverageRiskLevel.ON_TRACK,
                riskScore = 0.0,
                riskFormulaExplanation = "Target complete ($totalCompletedThisQuarter/${hcp.quarterlyTarget} visits). Risk score = 0.",
                projectedQuarterEndVisits = totalCompletedThisQuarter
            )
        }

        val requiredVisitsPerWeek = remainingVisits.toDouble() / (remWorkdays / 6.0).coerceAtLeast(1.0)
        val paceComponent = (requiredVisitsPerWeek / 1.5).coerceIn(0.0, 1.0) * 50.0
        val windowPenalty = when {
            confirmedWeeklyWindows == 0 || verifiedLocationsCount == 0 -> 30.0
            confirmedWeeklyWindows == 1 -> 18.0
            confirmedWeeklyWindows == 2 -> 8.0
            else -> 0.0
        }
        val overduePenalty = if (isOverdue) 20.0 else 0.0
        val rawScore = (paceComponent + windowPenalty + overduePenalty).coerceIn(0.0, 100.0)

        val riskLevel = when {
            rawScore >= 75.0 || confirmedWeeklyWindows == 0 || verifiedLocationsCount == 0 -> CoverageRiskLevel.CRITICAL_AT_RISK
            rawScore >= 50.0 -> CoverageRiskLevel.HIGH_RISK
            rawScore >= 28.0 -> CoverageRiskLevel.MODERATE_RISK
            else -> CoverageRiskLevel.ON_TRACK
        }

        val formulaExplanation = String.format(
            Locale.US,
            "Formula: Pace(%.1f/50 from %d rem visits over %d workdays) + WindowAccess(%.0f/30 from %d weekly windows & %d verified GPS) + Overdue(%.0f/20) = %.0f/100 (%s)",
            paceComponent,
            remainingVisits,
            remWorkdays,
            windowPenalty,
            confirmedWeeklyWindows,
            verifiedLocationsCount,
            overduePenalty,
            rawScore,
            riskLevel.label
        )

        return HcpCoverageMetrics(
            quarterlyTarget = hcp.quarterlyTarget,
            completedVisitsThisQuarter = totalCompletedThisQuarter,
            remainingVisits = remainingVisits,
            lastCompletedVisitDateIso = lastCompletedIso,
            daysSinceLastVisit = daysSinceLast,
            isOverdue = isOverdue,
            overdueReason = overdueReason,
            riskLevel = riskLevel,
            riskScore = rawScore,
            riskFormulaExplanation = formulaExplanation,
            projectedQuarterEndVisits = projectedQuarterEnd
        )
    }
}
