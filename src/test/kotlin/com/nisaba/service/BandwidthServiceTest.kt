package com.nisaba.service

import com.nisaba.config.NisabaProperties
import com.nisaba.persistence.entity.BandwidthSampleEntity
import com.nisaba.persistence.entity.NodeEntity
import com.nisaba.persistence.repository.BandwidthSampleRepository
import com.nisaba.persistence.repository.NodeRepository
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.junit.jupiter.api.Test
import java.util.*

class BandwidthServiceTest {

    private val nodeRepository = mockk<NodeRepository>(relaxed = true)
    private val bandwidthSampleRepository = mockk<BandwidthSampleRepository> {
        every { save(any<BandwidthSampleEntity>()) } answers { firstArg() }
        every { countRecentSamples() } returns 0L
    }
    private val properties = NisabaProperties(
        auth = NisabaProperties.AuthProperties("admin", "pass"),
        ema = NisabaProperties.EmaProperties(alpha = 0.3f, coldStartWeight = 0.5f)
    )
    private val bandwidthService = BandwidthService(nodeRepository, bandwidthSampleRepository, properties)

    private fun node(id: String, emaWeight: Float = 0.5f) = NodeEntity(
        nodeId = id,
        baseUrl = "http://$id:8080",
        emaWeight = emaWeight
    )

    @Test
    fun `recordSampleAndUpdateEma saves sample and updates weight`() {
        val nodeId = "node-a"
        every { nodeRepository.findById(nodeId) } returns Optional.of(node(nodeId, 0.5f))

        // 50 MB/s -> normalized = 0.5
        // newWeight = 0.3 * 0.5 + 0.7 * 0.5 = 0.15 + 0.35 = 0.5
        bandwidthService.recordSampleAndUpdateEma(nodeId, 50_000_000L)

        verify { bandwidthSampleRepository.save(match<BandwidthSampleEntity> { it.nodeId == nodeId && it.speedBps == 50_000_000L }) }
        verify { nodeRepository.updateEmaWeight(nodeId, 0.5f, 50_000_000L) }
    }

    @Test
    fun `EMA increases when speed is high`() {
        val nodeId = "node-a"
        every { nodeRepository.findById(nodeId) } returns Optional.of(node(nodeId, 0.5f))

        // 100 MB/s -> normalized = 1.0
        // newWeight = 0.3 * 1.0 + 0.7 * 0.5 = 0.3 + 0.35 = 0.65
        bandwidthService.recordSampleAndUpdateEma(nodeId, 100_000_000L)

        verify { nodeRepository.updateEmaWeight(nodeId, 0.65f, 100_000_000L) }
    }

    @Test
    fun `EMA decreases when speed is zero`() {
        val nodeId = "node-a"
        every { nodeRepository.findById(nodeId) } returns Optional.of(node(nodeId, 0.5f))

        // 0 MB/s -> normalized = 0.0
        // newWeight = 0.3 * 0.0 + 0.7 * 0.5 = 0.0 + 0.35 = 0.35
        bandwidthService.recordSampleAndUpdateEma(nodeId, 0L)

        verify { nodeRepository.updateEmaWeight(nodeId, 0.35f, 0L) }
    }

    @Test
    fun `EMA is clamped to maximum 1`() {
        val nodeId = "node-a"
        every { nodeRepository.findById(nodeId) } returns Optional.of(node(nodeId, 0.9f))

        // 500 MB/s -> normalized = 5.0
        // newWeight = 0.3 * 5.0 + 0.7 * 0.9 = 1.5 + 0.63 = 2.13 -> clamped to 1.0
        bandwidthService.recordSampleAndUpdateEma(nodeId, 500_000_000L)

        verify { nodeRepository.updateEmaWeight(nodeId, 1.0f, 500_000_000L) }
    }

    @Test
    fun `hasNoRecentSamples returns true when count is zero`() {
        every { bandwidthSampleRepository.countRecentSamples() } returns 0L

        bandwidthService.hasNoRecentSamples() shouldBe true
    }

    @Test
    fun `hasNoRecentSamples returns false when samples exist`() {
        every { bandwidthSampleRepository.countRecentSamples() } returns 5L

        bandwidthService.hasNoRecentSamples() shouldBe false
    }
}
