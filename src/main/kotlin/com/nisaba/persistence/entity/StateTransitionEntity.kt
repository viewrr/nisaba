package com.nisaba.persistence.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("state_transitions")
data class StateTransitionEntity(
    @Id val id: Long? = null,
    val infohash: String,
    val fromState: TorrentState? = null,
    val toState: TorrentState,
    val nodeId: String? = null,
    val reason: String? = null,
    val occurredAt: Instant = Instant.now()
)
