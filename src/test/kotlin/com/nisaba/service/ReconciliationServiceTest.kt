package com.nisaba.service

import arrow.core.left
import arrow.core.right
import com.nisaba.client.TorrentClient
import com.nisaba.client.TorrentClientFactory
import com.nisaba.client.dto.ClientTorrentState
import com.nisaba.client.dto.NodeHealth
import com.nisaba.client.dto.TorrentStatus
import com.nisaba.config.BootGate
import com.nisaba.config.NodeDefinition
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
import java.util.*

class ReconciliationServiceTest {

    private lateinit var nodeRepository: NodeRepository
    private lateinit var torrentRepository: TorrentRepository
    private lateinit var bandwidthService: BandwidthService
    private lateinit var stateMachine: StateMachine
    private lateinit var torrentClientFactory: TorrentClientFactory
    private lateinit var torrentClient: TorrentClient
    private lateinit var bootGate: BootGate
    private lateinit var nodeDefinitions: List<NodeDefinition>
    private lateinit var reconciliationService: ReconciliationService

    private fun node(id: String, healthy: Boolean = true) = NodeEntity(
        nodeId = id,
        baseUrl = "http://$id:8080",
        healthy = healthy,
        emaWeight = 0.5f
    )

    private fun torrent(
        infohash: String = "abc123",
        state: TorrentState = TorrentState.DOWNLOADING,
        assignedNodeId: String? = "node-1"
    ) = TorrentEntity(
        infohash = infohash,
        magnetUri = "magnet:?xt=urn:btih:$infohash",
        savePath = "/data/media",
        state = state,
        assignedNodeId = assignedNodeId,
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
        contentPath = "/data/movie.mkv",
        totalSize = 1000000L,
        eta = 3600L,
        ratio = 0.0f,
        seedingTime = null
    )

    private fun nodeDef(id: String) = NodeDefinition(
        id = id,
        url = "http://$id:8080",
        username = "admin",
        password = "pass",
        label = "Node $id"
    )

    @BeforeEach
    fun setUp() {
        nodeRepository = mockk(relaxed = true)
        torrentRepository = mockk(relaxed = true)
        bandwidthService = mockk(relaxed = true)
        stateMachine = mockk(relaxed = true)
        torrentClientFactory = mockk()
        torrentClient = mockk()
        bootGate = mockk(relaxed = true)
        nodeDefinitions = listOf(nodeDef("node-1"), nodeDef("node-2"))

        every { torrentClientFactory.clientFor(any()) } returns torrentClient
        every { stateMachine.transition(any(), any(), any(), any()) } returns torrent().right()
        every { nodeRepository.save(any<NodeEntity>()) } answers { firstArg() }
        every { torrentRepository.save(any<TorrentEntity>()) } answers { firstArg() }
    }

    private fun createService() = ReconciliationService(
        nodeRepository,
        torrentRepository,
        bandwidthService,
        stateMachine,
        torrentClientFactory,
        bootGate,
        nodeDefinitions
    )

    // --- syncNodeDefinitions tests ---

    @Test
    fun `reconcile registers new nodes from definitions`() = runBlocking {
        every { nodeRepository.findById("node-1") } returns Optional.empty()
        every { nodeRepository.findById("node-2") } returns Optional.empty()
        every { nodeRepository.findAll() } returns emptyList()
        every { nodeRepository.findByHealthyTrue() } returns emptyList()
        every { torrentRepository.findAll() } returns emptyList()

        val service = createService()
        service.reconcile()

        verify {
            nodeRepository.save(match<NodeEntity> {
                it.nodeId == "node-1" && it.baseUrl == "http://node-1:8080" && it.isNew
            })
        }
        verify {
            nodeRepository.save(match<NodeEntity> {
                it.nodeId == "node-2" && it.baseUrl == "http://node-2:8080" && it.isNew
            })
        }
    }

    @Test
    fun `reconcile updates existing node when URL changes`() = runBlocking {
        // Use single node definition for simpler test
        val singleNodeDef = listOf(nodeDef("node-1"))
        val existingNode = node("node-1").copy(baseUrl = "http://old-url:8080", label = "Node node-1")
        every { nodeRepository.findById("node-1") } returns Optional.of(existingNode)
        every { nodeRepository.findAll() } returns listOf(existingNode)
        every { nodeRepository.findByHealthyTrue() } returns emptyList()
        every { torrentRepository.findAll() } returns emptyList()
        coEvery { torrentClient.probe(any()) } returns NisabaError.NodeUnreachable("node-1", "timeout").left()

        val service = ReconciliationService(
            nodeRepository, torrentRepository, bandwidthService, stateMachine,
            torrentClientFactory, bootGate, singleNodeDef
        )
        service.reconcile()

        verify {
            nodeRepository.save(match<NodeEntity> {
                it.nodeId == "node-1" && it.baseUrl == "http://node-1:8080" && !it.isNew
            })
        }
    }

