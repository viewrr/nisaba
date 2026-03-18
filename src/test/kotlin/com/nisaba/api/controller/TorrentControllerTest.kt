package com.nisaba.api.controller

import arrow.core.left
import arrow.core.right
import com.nisaba.client.TorrentClient
import com.nisaba.client.TorrentClientFactory
import com.nisaba.client.dto.TorrentFile
import com.nisaba.client.dto.TorrentProperties
import com.nisaba.config.NisabaProperties
import com.nisaba.error.NisabaError
import com.nisaba.persistence.entity.NodeEntity
import com.nisaba.persistence.entity.TorrentEntity
import com.nisaba.persistence.entity.TorrentState
import com.nisaba.persistence.repository.NodeRepository
import com.nisaba.persistence.repository.TorrentRepository
import com.nisaba.service.RegistryService
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.*

class TorrentControllerTest {

    private lateinit var torrentRepository: TorrentRepository
    private lateinit var nodeRepository: NodeRepository
    private lateinit var registryService: RegistryService
    private lateinit var torrentClientFactory: TorrentClientFactory
    private lateinit var torrentClient: TorrentClient
    private lateinit var properties: NisabaProperties
    private lateinit var controller: TorrentController

    private fun torrent(
        infohash: String = "abc123",
        state: TorrentState = TorrentState.DOWNLOADING,
        assignedNodeId: String? = "node-1",
        category: String? = "movies"
    ) = TorrentEntity(
        infohash = infohash,
        magnetUri = "magnet:?xt=urn:btih:$infohash",
        savePath = "/data/media",
        state = state,
        assignedNodeId = assignedNodeId,
        category = category,
        totalSize = 1000000L,
        progressPct = 0.5f,
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
        registryService = mockk()
        torrentClientFactory = mockk()
        torrentClient = mockk()
        properties = NisabaProperties(
            auth = NisabaProperties.AuthProperties("user", "pass"),
            categories = mapOf("movies" to "/data/movies", "default" to "/data/media")
        )

        controller = TorrentController(
            torrentRepository,
            nodeRepository,
            registryService,
            torrentClientFactory,
            properties
        )

        every { torrentClientFactory.clientFor(any()) } returns torrentClient
    }

    @Test
    fun `info returns all torrents when no filter`() {
        val torrents = listOf(torrent("hash1"), torrent("hash2"))
        every { torrentRepository.findAll() } returns torrents

        val result = controller.info(category = null, filter = null, hashes = null)

        result.size shouldBe 2
        result[0].hash shouldBe "hash1"
        result[1].hash shouldBe "hash2"
    }

    @Test
    fun `info returns torrents filtered by category`() {
        val torrents = listOf(torrent("hash1", category = "movies"))
        every { torrentRepository.findByCategory("movies") } returns torrents

        val result = controller.info(category = "movies", filter = null, hashes = null)

        result.size shouldBe 1
        result[0].category shouldBe "movies"
    }

    @Test
    fun `info returns torrents filtered by hashes`() {
        val t1 = torrent("hash1")
        every { torrentRepository.findByInfohash("hash1") } returns t1
        every { torrentRepository.findByInfohash("hash2") } returns null

        val result = controller.info(category = null, filter = null, hashes = "hash1|hash2")

        result.size shouldBe 1
        result[0].hash shouldBe "hash1"
    }

