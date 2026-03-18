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

class ReassignmentServiceTest {

    private lateinit var torrentRepository: TorrentRepository
    private lateinit var nodeRepository: NodeRepository
    private lateinit var stateMachine: StateMachine
    private lateinit var routerService: RouterService
    private lateinit var torrentClientFactory: TorrentClientFactory
    private lateinit var torrentClient: TorrentClient
    private lateinit var reassignmentService: ReassignmentService

    private fun torrent(
        infohash: String = "abc123",
        state: TorrentState = TorrentState.DOWNLOADING,
        assignedNodeId: String? = "old-node"
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
        stateMachine = mockk()
        routerService = mockk()
        torrentClientFactory = mockk()
        torrentClient = mockk()

        reassignmentService = ReassignmentService(
            torrentRepository,
            nodeRepository,
            stateMachine,
            routerService,
            torrentClientFactory
        )

        every { torrentClientFactory.clientFor(any()) } returns torrentClient
    }

    @Test
    fun `reassign returns TorrentNotFound when torrent does not exist`() = runBlocking {
        every { torrentRepository.findByInfohash("unknown") } returns null

        val result = reassignmentService.reassign("unknown")

        result.isLeft() shouldBe true
        result.leftOrNull().shouldBeInstanceOf<NisabaError.TorrentNotFound>()
    }

    @Test
    fun `reassign transitions through states and assigns to new node on success`() = runBlocking {
        val oldNode = node("old-node")
        val newNode = node("new-node")
        val t = torrent()

        every { torrentRepository.findByInfohash("abc123") } returns t
        every { nodeRepository.findById("old-node") } returns Optional.of(oldNode)
        every { stateMachine.transition(any(), any(), any(), any()) } returns t.right()
        every { routerService.selectNode(excludeNodeId = "old-node") } returns newNode.right()
        coEvery { torrentClient.pauseTorrent(oldNode, "abc123") } returns Unit.right()
        coEvery {
            torrentClient.addTorrent(newNode, t.magnetUri, t.savePath, t.category, false)
        } returns AddResult("abc123", true).right()
        every { torrentRepository.save(any<TorrentEntity>()) } answers { firstArg() }

        val result = reassignmentService.reassign("abc123")

        result.isRight() shouldBe true

        // Verify state transitions
        verify { stateMachine.transition("abc123", TorrentState.REASSIGNING, any(), any()) }
        verify { stateMachine.transition("abc123", TorrentState.ASSIGNING, nodeId = "new-node") }
        verify { stateMachine.transition("abc123", TorrentState.DOWNLOADING, nodeId = "new-node", any()) }

        // Verify torrent was saved with new node
        verify { torrentRepository.save(match<TorrentEntity> { it.assignedNodeId == "new-node" }) }
    }

    @Test
    fun `reassign fails when no healthy nodes available`() = runBlocking {
        val oldNode = node("old-node")
        val t = torrent()

        every { torrentRepository.findByInfohash("abc123") } returns t
        every { nodeRepository.findById("old-node") } returns Optional.of(oldNode)
        every { stateMachine.transition(any(), any(), any(), any()) } returns t.right()
        every { routerService.selectNode(excludeNodeId = "old-node") } returns
            NisabaError.NoHealthyNodes("No nodes").left()
        coEvery { torrentClient.pauseTorrent(oldNode, "abc123") } returns Unit.right()

        val result = reassignmentService.reassign("abc123")

        result.isLeft() shouldBe true
        result.leftOrNull().shouldBeInstanceOf<NisabaError.NoHealthyNodes>()

        // Verify transition to FAILED
        verify { stateMachine.transition("abc123", TorrentState.FAILED, any(), any()) }
    }

    @Test
    fun `reassign fails when new node rejects torrent`() = runBlocking {
        val oldNode = node("old-node")
        val newNode = node("new-node")
        val t = torrent()

        every { torrentRepository.findByInfohash("abc123") } returns t
        every { nodeRepository.findById("old-node") } returns Optional.of(oldNode)
        every { stateMachine.transition(any(), any(), any(), any()) } returns t.right()
        every { routerService.selectNode(excludeNodeId = "old-node") } returns newNode.right()
        coEvery { torrentClient.pauseTorrent(oldNode, "abc123") } returns Unit.right()
        coEvery {
            torrentClient.addTorrent(newNode, t.magnetUri, t.savePath, t.category, false)
        } returns AddResult("abc123", false).right()

        val result = reassignmentService.reassign("abc123")

        result.isLeft() shouldBe true
        result.leftOrNull().shouldBeInstanceOf<NisabaError.ReassignmentFailed>()

        // Verify transition to FAILED
        verify { stateMachine.transition("abc123", TorrentState.FAILED, any(), any()) }
    }

    @Test
    fun `reassign continues even if pause on old node fails`() = runBlocking {
        val oldNode = node("old-node")
        val newNode = node("new-node")
        val t = torrent()

        every { torrentRepository.findByInfohash("abc123") } returns t
        every { nodeRepository.findById("old-node") } returns Optional.of(oldNode)
        every { stateMachine.transition(any(), any(), any(), any()) } returns t.right()
        every { routerService.selectNode(excludeNodeId = "old-node") } returns newNode.right()
        coEvery { torrentClient.pauseTorrent(oldNode, "abc123") } returns
            NisabaError.NodeUnreachable("old-node", "timeout").left()
        coEvery {
            torrentClient.addTorrent(newNode, t.magnetUri, t.savePath, t.category, false)
        } returns AddResult("abc123", true).right()
        every { torrentRepository.save(any<TorrentEntity>()) } answers { firstArg() }

        val result = reassignmentService.reassign("abc123")

        result.isRight() shouldBe true
    }

    @Test
    fun `reassign works when torrent has no previous node assignment`() = runBlocking {
        val newNode = node("new-node")
        val t = torrent(assignedNodeId = null)

        every { torrentRepository.findByInfohash("abc123") } returns t
        every { stateMachine.transition(any(), any(), any(), any()) } returns t.right()
        every { routerService.selectNode(excludeNodeId = null) } returns newNode.right()
        coEvery {
            torrentClient.addTorrent(newNode, t.magnetUri, t.savePath, t.category, false)
        } returns AddResult("abc123", true).right()
        every { torrentRepository.save(any<TorrentEntity>()) } answers { firstArg() }

        val result = reassignmentService.reassign("abc123")

        result.isRight() shouldBe true
        coVerify(exactly = 0) { torrentClient.pauseTorrent(any(), any()) }
    }
}
