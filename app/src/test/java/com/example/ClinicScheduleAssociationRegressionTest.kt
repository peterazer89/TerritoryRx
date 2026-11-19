package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.AvailabilityWindowEntity
import com.example.data.model.ClinicEntity
import com.example.data.model.HcpEntity
import com.example.data.model.PlannedStopEntity
import com.example.data.model.DailyPlanEntity
import com.example.data.model.DailyPlanWithStops
import com.example.data.repository.TerritoryRepository
import com.example.domain.importer.ExcelTerritoryImporter
import com.example.domain.importer.ImportMode
import com.example.domain.optimizer.PlanValidator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * Mandatory regression suite for CRITICAL BUG FIX — CLINIC SCHEDULE CROSS-ASSIGNMENT (Section 10: TEST A – TEST J).
 * Verifies database foreign-key relationships, schedule provenance fields, Excel column & row independence,
 * editing isolation, cloud synchronization stability, optimizer/validator exact clinicId lookup, and rejection of
 * mismatched (hcpId, clinicId) saves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClinicScheduleAssociationRegressionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: TerritoryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = TerritoryRepository(
            context = context,
            dao = db.territoryDao(),
            hcpDao = db.hcpDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * TEST A: One HCP with two clinics and two different schedules.
     * Verifies unique clinicIds, independent schedules, and required provenance fields on every schedule record:
     * scheduleId, hcpId, clinicId, originalExcelText, parsedTimeWindows, sourceSheet, sourceRow, sourceColumn, parsingStatus.
     */
    @Test
    fun `TEST A - one HCP with two clinics and two different schedules owns independent schedule records`() = runBlocking {
        val csv = """
            target_id,HCP Name,Specialty,zone 1,clinic 1 GPS,clinic 1 times,zone 2,clinic 2 GPS,clinic 2 times,Frequency
            HCP-A1,Dr. Tarek Mansour,Cardiology,Smouha,"https://maps.google.com/?q=31.2156,29.9553","Saturday and Monday 2:00 PM - 5:00 PM",Agamy,"https://maps.google.com/?q=31.1045,29.7667","Sunday and Wednesday 7:00 PM - 10:00 PM",8
        """.trimIndent()

        val sheets = ExcelTerritoryImporter.parseCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        val report = ExcelTerritoryImporter.buildPreviewReport(
            fileName = "TestA.csv",
            sheets = sheets
        )
        repo.executeImport(report, ImportMode.REPLACE_ALL)

        val hcp = db.territoryDao().getHcpByTargetId("HCP-A1")!!
        val clinics = db.territoryDao().getClinicsForHcp(hcp.id).sortedBy { it.clinicIndex }
        assertEquals(2, clinics.size)

        val clinic1 = clinics[0]
        val clinic2 = clinics[1]
        assertNotEquals("Clinic 1 and Clinic 2 must have distinct unique clinicIds", clinic1.id, clinic2.id)
        assertEquals("Smouha", clinic1.zone)
        assertEquals("Agamy", clinic2.zone)
        assertEquals("Saturday and Monday 2:00 PM - 5:00 PM", clinic1.originalExcelScheduleText)
        assertEquals("Sunday and Wednesday 7:00 PM - 10:00 PM", clinic2.originalExcelScheduleText)
        assertTrue(clinic1.sourceColumn.contains("clinic 1 times", ignoreCase = true))
        assertTrue(clinic2.sourceColumn.contains("clinic 2 times", ignoreCase = true))

        val c1Windows = db.territoryDao().getWindowsForClinic(clinic1.id)
        val c2Windows = db.territoryDao().getWindowsForClinic(clinic2.id)
        assertEquals(setOf(6, 1), c1Windows.map { it.dayOfWeek }.toSet())
        assertEquals(setOf(7, 3), c2Windows.map { it.dayOfWeek }.toSet())

        c1Windows.forEach { win ->
            assertTrue(win.scheduleId > 0L)
            assertEquals(hcp.id, win.hcpId)
            assertEquals(clinic1.id, win.clinicId)
            assertEquals("Saturday and Monday 2:00 PM - 5:00 PM", win.originalExcelText)
            assertTrue(win.parsedTimeWindows.isNotBlank())
            assertTrue(win.sourceSheet.isNotBlank())
            assertEquals(2, win.sourceRow)
            assertTrue(win.sourceColumn.contains("clinic 1 times", ignoreCase = true))
            assertTrue(win.parsingStatus in setOf("CONFIRMED", "PARSED_HIGH_CONFIDENCE"))
            assertEquals(14 * 60, win.startMinuteOfDay)
            assertEquals(17 * 60, win.endMinuteOfDay)
        }

        c2Windows.forEach { win ->
            assertTrue(win.scheduleId > 0L)
            assertEquals(hcp.id, win.hcpId)
            assertEquals(clinic2.id, win.clinicId)
            assertEquals("Sunday and Wednesday 7:00 PM - 10:00 PM", win.originalExcelText)
            assertTrue(win.parsedTimeWindows.isNotBlank())
            assertTrue(win.sourceSheet.isNotBlank())
            assertEquals(2, win.sourceRow)
            assertTrue(win.sourceColumn.contains("clinic 2 times", ignoreCase = true))
            assertTrue(win.parsingStatus in setOf("CONFIRMED", "PARSED_HIGH_CONFIDENCE"))
            assertEquals(19 * 60, win.startMinuteOfDay)
            assertEquals(22 * 60, win.endMinuteOfDay)
        }

        // Also verify blank Clinic 2 schedule column never copies Clinic 1's schedule into Clinic 2
        val blankC2Csv = """
            target_id,HCP Name,Specialty,zone 1,clinic 1 GPS,clinic 1 times,zone 2,clinic 2 GPS,clinic 2 times,Frequency
            HCP-A2,Dr. Blank SecondSched,Cardiology,Smouha,"https://maps.google.com/?q=31.2156,29.9553","Tuesday 4:00 PM - 7:00 PM",Agamy,"https://maps.google.com/?q=31.1045,29.7667","",8
        """.trimIndent()
        val blankSheets = ExcelTerritoryImporter.parseCsv(ByteArrayInputStream(blankC2Csv.toByteArray(Charsets.UTF_8)))
        val blankReport = ExcelTerritoryImporter.buildPreviewReport("BlankC2.csv", blankSheets)
        repo.executeImport(blankReport, ImportMode.UPDATE_EXISTING)
        repo.auditAllImportedSchedulesAndCorrectFalseAmbiguity()

        val hcpA2 = db.territoryDao().getHcpByTargetId("HCP-A2")!!
        val a2Clinics = db.territoryDao().getClinicsForHcp(hcpA2.id).sortedBy { it.clinicIndex }
        assertEquals(2, a2Clinics.size)
        val a2c1Wins = db.territoryDao().getWindowsForClinic(a2Clinics[0].id)
        val a2c2Wins = db.territoryDao().getWindowsForClinic(a2Clinics[1].id)
        assertEquals(listOf(2), a2c1Wins.map { it.dayOfWeek })
        assertTrue("Clinic 2 with blank schedule cell must NOT inherit Clinic 1's Tuesday schedule", a2c2Wins.isEmpty())
    }

    /**
     * TEST B: Import the same Excel file twice.
     * Verifies stable clinicIds and identical schedule associations without duplication or swapping.
     */
    @Test
    fun `TEST B - importing the same Excel file twice preserves clinicIds and schedule associations`() = runBlocking {
        val csv = """
            target_id,HCP Name,Specialty,zone 1,clinic 1 GPS,clinic 1 times,zone 2,clinic 2 GPS,clinic 2 times,Frequency
            HCP-B1,Dr. Hany Adel,Internal Medicine,Mohandessin,"https://maps.google.com/?q=30.0561,31.2001","Monday, Thursday 1:00 PM - 4:00 PM",Dokki,"https://maps.google.com/?q=30.0385,31.2125","Sunday, Tuesday 6:00 PM - 9:00 PM",8
        """.trimIndent()

        val sheets1 = ExcelTerritoryImporter.parseCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        val report1 = ExcelTerritoryImporter.buildPreviewReport("Twice.csv", sheets1)
        repo.executeImport(report1, ImportMode.REPLACE_ALL)

        val hcpFirst = db.territoryDao().getHcpByTargetId("HCP-B1")!!
        val clinicsFirst = db.territoryDao().getClinicsForHcp(hcpFirst.id).sortedBy { it.clinicIndex }
        val c1IdBefore = clinicsFirst[0].id
        val c2IdBefore = clinicsFirst[1].id

        // Re-import the exact same file via UPDATE_EXISTING
        val existingMap = db.territoryDao().getAllActiveHcps().associateBy { it.targetId }
        val sheets2 = ExcelTerritoryImporter.parseCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        val report2 = ExcelTerritoryImporter.buildPreviewReport("Twice.csv", sheets2, existingHcpsByTargetId = existingMap)
        repo.executeImport(report2, ImportMode.UPDATE_EXISTING)

        val clinicsSecond = db.territoryDao().getClinicsForHcp(hcpFirst.id).sortedBy { it.clinicIndex }
        assertEquals(2, clinicsSecond.size)
        assertEquals("Clinic 1 ID must remain stable on re-import", c1IdBefore, clinicsSecond[0].id)
        assertEquals("Clinic 2 ID must remain stable on re-import", c2IdBefore, clinicsSecond[1].id)

        val c1Wins = db.territoryDao().getWindowsForClinic(c1IdBefore)
        val c2Wins = db.territoryDao().getWindowsForClinic(c2IdBefore)
        assertEquals(setOf(1, 4), c1Wins.map { it.dayOfWeek }.toSet())
        assertEquals(setOf(7, 2), c2Wins.map { it.dayOfWeek }.toSet())
        assertTrue(c1Wins.all { it.clinicId == c1IdBefore && it.hcpId == hcpFirst.id && it.startMinuteOfDay == 13 * 60 })
        assertTrue(c2Wins.all { it.clinicId == c2IdBefore && it.hcpId == hcpFirst.id && it.startMinuteOfDay == 18 * 60 })
    }

    /**
     * TEST C: Sort clinics alphabetically and verify schedules remain attached to their original clinicIds.
     */
    @Test
    fun `TEST C - sorting clinics alphabetically keeps schedules attached to their original clinicIds`() = runBlocking {
        val csv = """
            target_id,HCP Name,Specialty,zone 1,clinic 1 GPS,clinic 1 times,zone 2,clinic 2 GPS,clinic 2 times,Frequency
            HCP-C1,Dr. Sherif Zaki,Cardiology,Zayed,"https://maps.google.com/?q=30.0444,30.9833","Saturday 10:00 AM - 1:00 PM",Agouza,"https://maps.google.com/?q=30.0600,31.2100","Wednesday 8:00 PM - 10:00 PM",8
        """.trimIndent()

        val sheets = ExcelTerritoryImporter.parseCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        val report = ExcelTerritoryImporter.buildPreviewReport("SortTest.csv", sheets)
        repo.executeImport(report, ImportMode.REPLACE_ALL)

        val hcpWithDetails = repo.hcpsWithDetailsFlow.first().single { it.hcp.targetId == "HCP-C1" }
        val zayedClinic = hcpWithDetails.clinics.first { it.clinic.zone == "Zayed" }
        val agouzaClinic = hcpWithDetails.clinics.first { it.clinic.zone == "Agouza" }

        // Sort clinics alphabetically by zone/label ("Agouza" comes before "Zayed", reversing list order)
        val alphabeticallySorted = hcpWithDetails.clinics.sortedBy { it.clinic.zone }
        assertEquals(agouzaClinic.clinic.id, alphabeticallySorted[0].clinic.id)
        assertEquals(zayedClinic.clinic.id, alphabeticallySorted[1].clinic.id)

        // Verify each clinic in the sorted list still owns its exact schedule by clinicId
        assertEquals(listOf(3), alphabeticallySorted[0].windows.map { it.dayOfWeek })
        assertEquals(20 * 60, alphabeticallySorted[0].windows.single().startMinuteOfDay)
        assertEquals(agouzaClinic.clinic.id, alphabeticallySorted[0].windows.single().clinicId)

        assertEquals(listOf(6), alphabeticallySorted[1].windows.map { it.dayOfWeek })
        assertEquals(10 * 60, alphabeticallySorted[1].windows.single().startMinuteOfDay)
        assertEquals(zayedClinic.clinic.id, alphabeticallySorted[1].windows.single().clinicId)
    }

    /**
     * TEST D & TEST E:
     * - TEST D: Edit Clinic 1 and verify Clinic 2 remains unchanged.
     * - TEST E: Edit Clinic 2 and verify Clinic 1 remains unchanged.
     */
    @Test
    fun `TEST D and TEST E - editing Clinic 1 never alters Clinic 2 and editing Clinic 2 never alters Clinic 1`() = runBlocking {
        val csv = """
            target_id,HCP Name,Specialty,zone 1,clinic 1 GPS,clinic 1 times,zone 2,clinic 2 GPS,clinic 2 times,Frequency
            HCP-DE,Dr. Mona El-Kholy,Pediatrics,Smouha,"https://maps.google.com/?q=31.2156,29.9553","Saturday 2:00 PM - 5:00 PM",Agamy,"https://maps.google.com/?q=31.1045,29.7667","Sunday 6:00 PM - 9:00 PM",8
        """.trimIndent()

        val sheets = ExcelTerritoryImporter.parseCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        val report = ExcelTerritoryImporter.buildPreviewReport("EditIsolation.csv", sheets)
        repo.executeImport(report, ImportMode.REPLACE_ALL)

        val hcp = db.territoryDao().getHcpByTargetId("HCP-DE")!!
        val clinicsInit = db.territoryDao().getClinicsForHcp(hcp.id).sortedBy { it.clinicIndex }
        val clinic1Init = clinicsInit[0]
        val clinic2Init = clinicsInit[1]
        val c2WinsBefore = db.territoryDao().getWindowsForClinic(clinic2Init.id)

        // TEST D: Edit Clinic 1 to Monday & Wednesday 3:00 PM - 6:00 PM
        repo.saveFullHcpAndClinicEdits(
            hcp = hcp,
            updatedName = hcp.name,
            updatedSpecialty = hcp.specialty,
            updatedQuarterlyTarget = hcp.quarterlyTarget,
            baselineCompletedCount = 0,
            quarterLabel = "2026-Q4",
            historyIncomplete = false,
            clinic = clinic1Init,
            updatedClinicZone = clinic1Init.zone,
            updatedClinicLocation = clinic1Init.originalLocationText,
            updatedClinicRawSchedule = "Monday and Wednesday 3:00 PM - 6:00 PM",
            updatedWindows = emptyList(),
            updatedLat = clinic1Init.latitude,
            updatedLng = clinic1Init.longitude
        )

        val clinicsAfterD = db.territoryDao().getClinicsForHcp(hcp.id).sortedBy { it.clinicIndex }
        val c1WinsAfterD = db.territoryDao().getWindowsForClinic(clinic1Init.id)
        val c2WinsAfterD = db.territoryDao().getWindowsForClinic(clinic2Init.id)

        // Clinic 1 updated to Mon (1) & Wed (3) 3-6 PM
        assertEquals(setOf(1, 3), c1WinsAfterD.map { it.dayOfWeek }.toSet())
        assertEquals(15 * 60, c1WinsAfterD.first().startMinuteOfDay)
        // Clinic 2 completely untouched!
        assertEquals(clinic2Init.rawScheduleText, clinicsAfterD[1].rawScheduleText)
        assertEquals(c2WinsBefore.map { Triple(it.dayOfWeek, it.startMinuteOfDay, it.endMinuteOfDay) },
            c2WinsAfterD.map { Triple(it.dayOfWeek, it.startMinuteOfDay, it.endMinuteOfDay) })

        // TEST E: Now edit Clinic 2 to Tuesday & Thursday 7:00 PM - 10:00 PM and verify Clinic 1 remains Mon & Wed 3-6 PM
        repo.saveFullHcpAndClinicEdits(
            hcp = db.territoryDao().getHcpByTargetId("HCP-DE")!!,
            updatedName = hcp.name,
            updatedSpecialty = hcp.specialty,
            updatedQuarterlyTarget = hcp.quarterlyTarget,
            baselineCompletedCount = 0,
            quarterLabel = "2026-Q4",
            historyIncomplete = false,
            clinic = clinicsAfterD[1],
            updatedClinicZone = clinicsAfterD[1].zone,
            updatedClinicLocation = clinicsAfterD[1].originalLocationText,
            updatedClinicRawSchedule = "Tuesday and Thursday 7:00 PM - 10:00 PM",
            updatedWindows = emptyList(),
            updatedLat = clinicsAfterD[1].latitude,
            updatedLng = clinicsAfterD[1].longitude
        )

        val c1WinsAfterE = db.territoryDao().getWindowsForClinic(clinic1Init.id)
        val c2WinsAfterE = db.territoryDao().getWindowsForClinic(clinic2Init.id)

        // Clinic 1 STILL Mon (1) & Wed (3) 3-6 PM
        assertEquals(setOf(1, 3), c1WinsAfterE.map { it.dayOfWeek }.toSet())
        assertEquals(15 * 60, c1WinsAfterE.first().startMinuteOfDay)
        assertEquals(18 * 60, c1WinsAfterE.first().endMinuteOfDay)
        // Clinic 2 updated to Tue (2) & Thu (4) 7-10 PM
        assertEquals(setOf(2, 4), c2WinsAfterE.map { it.dayOfWeek }.toSet())
        assertEquals(19 * 60, c2WinsAfterE.first().startMinuteOfDay)
        assertEquals(22 * 60, c2WinsAfterE.first().endMinuteOfDay)
    }

    /**
     * TEST F: Synchronize data between local database and cloud, plus schedule audit,
     * and verify clinic schedules remain intact on their exact clinicIds.
     */
    @Test
    fun `TEST F - cloud sync and schedule audit preserve clinic schedules on their exact clinicIds`() = runBlocking {
        val csv = """
            target_id,HCP Name,Specialty,zone 1,clinic 1 GPS,clinic 1 times,zone 2,clinic 2 GPS,clinic 2 times,Frequency
            HCP-F1,Dr. Karim Nabil,Neurology,Smouha,"https://maps.google.com/?q=31.2156,29.9553","Monday 1:00 PM - 4:00 PM",Agamy,"https://maps.google.com/?q=31.1045,29.7667","Thursday 6:00 PM - 9:00 PM",8
        """.trimIndent()

        val sheets = ExcelTerritoryImporter.parseCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        val report = ExcelTerritoryImporter.buildPreviewReport("CloudSyncTest.csv", sheets)
        repo.executeImport(report, ImportMode.REPLACE_ALL)

        val hcp = db.territoryDao().getHcpByTargetId("HCP-F1")!!
        val clinicsBefore = db.territoryDao().getClinicsForHcp(hcp.id).sortedBy { it.clinicIndex }
        val c1Id = clinicsBefore[0].id
        val c2Id = clinicsBefore[1].id

        // Execute cloud sync attempt and schedule audit/migration
        repo.synchronizeWithCloud()
        repo.auditAllImportedSchedulesAndCorrectFalseAmbiguity()

        val clinicsAfter = db.territoryDao().getClinicsForHcp(hcp.id).sortedBy { it.clinicIndex }
        assertEquals(c1Id, clinicsAfter[0].id)
        assertEquals(c2Id, clinicsAfter[1].id)

        val c1Wins = db.territoryDao().getWindowsForClinic(c1Id)
        val c2Wins = db.territoryDao().getWindowsForClinic(c2Id)
        assertEquals(listOf(1), c1Wins.map { it.dayOfWeek })
        assertEquals(13 * 60, c1Wins.single().startMinuteOfDay)
        assertEquals(listOf(4), c2Wins.map { it.dayOfWeek })
        assertEquals(18 * 60, c2Wins.single().startMinuteOfDay)
    }

    /**
     * TEST G: Generate daily plans using both clinics.
     * Verifies that a visit on Clinic 1's day uses Clinic 1's clinicId and window,
     * and a visit on Clinic 2's day uses Clinic 2's clinicId and window.
     */
    @Test
    fun `TEST G - route optimizer selects Clinic 1 with Clinic 1 availability and Clinic 2 with Clinic 2 availability`() = runBlocking {
        val csv = """
            target_id,HCP Name,Specialty,zone 1,clinic 1 GPS,clinic 1 times,zone 2,clinic 2 GPS,clinic 2 times,Frequency
            HCP-G1,Dr. Yasser Fouad,Cardiology,Smouha,"https://maps.google.com/?q=31.2156,29.9553","Monday 2:00 PM - 5:00 PM",Agamy,"https://maps.google.com/?q=31.1045,29.7667","Wednesday 6:00 PM - 9:00 PM",8
        """.trimIndent()

        val sheets = ExcelTerritoryImporter.parseCsv(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))
        val report = ExcelTerritoryImporter.buildPreviewReport("OptimizerTest.csv", sheets)
        repo.executeImport(report, ImportMode.REPLACE_ALL)

        val hcp = db.territoryDao().getHcpByTargetId("HCP-G1")!!
        val clinics = db.territoryDao().getClinicsForHcp(hcp.id).sortedBy { it.clinicIndex }
        val clinic1 = clinics[0] // Monday 14:00 - 17:00
        val clinic2 = clinics[1] // Wednesday 18:00 - 21:00

        // Allow same-week scheduling in this clinic-schedule isolation test so Monday and Wednesday in the same week can both schedule HCP-G1
        repo.updateWorkSettings(
            repo.workSettingsFlow.first().copy(enforceWeeklyHcpVisitLimit = false)
        )

        // Plan for Monday 2026-11-16 -> MUST schedule Clinic 1 (clinic1.id) within 14:00..17:00
        val mondayOutcome = repo.generateAndSaveDailyPlans(dateIso = "2026-11-16")
        val mondayStops = mondayOutcome.alternatives.first().stops
        assertEquals(1, mondayStops.size)
        val monStop = mondayStops.single()
        assertEquals(clinic1.id, monStop.clinicId)
        assertEquals(14 * 60, monStop.windowStartMinute)
        assertEquals(17 * 60, monStop.windowEndMinute)
        assertTrue(monStop.visitStartMinute >= 14 * 60 && monStop.visitEndMinute <= 17 * 60)

        // Plan for Wednesday 2026-11-18 -> MUST schedule Clinic 2 (clinic2.id) within 18:00..21:00
        val wednesdayOutcome = repo.generateAndSaveDailyPlans(dateIso = "2026-11-18")
        val wednesdayStops = wednesdayOutcome.alternatives.first().stops
        assertEquals(1, wednesdayStops.size)
        val wedStop = wednesdayStops.single()
        assertEquals(clinic2.id, wedStop.clinicId)
        assertEquals(18 * 60, wedStop.windowStartMinute)
        assertEquals(21 * 60, wedStop.windowEndMinute)
        assertTrue(wedStop.visitStartMinute >= 18 * 60 && wedStop.visitEndMinute <= 21 * 60)
    }

    /**
     * TEST H: Import schedules in a different Excel row order.
     * Verifies that re-importing an Excel sheet with rows in reversed order (and multi-row clinic entries in reversed order)
     * preserves each HCP's and each clinic's exact schedule without cross-assignment.
     */
    @Test
    fun `TEST H - importing schedules in a different Excel row order preserves exact clinic schedule associations`() = runBlocking {
        val csvOrder1 = """
            target_id,HCP Name,Specialty,zone 1,clinic 1 GPS,clinic 1 times,zone 2,clinic 2 GPS,clinic 2 times,Frequency
            HCP-H1,Dr. First Doctor,Cardiology,Smouha,"https://maps.google.com/?q=31.2156,29.9553","Saturday 1:00 PM - 4:00 PM",Agamy,"https://maps.google.com/?q=31.1045,29.7667","Tuesday 6:00 PM - 9:00 PM",8
            HCP-H2,Dr. Second Doctor,Cardiology,Sidi Gaber,"https://maps.google.com/?q=31.2200,29.9400","Monday 3:00 PM - 7:00 PM",Miami,"https://maps.google.com/?q=31.2600,29.9900","Thursday 5:00 PM - 8:00 PM",8
        """.trimIndent()

        val sheets1 = ExcelTerritoryImporter.parseCsv(ByteArrayInputStream(csvOrder1.toByteArray(Charsets.UTF_8)))
        val report1 = ExcelTerritoryImporter.buildPreviewReport("Order1.csv", sheets1)
        repo.executeImport(report1, ImportMode.REPLACE_ALL)

        // Now re-import with HCP-H2 first and HCP-H1 second
        val csvOrder2 = """
            target_id,HCP Name,Specialty,zone 1,clinic 1 GPS,clinic 1 times,zone 2,clinic 2 GPS,clinic 2 times,Frequency
            HCP-H2,Dr. Second Doctor,Cardiology,Sidi Gaber,"https://maps.google.com/?q=31.2200,29.9400","Monday 3:00 PM - 7:00 PM",Miami,"https://maps.google.com/?q=31.2600,29.9900","Thursday 5:00 PM - 8:00 PM",8
            HCP-H1,Dr. First Doctor,Cardiology,Smouha,"https://maps.google.com/?q=31.2156,29.9553","Saturday 1:00 PM - 4:00 PM",Agamy,"https://maps.google.com/?q=31.1045,29.7667","Tuesday 6:00 PM - 9:00 PM",8
        """.trimIndent()

        val existingMap = db.territoryDao().getAllActiveHcps().associateBy { it.targetId }
        val sheets2 = ExcelTerritoryImporter.parseCsv(ByteArrayInputStream(csvOrder2.toByteArray(Charsets.UTF_8)))
        val report2 = ExcelTerritoryImporter.buildPreviewReport("Order2.csv", sheets2, existingHcpsByTargetId = existingMap)
        repo.executeImport(report2, ImportMode.UPDATE_EXISTING)

        val h1 = db.territoryDao().getHcpByTargetId("HCP-H1")!!
        val h1Clinics = db.territoryDao().getClinicsForHcp(h1.id).sortedBy { it.clinicIndex }
        assertEquals(listOf(6), db.territoryDao().getWindowsForClinic(h1Clinics[0].id).map { it.dayOfWeek })
        assertEquals(listOf(2), db.territoryDao().getWindowsForClinic(h1Clinics[1].id).map { it.dayOfWeek })

        val h2 = db.territoryDao().getHcpByTargetId("HCP-H2")!!
        val h2Clinics = db.territoryDao().getClinicsForHcp(h2.id).sortedBy { it.clinicIndex }
        assertEquals(listOf(1), db.territoryDao().getWindowsForClinic(h2Clinics[0].id).map { it.dayOfWeek })
        assertEquals(listOf(4), db.territoryDao().getWindowsForClinic(h2Clinics[1].id).map { it.dayOfWeek })
    }

    /**
     * TEST I: Import and validate schedules with duplicate clinic display names (`label` / `zone`).
     * Verifies that even if two clinics of the same HCP have the exact same display name (`label = "Main Clinic"`),
     * their schedules remain strictly separated by `clinicId` in the database, UI flow, and PlanValidator.
     */
    @Test
    fun `TEST I - duplicate clinic display names never cause schedule cross-assignment`() = runBlocking {
        val hcpId = db.territoryDao().insertHcp(
            HcpEntity(
                targetId = "HCP-I1",
                name = "Dr. Same Clinic Name",
                specialty = "Cardiology",
                quarterlyTarget = 8,
                territoryZone = "Smouha"
            )
        )

        val clinic1Id = db.territoryDao().insertClinic(
            ClinicEntity(
                hcpId = hcpId,
                clinicIndex = 1,
                label = "Shared Display Name",
                zone = "Smouha",
                originalLocationText = "Tower A, Smouha",
                latitude = 31.2156,
                longitude = 29.9553,
                geocodingStatus = "VERIFIED",
                rawScheduleText = "Saturday 1:00 PM - 4:00 PM",
                originalExcelScheduleText = "Saturday 1:00 PM - 4:00 PM",
                scheduleParseStatus = "CONFIRMED",
                userConfirmedSchedule = true
            )
        )

        val clinic2Id = db.territoryDao().insertClinic(
            ClinicEntity(
                hcpId = hcpId,
                clinicIndex = 2,
                label = "Shared Display Name",
                zone = "Smouha",
                originalLocationText = "Tower B, Smouha",
                latitude = 31.2200,
                longitude = 29.9600,
                geocodingStatus = "VERIFIED",
                rawScheduleText = "Wednesday 6:00 PM - 9:00 PM",
                originalExcelScheduleText = "Wednesday 6:00 PM - 9:00 PM",
                scheduleParseStatus = "CONFIRMED",
                userConfirmedSchedule = true
            )
        )

        val clinic1 = db.territoryDao().getClinicById(clinic1Id)!!
        val clinic2 = db.territoryDao().getClinicById(clinic2Id)!!

        repo.saveScheduleForHcpAndClinic(
            hcpId = hcpId,
            clinicId = clinic1Id,
            rawScheduleText = "Saturday 1:00 PM - 4:00 PM",
            windows = listOf(
                AvailabilityWindowEntity(
                    hcpId = hcpId,
                    clinicId = clinic1Id,
                    dayOfWeek = 6,
                    startMinuteOfDay = 13 * 60,
                    endMinuteOfDay = 16 * 60,
                    isConfirmed = true
                )
            )
        )

        repo.saveScheduleForHcpAndClinic(
            hcpId = hcpId,
            clinicId = clinic2Id,
            rawScheduleText = "Wednesday 6:00 PM - 9:00 PM",
            windows = listOf(
                AvailabilityWindowEntity(
                    hcpId = hcpId,
                    clinicId = clinic2Id,
                    dayOfWeek = 3,
                    startMinuteOfDay = 18 * 60,
                    endMinuteOfDay = 21 * 60,
                    isConfirmed = true
                )
            )
        )

        val c1After = db.territoryDao().getClinicById(clinic1Id)!!
        val c2After = db.territoryDao().getClinicById(clinic2Id)!!
        assertEquals("Shared Display Name", c1After.label)
        assertEquals("Shared Display Name", c2After.label)

        val c1Wins = db.territoryDao().getWindowsForClinic(clinic1Id)
        val c2Wins = db.territoryDao().getWindowsForClinic(clinic2Id)
        assertEquals(listOf(6), c1Wins.map { it.dayOfWeek })
        assertEquals(listOf(3), c2Wins.map { it.dayOfWeek })

        // Verify PlanValidator validates stop at clinic2Id against clinic2's Wednesday schedule, NOT clinic1's Saturday schedule
        val hcpWithDetails = repo.hcpsWithDetailsFlow.first().single { it.hcp.id == hcpId }
        val wednesdayPlan = DailyPlanEntity(
            id = 1L,
            dateIso = "2026-11-18", // Wednesday
            variantCode = "PLAN_A",
            variantLabel = "Plan A",
            startMinuteOfDay = 18 * 60,
            endMinuteOfDay = 19 * 60,
            totalDutySpanMinutes = 60,
            plannedVisitsCount = 1,
            uniqueHcpsCount = 1
        )
        val wednesdayStopAtClinic2 = PlannedStopEntity(
            id = 1L,
            planId = 1L,
            stopOrder = 1,
            hcpId = hcpId,
            hcpTargetId = "HCP-I1",
            hcpName = "Dr. Same Clinic Name",
            specialty = "Cardiology",
            clinicId = clinic2Id,
            clinicLabel = "Shared Display Name",
            zone = "Smouha",
            latitude = clinic2.latitude,
            longitude = clinic2.longitude,
            locationVerified = true,
            departureFromPrevMinute = 18 * 60,
            travelMinutesFromPrev = 10,
            distanceKmFromPrev = 2.0,
            trafficSource = "TYPICAL_ROAD",
            arrivalMinute = 18 * 60 + 10,
            waitingMinutes = 0,
            visitStartMinute = 18 * 60 + 10,
            visitDurationMinutes = 15,
            visitEndMinute = 18 * 60 + 25,
            windowStartMinute = 18 * 60,
            windowEndMinute = 21 * 60,
            priorityScore = 80,
            remainingQuarterlyVisits = 8
        )
        val validation = PlanValidator.validate(
            planWithStops = DailyPlanWithStops(wednesdayPlan, listOf(wednesdayStopAtClinic2)),
            settings = repo.workSettingsFlow.first(),
            returnHomeTravelMinutes = 10,
            hcpsById = mapOf(hcpId to hcpWithDetails)
        )
        assertTrue("Validator must match clinic2 by exact clinicId even with duplicate label: ${validation.summary}", validation.isValid)
    }

    /**
     * TEST J: Attempt to save a schedule with a mismatched hcpId and clinicId and verify it is rejected.
     */
    @Test
    fun `TEST J - saving a schedule whose clinicId does not belong to the specified hcpId is rejected`() = runBlocking {
        val hcp1Id = db.territoryDao().insertHcp(
            HcpEntity(
                targetId = "HCP-J1",
                name = "Dr. Owner One",
                specialty = "Cardiology",
                quarterlyTarget = 8,
                territoryZone = "Smouha"
            )
        )
        val hcp2Id = db.territoryDao().insertHcp(
            HcpEntity(
                targetId = "HCP-J2",
                name = "Dr. Owner Two",
                specialty = "Cardiology",
                quarterlyTarget = 8,
                territoryZone = "Agamy"
            )
        )

        val clinicOfHcp1Id = db.territoryDao().insertClinic(
            ClinicEntity(
                hcpId = hcp1Id,
                clinicIndex = 1,
                label = "Clinic 1 (Smouha)",
                zone = "Smouha",
                rawScheduleText = "Saturday 2:00 PM - 5:00 PM",
                originalExcelScheduleText = "Saturday 2:00 PM - 5:00 PM",
                scheduleParseStatus = "CONFIRMED",
                userConfirmedSchedule = true
            )
        )
        val clinicOfHcp2Id = db.territoryDao().insertClinic(
            ClinicEntity(
                hcpId = hcp2Id,
                clinicIndex = 1,
                label = "Clinic 1 (Agamy)",
                zone = "Agamy",
                rawScheduleText = "Monday 6:00 PM - 9:00 PM",
                originalExcelScheduleText = "Monday 6:00 PM - 9:00 PM",
                scheduleParseStatus = "CONFIRMED",
                userConfirmedSchedule = true
            )
        )
        db.territoryDao().insertAvailabilityWindows(
            listOf(
                AvailabilityWindowEntity(
                    hcpId = hcp2Id,
                    clinicId = clinicOfHcp2Id,
                    dayOfWeek = 1,
                    startMinuteOfDay = 18 * 60,
                    endMinuteOfDay = 21 * 60,
                    isConfirmed = true
                )
            )
        )

        // 1. Attempt to save schedule for hcp1Id targeting clinicOfHcp2Id -> MUST throw IllegalArgumentException
        try {
            repo.saveScheduleForHcpAndClinic(
                hcpId = hcp1Id,
                clinicId = clinicOfHcp2Id,
                rawScheduleText = "Thursday 1:00 PM - 3:00 PM",
                windows = listOf(
                    AvailabilityWindowEntity(
                        hcpId = hcp1Id,
                        clinicId = clinicOfHcp2Id,
                        dayOfWeek = 4,
                        startMinuteOfDay = 13 * 60,
                        endMinuteOfDay = 15 * 60,
                        isConfirmed = true
                    )
                )
            )
            fail("Expected IllegalArgumentException when saving schedule with mismatched hcpId and clinicId")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "Unexpected message: ${expected.message}",
                expected.message!!.contains("Schedule rejected", ignoreCase = true) &&
                    expected.message!!.contains("not specified hcpId", ignoreCase = true)
            )
        }

        // 2. Attempt to pass a window belonging to clinicOfHcp2Id when updating clinicOfHcp1Id -> MUST throw IllegalArgumentException
        val clinic1Entity = db.territoryDao().getClinicById(clinicOfHcp1Id)!!
        try {
            repo.updateClinicScheduleAndConfirm(
                clinic = clinic1Entity,
                updatedWindows = listOf(
                    AvailabilityWindowEntity(
                        hcpId = hcp1Id,
                        clinicId = clinicOfHcp2Id, // Mismatched clinicId!
                        dayOfWeek = 4,
                        startMinuteOfDay = 13 * 60,
                        endMinuteOfDay = 15 * 60,
                        isConfirmed = true
                    )
                ),
                expectedHcpId = hcp1Id
            )
            fail("Expected IllegalArgumentException when window clinicId does not match target clinic.id")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does not match target clinicId", ignoreCase = true))
        }

        // Verify clinicOfHcp2's original Monday 6-9 PM schedule is completely untouched
        val hcp2WinsAfter = db.territoryDao().getWindowsForClinic(clinicOfHcp2Id)
        assertEquals(listOf(1), hcp2WinsAfter.map { it.dayOfWeek })
        assertEquals(18 * 60, hcp2WinsAfter.single().startMinuteOfDay)
    }
}
