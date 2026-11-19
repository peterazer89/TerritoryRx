package com.example.domain

import com.example.domain.parser.AvailabilityScheduleParser
import com.example.domain.parser.ScheduleClassification
import com.example.domain.parser.ScheduleParseStatus
import com.example.domain.parser.ScheduleValidationStatus
import com.example.domain.parser.ScheduleValidator
import com.example.domain.parser.TimeInfoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailabilityScheduleParserTest {

    @Test
    fun parseFullDayNames_saturdayAndMonday2To6Pm() {
        val result = AvailabilityScheduleParser.parse("Saturday and Monday 2-6 PM")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, result.status)
        assertEquals(2, result.windows.size)
        val days = result.windows.map { it.dayOfWeek }.toSet()
        assertEquals(setOf(6, 1), days) // Saturday=6, Monday=1
        result.windows.forEach { win ->
            assertEquals(14 * 60, win.startMinuteOfDay)
            assertEquals(18 * 60, win.endMinuteOfDay)
        }
    }

    @Test
    fun parseFullDayNames_sundayAndTuesday7PmTo10Pm() {
        val result = AvailabilityScheduleParser.parse("Sunday and Tuesday 7 PM-10 PM")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, result.status)
        assertEquals(2, result.windows.size)
        assertEquals(setOf(7, 2), result.windows.map { it.dayOfWeek }.toSet())
        result.windows.forEach { win ->
            assertEquals(19 * 60, win.startMinuteOfDay)
            assertEquals(22 * 60, win.endMinuteOfDay)
        }
    }

    @Test
    fun parseAbbreviatedDayNames_satMonWedThu4To10Pm() {
        val result = AvailabilityScheduleParser.parse("Sat, Mon, Wed, Thu 4-10 PM")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, result.status)
        assertEquals(4, result.windows.size)
        assertEquals(setOf(6, 1, 3, 4), result.windows.map { it.dayOfWeek }.toSet())
        result.windows.forEach { win ->
            assertEquals(16 * 60, win.startMinuteOfDay)
            assertEquals(22 * 60, win.endMinuteOfDay)
        }
    }

    @Test
    fun parseEveryDaySchedule_eveningFrom8To9PmEveryDay() {
        val result = AvailabilityScheduleParser.parse("Evening from 8-9 PM EVERY DAY")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, result.status)
        assertEquals(7, result.windows.size)
        assertEquals((1..7).toSet(), result.windows.map { it.dayOfWeek }.toSet())
        result.windows.forEach { win ->
            assertEquals(20 * 60, win.startMinuteOfDay)
            assertEquals(21 * 60, win.endMinuteOfDay)
        }
    }

    @Test
    fun parseDailyWithExceptions_daily830To10PmExceptMondayAndFriday() {
        val result = AvailabilityScheduleParser.parse("Daily 8:30-10 PM except Monday and Friday")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, result.status)
        val days = result.windows.map { it.dayOfWeek }.toSet()
        assertFalse(days.contains(1)) // Monday excluded
        assertFalse(days.contains(5)) // Friday excluded
        assertEquals(setOf(2, 3, 4, 6, 7), days)
        result.windows.forEach { win ->
            assertEquals(20 * 60 + 30, win.startMinuteOfDay)
            assertEquals(22 * 60, win.endMinuteOfDay)
        }
    }

    @Test
    fun parseMultipleWindowsInOneCell_satMon1To3PmAnd7To9Pm() {
        val result = AvailabilityScheduleParser.parse("Sat, Mon 1-3 PM and 7-9 PM")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, result.status)
        assertEquals(4, result.windows.size)
        val mondayWindows = result.windows.filter { it.dayOfWeek == 1 }.sortedBy { it.startMinuteOfDay }
        assertEquals(2, mondayWindows.size)
        assertEquals(13 * 60, mondayWindows[0].startMinuteOfDay)
        assertEquals(15 * 60, mondayWindows[0].endMinuteOfDay)
        assertEquals(19 * 60, mondayWindows[1].startMinuteOfDay)
        assertEquals(21 * 60, mondayWindows[1].endMinuteOfDay)
    }

    @Test
    fun parse24HourFormat_monWed1400To1800() {
        val result = AvailabilityScheduleParser.parse("Mon, Wed 14:00-18:00")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, result.status)
        assertEquals(2, result.windows.size)
        assertEquals(setOf(1, 3), result.windows.map { it.dayOfWeek }.toSet())
        result.windows.forEach { win ->
            assertEquals(14 * 60, win.startMinuteOfDay)
            assertEquals(18 * 60, win.endMinuteOfDay)
        }
    }

    @Test
    fun flagAmbiguousAndInvalidSchedulesWithoutGuessing() {
        val missingAmPm = AvailabilityScheduleParser.parse("8-10")
        assertEquals(ScheduleParseStatus.AMBIGUOUS, missingAmPm.status)
        assertTrue(missingAmPm.ambiguityReason.isNotBlank())

        val vagueMorning = AvailabilityScheduleParser.parse("Morning")
        assertEquals(ScheduleParseStatus.AMBIGUOUS, vagueMorning.status)

        val callFirst = AvailabilityScheduleParser.parse("Call before visiting")
        assertEquals(ScheduleParseStatus.UNPARSED, callFirst.status)

        val invertedRange = AvailabilityScheduleParser.parse("Monday 10 PM-8 PM")
        assertEquals(ScheduleParseStatus.AMBIGUOUS, invertedRange.status)
    }

    @Test
    fun parseColonAndDashTimeFormats_4To7PmVariants() {
        val formats = listOf(
            "4-7 PM",
            "4:7 PM",
            "4 : 7 PM",
            "4 PM : 7 PM",
            "4PM : 7PM",
            "4pm:7pm",
            "4 - 7 pm",
            "Saturday and Monday 4-7 PM",
            "Saturday and Monday 4:7 PM",
            "Saturday and Monday 4 PM : 7 PM",
            "Saturday and Monday 4 : 7 PM"
        )

        for (expr in formats) {
            val result = AvailabilityScheduleParser.parse(expr)
            assertEquals("Expected high confidence for '$expr'", ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, result.status)
            assertTrue("Expected non-empty windows for '$expr'", result.windows.isNotEmpty())
            result.windows.forEach { win ->
                assertEquals("Start minute mismatch for '$expr'", 16 * 60, win.startMinuteOfDay)
                assertEquals("End minute mismatch for '$expr'", 19 * 60, win.endMinuteOfDay)
            }
        }
    }

    @Test
    fun parseColonWithMinutesAndArabicTimeFormats() {
        val withMinutes1 = AvailabilityScheduleParser.parse("Sat, Mon 4:30 PM : 7:30 PM")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, withMinutes1.status)
        assertEquals(16 * 60 + 30, withMinutes1.windows.first().startMinuteOfDay)
        assertEquals(19 * 60 + 30, withMinutes1.windows.first().endMinuteOfDay)

        val withMinutes2 = AvailabilityScheduleParser.parse("Sat, Mon 4:30 : 7:30 PM")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, withMinutes2.status)
        assertEquals(16 * 60 + 30, withMinutes2.windows.first().startMinuteOfDay)
        assertEquals(19 * 60 + 30, withMinutes2.windows.first().endMinuteOfDay)

        val withMinutes3 = AvailabilityScheduleParser.parse("Sat, Mon 4 : 7:30 PM")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, withMinutes3.status)
        assertEquals(16 * 60, withMinutes3.windows.first().startMinuteOfDay)
        assertEquals(19 * 60 + 30, withMinutes3.windows.first().endMinuteOfDay)

        val arabicMeridiem = AvailabilityScheduleParser.parse("Sat, Mon 4:7 م")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, arabicMeridiem.status)
        assertEquals(16 * 60, arabicMeridiem.windows.first().startMinuteOfDay)
        assertEquals(19 * 60, arabicMeridiem.windows.first().endMinuteOfDay)
    }

    @Test
    fun parseRealExcelSheetSchedules_multiClauseOrRangeAndWeekdayTypos() {
        // Row 1: Abdallah AboRehab -> "Sunday from 8:10 PM, Tuesday and Wednesday from 3 or 4 : 6 PM"
        val r1 = AvailabilityScheduleParser.parse("Sunday from 8:10 PM, Tuesday and Wednesday from 3 or 4 : 6 PM")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, r1.status)
        assertEquals(3, r1.windows.size)
        val sunWin = r1.windows.first { it.dayOfWeek == 7 }
        assertEquals(20 * 60, sunWin.startMinuteOfDay)
        assertEquals(22 * 60, sunWin.endMinuteOfDay)
        val tueWin = r1.windows.first { it.dayOfWeek == 2 }
        assertEquals(15 * 60, tueWin.startMinuteOfDay)
        assertEquals(18 * 60, tueWin.endMinuteOfDay)
        val wedWin = r1.windows.first { it.dayOfWeek == 3 }
        assertEquals(15 * 60, wedWin.startMinuteOfDay)
        assertEquals(18 * 60, wedWin.endMinuteOfDay)

        // Row 3: Ahmed Farid -> "sunday tuseday wednesday thursday 4 : 5 PM" (with 'tuseday' typo)
        val r3 = AvailabilityScheduleParser.parse("sunday tuseday wednesday thursday 4 : 5 PM")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, r3.status)
        assertEquals(setOf(7, 2, 3, 4), r3.windows.map { it.dayOfWeek }.toSet())
        r3.windows.forEach { win ->
            assertEquals(16 * 60, win.startMinuteOfDay)
            assertEquals(17 * 60, win.endMinuteOfDay)
        }

        // Row 4: Ahmed Hamdy Shokry -> "Evening from 8 : 9 PM EVERY DAY"
        val r4 = AvailabilityScheduleParser.parse("Evening from 8 : 9 PM EVERY DAY")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, r4.status)
        assertEquals(7, r4.windows.size)
        r4.windows.forEach { win ->
            assertEquals(20 * 60, win.startMinuteOfDay)
            assertEquals(21 * 60, win.endMinuteOfDay)
        }
    }

    @Test
    fun parseArabicShortPmAndSuffixDaysAndMixedClauses() {
        val arabicSchedule = AvailabilityScheduleParser.parse("السبت والاثنين من 5م ل 8م")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, arabicSchedule.status)
        assertEquals(setOf(6, 1), arabicSchedule.windows.map { it.dayOfWeek }.toSet())
        arabicSchedule.windows.forEach { win ->
            assertEquals(17 * 60, win.startMinuteOfDay)
            assertEquals(20 * 60, win.endMinuteOfDay)
        }

        val suffixDays = AvailabilityScheduleParser.parse("2 - 6 PM Saturday and Monday | 7 - 10 PM Sunday and Tuesday")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, suffixDays.status)
        val satWin = suffixDays.windows.first { it.dayOfWeek == 6 }
        assertEquals(14 * 60, satWin.startMinuteOfDay)
        assertEquals(18 * 60, satWin.endMinuteOfDay)
        val sunWin = suffixDays.windows.first { it.dayOfWeek == 7 }
        assertEquals(19 * 60, sunWin.startMinuteOfDay)
        assertEquals(22 * 60, sunWin.endMinuteOfDay)

        val mixedRangeAndSingle = AvailabilityScheduleParser.parse("Saturday 2-5 PM, Monday from 6 PM")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, mixedRangeAndSingle.status)
        val sat = mixedRangeAndSingle.windows.first { it.dayOfWeek == 6 }
        assertEquals(14 * 60, sat.startMinuteOfDay)
        assertEquals(17 * 60, sat.endMinuteOfDay)
        val mon = mixedRangeAndSingle.windows.first { it.dayOfWeek == 1 }
        assertEquals(18 * 60, mon.startMinuteOfDay)
        assertEquals(21 * 60, mon.endMinuteOfDay)

        val noonCrossing = AvailabilityScheduleParser.parse("Saturday 10 - 2")
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, noonCrossing.status)
        assertEquals(10 * 60, noonCrossing.windows.first().startMinuteOfDay)
        assertEquals(14 * 60, noonCrossing.windows.first().endMinuteOfDay)
    }

    @Test
    fun strictExcelAccuracy_neverExpandsSingleTimeOrBookingOrCallOrApproximateOrMissingWeekdays() {
        // 1. Booking / phone-call time must NOT be treated as 7 PM - 10 PM availability
        val callAt7 = AvailabilityScheduleParser.parseForExcelImport("Call at 7 PM to book")
        assertEquals(com.example.domain.parser.TimeInfoType.BOOKING_OR_CALL_TIME, callAt7.timeInfoType)
        assertEquals(ScheduleParseStatus.AMBIGUOUS, callAt7.status)
        assertTrue("Booking instruction must never generate availability windows", callAt7.windows.isEmpty())
        assertTrue(callAt7.bookingInstructions.contains("Call at 7 PM to book"))

        // 2. Single appointment time without end time must NOT be expanded into a 3-hour range
        val singleTime = AvailabilityScheduleParser.parseForExcelImport("Saturday at 5 PM")
        assertEquals(com.example.domain.parser.TimeInfoType.SPECIFIC_APPOINTMENT_TIME, singleTime.timeInfoType)
        assertEquals(ScheduleParseStatus.AMBIGUOUS, singleTime.status)
        assertTrue("Single time must not be converted into a time range", singleTime.windows.isEmpty())
        assertEquals(17 * 60, singleTime.exactStartMinute)
        assertEquals(null, singleTime.exactEndMinute)

        val fromSingleTime = AvailabilityScheduleParser.parseForExcelImport("Monday from 6 PM")
        assertEquals(com.example.domain.parser.TimeInfoType.SPECIFIC_APPOINTMENT_TIME, fromSingleTime.timeInfoType)
        assertEquals(ScheduleParseStatus.AMBIGUOUS, fromSingleTime.status)
        assertTrue(fromSingleTime.windows.isEmpty())

        // 3. Approximate time ("around", "approx", "~") in Excel import must be flagged for confirmation
        val approxWord = AvailabilityScheduleParser.parseForExcelImport("Monday around 5-7 PM")
        assertEquals(com.example.domain.parser.TimeInfoType.APPROXIMATE_TIME, approxWord.timeInfoType)
        assertEquals(ScheduleParseStatus.AMBIGUOUS, approxWord.status)
        assertTrue(approxWord.windows.isEmpty())

        val approxTilde = AvailabilityScheduleParser.parseForExcelImport("Tuesday approx 4-6 PM")
        assertEquals(com.example.domain.parser.TimeInfoType.APPROXIMATE_TIME, approxTilde.timeInfoType)
        assertEquals(ScheduleParseStatus.AMBIGUOUS, approxTilde.status)
        assertTrue(approxTilde.windows.isEmpty())

        // 4. Rule 2: Valid unambiguous time range WITHOUT weekdays ("4-7 PM") defaults to EVERY DAY (1..7)
        val everydayDefaultRange = ScheduleValidator.validate(AvailabilityScheduleParser.parseForExcelImport("4-7 PM"))
        assertEquals(TimeInfoType.CLINIC_AVAILABILITY_RANGE, everydayDefaultRange.timeInfoType)
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, everydayDefaultRange.status)
        assertEquals(ScheduleClassification.DAILY_DEFAULT_WITH_TIME, everydayDefaultRange.classification)
        assertEquals(ScheduleValidationStatus.VALID, everydayDefaultRange.validationStatus)
        assertEquals((1..7).toSet(), everydayDefaultRange.normalizedDays.toSet())
        assertEquals(7, everydayDefaultRange.windows.size)

        // Missing AM/PM ("8-10") must remain genuinely ambiguous
        val missingAmPmBare = ScheduleValidator.validate(AvailabilityScheduleParser.parseForExcelImport("8-10"))
        assertEquals(TimeInfoType.REQUIRES_MANUAL_CLARIFICATION, missingAmPmBare.timeInfoType)
        assertEquals(ScheduleParseStatus.AMBIGUOUS, missingAmPmBare.status)
        assertTrue(missingAmPmBare.windows.isEmpty())

        // 5. Explicit weekdays + explicit start & end time in Excel -> verified CLINIC_AVAILABILITY_RANGE
        val strictValid = AvailabilityScheduleParser.parseForExcelImport("Monday, Tuesday and Thursday 4-7 PM")
        assertEquals(com.example.domain.parser.TimeInfoType.CLINIC_AVAILABILITY_RANGE, strictValid.timeInfoType)
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, strictValid.status)
        assertEquals(setOf(1, 2, 4), strictValid.normalizedDays.toSet())
        assertEquals(16 * 60, strictValid.exactStartMinute)
        assertEquals(19 * 60, strictValid.exactEndMinute)
        assertEquals(3, strictValid.windows.size)
    }

    @Test
    fun reduceFalseAmbiguity_mondayTuesdayThursdaySemicolon8PmTo10Pm() {
        val raw = "Monday, Tuesday, Thursday; 8 pm : 10 PM"
        val result = AvailabilityScheduleParser.parseForExcelImport(raw)

        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, result.status)
        assertEquals(com.example.domain.parser.TimeInfoType.CLINIC_AVAILABILITY_RANGE, result.timeInfoType)
        assertFalse(result.clarificationRequired)
        assertEquals("CONFIRMED_TIME_WINDOW", result.availabilityTypeLabel)
        assertEquals("PARSED_FROM_EXCEL", result.verificationStatusLabel)
        assertEquals("20:00", result.startTime24Hour)
        assertEquals("22:00", result.endTime24Hour)
        assertTrue(result.ambiguityReason.isBlank())

        // Must parse ONLY Monday (1), Tuesday (2), Thursday (4) — never add Wed/Fri/Sat/Sun
        assertEquals(setOf(1, 2, 4), result.normalizedDays.toSet())
        assertEquals(3, result.windows.size)
        result.windows.forEach { win ->
            assertEquals(20 * 60, win.startMinuteOfDay)
            assertEquals(22 * 60, win.endMinuteOfDay)
        }
    }

    @Test
    fun reduceFalseAmbiguity_allTimeRangeFormatsAndClearExamples() {
        val timeFormats8To10Pm = listOf(
            "8 pm - 10 pm",
            "8 pm : 10 PM",
            "8PM-10PM",
            "8:00 PM to 10:00 PM",
            "20:00-22:00",
            "8-10 PM",
            "08:00 PM – 10:00 PM"
        )
        for (tf in timeFormats8To10Pm) {
            val expr = "Monday, Tuesday; $tf"
            val result = AvailabilityScheduleParser.parseForExcelImport(expr)
            assertFalse("Must NOT require clarification for '$expr': ${result.ambiguityReason}", result.clarificationRequired)
            assertEquals("Expected PARSED_FROM_EXCEL for '$expr'", "PARSED_FROM_EXCEL", result.verificationStatusLabel)
            assertEquals("CONFIRMED_TIME_WINDOW", result.availabilityTypeLabel)
            assertEquals("20:00", result.startTime24Hour)
            assertEquals("22:00", result.endTime24Hour)
            assertEquals(setOf(1, 2), result.normalizedDays.toSet())
        }

        val satMonWed = AvailabilityScheduleParser.parseForExcelImport("Sat, Mon, Wed 4-10 PM")
        assertFalse(satMonWed.clarificationRequired)
        assertEquals(setOf(6, 1, 3), satMonWed.normalizedDays.toSet())
        assertEquals("16:00", satMonWed.startTime24Hour)
        assertEquals("22:00", satMonWed.endTime24Hour)

        val dailyExceptMon = AvailabilityScheduleParser.parseForExcelImport("Daily 8:30 PM - 10 PM except Monday")
        assertFalse(dailyExceptMon.clarificationRequired)
        assertEquals(setOf(2, 3, 4, 5, 6, 7), dailyExceptMon.normalizedDays.toSet())
        assertEquals("20:30", dailyExceptMon.startTime24Hour)
        assertEquals("22:00", dailyExceptMon.endTime24Hour)
    }

    @Test
    fun preserveGenuineAmbiguity_andPreventOrdinalThFalseWeekday() {
        val eveningOnly = AvailabilityScheduleParser.parseForExcelImport("Evening")
        assertTrue(eveningOnly.clarificationRequired)

        val noMeridiemBare = AvailabilityScheduleParser.parseForExcelImport("8-10")
        assertTrue(noMeridiemBare.clarificationRequired)

        val noMeridiemWithDay = AvailabilityScheduleParser.parseForExcelImport("Monday 8-10")
        assertTrue("8-10 without AM/PM must remain genuinely ambiguous", noMeridiemWithDay.clarificationRequired)

        val conflictingRanges = AvailabilityScheduleParser.parseForExcelImport("Monday 4-7 PM, Monday 5-8 PM")
        assertTrue("Overlapping conflicting ranges must require clarification", conflictingRanges.clarificationRequired)

        val secondClauseMissingHours = AvailabilityScheduleParser.parseForExcelImport("Sunday 2-5 PM | Tuesday")
        assertTrue(secondClauseMissingHours.clarificationRequired)

        val withOrdinalAddress = AvailabilityScheduleParser.parseForExcelImport("6th of October; Monday, Tuesday; 4-7 PM")
        assertFalse(withOrdinalAddress.clarificationRequired)
        assertEquals(setOf(1, 2), withOrdinalAddress.normalizedDays.toSet())
        assertFalse("6th must never trigger Thursday", withOrdinalAddress.normalizedDays.contains(4))
    }

    @Test
    fun masterPrompt_rule2EverydayDefaultAndLocationExtraction() {
        val r1 = ScheduleValidator.validate(AvailabilityScheduleParser.parseForExcelImport("From 1 PM : 7PM"))
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, r1.status)
        assertEquals(ScheduleClassification.DAILY_DEFAULT_WITH_TIME, r1.classification)
        assertEquals(ScheduleValidationStatus.VALID, r1.validationStatus)
        assertEquals((1..7).toSet(), r1.normalizedDays.toSet())
        assertEquals(13 * 60, r1.windows.first().startMinuteOfDay)
        assertEquals(19 * 60, r1.windows.first().endMinuteOfDay)

        val r2 = ScheduleValidator.validate(
            AvailabilityScheduleParser.parseForExcelImport("El Nokrashy in the morning 8 AM : 9 AM")
        )
        assertEquals(ScheduleParseStatus.PARSED_HIGH_CONFIDENCE, r2.status)
        assertEquals(ScheduleClassification.DAILY_DEFAULT_WITH_TIME, r2.classification)
        assertEquals((1..7).toSet(), r2.normalizedDays.toSet())
        assertEquals(8 * 60, r2.windows.first().startMinuteOfDay)
        assertEquals(9 * 60, r2.windows.first().endMinuteOfDay)
        assertTrue("Expected El Nokrashy in locationDescription, got '${r2.locationDescription}'",
            r2.locationDescription.contains("El Nokrashy", ignoreCase = true))

        val r3 = ScheduleValidator.validate(AvailabilityScheduleParser.parseForExcelImport("2-6 PM"))
        assertEquals(ScheduleClassification.DAILY_DEFAULT_WITH_TIME, r3.classification)
        assertEquals((1..7).toSet(), r3.normalizedDays.toSet())
        assertEquals(14 * 60, r3.windows.first().startMinuteOfDay)
        assertEquals(18 * 60, r3.windows.first().endMinuteOfDay)
    }

    @Test
    fun masterPrompt_rule3DailyWithExceptionsAndPerDayOverrides() {
        val exc1 = ScheduleValidator.validate(
            AvailabilityScheduleParser.parseForExcelImport("Daily 3 : 7 PM except Friday and Saturday")
        )
        assertEquals(ScheduleClassification.DAILY_WITH_EXCEPTIONS, exc1.classification)
        assertEquals(setOf(5, 6), exc1.excludedDays.toSet())
        assertEquals(setOf(1, 2, 3, 4, 7), exc1.normalizedDays.toSet())
        assertEquals(15 * 60, exc1.windows.first().startMinuteOfDay)
        assertEquals(19 * 60, exc1.windows.first().endMinuteOfDay)

        val overrideDaily = ScheduleValidator.validate(
            AvailabilityScheduleParser.parseForExcelImport("Daily 8 PM–10 PM, Friday 5 PM–7 PM")
        )
        assertEquals(ScheduleValidationStatus.VALID, overrideDaily.validationStatus)
        assertEquals(7, overrideDaily.windows.size)
        val fridayWin = overrideDaily.windows.single { it.dayOfWeek == 5 }
        assertEquals(17 * 60, fridayWin.startMinuteOfDay)
        assertEquals(19 * 60, fridayWin.endMinuteOfDay)
        val mondayWin = overrideDaily.windows.single { it.dayOfWeek == 1 }
        assertEquals(20 * 60, mondayWin.startMinuteOfDay)
        assertEquals(22 * 60, mondayWin.endMinuteOfDay)

        val multiDayMultiTime = ScheduleValidator.validate(
            AvailabilityScheduleParser.parseForExcelImport("Saturday and Monday from 2 PM to 6 PM, Thursday from 5 PM to 8 PM")
        )
        assertEquals(ScheduleValidationStatus.VALID, multiDayMultiTime.validationStatus)
        assertEquals(3, multiDayMultiTime.windows.size)
        assertEquals(14 * 60, multiDayMultiTime.windows.first { it.dayOfWeek == 6 }.startMinuteOfDay)
        assertEquals(17 * 60, multiDayMultiTime.windows.first { it.dayOfWeek == 4 }.startMinuteOfDay)

        val twoWinsSameDay = ScheduleValidator.validate(
            AvailabilityScheduleParser.parseForExcelImport("Monday 10 AM–1 PM and 6 PM–9 PM")
        )
        assertEquals(ScheduleClassification.MULTIPLE_WEEKLY_WINDOWS, twoWinsSameDay.classification)
        assertEquals(2, twoWinsSameDay.windows.size)
        assertTrue(twoWinsSameDay.windows.all { it.dayOfWeek == 1 })
    }

    @Test
    fun masterPrompt_overnightMidnightNoonMinutesAndArabicFormats() {
        val halfHour = AvailabilityScheduleParser.parseForExcelImport("Monday 8:30 AM–12:30 PM")
        assertEquals(8 * 60 + 30, halfHour.windows.single().startMinuteOfDay)
        assertEquals(12 * 60 + 30, halfHour.windows.single().endMinuteOfDay)

        val quarterHour = AvailabilityScheduleParser.parseForExcelImport("Tuesday 1:15 PM–5:45 PM")
        assertEquals(13 * 60 + 15, quarterHour.windows.single().startMinuteOfDay)
        assertEquals(17 * 60 + 45, quarterHour.windows.single().endMinuteOfDay)

        val midnightNoon = AvailabilityScheduleParser.parseForExcelImport("Saturday 12 AM–2 AM | Sunday 12 PM–3 PM")
        val sat = midnightNoon.windows.first { it.dayOfWeek == 6 }
        val sun = midnightNoon.windows.first { it.dayOfWeek == 7 }
        assertEquals(0, sat.startMinuteOfDay)
        assertEquals(120, sat.endMinuteOfDay)
        assertEquals(12 * 60, sun.startMinuteOfDay)
        assertEquals(15 * 60, sun.endMinuteOfDay)

        val overnight12h = ScheduleValidator.validate(
            AvailabilityScheduleParser.parseForExcelImport("Monday 11 PM–1 AM")
        )
        assertEquals(ScheduleValidationStatus.VALID, overnight12h.validationStatus)
        val ov1 = overnight12h.windows.single()
        assertEquals(23 * 60, ov1.startMinuteOfDay)
        assertEquals(60, ov1.endMinuteOfDay)
        assertTrue(ov1.endsNextDay)
        assertEquals(120, ov1.durationMinutes)

        val overnight24h = ScheduleValidator.validate(
            AvailabilityScheduleParser.parseForExcelImport("Thursday 23:00–01:00")
        )
        assertEquals(ScheduleValidationStatus.VALID, overnight24h.validationStatus)
        assertTrue(overnight24h.windows.single().endsNextDay)
        assertEquals(120, overnight24h.windows.single().durationMinutes)

        val arabicNumerals = ScheduleValidator.validate(
            AvailabilityScheduleParser.parseForExcelImport("من ٣ العصر إلى ٧ مساءً")
        )
        assertEquals(ScheduleClassification.DAILY_DEFAULT_WITH_TIME, arabicNumerals.classification)
        assertEquals(15 * 60, arabicNumerals.windows.first().startMinuteOfDay)
        assertEquals(19 * 60, arabicNumerals.windows.first().endMinuteOfDay)

        val arabicDailyExcept = ScheduleValidator.validate(
            AvailabilityScheduleParser.parseForExcelImport("يوميًا من 3 ل 7 م ما عدا الجمعة والسبت")
        )
        assertEquals(ScheduleClassification.DAILY_WITH_EXCEPTIONS, arabicDailyExcept.classification)
        assertEquals(setOf(5, 6), arabicDailyExcept.excludedDays.toSet())
        assertEquals(setOf(1, 2, 3, 4, 7), arabicDailyExcept.normalizedDays.toSet())

        val incomplete = ScheduleValidator.validate(
            AvailabilityScheduleParser.parseForExcelImport("Clinic in the morning 8 AM")
        )
        assertEquals(ScheduleClassification.PARTIALLY_PARSED, incomplete.classification)
        assertEquals(ScheduleValidationStatus.INCOMPLETE, incomplete.validationStatus)
        assertTrue(incomplete.windows.isEmpty())

        val noInfo = ScheduleValidator.validate(
            AvailabilityScheduleParser.parseForExcelImport("N/A")
        )
        assertEquals(ScheduleClassification.NO_SCHEDULE_INFORMATION, noInfo.classification)
        assertEquals(ScheduleValidationStatus.NO_SCHEDULE, noInfo.validationStatus)
        assertTrue(noInfo.windows.isEmpty())
    }
}
