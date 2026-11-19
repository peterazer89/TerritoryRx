package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.CloudHcp
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun upsertCloudHcp_validPayload_createsAndReadsDocument() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = CloudSyncRepository(firestore)
        val docId = "hcp_${UUID.randomUUID().toString().take(8)}"

        val hcp = CloudHcp(
            userId = uid,
            targetId = "T-500",
            name = "Dr. Tarek El-Sayed",
            specialty = "Cardiology",
            quarterlyTarget = 8,
            priorityScore = 90,
            zone = "Zamalek",
            isActive = true,
            version = 1,
            isDeleted = false
        )

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.upsertCloudHcp(docId, hcp)
        }
        assertTrue(createResult.isSuccess)

        val listResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.getUserCloudHcps()
        }
        assertTrue(listResult.isSuccess)
        assertTrue(listResult.getOrThrow().any { it.id == docId && it.name == "Dr. Tarek El-Sayed" })
    }

    @Test
    fun observeCloudHcps_authenticatedOwner_emitsUpdates() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = CloudSyncRepository(firestore)
        val docId = "hcp_${UUID.randomUUID().toString().take(8)}"

        val hcp = CloudHcp(
            userId = uid,
            targetId = "T-501",
            name = "Dr. Nadia Mansour",
            specialty = "Endocrinology",
            quarterlyTarget = 6,
            priorityScore = 80,
            zone = "Maadi",
            isActive = true,
            version = 1,
            isDeleted = false
        )

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.upsertCloudHcp(docId, hcp).getOrThrow()
        }

        val emitted = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeCloudHcps().first { list -> list.any { it.id == docId } }
        }
        assertTrue(emitted.any { it.id == docId })
    }

    @Test
    fun getCloudHcpById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = CloudSyncRepository(firestore)
        val docId = "hcp_${UUID.randomUUID().toString().take(8)}"

        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.upsertCloudHcp(
                docId,
                CloudHcp(
                    userId = aliceUid,
                    targetId = "T-502",
                    name = "Dr. Karim Nabil",
                    specialty = "Pediatrics",
                    quarterlyTarget = 8,
                    priorityScore = 75,
                    zone = "Heliopolis"
                )
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = CloudSyncRepository(firestore)
        val crossResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getCloudHcpById(aliceUid, docId)
        }
        assertTrue(crossResult.isFailure)
        val firestoreEx = crossResult.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull(firestoreEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
    }

    @Test
    fun observeCloudHcps_unauthenticatedUser_fails() = runBlocking {
        auth.signOut()
        val repository = CloudSyncRepository(firestore)
        var caught: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeCloudHcps().first()
            }
        } catch (t: Throwable) {
            caught = t
        }
        assertNotNull(caught)
    }

    private companion object {
        const val ALICE_EMAIL = "alice.medrep@test.com"
        const val BOB_EMAIL = "bob.medrep@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
