package com.nisaba.persistence.entity

import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("bandwidth_samples")
data class BandwidthSampleEntity(
    val sampledAt: Instant = Instant.now(),
    val nodeId: String,
    val speedBps: Long
)
