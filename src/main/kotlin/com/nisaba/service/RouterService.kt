package com.nisaba.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.nisaba.error.NisabaError
import com.nisaba.persistence.entity.NodeEntity
import com.nisaba.persistence.repository.NodeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class RouterService(
    private val nodeRepository: NodeRepository
) {
    companion object {
        private val logger = LoggerFactory.getLogger(RouterService::class.java)
    }

    /**
     * Selects the healthy node with the highest EMA weight,
     * optionally excluding a specific node (e.g., during reassignment).
     */
    fun selectNode(excludeNodeId: String? = null): Either<NisabaError, NodeEntity> {
        val candidates = nodeRepository.findByHealthyTrue()
            .filter { it.nodeId != excludeNodeId }
            .sortedByDescending { it.emaWeight }

        return if (candidates.isEmpty()) {
            logger.warn("No healthy nodes available (excludeNodeId=$excludeNodeId)")
            NisabaError.NoHealthyNodes("No healthy nodes available").left()
        } else {
            val selected = candidates.first()
            logger.debug("Selected node ${selected.nodeId} (emaWeight=${selected.emaWeight})")
            selected.right()
        }
    }
}