    @Test
    fun `add returns bad request when urls is null`() {
        val result = controller.add(urls = null, savepath = null, category = null, paused = null)

        result.statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `add returns bad request when urls is blank`() {
        val result = controller.add(urls = "  ", savepath = null, category = null, paused = null)

        result.statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `add successfully adds torrent`() {
        val magnetUri = "magnet:?xt=urn:btih:abc123def456789012345678901234567890abcd"
        coEvery {
            registryService.addTorrent(any(), any(), any(), any(), any())
        } returns torrent().right()

        val result = controller.add(urls = magnetUri, savepath = "/data", category = "movies", paused = null)

        result.statusCode shouldBe HttpStatus.OK
        result.body shouldBe "Ok."
    }

    @Test
    fun `add returns conflict when torrent already exists`() {
        val magnetUri = "magnet:?xt=urn:btih:abc123def456789012345678901234567890abcd"
        coEvery {
            registryService.addTorrent(any(), any(), any(), any(), any())
        } returns NisabaError.AlreadyExists("abc123").left()

        val result = controller.add(urls = magnetUri, savepath = null, category = null, paused = null)

        result.statusCode shouldBe HttpStatus.CONFLICT
    }

    @Test
    fun `add uses default save path when not provided`() {
        val magnetUri = "magnet:?xt=urn:btih:abc123def456789012345678901234567890abcd"
        val savedPath = slot<String>()
        coEvery {
            registryService.addTorrent(any(), any(), capture(savedPath), any(), any())
        } returns torrent().right()

        controller.add(urls = magnetUri, savepath = null, category = null, paused = null)

        savedPath.captured shouldBe "/data/media"
    }

    @Test
    fun `add uses category save path when provided`() {
        val magnetUri = "magnet:?xt=urn:btih:abc123def456789012345678901234567890abcd"
        val savedPath = slot<String>()
        coEvery {
            registryService.addTorrent(any(), any(), capture(savedPath), any(), any())
        } returns torrent().right()

        controller.add(urls = magnetUri, savepath = null, category = "movies", paused = null)

        savedPath.captured shouldBe "/data/movies"
    }

    @Test
    fun `delete removes multiple torrents`() {
        coEvery { registryService.removeTorrent("hash1", false) } returns Unit.right()
        coEvery { registryService.removeTorrent("hash2", false) } returns Unit.right()

        val result = controller.delete(hashes = "hash1|hash2", deleteFiles = null)

        result.statusCode shouldBe HttpStatus.OK
        coVerify { registryService.removeTorrent("hash1", false) }
        coVerify { registryService.removeTorrent("hash2", false) }
    }

    @Test
    fun `delete with deleteFiles true passes flag correctly`() {
        coEvery { registryService.removeTorrent("hash1", true) } returns Unit.right()

        val result = controller.delete(hashes = "hash1", deleteFiles = "true")

        result.statusCode shouldBe HttpStatus.OK
        coVerify { registryService.removeTorrent("hash1", true) }
    }

    @Test
    fun `properties returns 404 when torrent not found`() {
        every { torrentRepository.findByInfohash("unknown") } returns null

        val result = controller.properties(hash = "unknown")

        result.statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `properties returns torrent properties from node`() {
        val t = torrent(assignedNodeId = "node-1")
        val n = node("node-1")
        every { torrentRepository.findByInfohash("abc123") } returns t
        every { nodeRepository.findById("node-1") } returns Optional.of(n)
        coEvery { torrentClient.getTorrentProperties(n, "abc123") } returns
            TorrentProperties("/data/media", 3600L).right()

        val result = controller.properties(hash = "abc123")

        result.statusCode shouldBe HttpStatus.OK
    }

    @Test
    fun `properties returns fallback when no assigned node`() {
        val t = torrent(assignedNodeId = null)
        every { torrentRepository.findByInfohash("abc123") } returns t

        val result = controller.properties(hash = "abc123")

        result.statusCode shouldBe HttpStatus.OK
        @Suppress("UNCHECKED_CAST")
        val body = result.body as Map<String, Any>
        body["save_path"] shouldBe "/data/media"
    }

    @Test
    fun `files returns 404 when torrent not found`() {
        every { torrentRepository.findByInfohash("unknown") } returns null

        val result = controller.files(hash = "unknown")

        result.statusCode shouldBe HttpStatus.NOT_FOUND
    }

    @Test
    fun `files returns torrent files from node`() {
        val t = torrent(assignedNodeId = "node-1")
        val n = node("node-1")
        every { torrentRepository.findByInfohash("abc123") } returns t
        every { nodeRepository.findById("node-1") } returns Optional.of(n)
        coEvery { torrentClient.getTorrentFiles(n, "abc123") } returns
            listOf(TorrentFile("movie.mkv", 1000000L)).right()

        val result = controller.files(hash = "abc123")

        result.statusCode shouldBe HttpStatus.OK
    }

    @Test
    fun `stub endpoints return ok`() {
        controller.setShareLimits().statusCode shouldBe HttpStatus.OK
        controller.topPrio().statusCode shouldBe HttpStatus.OK
        controller.setForceStart().statusCode shouldBe HttpStatus.OK
    }
}