    @Test
    fun `reconcile does not update node when nothing changed`() = runBlocking {
        // Use single node definition for simpler test
        val singleNodeDef = listOf(nodeDef("node-1"))
        val existingNode = node("node-1").copy(
            baseUrl = "http://node-1:8080",
            label = "Node node-1"
        )
        every { nodeRepository.findById("node-1") } returns Optional.of(existingNode)
        every { nodeRepository.findAll() } returns listOf(existingNode)
        every { nodeRepository.findByHealthyTrue() } returns emptyList()
        every { torrentRepository.findAll() } returns emptyList()
        coEvery { torrentClient.probe(any()) } returns NisabaError.NodeUnreachable("node-1", "timeout").left()

        val service = ReconciliationService(
            nodeRepository, torrentRepository, bandwidthService, stateMachine,
            torrentClientFactory, bootGate, singleNodeDef
        )
        service.reconcile()

        // Should not save any nodes since nothing changed
        verify(exactly = 0) {
            nodeRepository.save(any())
        }
    }

    // --- probeNodesWithRetry tests ---

    @Test
    fun `reconcile marks nodes healthy on successful probe`() = runBlocking {
        // Use single node definition for simpler test
        val singleNodeDef = listOf(nodeDef("node-1"))
        val n1 = node("node-1", healthy = false).copy(label = "Node node-1")
        every { nodeRepository.findById("node-1") } returns Optional.of(n1)
        every { nodeRepository.findAll() } returns listOf(n1)
        every { nodeRepository.findByHealthyTrue() } returns listOf(n1.copy(healthy = true))
        coEvery { torrentClient.probe(any()) } returns NodeHealth("node-1", true, "v4.6.7").right()
        coEvery { torrentClient.listTorrents(any()) } returns emptyList<TorrentStatus>().right()
        every { torrentRepository.findAll() } returns emptyList()

        val service = ReconciliationService(
            nodeRepository, torrentRepository, bandwidthService, stateMachine,
            torrentClientFactory, bootGate, singleNodeDef
        )
        service.reconcile()

        verify { nodeRepository.markHealthy("node-1", any()) }
    }

    @Test
    fun `reconcile marks nodes unhealthy on failed probe`() = runBlocking {
        val n1 = node("node-1")
        every { nodeRepository.findById(any()) } returns Optional.empty()
        every { nodeRepository.findAll() } returns listOf(n1)
        every { nodeRepository.findByHealthyTrue() } returns emptyList()
        coEvery { torrentClient.probe(any()) } returns NisabaError.NodeUnreachable("node-1", "timeout").left()
        every { torrentRepository.findAll() } returns emptyList()

        val service = createService()
        service.reconcile()

        verify(atLeast = 1) { nodeRepository.markUnhealthy("node-1") }
    }

    @Test
    fun `reconcile opens boot gate in degraded mode when no healthy nodes`() = runBlocking {
        every { nodeRepository.findById(any()) } returns Optional.empty()
        every { nodeRepository.findAll() } returns listOf(node("node-1"))
        every { nodeRepository.findByHealthyTrue() } returns emptyList()
        coEvery { torrentClient.probe(any()) } returns NisabaError.NodeUnreachable("node-1", "timeout").left()

        val service = createService()
        service.reconcile()

        verify { bootGate.open() }
    }

    // --- reconcileState tests ---

    @Test
    fun `reconcile fast-forwards completed torrent to DONE`() = runBlocking {
        val n1 = node("node-1")
        val t = torrent(state = TorrentState.DOWNLOADING)
        val completedStatus = torrentStatus(progress = 1.0f)

        every { nodeRepository.findById(any()) } returns Optional.empty()
        every { nodeRepository.findAll() } returns listOf(n1)
        every { nodeRepository.findByHealthyTrue() } returns listOf(n1)
        coEvery { torrentClient.probe(any()) } returns NodeHealth("node-1", true).right()
        coEvery { torrentClient.listTorrents(any()) } returns listOf(completedStatus).right()
        every { torrentRepository.findAll() } returns listOf(t)

        val service = createService()
        service.reconcile()

        verify { stateMachine.transition("abc123", TorrentState.DONE, any(), any()) }
        verify { torrentRepository.updateProgress("abc123", 1.0f, any(), 1000000L, "/data/movie.mkv", 3600L, 0.0f, null) }
    }

