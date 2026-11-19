package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class CloudHcp(
    @DocumentId val id: String = "",
    val userId: String = "",
    val targetId: String = "",
    val name: String = "",
    val specialty: String = "",
    val quarterlyTarget: Int = 8,
    val priorityScore: Int = 50,
    val zone: String = "",
    @get:PropertyName("isActive")
    val isActive: Boolean = true,
    val version: Int = 1,
    @get:PropertyName("isDeleted")
    val isDeleted: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toFirestoreMap(uid: String, nextVersion: Int, existingCreatedAt: Timestamp?): Map<String, Any> {
        return mapOf(
            "userId" to uid,
            "targetId" to targetId,
            "name" to name,
            "specialty" to specialty,
            "quarterlyTarget" to quarterlyTarget,
            "priorityScore" to priorityScore,
            "zone" to zone,
            "isActive" to isActive,
            "version" to nextVersion,
            "isDeleted" to isDeleted,
            "createdAt" to (existingCreatedAt ?: FieldValue.serverTimestamp()),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }
}

@IgnoreExtraProperties
data class CloudVisit(
    @DocumentId val id: String = "",
    val userId: String = "",
    val hcpTargetId: String = "",
    val clinicLabel: String = "",
    val plannedDateIso: String = "",
    val status: String = "PLANNED",
    val qualifiesForCoverage: Boolean = true,
    val notes: String = "",
    val version: Int = 1,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toFirestoreMap(uid: String, nextVersion: Int, existingCreatedAt: Timestamp?): Map<String, Any> {
        return mapOf(
            "userId" to uid,
            "hcpTargetId" to hcpTargetId,
            "clinicLabel" to clinicLabel,
            "plannedDateIso" to plannedDateIso,
            "status" to status,
            "qualifiesForCoverage" to qualifiesForCoverage,
            "notes" to notes,
            "version" to nextVersion,
            "createdAt" to (existingCreatedAt ?: FieldValue.serverTimestamp()),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }
}

data class SyncConflict(
    val entityType: String,
    val recordId: String,
    val title: String,
    val localSummary: String,
    val remoteSummary: String,
    val localVersion: Int,
    val remoteVersion: Int
)
