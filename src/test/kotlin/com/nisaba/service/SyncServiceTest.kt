package com.nisaba.service

import arrow.core.left
import arrow.core.right
import com.nisaba.client.TorrentClient
import com.nisaba.client.TorrentClientFactory
import com.nisaba.client.dto.ClientTorrentState
import com.nisaba.client.dto.TorrentStatus
import com.nisaba.client.dto.TransferInfo
import com.nisaba.config.NisabaProperties
import com.nisaba.error.NisabaError
import com.nisaba.persistence.entity.NodeEntity
import com.nisaba.persistence.entity.TorrentEntity
import com.nisaba.persistence.entity.TorrentState
import com.nisaba.persistence.repository.NodeRepository
import com.nisaba.persistence.repository.TorrentRepository
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class SyncServiceTest {

    private lateinit var nodeRepository: NodeRepository
    private lateinit var torrentRepository: TorrentRepository
    private lateinit var bandwidthService: BandwidthService
    private lateinit var stateMachine: StateMachine
    private lateinit var reassignmentService: ReassignmentService
    private lateinit var torrentClientFactory: TorrentClientFactory
    private lateinit var torrentClient: TorrentClient
    private lateinit var properties: NisabaProperties
    private lateinit var syncService: SyncService

    private fun node(id: String) = NodeEntity(
        nodeId = id,
        baseUrl = "http://$id:8080",
        healthy = true,
        emaWeight = 0.5f
    )

    private fun torrent(
        infohash: String = "abc123",
        state: TorrentState = TorrentState.DOWNLOADING,
        assignedNodeId: String? = "node-1",
        lastSyncedAt: Instant? = null,
        createdAt: Instant = Instant.now()
    ) = TorrentEntity(
        infohash = infohash,
        magnetUri = "magnet:?xt=urn:btih:$infohash",
        savePath = "/data/media",
        state = state,
        assignedNodeId = assignedNodeId,
        lastSyncedAt = lastSyncedAt,
        createdAt = createdAt,
        isNewEntity = false
    )

    private fun torrentStatus(
        infohash: String = "abc123",
        progress: Float = 0.5f,
        speedBps: Long = 1000L
    ) = TorrentStatus(
        infohash = infohash,
        name = "Test Torrent",
        progress = progress,
        speedBps = speedBps,
        state = ClientTorrentState.DOWNLOADING,
        savePath = "/data",
        contentPath = null,
        totalSize = 1000000L,
        eta = 3600L,
        ratio = 0.0f,
        seedingTime = null
    )

    @BeforeEach
    fun setUp() {
        nodeRepository = mockk()
        torrentRepository = mockk()
        bandwidthService = mockk()
        stateMachine = mockk()
        reassignmentService = mockk()
        torrentClientFactory = mockk()
        torrentClient = mockk()
        properties = NisabaProperties(
            auth = NisabaProperties.AuthProperties("user", "pass"),
            poll = NisabaProperties.PollProperties(
                intervalSeconds = 30,
                stallThresholdCycles = 2,
                assigningTimeoutSeconds = 90
            )
        )

        syncService = SyncService(
            nodeRepository,
            torrentRepository,
            bandwidthService,
            stateMachine,
            reassignmentService,
            torrentClientFactory,
            properties
        )

        every { torrentClientFactory.clientFor(any()) } returns torrentClient
    }

    @Test
    fun `syncLoop does nothing when no healthy nodes`() = runBlocking {
        every { nodeRepository.findByHealthyTrue() } returns emptyList()

        syncService.syncLoop()

        verify(exactly = 0) { torrentRepository.findByStateIn(any()) }
    }

    @Test
    fun `syncLoop updates bandwidth EMA for each node`() = runBlocking {
        val node1 = node("node-1")
        every { nodeRepository.findByHealthyTrue() } returns listOf(node1)
        coEvery { torrentClient.getTransferSpeed(node1) } returns TransferInfo(5000L, 1000L).right()
        coEvery { torrentClient.listTorrents(node1) } returns emptyList<TorrentStatus>().right()
        every { bandwidthService.recordSampleAndUpdateEma("node-1", 5000L) } just runs
        every { torrentRepository.findByStateIn(any()) } returns emptyList()

        syncService.syncLoop()

        verify { bandwidthService.recordSampleAndUpdateEma("node-1", 5000L) }
    }

    @Test
    fun `syncLoop marks torrent as DONE when progress is complete`() = runBlocking {
        val node1 = node("node-1")
        val t = torrent(state = TorrentState.DOWNLOADING)
        val completedStatus = torrentStatus(progress = 1.0f)

        every { nodeRepository.findByHealthyTrue() } returns listOf(node1)
        coEvery { torrentClient.getTransferSpeed(node1) } returns TransferInfo(5000L, 1000L).right()
        coEvery { torrentClient.listTorrents(node1) } returns listOf(completedStatus).right()
        every { bandwidthService.recordSampleAndUpdateEma(any(), any()) } just runs
        every { torrentRepository.findByStateIn(any()) } returns listOf(t)
        every { stateMachine.transition(any(), any(), any(), any()) } returns t.right()
        every { torrentRepository.updateProgress(any(), any(), any(), any(), any(), any(), any()) } just runs

        syncService.syncLoop()

        verify { stateMachine.transition("abc123", TorrentState.DONE, any(), any()) }
    }

    @Test
    fun `syncLoop recovers STALLED torrent when speed resumes`() = runBlocking {
        val node1 = node("node-1")
        val t = torrent(state = TorrentState.STALLED)
        val activeStatus = torrentStatus(speedBps = 5000L)

        every { nodeRepository.findByHealthyTrue() } returns listOf(node1)
        coEvery { torrentClient.getTransferSpeed(node1) } returns TransferInfo(5000L, 1000L).right()
        coEvery { torrentClient.listTorrents(node1) } returns listOf(activeStatus).right()
        every { bandwidthService.recordSampleAndUpdateEma(any(), any()) } just runs
        every { torrentRepository.findByStateIn(any()) } returns listOf(t)
        every { stateMachine.transition(any(), any(), any(), any()) } returns t.right()
        every { torrentRepository.updateProgress(any(), any(), any(), any(), any(), any(), any()) } just runs

        // Set a stall counter to ensure it gets cleared
        syncService.stallCounters["abc123"] = 1

        syncService.syncLoop()

        verify { stateMachine.transition("abc123", TorrentState.DOWNLOADING, any(), any()) }
        syncService.stallCounters["abc123"] shouldBe null
    }

    @Test
    fun `syncLoop triggers reassignment when torrent not found on node`() = runBlocking {
        val node1 = node("node-1")
        val t = torrent(state = TorrentState.DOWNLOADING)

        every { nodeRepository.findByHealthyTrue() } returns listOf(node1)
        coEvery { torrentClient.getTransferSpeed(node1) } returns TransferInfo(0L, 0L).right()
        coEvery { torrentClient.listTorrents(node1) } returns emptyList<TorrentStatus>().right()
        every { bandwidthService.recordSampleAndUpdateEma(any(), any()) } just runs
        every { torrentRepository.findByStateIn(any()) } returns listOf(t)
        every { stateMachine.transition(any(), any(), any(), any()) } returns t.right()
        coEvery { reassignmentService.reassign("abc123") } returns Unit.right()

        syncService.syncLoop()

        verify { stateMachine.transition("abc123", TorrentState.STALLED, any(), any()) }
        coVerify { reassignmentService.reassign("abc123") }
    }

    @Test
    fun `syncLoop times out ASSIGNING state after timeout`() = runBlocking {
        val node1 = node("node-1")
        val oldTime = Instant.now().minus(100, ChronoUnit.SECONDS)
        val t = torrent(state = TorrentState.ASSIGNING, lastSyncedAt = oldTime, createdAt = oldTime)

        every { nodeRepository.findByHealthyTrue() } returns listOf(node1)
        coEvery { torrentClient.getTransferSpeed(node1) } returns TransferInfo(0L, 0L).right()
        coEvery { torrentClient.listTorrents(node1) } returns emptyList<TorrentStatus>().right()
        every { bandwidthService.recordSampleAndUpdateEma(any(), any()) } just runs
        every { torrentRepository.findByStateIn(any()) } returns listOf(t)
        every { stateMachine.transition(any(), any(), any(), any()) } returns t.right()

        syncService.syncLoop()

        verify { stateMachine.transition("abc123", TorrentState.QUEUED, any(), any()) }
    }

    @Test
    fun `syncLoop increments stall counter when speed is zero`() = runBlocking {
        val node1 = node("node-1")
        val t = torrent(state = TorrentState.DOWNLOADING)
        val stalledStatus = torrentStatus(speedBps = 0L, progress = 0.5f)

        every { nodeRepository.findByHealthyTrue() } returns listOf(node1)
        coEvery { torrentClient.getTransferSpeed(node1) } returns TransferInfo(0L, 0L).right()
        coEvery { torrentClient.listTorrents(node1) } returns listOf(stalledStatus).right()
        every { bandwidthService.recordSampleAndUpdateEma(any(), any()) } just runs
        every { torrentRepository.findByStateIn(any()) } returns listOf(t)
        every { torrentRepository.updateProgress(any(), any(), any(), any(), any(), any(), any()) } just runs

        syncService.syncLoop()

        syncService.stallCounters["abc123"] shouldBe 1
    }

    @Test
    fun `syncLoop marks STALLED after threshold cycles`() = runBlocking {
        val node1 = node("node-1")
        val t = torrent(state = TorrentState.DOWNLOADING)
        val stalledStatus = torrentStatus(speedBps = 0L, progress = 0.5f)

        every { nodeRepository.findByHealthyTrue() } returns listOf(node1)
        coEvery { torrentClient.getTransferSpeed(node1) } returns TransferInfo(0L, 0L).right()
        coEvery { torrentClient.listTorrents(node1) } returns listOf(stalledStatus).right()
        every { bandwidthService.recordSampleAndUpdateEma(any(), any()) } just runs
        every { torrentRepository.findByStateIn(any()) } returns listOf(t)
        every { torrentRepository.updateProgress(any(), any(), any(), any(), any(), any(), any()) } just runs
        every { stateMachine.transition(any(), any(), any(), any()) } returns t.right()

        // Pre-set counter to threshold - 1
        syncService.stallCounters["abc123"] = 1

        syncService.syncLoop()

        verify { stateMachine.transition("abc123", TorrentState.STALLED, any(), any()) }
    }

    @Test
    fun `syncLoop resets stall counter when speed resumes`() = runBlocking {
        val node1 = node("node-1")
        val t = torrent(state = TorrentState.DOWNLOADING)
        val activeStatus = torrentStatus(speedBps = 5000L, progress = 0.5f)

        every { nodeRepository.findByHealthyTrue() } returns listOf(node1)
        coEvery { torrentClient.getTransferSpeed(node1) } returns TransferInfo(5000L, 0L).right()
        coEvery { torrentClient.listTorrents(node1) } returns listOf(activeStatus).right()
        every { bandwidthService.recordSampleAndUpdateEma(any(), any()) } just runs
        every { torrentRepository.findByStateIn(any()) } returns listOf(t)
        every { torrentRepository.updateProgress(any(), any(), any(), any(), any(), any(), any()) } just runs

        // Pre-set counter
        syncService.stallCounters["abc123"] = 1

        syncService.syncLoop()

        syncService.stallCounters["abc123"] shouldBe null
    }

    @Test
    fun `syncLoop handles failed node poll gracefully`() = runBlocking {
        val node1 = node("node-1")
        val node2 = node("node-2")

        every { nodeRepository.findByHealthyTrue() } returns listOf(node1, node2)
        coEvery { torrentClient.getTransferSpeed(node1) } throws RuntimeException("Connection failed")
        coEvery { torrentClient.getTransferSpeed(node2) } returns TransferInfo(1000L, 500L).right()
        coEvery { torrentClient.listTorrents(node2) } returns emptyList<TorrentStatus>().right()
        every { bandwidthService.recordSampleAndUpdateEma("node-2", 1000L) } just runs
        every { torrentRepository.findByStateIn(any()) } returns emptyList()

        syncService.syncLoop()

        // Should still process node-2
        verify { bandwidthService.recordSampleAndUpdateEma("node-2", 1000L) }
        // Should not process node-1
        verify(exactly = 0) { bandwidthService.recordSampleAndUpdateEma("node-1", any()) }
    }
}