    @Test
    fun `reconcile fast-forwards QUEUED torrent through all states to DONE`() = runBlocking {
        val n1 = node("node-1")
        val t = torrent(state = TorrentState.QUEUED)
        val completedStatus = torrentStatus(progress = 1.0f)

        every { nodeRepository.findById(any()) } returns Optional.empty()
        every { nodeRepository.findAll() } returns listOf(n1)
        every { nodeRepository.findByHealthyTrue() } returns listOf(n1)
        coEvery { torrentClient.probe(any()) } returns NodeHealth("node-1", true).right()
        coEvery { torrentClient.listTorrents(any()) } returns listOf(completedStatus).right()
        every { torrentRepository.findAll() } returns listOf(t)

        val service = createService()
        service.reconcile()

        verifyOrder {
            stateMachine.transition("abc123", TorrentState.ASSIGNING, any(), any())
            stateMachine.transition("abc123", TorrentState.DOWNLOADING, any(), any())
            stateMachine.transition("abc123", TorrentState.DONE, any(), any())
        }
    }

    @Test
    fun `reconcile marks DOWNLOADING torrent as STALLED when not found on node`() = runBlocking {
        val n1 = node("node-1")
        val t = torrent(state = TorrentState.DOWNLOADING)

        every { nodeRepository.findById(any()) } returns Optional.empty()
        every { nodeRepository.findAll() } returns listOf(n1)
        every { nodeRepository.findByHealthyTrue() } returns listOf(n1)
        coEvery { torrentClient.probe(any()) } returns NodeHealth("node-1", true).right()
        coEvery { torrentClient.listTorrents(any()) } returns emptyList<TorrentStatus>().right()
        every { torrentRepository.findAll() } returns listOf(t)

        val service = createService()
        service.reconcile()

        verify { stateMachine.transition("abc123", TorrentState.STALLED, any(), any()) }
    }

    @Test
    fun `reconcile marks ASSIGNING torrent as QUEUED when not found on node`() = runBlocking {
        val n1 = node("node-1")
        val t = torrent(state = TorrentState.ASSIGNING)

        every { nodeRepository.findById(any()) } returns Optional.empty()
        every { nodeRepository.findAll() } returns listOf(n1)
        every { nodeRepository.findByHealthyTrue() } returns listOf(n1)
        coEvery { torrentClient.probe(any()) } returns NodeHealth("node-1", true).right()
        coEvery { torrentClient.listTorrents(any()) } returns emptyList<TorrentStatus>().right()
        every { torrentRepository.findAll() } returns listOf(t)

        val service = createService()
        service.reconcile()

        verify { stateMachine.transition("abc123", TorrentState.QUEUED, any(), any()) }
    }

    @Test
    fun `reconcile updates progress for normal sync`() = runBlocking {
        val n1 = node("node-1")
        val t = torrent(state = TorrentState.DOWNLOADING, assignedNodeId = "node-1")
        val liveStatus = torrentStatus(progress = 0.75f)

        every { nodeRepository.findById(any()) } returns Optional.empty()
        every { nodeRepository.findAll() } returns listOf(n1)
        every { nodeRepository.findByHealthyTrue() } returns listOf(n1)
        coEvery { torrentClient.probe(any()) } returns NodeHealth("node-1", true).right()
        coEvery { torrentClient.listTorrents(any()) } returns listOf(liveStatus).right()
        every { torrentRepository.findAll() } returns listOf(t)

        val service = createService()
        service.reconcile()

        verify { torrentRepository.updateProgress("abc123", 0.75f, any(), any(), any(), any(), any()) }
    }

    @Test
    fun `reconcile updates assignedNodeId when torrent moved to different node`() = runBlocking {
        val n1 = node("node-1")
        val n2 = node("node-2")
        val t = torrent(state = TorrentState.DOWNLOADING, assignedNodeId = "node-1")
        val liveStatus = torrentStatus(progress = 0.5f)

        every { nodeRepository.findById(any()) } returns Optional.empty()
        every { nodeRepository.findAll() } returns listOf(n1, n2)
        every { nodeRepository.findByHealthyTrue() } returns listOf(n1, n2)
        coEvery { torrentClient.probe(any()) } returns NodeHealth("node-1", true).right()
        // Torrent only found on node-2
        coEvery { torrentClient.listTorrents(n1) } returns emptyList<TorrentStatus>().right()
        coEvery { torrentClient.listTorrents(n2) } returns listOf(liveStatus).right()
        every { torrentRepository.findAll() } returns listOf(t)

        val service = createService()
        service.reconcile()

        verify {
            torrentRepository.save(match<TorrentEntity> { it.assignedNodeId == "node-2" })
        }
    }

    // --- EMA seeding tests ---

