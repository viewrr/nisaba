package com.nisaba.service

import com.nisaba.config.NisabaProperties
import com.nisaba.persistence.entity.BandwidthSampleEntity
import com.nisaba.persistence.repository.BandwidthSampleRepository
import com.nisaba.persistence.repository.NodeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class BandwidthService(
    private val nodeRepository: NodeRepository,
    private val bandwidthSampleRepository: BandwidthSampleRepository,
    private val properties: NisabaProperties
) {
    companion object {
        private val logger = LoggerFactory.getLogger(BandwidthService::class.java)

        /** 100 MB/s = weight 1.0 */
        const val NORMALIZATION_BPS: Long = 100_000_000L
    }

    /**
     * Records a bandwidth sample for the given node and updates its EMA weight.
     *
     * EMA formula: alpha * normalizedSpeed + (1 - alpha) * previousWeight
     * Normalization: 100 MB/s maps to weight 1.0.
     * Result is clamped to [0.0, 1.0].
     */
    fun recordSampleAndUpdateEma(nodeId: String, speedBps: Long) {
        bandwidthSampleRepository.save(
            BandwidthSampleEntity(nodeId = nodeId, speedBps = speedBps)
        )

        val node = nodeRepository.findById(nodeId).orElse(null) ?: run {
            logger.warn("Cannot update EMA: node $nodeId not found")
            return
        }

        val alpha = properties.ema.alpha
        val normalizedSpeed = speedBps.toFloat() / NORMALIZATION_BPS
        val previousWeight = node.emaWeight
        val newWeight = (alpha * normalizedSpeed + (1 - alpha) * previousWeight)
            .coerceIn(0f, 1f)

        nodeRepository.updateEmaWeight(nodeId, newWeight, speedBps)
        logger.debug(
            "Node $nodeId EMA: $previousWeight -> $newWeight " +
                "(speed=${speedBps}bps, normalized=$normalizedSpeed, alpha=$alpha)"
        )
    }

    /**
     * Returns true if there are no bandwidth samples in the last 48 hours.
     */
    fun hasNoRecentSamples(): Boolean {
        return bandwidthSampleRepository.countRecentSamples() == 0L
    }
}
