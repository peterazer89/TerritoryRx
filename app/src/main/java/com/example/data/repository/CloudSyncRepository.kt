package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.CloudHcp
import com.example.data.model.CloudVisit
import com.example.util.OperationType
import com.example.util.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class CloudSyncRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeCloudHcps(): Flow<List<CloudHcp>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/hcps"
        emitAll(
            db.collection("users").document(uid).collection("hcps")
                .whereEqualTo("userId", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        CloudHcp::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    if (error is CancellationException) throw error
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun upsertCloudHcp(docId: String, hcp: CloudHcp): Result<String> {
        val uid = requireUserId()
        val safeDocId = docId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(100).ifBlank { "hcp_default" }
        val docRef = db.collection("users").document(uid).collection("hcps").document(safeDocId)
        return try {
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                val existingCreatedAt = existingSnap.getTimestamp(
                    "createdAt",
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                val existingVersion = (existingSnap.getLong("version") ?: 1L).toInt()
                val nextVersion = maxOf(hcp.version, existingVersion + 1)
                docRef.set(hcp.toFirestoreMap(uid, nextVersion, existingCreatedAt)).await()
            } else {
                val nextVersion = maxOf(1, hcp.version)
                docRef.set(hcp.toFirestoreMap(uid, nextVersion, null)).await()
            }
            Result.success(safeDocId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getCloudHcpById(targetUserId: String, docId: String): Result<CloudHcp?> {
        val docRef = db.collection("users").document(targetUserId).collection("hcps").document(docId)
        return try {
            val snap = docRef.get().await()
            Result.success(
                snap.toObject(CloudHcp::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getUserCloudHcps(): Result<List<CloudHcp>> {
        val uid = requireUserId()
        val path = "users/$uid/hcps"
        return try {
            val snap = db.collection("users").document(uid).collection("hcps")
                .whereEqualTo("userId", uid)
                .get()
                .await()
            Result.success(
                snap.toObjects(CloudHcp::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun upsertCloudVisit(docId: String, visit: CloudVisit): Result<String> {
        val uid = requireUserId()
        val safeDocId = docId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(100).ifBlank { "visit_default" }
        val docRef = db.collection("users").document(uid).collection("visits").document(safeDocId)
        return try {
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                val existingCreatedAt = existingSnap.getTimestamp(
                    "createdAt",
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                val existingVersion = (existingSnap.getLong("version") ?: 1L).toInt()
                val nextVersion = maxOf(visit.version, existingVersion + 1)
                docRef.set(visit.toFirestoreMap(uid, nextVersion, existingCreatedAt)).await()
            } else {
                val nextVersion = maxOf(1, visit.version)
                docRef.set(visit.toFirestoreMap(uid, nextVersion, null)).await()
            }
            Result.success(safeDocId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getUserCloudVisits(): Result<List<CloudVisit>> {
        val uid = requireUserId()
        val path = "users/$uid/visits"
        return try {
            val snap = db.collection("users").document(uid).collection("visits")
                .whereEqualTo("userId", uid)
                .get()
                .await()
            Result.success(
                snap.toObjects(CloudVisit::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }
}
