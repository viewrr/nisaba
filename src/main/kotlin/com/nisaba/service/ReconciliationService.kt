package com.nisaba.service

import arrow.fx.coroutines.parMap
import com.nisaba.client.TorrentClientFactory
import com.nisaba.client.dto.TorrentStatus
import com.nisaba.config.BootGate
import com.nisaba.config.NodeDefinition
import com.nisaba.persistence.entity.NodeEntity
import com.nisaba.persistence.entity.TorrentState
import com.nisaba.persistence.repository.NodeRepository
import com.nisaba.persistence.repository.TorrentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class ReconciliationService(
    private val nodeRepository: NodeRepository,
    private val torrentRepository: TorrentRepository,
    private val bandwidthService: BandwidthService,
    private val stateMachine: StateMachine,
    private val torrentClientFactory: TorrentClientFactory,
    private val bootGate: BootGate,
    private val nodeDefinitions: List<NodeDefinition>
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ReconciliationService::class.java)
        private const val PROBE_RETRY_DELAY_MS = 5000L
        private const val MAX_PROBE_RETRIES = 3
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        runBlocking(Dispatchers.IO) { reconcile() }
    }

    suspend fun reconcile() {
        logger.info("Starting reconciliation...")

        // Phase 1: DB ready (handled by Flyway)
        logger.info("Phase 1: DB ready (Flyway)")

        // Sync NodeDefinition entries into DB
        syncNodeDefinitions()

        // Phase 2: Probe all nodes
        logger.info("Phase 2: Probing nodes...")
        val healthyNodes = probeNodesWithRetry()
        if (healthyNodes.isEmpty()) {
            logger.error("No healthy nodes after retries. Starting in degraded mode.")
            bootGate.open()
            return
        }

        // Phase 3: Fetch live torrents from all healthy nodes
        logger.info("Phase 3: Fetching live torrents from ${healthyNodes.size} healthy nodes...")
        val liveData = fetchLiveTorrents(healthyNodes)

        // Phase 4: Reconcile DB vs live state
        logger.info("Phase 4: Reconciling DB vs live state...")
        reconcileState(liveData)

        // Phase 5: Seed EMA if needed, open BootGate
        logger.info("Phase 5: Seeding EMA and opening gate...")
        if (bandwidthService.hasNoRecentSamples()) {
            logger.info("No recent bandwidth samples, seeding all node weights to cold-start value")
            nodeRepository.seedAllWeights(0.5f)
        }

        bootGate.open()
        logger.info("Reconciliation complete")
    }

    private fun syncNodeDefinitions() {
        for (def in nodeDefinitions) {
            val existing = nodeRepository.findById(def.id).orElse(null)
            if (existing == null) {
                nodeRepository.save(
                    NodeEntity(
                        nodeId = def.id,
                        baseUrl = def.url,
                        label = def.label,
                        clientType = def.clientType,
                        isNewEntity = true
                    )
                )
                logger.info("Registered new node: ${def.id} at ${def.url}")
            } else {
                // Update URL/label if changed
                if (existing.baseUrl != def.url || existing.label != def.label) {
                    nodeRepository.save(
                        existing.copy(
                            baseUrl = def.url,
                            label = def.label,
                            clientType = def.clientType,
                            isNewEntity = false
                        )
                    )
                    logger.info("Updated node definition: ${def.id}")
                }
            }
        }
    }

    private suspend fun probeNodesWithRetry(): List<NodeEntity> {
        val allNodes = nodeRepository.findAll().toList()
        var attempt = 0

        while (attempt < MAX_PROBE_RETRIES) {
            attempt++
            logger.info("Probe attempt $attempt/$MAX_PROBE_RETRIES")

            allNodes.parMap { node ->
                val client = torrentClientFactory.clientFor(node)
                client.probe(node).fold(
                    ifLeft = {
                        logger.warn("Node ${node.nodeId} probe failed: $it")
                        nodeRepository.markUnhealthy(node.nodeId)
                    },
                    ifRight = {
                        logger.info("Node ${node.nodeId} is healthy (version=${it.version})")
                        nodeRepository.markHealthy(node.nodeId)
                    }
                )
            }

            val healthy = nodeRepository.findByHealthyTrue()
            if (healthy.isNotEmpty()) {
                logger.info("${healthy.size} healthy node(s) found")
                return healthy
            }

            if (attempt < MAX_PROBE_RETRIES) {
                logger.warn("No healthy nodes, retrying in ${PROBE_RETRY_DELAY_MS}ms...")
                delay(PROBE_RETRY_DELAY_MS)
            }
        }

        return emptyList()
    }

    private suspend fun fetchLiveTorrents(
        healthyNodes: List<NodeEntity>
    ): Map<String, List<TorrentStatus>> {
        val result = mutableMapOf<String, List<TorrentStatus>>()

        healthyNodes.parMap { node ->
            val client = torrentClientFactory.clientFor(node)
            client.listTorrents(node).fold(
                ifLeft = {
                    logger.warn("Failed to fetch torrents from ${node.nodeId}: $it")
                    node.nodeId to emptyList()
                },
                ifRight = { torrents ->
                    node.nodeId to torrents
                }
            )
        }.forEach { (nodeId, torrents) ->
            result[nodeId] = torrents
        }

        return result
    }

    private fun reconcileState(liveData: Map<String, List<TorrentStatus>>) {
        // Build infohash -> (nodeId, TorrentStatus) map
        val liveMap = mutableMapOf<String, Pair<String, TorrentStatus>>()
        for ((nodeId, torrents) in liveData) {
            for (torrent in torrents) {
                liveMap[torrent.infohash] = nodeId to torrent
            }
        }

        val allTorrents = torrentRepository.findAll()
        for (torrent in allTorrents) {
            val live = liveMap[torrent.infohash]

            when {
                // Fast-forward: live is complete
                live != null && live.second.progress >= 1.0f && torrent.state != TorrentState.DONE -> {
                    logger.info("Fast-forwarding ${torrent.infohash} to DONE (was ${torrent.state})")
                    fastForwardToDone(torrent.infohash, torrent.state)
                    updateProgress(torrent.infohash, live.second)
                }

                // Crash recovery: ASSIGNING/DOWNLOADING/STALLED but not live
                live == null && torrent.state in listOf(
                    TorrentState.DOWNLOADING,
                    TorrentState.ASSIGNING,
                    TorrentState.STALLED
                ) -> {
                    logger.warn(
                        "Crash recovery: ${torrent.infohash} was ${torrent.state} " +
                            "but not found on any node. Marking as STALLED."
                    )
                    if (torrent.state == TorrentState.DOWNLOADING) {
                        stateMachine.transition(
                            torrent.infohash, TorrentState.STALLED,
                            reason = "Not found on any node during reconciliation"
                        )
                    } else if (torrent.state == TorrentState.ASSIGNING) {
                        stateMachine.transition(
                            torrent.infohash, TorrentState.QUEUED,
                            reason = "ASSIGNING state during reconciliation"
                        )
                    }
                }

                // Normal sync
                live != null -> {
                    updateProgress(torrent.infohash, live.second)
                    // Update assignedNodeId if it changed
                    if (torrent.assignedNodeId != live.first) {
                        val updated = torrent.copy(
                            assignedNodeId = live.first,
                            isNewEntity = false
                        )
                        torrentRepository.save(updated)
                    }
                }
            }

            // Remove from liveMap as we process
            liveMap.remove(torrent.infohash)
        }

        // Log orphans (on nodes but not in DB)
        for ((infohash, pair) in liveMap) {
            logger.warn("Orphan torrent $infohash found on node ${pair.first} (not in registry)")
        }
    }

    private fun fastForwardToDone(infohash: String, currentState: TorrentState) {
        // Need to get to DONE through valid transitions
        when (currentState) {
            TorrentState.QUEUED -> {
                stateMachine.transition(infohash, TorrentState.ASSIGNING, reason = "Reconciliation fast-forward")
                stateMachine.transition(infohash, TorrentState.DOWNLOADING, reason = "Reconciliation fast-forward")
                stateMachine.transition(infohash, TorrentState.DONE, reason = "Reconciliation fast-forward")
            }
            TorrentState.ASSIGNING -> {
                stateMachine.transition(infohash, TorrentState.DOWNLOADING, reason = "Reconciliation fast-forward")
                stateMachine.transition(infohash, TorrentState.DONE, reason = "Reconciliation fast-forward")
            }
            TorrentState.DOWNLOADING -> {
                stateMachine.transition(infohash, TorrentState.DONE, reason = "Reconciliation fast-forward")
            }
            TorrentState.STALLED -> {
                stateMachine.transition(infohash, TorrentState.DOWNLOADING, reason = "Reconciliation fast-forward")
                stateMachine.transition(infohash, TorrentState.DONE, reason = "Reconciliation fast-forward")
            }
            TorrentState.PAUSED -> {
                stateMachine.transition(infohash, TorrentState.DOWNLOADING, reason = "Reconciliation fast-forward")
                stateMachine.transition(infohash, TorrentState.DONE, reason = "Reconciliation fast-forward")
            }
            TorrentState.REASSIGNING -> {
                stateMachine.transition(infohash, TorrentState.ASSIGNING, reason = "Reconciliation fast-forward")
                stateMachine.transition(infohash, TorrentState.DOWNLOADING, reason = "Reconciliation fast-forward")
                stateMachine.transition(infohash, TorrentState.DONE, reason = "Reconciliation fast-forward")
            }
            TorrentState.FAILED -> {
                stateMachine.transition(infohash, TorrentState.QUEUED, reason = "Reconciliation fast-forward")
                stateMachine.transition(infohash, TorrentState.ASSIGNING, reason = "Reconciliation fast-forward")
                stateMachine.transition(infohash, TorrentState.DOWNLOADING, reason = "Reconciliation fast-forward")
                stateMachine.transition(infohash, TorrentState.DONE, reason = "Reconciliation fast-forward")
            }
            TorrentState.DONE -> {} // Already done
        }
    }

    private fun updateProgress(infohash: String, status: TorrentStatus) {
        torrentRepository.updateProgress(
            infohash = infohash,
            progress = status.progress,
            totalSize = status.totalSize,
            contentPath = status.contentPath,
            eta = status.eta,
            ratio = status.ratio,
            seedingTime = status.seedingTime
        )
    }
}
