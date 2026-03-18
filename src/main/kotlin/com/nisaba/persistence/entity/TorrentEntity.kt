package com.nisaba.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.PersistenceCreator
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("torrents")
data class TorrentEntity(
    @Id val infohash: String,
    val name: String? = null,
    val magnetUri: String,
    val category: String? = null,
    val savePath: String,
    val state: TorrentState = TorrentState.QUEUED,
    val assignedNodeId: String? = null,
    val progressPct: Float = 0f,
    val totalSize: Long? = null,
    val contentPath: String? = null,
    val eta: Long? = null,
    val ratio: Float? = null,
    val seedingTime: Long? = null,
    val piecesBitmap: ByteArray? = null,
    val lastSyncedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    @Transient val isNewEntity: Boolean = true
) : Persistable<String> {
    
    @PersistenceCreator
    constructor(
        infohash: String,
        name: String?,
        magnetUri: String,
        category: String?,
        savePath: String,
        state: TorrentState,
        assignedNodeId: String?,
        progressPct: Float,
        totalSize: Long?,
        contentPath: String?,
        eta: Long?,
        ratio: Float?,
        seedingTime: Long?,
        piecesBitmap: ByteArray?,
        lastSyncedAt: Instant?,
        createdAt: Instant
    ) : this(infohash, name, magnetUri, category, savePath, state, assignedNodeId, progressPct, 
             totalSize, contentPath, eta, ratio, seedingTime, piecesBitmap, lastSyncedAt, createdAt, false)
    
    override fun getId(): String = infohash
    override fun isNew(): Boolean = isNewEntity

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TorrentEntity) return false
        return infohash == other.infohash
    }

    override fun hashCode(): Int = infohash.hashCode()
}
