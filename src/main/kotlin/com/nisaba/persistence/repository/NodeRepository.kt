package com.nisaba.persistence.repository

import com.nisaba.persistence.entity.NodeEntity
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.time.Instant

interface NodeRepository : CrudRepository<NodeEntity, String> {
    fun findByHealthyTrue(): List<NodeEntity>

    @Query("SELECT * FROM nodes ORDER BY ema_weight DESC")
    fun findAllOrderByEmaWeightDesc(): List<NodeEntity>

    @Modifying
    @Query("UPDATE nodes SET healthy = true, last_seen_at = :lastSeenAt WHERE node_id = :nodeId")
    fun markHealthy(nodeId: String, lastSeenAt: Instant = Instant.now())

    @Modifying
    @Query("UPDATE nodes SET healthy = false WHERE node_id = :nodeId")
    fun markUnhealthy(nodeId: String)

    @Modifying
    @Query("UPDATE nodes SET ema_weight = :weight, last_speed_bps = :speedBps WHERE node_id = :nodeId")
    fun updateEmaWeight(nodeId: String, weight: Float, speedBps: Long)

    @Modifying
    @Query("UPDATE nodes SET ema_weight = :weight")
    fun seedAllWeights(weight: Float)
}
