package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.parser.AvailabilityScheduleParser
import com.example.ui.TerritoryViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("TerritoryRx", appName)
  }

  @Test
  fun `mainActivity launches and TerritoryViewModel initializes without crashing`() {
    val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity = controller.get()
    assertNotNull(activity)

    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = TerritoryViewModel(app)
    assertNotNull(viewModel)

    // Verify multi-clause and typo schedules parse cleanly
    val parsed = AvailabilityScheduleParser.parseForExcelImport(
      "Sunday from 8:10 PM, Tuesday and Wednesday from 3 or 4 : 6 PM"
    )
    assertTrue(parsed.windows.isNotEmpty())
    controller.destroy()
  }

  @Test
  fun `multi clinic schedules and HCP card edits save and persist accurately`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.AppDatabase::class.java
    ).allowMainThreadQueries().build()

    val repo = com.example.data.repository.TerritoryRepository(
      context = context,
      dao = db.territoryDao(),
      hcpDao = db.hcpDao()
    )

    kotlinx.coroutines.runBlocking {
      val bytes = com.example.domain.importer.ExcelTerritoryImporter.generateSampleWestXlsxBytes()
      val sheets = com.example.domain.importer.ExcelTerritoryImporter.parseXlsx(java.io.ByteArrayInputStream(bytes))
      val preview = com.example.domain.importer.ExcelTerritoryImporter.buildPreviewReport(
        fileName = "West.xlsx",
        sheets = sheets,
        selectedSheetIndex = 0
      )
      repo.executeImport(preview, com.example.domain.importer.ImportMode.REPLACE_ALL)

      val drAhmed = db.territoryDao().getHcpByTargetId("W-1001")!!
      val clinicsBefore = db.territoryDao().getClinicsForHcp(drAhmed.id).sortedBy { it.clinicIndex }
      assertEquals(2, clinicsBefore.size)

      val c1Wins = db.territoryDao().getWindowsForClinic(clinicsBefore[0].id)
      val c2Wins = db.territoryDao().getWindowsForClinic(clinicsBefore[1].id)
      // Clinic 1: Saturday (6) and Monday (1) 2-6 PM (840..1080)
      assertEquals(setOf(6, 1), c1Wins.map { it.dayOfWeek }.toSet())
      assertEquals(840, c1Wins.first().startMinuteOfDay)
      assertEquals(1080, c1Wins.first().endMinuteOfDay)
      // Clinic 2: Sunday (7) and Tuesday (2) 7-10 PM (1140..1320)
      assertEquals(setOf(7, 2), c2Wins.map { it.dayOfWeek }.toSet())
      assertEquals(1140, c2Wins.first().startMinuteOfDay)
      assertEquals(1320, c2Wins.first().endMinuteOfDay)

      // Edit HCP card data + Clinic 2 schedule text without manually building windows list
      repo.saveFullHcpAndClinicEdits(
        hcp = drAhmed,
        updatedName = "Dr. Ahmed El-Masry Updated",
        updatedSpecialty = "Interventional Cardiology",
        updatedQuarterlyTarget = 10,
        baselineCompletedCount = 3,
        quarterLabel = "2026-Q4",
        historyIncomplete = false,
        clinic = clinicsBefore[1],
        updatedClinicZone = "Smouha",
        updatedClinicLocation = "https://maps.google.com/?q=31.2156,29.9556",
        updatedClinicRawSchedule = "Wednesday and Thursday 5-9 PM",
        updatedWindows = c2Wins, // even if old windows were passed, typed schedule text takes effect!
        updatedLat = 31.2156,
        updatedLng = 29.9556
      )

      val updatedHcp = db.territoryDao().getHcpByTargetId("W-1001")!!
      assertEquals("Dr. Ahmed El-Masry Updated", updatedHcp.name)
      assertEquals("Interventional Cardiology", updatedHcp.specialty)
      assertEquals(10, updatedHcp.quarterlyTarget)
      assertEquals(3, updatedHcp.importedBaselineVisits)

      val clinicsAfter = db.territoryDao().getClinicsForHcp(drAhmed.id).sortedBy { it.clinicIndex }
      val c2After = clinicsAfter[1]
      assertEquals("Smouha", c2After.zone)
      assertEquals("CONFIRMED", c2After.scheduleParseStatus)
      val c2WinsAfter = db.territoryDao().getWindowsForClinic(c2After.id)
      assertEquals(setOf(3, 4), c2WinsAfter.map { it.dayOfWeek }.toSet())
      assertEquals(17 * 60, c2WinsAfter.first().startMinuteOfDay)
      assertEquals(21 * 60, c2WinsAfter.first().endMinuteOfDay)

      // Verify database migration splits composite bricks ("Brick A • Brick B"), rebuilds brick registry,
      // and preserves all HCPs, clinics, and visit execution history
      val legacyHcpId = db.territoryDao().insertHcp(
        com.example.data.model.HcpEntity(
          targetId = "W-9001",
          name = "Dr. Legacy Composite",
          specialty = "Cardiology",
          quarterlyTarget = 8,
          territoryZone = "Brick Alpha • Brick Beta"
        )
      )
      val legacyClinic1Id = db.territoryDao().insertClinic(
        com.example.data.model.ClinicEntity(
          hcpId = legacyHcpId,
          clinicIndex = 1,
          label = "Clinic 1",
          zone = "Brick Alpha • Brick Beta",
          originalLocationText = "https://www.google.com/maps?q=31.1000,29.7500 - https://www.google.com/maps?q=31.0500,29.8000",
          googleMapsUrl = "https://www.google.com/maps?q=31.1000,29.7500",
          latitude = 31.1000,
          longitude = 29.7500,
          geocodingStatus = "EXTRACTED_URL",
          rawScheduleText = "Saturday 2-6 PM",
          scheduleParseStatus = "PARSED_HIGH_CONFIDENCE",
          userConfirmedSchedule = true
        )
      )
      db.territoryDao().insertVisit(
        com.example.data.model.VisitEntity(
          hcpId = legacyHcpId,
          hcpTargetId = "W-9001",
          clinicId = legacyClinic1Id,
          clinicLabel = "Clinic 1",
          plannedDateIso = "2026-10-25",
          quarterLabel = "2026-Q4",
          status = "COMPLETED",
          notes = "Completed visit before migration"
        )
      )

      repo.migrateCompositeBricksAndRebuildRegistry()

      val migratedHcp = db.territoryDao().getHcpByTargetId("W-9001")!!
      assertEquals("Brick Alpha", migratedHcp.territoryZone)
      val migratedClinics = db.territoryDao().getClinicsForHcp(legacyHcpId).sortedBy { it.clinicIndex }
      assertEquals(2, migratedClinics.size)
      assertEquals("Brick Alpha", migratedClinics[0].zone)
      assertEquals("Brick Beta", migratedClinics[1].zone)

      // Completed visit history preserved
      val preservedVisits = db.territoryDao().getVisitsForHcp(legacyHcpId)
      assertEquals(1, preservedVisits.size)
      assertEquals("COMPLETED", preservedVisits.first().status)

      // Active brick registry contains "Brick Alpha" and "Brick Beta" separately, never "Brick Alpha • Brick Beta"
      val activeBricks = db.territoryDao().getAllActiveBricks().map { it.name }
      assertTrue(activeBricks.contains("Brick Alpha"))
      assertTrue(activeBricks.contains("Brick Beta"))
      assertTrue(activeBricks.none { it.contains("•") || it.contains("+") })
    }
    db.close()
  }

  @Test
  fun `auditAllImportedSchedules corrects false ambiguity without modifying original Excel text or visits`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.AppDatabase::class.java
    ).allowMainThreadQueries().build()

    val repo = com.example.data.repository.TerritoryRepository(
      context = context,
      dao = db.territoryDao(),
      hcpDao = db.hcpDao()
    )

    kotlinx.coroutines.runBlocking {
      val hcpId = db.territoryDao().insertHcp(
        com.example.data.model.HcpEntity(
          targetId = "W-777",
          name = "Dr. False Ambiguity Test",
          specialty = "Cardiology",
          quarterlyTarget = 8,
          territoryZone = "Mohandessin"
        )
      )

      val falselyAmbiguousClinicId = db.territoryDao().insertClinic(
        com.example.data.model.ClinicEntity(
          hcpId = hcpId,
          clinicIndex = 1,
          label = "Clinic 1 (Mohandessin)",
          zone = "Mohandessin",
          originalLocationText = "https://maps.google.com/?q=30.06,31.20",
          rawScheduleText = "Monday, Tuesday, Thursday; 8 pm : 10 PM",
          originalExcelScheduleText = "Monday, Tuesday, Thursday; 8 pm : 10 PM",
          timeInfoType = "REQUIRES_MANUAL_CLARIFICATION",
          scheduleParseStatus = "AMBIGUOUS",
          scheduleAmbiguityReason = "Missing weekdays; Without explicit clock hours",
          userConfirmedSchedule = false
        )
      )

      val genuinelyAmbiguousClinicId = db.territoryDao().insertClinic(
        com.example.data.model.ClinicEntity(
          hcpId = hcpId,
          clinicIndex = 2,
          label = "Clinic 2 (Dokki)",
          zone = "Dokki",
          originalLocationText = "https://maps.google.com/?q=30.04,31.21",
          rawScheduleText = "Monday 8-10",
          originalExcelScheduleText = "Monday 8-10",
          timeInfoType = "REQUIRES_MANUAL_CLARIFICATION",
          scheduleParseStatus = "AMBIGUOUS",
          scheduleAmbiguityReason = "Missing AM/PM context",
          userConfirmedSchedule = false
        )
      )

      db.territoryDao().insertVisit(
        com.example.data.model.VisitEntity(
          hcpId = hcpId,
          hcpTargetId = "W-777",
          clinicId = falselyAmbiguousClinicId,
          clinicLabel = "Clinic 1 (Mohandessin)",
          plannedDateIso = "2026-11-01",
          quarterLabel = "2026-Q4",
          status = "COMPLETED",
          notes = "Existing completed call"
        )
      )

      val auditSummary = repo.auditAllImportedSchedulesAndCorrectFalseAmbiguity()
      assertEquals(2, auditSummary.totalClinicsAudited)
      assertEquals(1, auditSummary.correctedFalseAmbiguityCount)
      assertEquals(1, auditSummary.confirmedCount)
      assertEquals(1, auditSummary.genuineClarificationCount)

      val fixedClinic = db.territoryDao().getClinicsForHcp(hcpId).first { it.id == falselyAmbiguousClinicId }
      assertEquals("Monday, Tuesday, Thursday; 8 pm : 10 PM", fixedClinic.originalExcelScheduleText)
      assertEquals("PARSED_HIGH_CONFIDENCE", fixedClinic.scheduleParseStatus)
      assertEquals("CLINIC_AVAILABILITY_RANGE", fixedClinic.timeInfoType)
      assertEquals("", fixedClinic.scheduleAmbiguityReason)
      assertTrue(fixedClinic.userConfirmedSchedule)
      assertEquals(20 * 60, fixedClinic.exactStartMinute)
      assertEquals(22 * 60, fixedClinic.exactEndMinute)

      val fixedWindows = db.territoryDao().getWindowsForClinic(falselyAmbiguousClinicId)
      assertEquals(setOf(1, 2, 4), fixedWindows.map { it.dayOfWeek }.toSet())
      assertTrue(fixedWindows.all { it.isConfirmed && it.startMinuteOfDay == 20 * 60 && it.endMinuteOfDay == 22 * 60 })

      val stillAmbiguousClinic = db.territoryDao().getClinicsForHcp(hcpId).first { it.id == genuinelyAmbiguousClinicId }
      assertEquals("Monday 8-10", stillAmbiguousClinic.originalExcelScheduleText)
      assertEquals("AMBIGUOUS", stillAmbiguousClinic.scheduleParseStatus)
      assertTrue(!stillAmbiguousClinic.userConfirmedSchedule)

      val visitsAfter = db.territoryDao().getVisitsForHcp(hcpId)
      assertEquals(1, visitsAfter.size)
      assertEquals("COMPLETED", visitsAfter.first().status)
    }
    db.close()
  }

  @Test
  fun `safe migration preview and apply protect USER_CONFIRMED edits while fixing outdated records`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.AppDatabase::class.java
    ).allowMainThreadQueries().build()

    val repo = com.example.data.repository.TerritoryRepository(
      context = context,
      dao = db.territoryDao(),
      hcpDao = db.hcpDao()
    )

    kotlinx.coroutines.runBlocking {
      val hcpId = db.territoryDao().insertHcp(
        com.example.data.model.HcpEntity(
          targetId = "MIG-01",
          name = "Dr. Migration Guard",
          specialty = "Cardiology",
          quarterlyTarget = 8,
          territoryZone = "Smouha"
        )
      )

      // Clinic 1: Outdated auto-parsed record with Rule 2 / weekday bug -> should be migrated safely
      val outdatedClinicId = db.territoryDao().insertClinic(
        com.example.data.model.ClinicEntity(
          hcpId = hcpId,
          clinicIndex = 1,
          label = "Clinic 1 (Smouha)",
          zone = "Smouha",
          latitude = 31.2156,
          longitude = 29.9553,
          geocodingStatus = "VERIFIED",
          rawScheduleText = "From 1 PM : 7PM",
          originalExcelScheduleText = "From 1 PM : 7PM",
          scheduleParseStatus = "AMBIGUOUS",
          scheduleClassification = "REQUIRES_MANUAL_CLARIFICATION",
          parsingMethod = "DETERMINISTIC",
          validationStatus = "NEEDS_CLARIFICATION",
          userConfirmedSchedule = false,
          isManualUserConfirmed = false
        )
      )

      // Clinic 2: Manually confirmed by user -> MUST be protected from Safe Migration AND Excel Re-Import!
      val protectedClinicId = db.territoryDao().insertClinic(
        com.example.data.model.ClinicEntity(
          hcpId = hcpId,
          clinicIndex = 2,
          label = "Clinic 2 (Dokki)",
          zone = "Dokki",
          latitude = 30.0410,
          longitude = 31.2110,
          geocodingStatus = "VERIFIED",
          rawScheduleText = "Sunday 5-7 PM",
          originalExcelScheduleText = "Monday 4-6 PM",
          scheduleParseStatus = "CONFIRMED",
          scheduleClassification = "EXPLICIT_WEEKDAYS_AND_TIME",
          parsingMethod = "USER_CONFIRMED",
          validationStatus = "VALID",
          userConfirmedSchedule = true,
          isManualUserConfirmed = true
        )
      )
      db.territoryDao().insertAvailabilityWindows(
        listOf(
          com.example.data.model.AvailabilityWindowEntity(
            clinicId = protectedClinicId,
            dayOfWeek = 7,
            startMinuteOfDay = 17 * 60,
            endMinuteOfDay = 19 * 60,
            isConfirmed = true
          )
        )
      )

      val preview = repo.previewScheduleMigration()
      assertEquals(2, preview.totalClinicsInspected)
      assertEquals(1, preview.candidatesToUpdate.size)
      assertEquals(1, preview.protectedUserConfirmedCount)
      assertEquals(outdatedClinicId, preview.candidatesToUpdate.single().clinicId)

      val summary = repo.applyScheduleMigration(allowOverwriteUserConfirmed = false)
      assertEquals(1, summary.correctedFalseAmbiguityCount)

      val migratedClinic1 = db.territoryDao().getClinicsForHcp(hcpId).first { it.id == outdatedClinicId }
      assertEquals("VALID", migratedClinic1.validationStatus)
      assertEquals("DAILY_DEFAULT_WITH_TIME", migratedClinic1.scheduleClassification)
      assertEquals(31.2156, migratedClinic1.latitude!!, 0.0001)
      val c1Wins = db.territoryDao().getWindowsForClinic(outdatedClinicId)
      assertEquals((1..7).toSet(), c1Wins.map { it.dayOfWeek }.toSet())

      // Protected clinic kept manual Sunday 5-7 PM edit untouched!
      val c2WinsAfter = db.territoryDao().getWindowsForClinic(protectedClinicId)
      assertEquals(listOf(7), c2WinsAfter.map { it.dayOfWeek })
      assertEquals(17 * 60, c2WinsAfter.single().startMinuteOfDay)

      // Now simulate re-importing an Excel sheet that tries to overwrite Dr. Migration Guard's Clinic 2
      val reimportCsv = """
        target_id,HCP Name,Specialty,zone 1,clinic 1 GPS,clinic 1 times,zone 2,clinic 2 GPS,clinic 2 times,Frequency
        MIG-01,Dr. Migration Guard,Cardiology,Smouha,"https://maps.google.com/?q=31.2156,29.9553","From 1 PM : 7PM",Dokki,"https://maps.google.com/?q=30.0410,31.2110","Monday 4-6 PM",8
      """.trimIndent()
      val sheets = com.example.domain.importer.ExcelTerritoryImporter.parseCsv(
        java.io.ByteArrayInputStream(reimportCsv.toByteArray(Charsets.UTF_8))
      )
      val existingById = db.territoryDao().getAllActiveHcps().associateBy { it.targetId }
      val reimportPreview = com.example.domain.importer.ExcelTerritoryImporter.buildPreviewReport(
        fileName = "Reimport.csv",
        sheets = sheets,
        existingHcpsByTargetId = existingById
      )
      repo.executeImport(reimportPreview, com.example.domain.importer.ImportMode.UPDATE_EXISTING)

      // Clinic 2 manual schedule MUST still be protected (Sunday 5-7 PM)
      val c2AfterReimport = db.territoryDao().getClinicsForHcp(hcpId).first { it.clinicIndex == 2 }
      assertTrue(c2AfterReimport.isManualUserConfirmed)
      val c2WinsReimport = db.territoryDao().getWindowsForClinic(c2AfterReimport.id)
      assertEquals(listOf(7), c2WinsReimport.map { it.dayOfWeek })
    }
    db.close()
  }
}

