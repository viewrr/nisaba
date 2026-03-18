package com.nisaba.api.controller

import com.nisaba.config.NisabaProperties
import com.nisaba.persistence.entity.TorrentEntity
import com.nisaba.persistence.entity.TorrentState
import com.nisaba.persistence.repository.TorrentRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SyncControllerTest {

    private lateinit var torrentRepository: TorrentRepository
    private lateinit var properties: NisabaProperties
    private lateinit var controller: SyncController

    private fun torrent(
        infohash: String = "abc123",
        state: TorrentState = TorrentState.DOWNLOADING,
        category: String? = "movies"
    ) = TorrentEntity(
        infohash = infohash,
        magnetUri = "magnet:?xt=urn:btih:$infohash",
        savePath = "/data/media",
        state = state,
        category = category,
        totalSize = 1000000L,
        progressPct = 0.5f,
        isNewEntity = false
    )

    @BeforeEach
    fun setUp() {
        torrentRepository = mockk()
        properties = NisabaProperties(
            auth = NisabaProperties.AuthProperties("user", "pass"),
            categories = mapOf(
                "movies" to "/data/movies",
                "default" to "/data/media"
            )
        )
        controller = SyncController(torrentRepository, properties)
    }

    @Test
    fun `maindata returns full update when rid is 0`() {
        val torrents = listOf(torrent("hash1"), torrent("hash2"))
        every { torrentRepository.findAll() } returns torrents

        val result = controller.maindata(rid = 0)

        result.fullUpdate shouldBe true
        result.rid shouldNotBe 0L
        result.torrents.size shouldBe 2
        result.torrents["hash1"] shouldNotBe null
        result.torrents["hash2"] shouldNotBe null
    }

    @Test
    fun `maindata returns full update when rid is unknown`() {
        every { torrentRepository.findAll() } returns emptyList()

        val result = controller.maindata(rid = 999999L)

        result.fullUpdate shouldBe true
    }

    @Test
    fun `maindata returns incremental update when rid is known`() {
        every { torrentRepository.findAll() } returns emptyList()

        // First call to establish a rid
        val firstResult = controller.maindata(rid = 0)
        val knownRid = firstResult.rid

        // Second call with known rid
        val result = controller.maindata(rid = knownRid)

        result.fullUpdate shouldBe false
    }

    @Test
    fun `maindata increments rid on each call`() {
        every { torrentRepository.findAll() } returns emptyList()

        val result1 = controller.maindata(rid = 0)
        val result2 = controller.maindata(rid = 0)
        val result3 = controller.maindata(rid = 0)

        result2.rid shouldBe result1.rid + 1
        result3.rid shouldBe result2.rid + 1
    }

    @Test
    fun `maindata returns all categories`() {
        every { torrentRepository.findAll() } returns emptyList()

        val result = controller.maindata(rid = 0)

        result.categories.size shouldBe 2
        result.categories["movies"]?.savePath shouldBe "/data/movies"
        result.categories["default"]?.savePath shouldBe "/data/media"
    }

    @Test
    fun `maindata maps torrent entity to QBitTorrentInfo correctly`() {
        val t = torrent(
            infohash = "testhash",
            state = TorrentState.DOWNLOADING,
            category = "movies"
        )
        every { torrentRepository.findAll() } returns listOf(t)

        val result = controller.maindata(rid = 0)

        val info = result.torrents["testhash"]!!
        info.hash shouldBe "testhash"
        info.state shouldBe "downloading"
        info.category shouldBe "movies"
        info.progress shouldBe 0.5f
        info.size shouldBe 1000000L
    }

    @Test
    fun `maindata calculates amountLeft correctly`() {
        val t = torrent().copy(progressPct = 0.75f, totalSize = 1000L)
        every { torrentRepository.findAll() } returns listOf(t)

        val result = controller.maindata(rid = 0)

        val info = result.torrents["abc123"]!!
        info.amountLeft shouldBe 250L  // 25% of 1000
        info.completed shouldBe 750L   // 75% of 1000
    }

    @Test
    fun `maindata sets amountLeft to 0 when complete`() {
        val t = torrent().copy(progressPct = 1.0f, totalSize = 1000L)
        every { torrentRepository.findAll() } returns listOf(t)

        val result = controller.maindata(rid = 0)

        val info = result.torrents["abc123"]!!
        info.amountLeft shouldBe 0L
    }
}
