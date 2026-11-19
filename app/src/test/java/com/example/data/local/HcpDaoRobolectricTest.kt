package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.HcpEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HcpDaoRobolectricTest {

    private lateinit var database: AppDatabase
    private lateinit var hcpDao: HcpDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        hcpDao = database.hcpDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndObserveActiveTargets_ordersByPriorityAndFiltersArchived() = runBlocking {
        val doctorA = HcpEntity(
            targetId = "W-301",
            name = "Dr. Nadia El-Sayed",
            specialty = "Cardiology",
            territoryZone = "Sheikh Zayed",
            quarterlyTarget = 8,
            priorityScore = 80
        )
        val doctorB = HcpEntity(
            targetId = "W-302",
            name = "Dr. Tarek Hassan",
            specialty = "Endocrinology",
            territoryZone = "Mohandessin",
            quarterlyTarget = 10,
            priorityScore = 95
        )

        hcpDao.insertTargets(listOf(doctorA, doctorB))

        val activeTargets = hcpDao.observeActiveTargets().first()
        assertEquals(2, activeTargets.size)
        // Highest priority score (95) should be first
        assertEquals("W-302", activeTargets[0].targetId)
        assertEquals("W-301", activeTargets[1].targetId)
        assertEquals(2, hcpDao.observeActiveTargetCount().first())
    }

    @Test
    fun searchAndFilterTargets_bySpecialtyZoneAndQuery() = runBlocking {
        hcpDao.insertTargets(
            listOf(
                HcpEntity(
                    targetId = "W-401",
                    name = "Dr. Karim Mansour",
                    specialty = "Cardiology",
                    territoryZone = "6th of October",
                    quarterlyTarget = 8,
                    priorityScore = 85
                ),
                HcpEntity(
                    targetId = "W-402",
                    name = "Dr. Salma Adel",
                    specialty = "Neurology",
                    territoryZone = "Dokki",
                    quarterlyTarget = 6,
                    priorityScore = 72
                )
            )
        )

        val cardiologyResults = hcpDao.observeTargetsBySpecialty("cardiology").first()
        assertEquals(1, cardiologyResults.size)
        assertEquals("Dr. Karim Mansour", cardiologyResults.first().name)

        val zoneResults = hcpDao.observeTargetsByZone("dokki").first()
        assertEquals(1, zoneResults.size)
        assertEquals("W-402", zoneResults.first().targetId)

        val searchByTargetId = hcpDao.searchTargets("W-401").first()
        assertEquals(1, searchByTargetId.size)
        assertEquals("Dr. Karim Mansour", searchByTargetId.first().name)
    }

    @Test
    fun updateQuarterlyTargetGoal_andArchiveTarget_updatesStateCorrectly() = runBlocking {
        val insertedId = hcpDao.insertTarget(
            HcpEntity(
                targetId = "W-501",
                name = "Dr. Hany Farouk",
                specialty = "Internal Medicine",
                territoryZone = "Haram",
                quarterlyTarget = 6,
                priorityScore = 70
            )
        )

        hcpDao.updateQuarterlyTargetGoal(
            targetId = "W-501",
            quarterlyTarget = 12,
            priorityScore = 92
        )

        val updated = hcpDao.getTargetByTargetId("W-501")
        assertNotNull(updated)
        assertEquals(12, updated?.quarterlyTarget)
        assertEquals(92, updated?.priorityScore)

        // Archive the target
        hcpDao.archiveTargetById(insertedId)

        val activeAfterArchive = hcpDao.observeActiveTargets().first()
        assertTrue(activeAfterArchive.isEmpty())

        val rawRecord = hcpDao.getTargetById(insertedId)
        assertNotNull(rawRecord)
        assertTrue(rawRecord!!.isDeleted)

        // Permanent deletion
        hcpDao.deleteTargetById(insertedId)
        assertNull(hcpDao.getTargetById(insertedId))
    }
}
