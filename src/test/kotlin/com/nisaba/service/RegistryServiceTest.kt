package com.nisaba.service

import arrow.core.left
import arrow.core.right
import com.nisaba.client.TorrentClient
import com.nisaba.client.TorrentClientFactory
import com.nisaba.client.dto.AddResult
import com.nisaba.error.NisabaError
import com.nisaba.persistence.entity.NodeEntity
import com.nisaba.persistence.entity.TorrentEntity
import com.nisaba.persistence.entity.TorrentState
import com.nisaba.persistence.repository.NodeRepository
import com.nisaba.persistence.repository.TorrentRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class RegistryServiceTest {

    private lateinit var torrentRepository: TorrentRepository
    private lateinit var nodeRepository: NodeRepository
    private lateinit var routerService: RouterService
    private lateinit var stateMachine: StateMachine
    private lateinit var torrentClientFactory: TorrentClientFactory
    private lateinit var torrentClient: TorrentClient
    private lateinit var registryService: RegistryService

    private fun torrent(
        infohash: String = "abc123",
        state: TorrentState = TorrentState.QUEUED,
        assignedNodeId: String? = null
    ) = TorrentEntity(
        infohash = infohash,
        magnetUri = "magnet:?xt=urn:btih:$infohash",
        savePath = "/data/media",
        state = state,
        assignedNodeId = assignedNodeId,
        isNewEntity = false
    )

    private fun node(id: String) = NodeEntity(
        nodeId = id,
        baseUrl = "http://$id:8080",
        healthy = true,
        emaWeight = 0.5f
    )

    @BeforeEach
    fun setUp() {
        torrentRepository = mockk()
        nodeRepository = mockk()
        routerService = mockk()
        stateMachine = mockk()
        torrentClientFactory = mockk()
        torrentClient = mockk()

        registryService = RegistryService(
            torrentRepository,
            nodeRepository,
            routerService,
            stateMachine,
            torrentClientFactory
        )

        every { torrentClientFactory.clientFor(any()) } returns torrentClient
    }

    @Test
    fun `addTorrent returns AlreadyExists when torrent already in registry`() = runBlocking {
        every { torrentRepository.findByInfohash("abc123") } returns torrent()

        val result = registryService.addTorrent(
            infohash = "abc123",
            magnetUri = "magnet:?xt=urn:btih:abc123",
            savePath = "/data",
            category = null,
            paused = false
        )

        result.isLeft() shouldBe true
        result.leftOrNull().shouldBeInstanceOf<NisabaError.AlreadyExists>()
    }

    @Test
    fun `addTorrent creates torrent and assigns to node on success`() = runBlocking {
        val selectedNode = node("node-1")
        val savedTorrent = torrent(state = TorrentState.DOWNLOADING, assignedNodeId = "node-1")

        every { torrentRepository.findByInfohash("abc123") } returns null
        every { torrentRepository.save(any<TorrentEntity>()) } answers { firstArg() }
        every { routerService.selectNode() } returns selectedNode.right()
        every { stateMachine.transition(any(), any(), any(), any()) } returns savedTorrent.right()
        coEvery {
            torrentClient.addTorrent(selectedNode, any(), any(), any(), false)
        } returns AddResult("abc123", true).right()

        val result = registryService.addTorrent(
            infohash = "abc123",
            magnetUri = "magnet:?xt=urn:btih:abc123",
            savePath = "/data",
            category = "movies",
            paused = false
        )

        result.isRight() shouldBe true

        // Verify state machine transitions
        verify { stateMachine.transition("abc123", TorrentState.ASSIGNING, nodeId = "node-1") }
        verify { stateMachine.transition("abc123", TorrentState.DOWNLOADING, nodeId = "node-1") }
    }

    @Test
    fun `addTorrent with paused flag transitions to PAUSED state`() = runBlocking {
        val savedTorrent = torrent(state = TorrentState.PAUSED)

        every { torrentRepository.findByInfohash("abc123") } returns null
        every { torrentRepository.save(any<TorrentEntity>()) } answers { firstArg() }
        every { stateMachine.transition(any(), any(), any(), any()) } returns savedTorrent.right()

        val result = registryService.addTorrent(
            infohash = "abc123",
            magnetUri = "magnet:?xt=urn:btih:abc123",
            savePath = "/data",
            category = null,
            paused = true
        )

        result.isRight() shouldBe true

        // Verify state transitions for paused torrent
        verify { stateMachine.transition("abc123", TorrentState.ASSIGNING) }
        verify { stateMachine.transition("abc123", TorrentState.DOWNLOADING) }
        verify { stateMachine.transition("abc123", TorrentState.PAUSED) }
    }

    @Test
    fun `addTorrent fails when no healthy nodes available`() = runBlocking {
        every { torrentRepository.findByInfohash("abc123") } returns null
        every { torrentRepository.save(any<TorrentEntity>()) } answers { firstArg() }
        every { routerService.selectNode() } returns NisabaError.NoHealthyNodes("No nodes").left()

        val result = registryService.addTorrent(
            infohash = "abc123",
            magnetUri = "magnet:?xt=urn:btih:abc123",
            savePath = "/data",
            category = null,
            paused = false
        )

        result.isLeft() shouldBe true
        result.leftOrNull().shouldBeInstanceOf<NisabaError.NoHealthyNodes>()
    }

    @Test
    fun `addTorrent transitions back to QUEUED when node rejects torrent`() = runBlocking {
        val selectedNode = node("node-1")
        val savedTorrent = torrent(state = TorrentState.QUEUED)

        every { torrentRepository.findByInfohash("abc123") } returns null
        every { torrentRepository.save(any<TorrentEntity>()) } answers { firstArg() }
        every { routerService.selectNode() } returns selectedNode.right()
        every { stateMachine.transition(any(), any(), any(), any()) } returns savedTorrent.right()
        coEvery {
            torrentClient.addTorrent(selectedNode, any(), any(), any(), false)
        } returns AddResult("abc123", false).right()

        val result = registryService.addTorrent(
            infohash = "abc123",
            magnetUri = "magnet:?xt=urn:btih:abc123",
            savePath = "/data",
            category = null,
            paused = false
        )

        result.isLeft() shouldBe true
        result.leftOrNull().shouldBeInstanceOf<NisabaError.NodeRejected>()

        // Verify back to QUEUED
        verify { stateMachine.transition("abc123", TorrentState.QUEUED, any(), any()) }
    }

    @Test
    fun `removeTorrent returns TorrentNotFound when torrent does not exist`() = runBlocking {
        every { torrentRepository.findByInfohash("unknown") } returns null

        val result = registryService.removeTorrent("unknown", deleteFiles = false)

        result.isLeft() shouldBe true
        result.leftOrNull().shouldBeInstanceOf<NisabaError.TorrentNotFound>()
    }

    @Test
    fun `removeTorrent removes from node and deletes from registry`() = runBlocking {
        val assignedNode = node("node-1")
        val t = torrent(assignedNodeId = "node-1")

        every { torrentRepository.findByInfohash("abc123") } returns t
        every { nodeRepository.findById("node-1") } returns Optional.of(assignedNode)
        coEvery { torrentClient.removeTorrent(assignedNode, "abc123", true) } returns Unit.right()
        every { torrentRepository.deleteById("abc123") } just runs

        val result = registryService.removeTorrent("abc123", deleteFiles = true)

        result.isRight() shouldBe true
        verify { torrentRepository.deleteById("abc123") }
        coVerify { torrentClient.removeTorrent(assignedNode, "abc123", true) }
    }

    @Test
    fun `removeTorrent continues even if node removal fails`() = runBlocking {
        val assignedNode = node("node-1")
        val t = torrent(assignedNodeId = "node-1")

        every { torrentRepository.findByInfohash("abc123") } returns t
        every { nodeRepository.findById("node-1") } returns Optional.of(assignedNode)
        coEvery { torrentClient.removeTorrent(assignedNode, "abc123", false) } returns
            NisabaError.NodeUnreachable("node-1", "timeout").left()
        every { torrentRepository.deleteById("abc123") } just runs

        val result = registryService.removeTorrent("abc123", deleteFiles = false)

        result.isRight() shouldBe true
        verify { torrentRepository.deleteById("abc123") }
    }

    @Test
    fun `removeTorrent works when torrent has no assigned node`() = runBlocking {
        val t = torrent(assignedNodeId = null)

        every { torrentRepository.findByInfohash("abc123") } returns t
        every { torrentRepository.deleteById("abc123") } just runs

        val result = registryService.removeTorrent("abc123", deleteFiles = false)

        result.isRight() shouldBe true
        verify { torrentRepository.deleteById("abc123") }
        verify(exactly = 0) { nodeRepository.findById(any()) }
    }
}
