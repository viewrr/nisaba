package com.nisaba.service

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.nisaba.client.TorrentClientFactory
import com.nisaba.error.NisabaError
import com.nisaba.persistence.entity.TorrentEntity
import com.nisaba.persistence.entity.TorrentState
import com.nisaba.persistence.repository.NodeRepository
import com.nisaba.persistence.repository.TorrentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class RegistryService(
    private val torrentRepository: TorrentRepository,
    private val nodeRepository: NodeRepository,
    private val routerService: RouterService,
    private val stateMachine: StateMachine,
    private val torrentClientFactory: TorrentClientFactory
) {
    companion object {
        private val logger = LoggerFactory.getLogger(RegistryService::class.java)
    }

    /**
     * Adds a torrent to the registry. If not paused, assigns it to a healthy node
     * and begins downloading. If paused, transitions through to PAUSED state.
     */
    suspend fun addTorrent(
        infohash: String,
        magnetUri: String,
        savePath: String,
        category: String?,
        paused: Boolean
    ): Either<NisabaError, TorrentEntity> {
        // Dedup check
        torrentRepository.findByInfohash(infohash)?.let {
            return NisabaError.AlreadyExists(infohash).left()
        }

        // Create QUEUED registry record
        val entity = TorrentEntity(
            infohash = infohash,
            magnetUri = magnetUri,
            savePath = savePath,
            category = category,
            state = TorrentState.QUEUED,
            isNewEntity = true
        )
        torrentRepository.save(entity)
        logger.info("Created torrent registry entry: $infohash (paused=$paused)")

        if (paused) {
            // Transition QUEUED → ASSIGNING → DOWNLOADING → PAUSED
            return stateMachine.transition(infohash, TorrentState.ASSIGNING)
                .flatMap { stateMachine.transition(infohash, TorrentState.DOWNLOADING) }
                .flatMap { stateMachine.transition(infohash, TorrentState.PAUSED) }
        }

        // Select node and assign
        return routerService.selectNode().flatMap { node ->
            stateMachine.transition(
                infohash, TorrentState.ASSIGNING,
                nodeId = node.nodeId
            ).flatMap { assignedTorrent ->
                val client = torrentClientFactory.clientFor(node)
                val addResult = client.addTorrent(
                    node = node,
                    magnetUri = magnetUri,
                    savePath = savePath,
                    category = category,
                    paused = false
                )
                addResult.flatMap { result ->
                    if (result.accepted) {
                        val updated = assignedTorrent.copy(
                            assignedNodeId = node.nodeId,
                            isNewEntity = false
                        )
                        torrentRepository.save(updated)
                        stateMachine.transition(
                            infohash, TorrentState.DOWNLOADING,
                            nodeId = node.nodeId
                        )
                    } else {
                        // Node rejected, transition back to QUEUED
                        stateMachine.transition(
                            infohash, TorrentState.QUEUED,
                            reason = "Node ${node.nodeId} rejected"
                        )
                        NisabaError.NodeRejected(node.nodeId, "Torrent rejected").left()
                    }
                }
            }
        }
    }

    /**
     * Removes a torrent from the registry and from its assigned node.
     */
    suspend fun removeTorrent(
        infohash: String,
        deleteFiles: Boolean
    ): Either<NisabaError, Unit> {
        val torrent = torrentRepository.findByInfohash(infohash)
            ?: return NisabaError.TorrentNotFound(infohash).left()

        // If assigned to a node, remove from it
        torrent.assignedNodeId?.let { nodeId ->
            val node = nodeRepository.findById(nodeId).orElse(null)
            if (node != null) {
                val client = torrentClientFactory.clientFor(node)
                client.removeTorrent(node, infohash, deleteFiles)
                    .onLeft { logger.warn("Failed to remove $infohash from node $nodeId: $it") }
            }
        }

        torrentRepository.deleteById(infohash)
        logger.info("Removed torrent $infohash from registry")
        return Unit.right()
    }
}
