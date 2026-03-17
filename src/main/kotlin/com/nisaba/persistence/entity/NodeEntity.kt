package com.nisaba.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("nodes")
data class NodeEntity(
    @Id val nodeId: String,
    val baseUrl: String,
    val label: String? = null,
    val clientType: String = "qbittorrent",
    val healthy: Boolean = false,
    val emaWeight: Float = 0.5f,
    val lastSpeedBps: Long? = null,
    val lastSeenAt: Instant? = null,
    @Transient val isNewEntity: Boolean = true
) : Persistable<String> {
    override fun getId(): String = nodeId
    override fun isNew(): Boolean = isNewEntity
}
