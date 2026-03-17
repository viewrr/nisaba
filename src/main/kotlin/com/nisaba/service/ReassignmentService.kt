package com.nisaba.service

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.nisaba.client.TorrentClientFactory
import com.nisaba.error.NisabaError
import com.nisaba.persistence.entity.TorrentState
import com.nisaba.persistence.repository.NodeRepository
import com.nisaba.persistence.repository.TorrentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ReassignmentService(
    private val torrentRepository: TorrentRepository,
    private val nodeRepository: NodeRepository,
    private val stateMachine: StateMachine,
    private val routerService: RouterService,
    private val torrentClientFactory: TorrentClientFactory
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ReassignmentService::class.java)
    }

    /**
     * Reassigns a torrent from its current node to a new healthy node.
     *
     * Steps:
     * 1. Transition to REASSIGNING
     * 2. Pause on old node
     * 3. Select new node (excluding old)
     * 4. Transition to ASSIGNING
     * 5. Add to new node
     * 6. If accepted: transition to DOWNLOADING, update assignedNodeId
     * 7. If no healthy nodes: transition to FAILED
     */
    suspend fun reassign(infohash: String): Either<NisabaError, Unit> {
        val torrent = torrentRepository.findByInfohash(infohash)
            ?: return NisabaError.TorrentNotFound(infohash).left()

        val oldNodeId = torrent.assignedNodeId
        logger.info("Reassigning torrent $infohash from node $oldNodeId")

        // Transition to REASSIGNING
        return stateMachine.transition(
            infohash, TorrentState.REASSIGNING,
            reason = "Reassigning from $oldNodeId"
        ).flatMap {
            // Pause on old node if assigned
            if (oldNodeId != null) {
                val oldNode = nodeRepository.findById(oldNodeId).orElse(null)
                if (oldNode != null) {
                    val client = torrentClientFactory.clientFor(oldNode)
                    client.pauseTorrent(oldNode, infohash)
                        .onLeft { logger.warn("Failed to pause $infohash on $oldNodeId: $it") }
                }
            }

            // Select new node
            routerService.selectNode(excludeNodeId = oldNodeId).fold(
                ifLeft = {
                    // No healthy nodes available
                    logger.error("No healthy nodes for reassignment of $infohash")
                    stateMachine.transition(
                        infohash, TorrentState.FAILED,
                        reason = "No healthy nodes for reassignment"
                    )
                    NisabaError.NoHealthyNodes("No healthy nodes for reassignment").left()
                },
                ifRight = { newNode ->
                    // Transition to ASSIGNING
                    stateMachine.transition(
                        infohash, TorrentState.ASSIGNING,
                        nodeId = newNode.nodeId
                    ).flatMap {
                        val client = torrentClientFactory.clientFor(newNode)
                        client.addTorrent(
                            node = newNode,
                            magnetUri = torrent.magnetUri,
                            savePath = torrent.savePath,
                            category = torrent.category,
                            paused = false
                        ).flatMap { result ->
                            if (result.accepted) {
                                val updated = torrent.copy(
                                    assignedNodeId = newNode.nodeId,
                                    isNewEntity = false
                                )
                                torrentRepository.save(updated)
                                stateMachine.transition(
                                    infohash, TorrentState.DOWNLOADING,
                                    nodeId = newNode.nodeId,
                                    reason = "Reassigned from $oldNodeId"
                                )
                                logger.info("Reassigned $infohash to ${newNode.nodeId}")
                                Unit.right()
                            } else {
                                stateMachine.transition(
                                    infohash, TorrentState.FAILED,
                                    reason = "New node ${newNode.nodeId} rejected"
                                )
                                NisabaError.ReassignmentFailed(
                                    infohash, oldNodeId ?: "none",
                                    "New node rejected"
                                ).left()
                            }
                        }
                    }
                }
            )
        }
    }
}
