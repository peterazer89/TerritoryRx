package com.example.domain

import com.example.domain.importer.CanonicalField
import com.example.domain.importer.ExcelTerritoryImporter
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcelTerritoryImporterTest {

    @Test
    fun importSampleWestXlsx_mapsAll10StandardColumnsWithoutRenaming() {
        val bytes = ExcelTerritoryImporter.generateSampleWestXlsxBytes()
        val sheets = ExcelTerritoryImporter.parseXlsx(ByteArrayInputStream(bytes))
        val report = ExcelTerritoryImporter.buildPreviewReport(
            fileName = "West.xlsx",
            sheets = sheets,
            selectedSheetIndex = 0,
            existingHcpsByTargetId = emptyMap()
        )

        val mapped = report.columnMapping.fieldToColumnIndex
        assertEquals(10, mapped.size)
        assertTrue(mapped.containsKey(CanonicalField.TARGET_ID))
        assertTrue(mapped.containsKey(CanonicalField.HCP_NAME))
        assertTrue(mapped.containsKey(CanonicalField.SPECIALTY))
        assertTrue(mapped.containsKey(CanonicalField.CLINIC_1_ZONE))
        assertTrue(mapped.containsKey(CanonicalField.CLINIC_1_LOCATION))
        assertTrue(mapped.containsKey(CanonicalField.CLINIC_1_SCHEDULE))
        assertTrue(mapped.containsKey(CanonicalField.CLINIC_2_ZONE))
        assertTrue(mapped.containsKey(CanonicalField.CLINIC_2_LOCATION))
        assertTrue(mapped.containsKey(CanonicalField.CLINIC_2_SCHEDULE))
        assertTrue(mapped.containsKey(CanonicalField.FREQUENCY_PER_QUARTER))

        assertEquals(12, report.totalDetectedRows)
        assertEquals(12, report.newHcpsCount)
        assertTrue(report.rows.sumOf { it.clinics.size } > 12)
        assertTrue(report.ambiguousSchedulesCount >= 1)
    }

    @Test
    fun importCsvWithReorderedRenamedHeaders_andCustomColumns_andDuplicateDetection() {
        val csvContent = """
            Doctor Name,Target ID,Doctor Specialty,Quarterly Visits,Zone 1,Clinic 1 Map,Clinic 1 Hours,Zone 2,Clinic 2 Map,Clinic 2 Hours,Hospital Affiliation,Key Brick
            Dr. Ahmed Samir,30622,Internal Medicine,6,El-Sheikh Zayed 2,"https://www.google.com/maps?q=30.0444,30.9760",Saturday and Monday 2-6 PM,El-Sheikh Zayed 1,https://maps.app.goo.gl/uL3wY6P8,Sunday and Tuesday 7 PM-10 PM,Dar Al Fouad,Brick-A
            Dr. Duplicate Id,30622,Internal Medicine,6,El-Sheikh Zayed 2,,Monday 5-8 PM,,,,Dar Al Fouad,Brick-A
            Dr. Invalid Freq,30999,Pediatrics,-2,El-Sheikh Zayed 1,,Monday 5-8 PM,,,,Zayed Spec,Brick-B
        """.trimIndent()

        val sheets = ExcelTerritoryImporter.parseCsv(
            inputStream = ByteArrayInputStream(csvContent.toByteArray(Charsets.UTF_8))
        )
        val report = ExcelTerritoryImporter.buildPreviewReport(
            fileName = "CustomReordered.csv",
            sheets = sheets,
            selectedSheetIndex = 0,
            existingHcpsByTargetId = emptyMap()
        )

        assertEquals(3, report.totalDetectedRows)
        assertEquals(1, report.missingOrDuplicateIdsCount)
        assertEquals(1, report.invalidFrequencyCount)
        assertEquals(
            listOf("Hospital Affiliation", "Key Brick"),
            report.columnMapping.unmappedColumns.map { it.second }
        )

        val validRow = report.rows.first()
        assertEquals("30622", validRow.targetId)
        assertEquals(2, validRow.clinics.size)
        assertEquals("EXTRACTED_URL", validRow.clinics[0].geocodingStatus)
        assertNotNull(validRow.clinics[0].latitude)
        assertEquals(30.0444, validRow.clinics[0].latitude!!, 0.001)

        // Clinic 2 has short URL with known Sheikh Zayed zone -> EXTRACTED_URL with resolved coordinates
        assertEquals("EXTRACTED_URL", validRow.clinics[1].geocodingStatus)
        assertNotNull(validRow.clinics[1].latitude)
        assertEquals("Dar Al Fouad", validRow.customFields["Hospital Affiliation"])

        // Verify Arabic zone (العامرية) and Salesforce-style ID (0011i000004nGHqAAM) resolve without location warning
        val amreyaCoords = ExcelTerritoryImporter.extractCoordinates(
            rawLocation = "https://maps.app.goo.gl/xyzAmreya",
            zoneHint = "العامرية",
            rowSeed = "0011i000004nGHqAAM"
        )
        assertEquals("EXTRACTED_URL", amreyaCoords.geocodingStatus)
        assertNotNull(amreyaCoords.latitude)
        assertNotNull(amreyaCoords.longitude)
    }

    @Test
    fun parseSampleCsvTargetList_andAutoModelTierSelection() {
        val csvText = ExcelTerritoryImporter.generateSampleWestCsvText()
        val sheets = ExcelTerritoryImporter.parseFileBytes(
            fileName = "West_Target_List.csv",
            bytes = csvText.toByteArray(Charsets.UTF_8)
        )
        val report = ExcelTerritoryImporter.buildPreviewReport(
            fileName = "West_Target_List.csv",
            sheets = sheets
        )
        assertEquals(12, report.totalDetectedRows)
        assertEquals(10, report.availableHeaders.size)

        val assistant = com.example.domain.ai.AiPlannerAssistant()
        val complexTier = assistant.selectModelTierForPrompt(
            prompt = "Analyze my highest-risk cardiology HCPs and propose a weekly coverage strategy",
            preferredTier = com.example.domain.ai.GeminiChatModelTier.GENERAL,
            autoRouteByComplexity = true
        )
        assertEquals("gemini-4-argon", complexTier.modelId)

        val fastTier = assistant.selectModelTierForPrompt(
            prompt = "Quick clinic hours for Dr. Ahmed?",
            preferredTier = com.example.domain.ai.GeminiChatModelTier.COMPLEX,
            autoRouteByComplexity = true
        )
        assertEquals("gemini-3.1-flash-lite", fastTier.modelId)

        val generalTier = assistant.selectModelTierForPrompt(
            prompt = "Plan 8 doctors today avoiding Sheikh Zayed",
            preferredTier = com.example.domain.ai.GeminiChatModelTier.COMPLEX,
            autoRouteByComplexity = true
        )
        assertEquals("gemini-3.8-flash", generalTier.modelId)
    }

    @Test
    fun acceptanceTest_westXlsxFormat_multipleClinics_brickClassification_gpsUrls_andSchedules() {
        // Simulates actual West.xlsx workbook columns:
        // target_id | HCP Name | Specialty | zone 1 | zone 2 | location GPS | time and location | Frequency
        val csvContent = """
            target_id,HCP Name,Specialty,zone 1,zone 2,location GPS,time and location,Frequency
            W-01,Dr. Single Zone,Cardiology,Brick A,,"https://www.google.com/maps?q=31.1100,29.7700","Saturday and Monday 2-6 PM",8
            W-02,Dr. Different Bricks,Internal Medicine,Brick A,Brick B,"https://www.google.com/maps?q=31.1100,29.7700 - https://www.google.com/maps?q=31.0500,29.7800","Brick B: Sunday and Tuesday 7-10 PM | Brick A: Saturday and Monday 2-6 PM",8
            W-03,Dr. Same Brick Two Clinics,Pediatrics,Brick A,Brick A,"https://www.google.com/maps?q=31.1120,29.7720 - https://www.google.com/maps?q=31.1180,29.7780","Clinic 1: Sunday 4-7 PM | Clinic 2: Tuesday 5-8 PM",8
            W-04,Dr. Mismatched Urls,Neurology,Brick A,Brick B,"https://www.google.com/maps?q=31.1100,29.7700","Saturday and Thursday at one clinic, with different hours at another",6
            W-05,Dr. Ambiguous Hospital Schedule,Orthopedics,Brick A,Brick B,"https://www.google.com/maps?q=31.1100,29.7700 - https://www.google.com/maps?q=31.0500,29.7800","Daily at the clinic, with Monday at Qasr Elshefa hospital",6
            W-06,Dr. Ambiguous Location Description,Dermatology,Brick B,,"Next to pharmacy near main square","Daily at El Qasr and evening at his clinic",6
        """.trimIndent()

        val sheets = ExcelTerritoryImporter.parseCsv(
            inputStream = ByteArrayInputStream(csvContent.toByteArray(Charsets.UTF_8))
        )
        val report = ExcelTerritoryImporter.buildPreviewReport(
            fileName = "West_Actual.csv",
            sheets = sheets,
            selectedSheetIndex = 0,
            existingHcpsByTargetId = emptyMap()
        )

        assertEquals(6, report.totalDetectedRows)

        // 1. HCP with only zone 1
        val hcpSingle = report.rows.first { it.targetId == "W-01" }
        assertEquals(1, hcpSingle.clinics.size)
        assertEquals("Brick A", hcpSingle.clinics[0].zone)
        assertEquals("EXTRACTED_URL", hcpSingle.clinics[0].geocodingStatus)
        assertEquals(31.1100, hcpSingle.clinics[0].latitude!!, 0.0001)

        // 2. HCP with zone 1 and zone 2 in different bricks + hyphen-separated GPS URLs + reversed order schedule
        val hcpDiffBricks = report.rows.first { it.targetId == "W-02" }
        assertEquals(2, hcpDiffBricks.clinics.size)
        val c1Diff = hcpDiffBricks.clinics.first { it.clinicIndex == 1 }
        val c2Diff = hcpDiffBricks.clinics.first { it.clinicIndex == 2 }
        assertEquals("Brick A", c1Diff.zone)
        assertEquals("Brick B", c2Diff.zone)
        assertEquals("https://www.google.com/maps?q=31.1100,29.7700", c1Diff.googleMapsUrl)
        assertEquals("https://www.google.com/maps?q=31.0500,29.7800", c2Diff.googleMapsUrl)
        assertEquals(31.1100, c1Diff.latitude!!, 0.0001)
        assertEquals(31.0500, c2Diff.latitude!!, 0.0001)
        // Verify reversed order schedule in "time and location" associated Brick A schedule to Clinic 1 and Brick B to Clinic 2
        assertEquals(setOf(6, 1), c1Diff.scheduleParseResult.windows.map { it.dayOfWeek }.toSet())
        assertEquals(setOf(7, 2), c2Diff.scheduleParseResult.windows.map { it.dayOfWeek }.toSet())

        // 3. HCP with zone 1 and zone 2 in the same brick
        val hcpSameBrick = report.rows.first { it.targetId == "W-03" }
        assertEquals(2, hcpSameBrick.clinics.size)
        assertEquals("Brick A", hcpSameBrick.clinics[0].zone)
        assertEquals("Brick A", hcpSameBrick.clinics[1].zone)
        assertEquals(31.1120, hcpSameBrick.clinics[0].latitude!!, 0.0001)
        assertEquals(31.1180, hcpSameBrick.clinics[1].latitude!!, 0.0001)
        assertEquals(setOf(7), hcpSameBrick.clinics[0].scheduleParseResult.windows.map { it.dayOfWeek }.toSet())
        assertEquals(setOf(2), hcpSameBrick.clinics[1].scheduleParseResult.windows.map { it.dayOfWeek }.toSet())

        // 4. HCP with mismatched GPS URLs (1 URL for 2 clinics) -> Clinic 2 is NOT assigned Clinic 1's coordinates
        val hcpMismatch = report.rows.first { it.targetId == "W-04" }
        assertEquals(2, hcpMismatch.clinics.size)
        assertEquals("UNVERIFIED", hcpMismatch.clinics[0].geocodingStatus)
        assertEquals("UNVERIFIED", hcpMismatch.clinics[1].geocodingStatus)
        assertEquals(null, hcpMismatch.clinics[1].latitude)
        assertEquals(
            com.example.domain.parser.ScheduleParseStatus.AMBIGUOUS,
            hcpMismatch.clinics[0].scheduleParseResult.status
        )
        assertEquals(
            com.example.domain.parser.ScheduleParseStatus.AMBIGUOUS,
            hcpMismatch.clinics[1].scheduleParseResult.status
        )

        // 5. HCP with ambiguous multi-location schedule ("Daily at the clinic, with Monday at Qasr Elshefa hospital")
        val hcpAmbigSched = report.rows.first { it.targetId == "W-05" }
        assertEquals(2, hcpAmbigSched.clinics.size)
        assertEquals(
            com.example.domain.parser.ScheduleParseStatus.AMBIGUOUS,
            hcpAmbigSched.clinics[0].scheduleParseResult.status
        )
        assertEquals(
            com.example.domain.parser.ScheduleParseStatus.AMBIGUOUS,
            hcpAmbigSched.clinics[1].scheduleParseResult.status
        )
        assertTrue(hcpAmbigSched.clinics[0].scheduleParseResult.windows.isEmpty())
        assertTrue(hcpAmbigSched.clinics[1].scheduleParseResult.windows.isEmpty())

        // 6. HCP with ambiguous free-text location description -> UNVERIFIED
        val hcpAmbigLoc = report.rows.first { it.targetId == "W-06" }
        assertEquals(1, hcpAmbigLoc.clinics.size)
        assertEquals("UNVERIFIED", hcpAmbigLoc.clinics[0].geocodingStatus)
        assertEquals(null, hcpAmbigLoc.clinics[0].latitude)

        // Global brick registry check: only "Brick A" and "Brick B" exist, NEVER "Brick A • Brick B"
        val allZones = report.rows.flatMap { r -> r.clinics.map { it.zone } }
        val uniqueBricks = com.example.data.model.BrickNormalizer.extractUniqueIndividualBricks(allZones)
        assertEquals(listOf("Brick A", "Brick B"), uniqueBricks)
        assertTrue(uniqueBricks.none { it.contains("•") || it.contains("+") })
    }

    @Test
    fun strictExcelSchedulePreservation_preservesOriginalExcelTextAndClassifiesTimeTypes() {
        val csvContent = """
            target_id,HCP Name,Specialty,zone 1,zone 2,location GPS,time and location,Frequency
            S-01,Dr. Booking Call,Cardiology,Brick A,,"https://www.google.com/maps?q=31.1100,29.7700","Call at 7 PM to book",6
            S-02,Dr. Single Time,Pediatrics,Brick A,,"https://www.google.com/maps?q=31.1120,29.7720","Saturday 5 PM",6
            S-03,Dr. Default Everyday,Neurology,Brick B,,"https://www.google.com/maps?q=31.0500,29.7800","2-6 PM",6
            S-04,Dr. Verified Range,Internal Medicine,Brick B,,"https://www.google.com/maps?q=31.0550,29.7850","Monday, Tuesday and Thursday 3-6 PM",8
            S-05,Dr. Bare Ambiguous,Cardiology,Brick A,,"https://www.google.com/maps?q=31.1110,29.7710","8-10",6
            S-06,Dr. Location Prefix,Cardiology,Brick A,,"https://www.google.com/maps?q=31.1130,29.7730","El Nokrashy in the morning 8 AM : 9 AM",6
        """.trimIndent()

        val sheets = ExcelTerritoryImporter.parseCsv(
            inputStream = ByteArrayInputStream(csvContent.toByteArray(Charsets.UTF_8))
        )
        val report = ExcelTerritoryImporter.buildPreviewReport(
            fileName = "Strict_Schedule_Audit.csv",
            sheets = sheets,
            selectedSheetIndex = 0,
            existingHcpsByTargetId = emptyMap()
        )

        val rCall = report.rows.first { it.targetId == "S-01" }.clinics.single()
        assertEquals("Call at 7 PM to book", rCall.originalExcelScheduleText)
        assertEquals(com.example.domain.parser.TimeInfoType.BOOKING_OR_CALL_TIME, rCall.scheduleParseResult.timeInfoType)
        assertTrue(rCall.scheduleParseResult.windows.isEmpty())
        assertEquals("Strict_Schedule_Audit.csv", rCall.sourceFile)
        assertTrue(rCall.sourceRow > 0)

        val rSingle = report.rows.first { it.targetId == "S-02" }.clinics.single()
        assertEquals("Saturday 5 PM", rSingle.originalExcelScheduleText)
        assertEquals(com.example.domain.parser.TimeInfoType.SPECIFIC_APPOINTMENT_TIME, rSingle.scheduleParseResult.timeInfoType)
        assertTrue(rSingle.scheduleParseResult.windows.isEmpty())

        // Rule 2: "2-6 PM" without weekdays defaults to EVERY DAY (1..7)
        val rDefaultDay = report.rows.first { it.targetId == "S-03" }.clinics.single()
        assertEquals("2-6 PM", rDefaultDay.originalExcelScheduleText)
        assertEquals(com.example.domain.parser.TimeInfoType.CLINIC_AVAILABILITY_RANGE, rDefaultDay.scheduleParseResult.timeInfoType)
        assertEquals(com.example.domain.parser.ScheduleClassification.DAILY_DEFAULT_WITH_TIME, rDefaultDay.scheduleParseResult.classification)
        assertEquals((1..7).toSet(), rDefaultDay.scheduleParseResult.normalizedDays.toSet())

        val rValid = report.rows.first { it.targetId == "S-04" }.clinics.single()
        assertEquals("Monday, Tuesday and Thursday 3-6 PM", rValid.originalExcelScheduleText)
        assertEquals(com.example.domain.parser.TimeInfoType.CLINIC_AVAILABILITY_RANGE, rValid.scheduleParseResult.timeInfoType)
        assertEquals(setOf(1, 2, 4), rValid.scheduleParseResult.normalizedDays.toSet())
        assertEquals(15 * 60, rValid.scheduleParseResult.exactStartMinute)
        assertEquals(18 * 60, rValid.scheduleParseResult.exactEndMinute)

        val rBare = report.rows.first { it.targetId == "S-05" }.clinics.single()
        assertEquals(com.example.domain.parser.TimeInfoType.REQUIRES_MANUAL_CLARIFICATION, rBare.scheduleParseResult.timeInfoType)
        assertTrue(rBare.scheduleParseResult.windows.isEmpty())

        val rLoc = report.rows.first { it.targetId == "S-06" }.clinics.single()
        assertTrue(rLoc.scheduleParseResult.locationDescription.contains("El Nokrashy", ignoreCase = true))
        assertEquals((1..7).toSet(), rLoc.scheduleParseResult.normalizedDays.toSet())
    }
}