    @Test
    fun `reconcile seeds EMA weights when no recent samples`() = runBlocking {
        val n1 = node("node-1")
        every { nodeRepository.findById(any()) } returns Optional.empty()
        every { nodeRepository.findAll() } returns listOf(n1)
        every { nodeRepository.findByHealthyTrue() } returns listOf(n1)
        coEvery { torrentClient.probe(any()) } returns NodeHealth("node-1", true).right()
        coEvery { torrentClient.listTorrents(any()) } returns emptyList<TorrentStatus>().right()
        every { torrentRepository.findAll() } returns emptyList()
        every { bandwidthService.hasNoRecentSamples() } returns true

        val service = createService()
        service.reconcile()

        verify { nodeRepository.seedAllWeights(0.5f) }
    }

    @Test
    fun `reconcile does not seed EMA weights when recent samples exist`() = runBlocking {
        val n1 = node("node-1")
        every { nodeRepository.findById(any()) } returns Optional.empty()
        every { nodeRepository.findAll() } returns listOf(n1)
        every { nodeRepository.findByHealthyTrue() } returns listOf(n1)
        coEvery { torrentClient.probe(any()) } returns NodeHealth("node-1", true).right()
        coEvery { torrentClient.listTorrents(any()) } returns emptyList<TorrentStatus>().right()
        every { torrentRepository.findAll() } returns emptyList()
        every { bandwidthService.hasNoRecentSamples() } returns false

        val service = createService()
        service.reconcile()

        verify(exactly = 0) { nodeRepository.seedAllWeights(any()) }
    }

    // --- Boot gate tests ---

    @Test
    fun `reconcile opens boot gate after successful reconciliation`() = runBlocking {
        val n1 = node("node-1")
        every { nodeRepository.findById(any()) } returns Optional.empty()
        every { nodeRepository.findAll() } returns listOf(n1)
        every { nodeRepository.findByHealthyTrue() } returns listOf(n1)
        coEvery { torrentClient.probe(any()) } returns NodeHealth("node-1", true).right()
        coEvery { torrentClient.listTorrents(any()) } returns emptyList<TorrentStatus>().right()
        every { torrentRepository.findAll() } returns emptyList()

        val service = createService()
        service.reconcile()

        verify { bootGate.open() }
    }

    // --- Fast-forward state transitions tests ---

    @Test
    fun `fast-forward from STALLED goes through DOWNLOADING to DONE`() = runBlocking {
        val n1 = node("node-1")
        val t = torrent(state = TorrentState.STALLED)
        val completedStatus = torrentStatus(progress = 1.0f)

        every { nodeRepository.findById(any()) } returns Optional.empty()
        every { nodeRepository.findAll() } returns listOf(n1)
        every { nodeRepository.findByHealthyTrue() } returns listOf(n1)
        coEvery { torrentClient.probe(any()) } returns NodeHealth("node-1", true).right()
        coEvery { torrentClient.listTorrents(any()) } returns listOf(completedStatus).right()
        every { torrentRepository.findAll() } returns listOf(t)

        val service = createService()
        service.reconcile()

        verifyOrder {
            stateMachine.transition("abc123", TorrentState.DOWNLOADING, any(), any())
            stateMachine.transition("abc123", TorrentState.DONE, any(), any())
        }
    }

    @Test
    fun `fast-forward from FAILED goes through all states to DONE`() = runBlocking {
        val n1 = node("node-1")
        val t = torrent(state = TorrentState.FAILED)
        val completedStatus = torrentStatus(progress = 1.0f)

        every { nodeRepository.findById(any()) } returns Optional.empty()
        every { nodeRepository.findAll() } returns listOf(n1)
        every { nodeRepository.findByHealthyTrue() } returns listOf(n1)
        coEvery { torrentClient.probe(any()) } returns NodeHealth("node-1", true).right()
        coEvery { torrentClient.listTorrents(any()) } returns listOf(completedStatus).right()
        every { torrentRepository.findAll() } returns listOf(t)

        val service = createService()
        service.reconcile()

        verifyOrder {
            stateMachine.transition("abc123", TorrentState.QUEUED, any(), any())
            stateMachine.transition("abc123", TorrentState.ASSIGNING, any(), any())
            stateMachine.transition("abc123", TorrentState.DOWNLOADING, any(), any())
            stateMachine.transition("abc123", TorrentState.DONE, any(), any())
        }
    }

    @Test
    fun `reconcile handles fetch torrents failure gracefully`() = runBlocking {
        val n1 = node("node-1")
        every { nodeRepository.findById(any()) } returns Optional.empty()
        every { nodeRepository.findAll() } returns listOf(n1)
        every { nodeRepository.findByHealthyTrue() } returns listOf(n1)
        coEvery { torrentClient.probe(any()) } returns NodeHealth("node-1", true).right()
        coEvery { torrentClient.listTorrents(any()) } returns NisabaError.NodeUnreachable("node-1", "error").left()
        every { torrentRepository.findAll() } returns emptyList()

        val service = createService()
        service.reconcile()

        // Should still complete and open boot gate
        verify { bootGate.open() }
    }
}
