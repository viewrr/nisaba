package com.nisaba.api.controller

import com.nisaba.config.BootGate
import com.nisaba.persistence.repository.NodeRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController(
    private val bootGate: BootGate,
    private val nodeRepository: NodeRepository
) {

    data class HealthStatus(
        val status: String,
        val ready: Boolean,
        val healthyNodes: Int,
        val totalNodes: Int
    )

    @GetMapping("/health")
    fun health(): ResponseEntity<HealthStatus> {
        val allNodes = nodeRepository.findAll().toList()
        val healthyNodes = allNodes.count { it.healthy }

        val status = HealthStatus(
            status = if (bootGate.isReady()) "UP" else "STARTING",
            ready = bootGate.isReady(),
            healthyNodes = healthyNodes,
            totalNodes = allNodes.size
        )

        return if (bootGate.isReady()) {
            ResponseEntity.ok(status)
        } else {
            ResponseEntity.status(503).body(status)
        }
    }

    @GetMapping("/health/live")
    fun liveness(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf("status" to "UP"))
    }

    @GetMapping("/health/ready")
    fun readiness(): ResponseEntity<Map<String, String>> {
        return if (bootGate.isReady()) {
            ResponseEntity.ok(mapOf("status" to "UP"))
        } else {
            ResponseEntity.status(503).body(mapOf("status" to "STARTING"))
        }
    }
}
