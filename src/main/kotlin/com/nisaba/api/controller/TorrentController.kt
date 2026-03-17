package com.nisaba.api.controller

import com.nisaba.api.dto.QBitTorrentInfo
import com.nisaba.api.mapper.StateMapper
import com.nisaba.client.TorrentClientFactory
import com.nisaba.client.dto.TorrentFile
import com.nisaba.client.dto.TorrentProperties
import com.nisaba.client.qbittorrent.QBittorrentClient
import com.nisaba.config.NisabaProperties
import com.nisaba.persistence.entity.TorrentEntity
import com.nisaba.persistence.repository.NodeRepository
import com.nisaba.persistence.repository.TorrentRepository
import com.nisaba.service.RegistryService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2/torrents")
class TorrentController(
    private val torrentRepository: TorrentRepository,
    private val nodeRepository: NodeRepository,
    private val registryService: RegistryService,
    private val torrentClientFactory: TorrentClientFactory,
    private val properties: NisabaProperties
) {
    companion object {
        private val logger = LoggerFactory.getLogger(TorrentController::class.java)
    }

    @GetMapping("/info")
    fun info(
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) filter: String?,
        @RequestParam(required = false) hashes: String?
    ): List<QBitTorrentInfo> {
        val torrents = when {
            hashes != null -> {
                val hashList = hashes.split("|")
                hashList.mapNotNull { torrentRepository.findByInfohash(it) }
            }
            category != null -> torrentRepository.findByCategory(category)
            else -> torrentRepository.findAll().toList()
        }
        return torrents.map { it.toQBitInfo() }
    }

    @PostMapping("/add")
    fun add(
        @RequestParam urls: String?,
        @RequestParam(required = false) savepath: String?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) paused: String?
    ): ResponseEntity<String> {
        if (urls.isNullOrBlank()) {
            return ResponseEntity.badRequest().body("No URLs provided")
        }

        return runBlocking {
            try {
                val magnetUri = urls.trim()
                val infohash = QBittorrentClient.extractInfohash(magnetUri)
                val resolvedSavePath = savepath
                    ?: category?.let { properties.categories[it] }
                    ?: properties.categories["default"]
                    ?: "/data/media"
                val isPaused = paused?.lowercase() == "true"

                registryService.addTorrent(
                    infohash = infohash,
                    magnetUri = magnetUri,
                    savePath = resolvedSavePath,
                    category = category,
                    paused = isPaused
                ).fold(
                    ifLeft = { error ->
                        logger.warn("Failed to add torrent: $error")
                        ResponseEntity.status(HttpStatus.CONFLICT)
                            .body("Failed: $error")
                    },
                    ifRight = {
                        ResponseEntity.ok("Ok.")
                    }
                )
            } catch (e: IllegalArgumentException) {
                ResponseEntity.badRequest().body("Invalid magnet URI: ${e.message}")
            }
        }
    }

    @PostMapping("/delete")
    fun delete(
        @RequestParam hashes: String,
        @RequestParam(required = false) deleteFiles: String?
    ): ResponseEntity<String> {
        return runBlocking {
            val hashList = hashes.split("|")
            val shouldDeleteFiles = deleteFiles?.lowercase() == "true"

            for (hash in hashList) {
                registryService.removeTorrent(hash.trim(), shouldDeleteFiles)
                    .onLeft { logger.warn("Failed to remove torrent $hash: $it") }
            }

            ResponseEntity.ok("Ok.")
        }
    }

    @GetMapping("/properties")
    fun properties(
        @RequestParam hash: String
    ): ResponseEntity<Any> {
        val torrent = torrentRepository.findByInfohash(hash)
            ?: return ResponseEntity.notFound().build()

        val node = torrent.assignedNodeId?.let { nodeRepository.findById(it).orElse(null) }

        return if (node != null) {
            runBlocking {
                val client = torrentClientFactory.clientFor(node)
                client.getTorrentProperties(node, hash).fold(
                    ifLeft = {
                        ResponseEntity.ok(
                            mapOf(
                                "save_path" to torrent.savePath,
                                "seeding_time" to (torrent.seedingTime ?: 0)
                            )
                        )
                    },
                    ifRight = { ResponseEntity.ok(it) }
                )
            }
        } else {
            ResponseEntity.ok(
                mapOf(
                    "save_path" to torrent.savePath,
                    "seeding_time" to (torrent.seedingTime ?: 0)
                )
            )
        }
    }

    @GetMapping("/files")
    fun files(
        @RequestParam hash: String
    ): ResponseEntity<Any> {
        val torrent = torrentRepository.findByInfohash(hash)
            ?: return ResponseEntity.notFound().build()

        val node = torrent.assignedNodeId?.let { nodeRepository.findById(it).orElse(null) }

        return if (node != null) {
            runBlocking {
                val client = torrentClientFactory.clientFor(node)
                client.getTorrentFiles(node, hash).fold(
                    ifLeft = { ResponseEntity.ok(emptyList<TorrentFile>()) },
                    ifRight = { ResponseEntity.ok(it) }
                )
            }
        } else {
            ResponseEntity.ok(emptyList<TorrentFile>())
        }
    }

    @PostMapping("/setShareLimits")
    fun setShareLimits(): ResponseEntity<String> = ResponseEntity.ok("Ok.")

    @PostMapping("/topPrio")
    fun topPrio(): ResponseEntity<String> = ResponseEntity.ok("Ok.")

    @PostMapping("/setForceStart")
    fun setForceStart(): ResponseEntity<String> = ResponseEntity.ok("Ok.")

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
