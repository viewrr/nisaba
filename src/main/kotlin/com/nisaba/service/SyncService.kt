package com.nisaba.service

import arrow.fx.coroutines.parMap
import com.nisaba.client.TorrentClientFactory
import com.nisaba.client.dto.TorrentStatus
import com.nisaba.client.dto.TransferInfo
import com.nisaba.config.NisabaProperties
import com.nisaba.persistence.entity.NodeEntity
import com.nisaba.persistence.entity.TorrentState
import com.nisaba.persistence.repository.NodeRepository
import com.nisaba.persistence.repository.TorrentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class NodeSnapshot(
    val node: NodeEntity,
    val speed: TransferInfo,
    val torrents: List<TorrentStatus>
)

@Service
class SyncService(
    private val nodeRepository: NodeRepository,
    private val torrentRepository: TorrentRepository,
    private val bandwidthService: BandwidthService,
    private val stateMachine: StateMachine,
    private val reassignmentService: ReassignmentService,
    private val torrentClientFactory: TorrentClientFactory,
    private val properties: NisabaProperties
) {
    companion object {
        private val logger = LoggerFactory.getLogger(SyncService::class.java)
    }

    /** Tracks consecutive zero-speed cycles per torrent infohash */
    val stallCounters = ConcurrentHashMap<String, Int>()

    @Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
    fun scheduledSync() {
        runBlocking(Dispatchers.IO) { syncLoop() }
    }

    suspend fun syncLoop() {
        val healthyNodes = nodeRepository.findByHealthyTrue()
        if (healthyNodes.isEmpty()) {
            logger.warn("No healthy nodes to sync")
            return
        }

        // Poll all healthy nodes concurrently
        val snapshots = healthyNodes.parMap { node ->
            pollNode(node)
        }.filterNotNull()

        // Update bandwidth EMA for each node
        for (snapshot in snapshots) {
            bandwidthService.recordSampleAndUpdateEma(
                snapshot.node.nodeId,
                snapshot.speed.downloadSpeedBps
            )
        }

        // Build a lookup: infohash+assignedNodeId -> TorrentStatus
        val liveMap = mutableMapOf<String, Pair<NodeEntity, TorrentStatus>>()
        for (snapshot in snapshots) {
            for (torrent in snapshot.torrents) {
                val key = "${torrent.infohash}:${snapshot.node.nodeId}"
                liveMap[key] = snapshot.node to torrent
            }
        }

        // Diff registry against live data
        val activeStates = arrayOf(
            TorrentState.DOWNLOADING.name,
            TorrentState.ASSIGNING.name,
            TorrentState.STALLED.name
        )
        val registryTorrents = torrentRepository.findByStateIn(activeStates)

        for (torrent in registryTorrents) {
            val key = "${torrent.infohash}:${torrent.assignedNodeId}"
            val live = liveMap[key]

            when {
                // Self-recovery: STALLED + speed > 0 → back to DOWNLOADING
                torrent.state == TorrentState.STALLED && live != null && live.second.speedBps > 0 -> {
                    logger.info("Torrent ${torrent.infohash} self-recovered from STALLED")
                    stateMachine.transition(
                        torrent.infohash, TorrentState.DOWNLOADING,
                        reason = "Self-recovered, speed=${live.second.speedBps}bps"
                    )
                    stallCounters.remove(torrent.infohash)
                    updateProgress(torrent.infohash, live.second)
                }

                // Completed: progress >= 1.0
                live != null && live.second.progress >= 1.0f -> {
                    logger.info("Torrent ${torrent.infohash} completed")
                    if (torrent.state != TorrentState.DOWNLOADING) {
                        // Fast-forward to DOWNLOADING first if in another state
                        stateMachine.transition(
                            torrent.infohash, TorrentState.DOWNLOADING,
                            reason = "Fast-forward for completion"
                        ).onLeft {
                            logger.warn("Cannot fast-forward ${torrent.infohash} to DOWNLOADING: $it")
                        }
                    }
                    stateMachine.transition(
                        torrent.infohash, TorrentState.DONE,
                        reason = "Download completed"
                    )
                    updateProgress(torrent.infohash, live.second)
                    stallCounters.remove(torrent.infohash)
                }

                // Normal progress update
                live != null -> {
                    updateProgress(torrent.infohash, live.second)
                    checkStall(torrent.infohash, live.second.speedBps)
                }

                // Not found on assigned node → STALLED + reassign
                torrent.state != TorrentState.ASSIGNING && torrent.assignedNodeId != null -> {
                    logger.warn("Torrent ${torrent.infohash} not found on node ${torrent.assignedNodeId}")
                    if (torrent.state == TorrentState.DOWNLOADING) {
                        stateMachine.transition(
                            torrent.infohash, TorrentState.STALLED,
                            reason = "Not found on assigned node"
                        ).onRight {
                            reassignmentService.reassign(torrent.infohash)
                                .onLeft { err ->
                                    logger.error("Reassignment failed for ${torrent.infohash}: $err")
                                }
                        }
                    }
                }

                // ASSIGNING timeout (90s) → back to QUEUED
                torrent.state == TorrentState.ASSIGNING -> {
                    val assigningTimeout = properties.poll.assigningTimeoutSeconds
                    val lastSync = torrent.lastSyncedAt ?: torrent.createdAt
                    if (lastSync.plus(assigningTimeout, ChronoUnit.SECONDS).isBefore(Instant.now())) {
                        logger.warn("Torrent ${torrent.infohash} ASSIGNING timeout after ${assigningTimeout}s")
                        stateMachine.transition(
                            torrent.infohash, TorrentState.QUEUED,
                            reason = "ASSIGNING timeout"
                        )
                    }
                }
            }
        }
    }

    private suspend fun pollNode(node: NodeEntity): NodeSnapshot? {
        val client = torrentClientFactory.clientFor(node)
        return try {
            val speed = client.getTransferSpeed(node).getOrNull() ?: TransferInfo(0, 0)
            val torrents = client.listTorrents(node).getOrNull() ?: emptyList()
            NodeSnapshot(node, speed, torrents)
        } catch (e: Exception) {
            logger.error("Failed to poll node ${node.nodeId}: ${e.message}")
            null
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

    private fun checkStall(infohash: String, speedBps: Long) {
        if (speedBps == 0L) {
            val count = stallCounters.merge(infohash, 1) { old, _ -> old + 1 } ?: 1
            if (count >= properties.poll.stallThresholdCycles) {
                logger.warn("Torrent $infohash stalled for $count cycles")
                stateMachine.transition(
                    infohash, TorrentState.STALLED,
                    reason = "Zero speed for $count consecutive cycles"
                )
            }
        } else {
            stallCounters.remove(infohash)
        }
    }
}
