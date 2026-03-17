package com.nisaba.client

import com.nisaba.persistence.entity.NodeEntity
import org.springframework.stereotype.Component

@Component
class TorrentClientFactory(
    private val qBittorrentClient: com.nisaba.client.qbittorrent.QBittorrentClient
) {
    fun clientFor(node: NodeEntity): TorrentClient = when (node.clientType) {
        "qbittorrent" -> qBittorrentClient
        else -> throw IllegalArgumentException("Unsupported client type: ${node.clientType}")
    }
}
