package com.nisaba.api.controller

import com.nisaba.api.dto.QBitCategory
import com.nisaba.api.dto.QBitSyncMaindata
import com.nisaba.api.dto.QBitTorrentInfo
import com.nisaba.api.mapper.StateMapper
import com.nisaba.config.NisabaProperties
import com.nisaba.persistence.entity.TorrentEntity
import com.nisaba.persistence.repository.TorrentRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@RestController
@RequestMapping("/api/v2/sync")
class SyncController(
    private val torrentRepository: TorrentRepository,
    private val properties: NisabaProperties
) {
    private val ridCounter = AtomicLong(0)
    private val ridTimestamps = ConcurrentHashMap<Long, Instant>()

    @GetMapping("/maindata")
    fun maindata(
        @RequestParam(required = false, defaultValue = "0") rid: Long
    ): QBitSyncMaindata {
        val currentRid = ridCounter.incrementAndGet()
        ridTimestamps[currentRid] = Instant.now()

        // Clean up old rid timestamps (keep last 100)
        if (ridTimestamps.size > 100) {
            val keysToRemove = ridTimestamps.keys.sorted().take(ridTimestamps.size - 100)
            keysToRemove.forEach { ridTimestamps.remove(it) }
        }

        val isFullUpdate = rid == 0L || !ridTimestamps.containsKey(rid)

        val allTorrents = torrentRepository.findAll().toList()
        val torrentMap = allTorrents.associate { it.infohash to it.toQBitInfo() }

        val categories = properties.categories.map { (name, path) ->
            name to QBitCategory(name = name, savePath = path)
        }.toMap()

        return QBitSyncMaindata(
            rid = currentRid,
            fullUpdate = isFullUpdate,
            torrents = torrentMap,
            categories = categories
        )
    }

    private fun TorrentEntity.toQBitInfo(): QBitTorrentInfo {
        val size = totalSize ?: 0L
        val amountLeft = if (progressPct >= 1.0f) 0L else ((1.0f - progressPct) * size).toLong()

        return QBitTorrentInfo(
            hash = infohash,
            name = name ?: infohash,
            size = size,
            progress = progressPct,
            state = StateMapper.toQBitState(state),
            savePath = savePath,
            contentPath = contentPath,
            category = category,
            eta = eta ?: 8640000,
            ratio = ratio ?: 0f,
            seedingTime = seedingTime ?: 0,
            addedOn = createdAt.epochSecond,
            amountLeft = amountLeft,
            completed = (progressPct * size).toLong(),
            downloaded = (progressPct * size).toLong(),
            magnetUri = magnetUri
        )
    }
}
