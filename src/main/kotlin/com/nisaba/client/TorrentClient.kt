package com.nisaba.client

import arrow.core.Either
import com.nisaba.client.dto.*
import com.nisaba.error.NisabaError
import com.nisaba.persistence.entity.NodeEntity

interface TorrentClient {
    suspend fun probe(node: NodeEntity): Either<NisabaError, NodeHealth>
    suspend fun authenticate(node: NodeEntity): Either<NisabaError, AuthSession>
    suspend fun addTorrent(
        node: NodeEntity,
        magnetUri: String,
        savePath: String,
        category: String?,
        paused: Boolean = false
    ): Either<NisabaError, AddResult>
    suspend fun pauseTorrent(node: NodeEntity, infohash: String): Either<NisabaError, Unit>
    suspend fun resumeTorrent(node: NodeEntity, infohash: String): Either<NisabaError, Unit>
    suspend fun removeTorrent(
        node: NodeEntity,
        infohash: String,
        deleteFiles: Boolean
    ): Either<NisabaError, Unit>
    suspend fun listTorrents(node: NodeEntity): Either<NisabaError, List<TorrentStatus>>
    suspend fun getTorrentProperties(
        node: NodeEntity,
        infohash: String
    ): Either<NisabaError, TorrentProperties>
    suspend fun getTorrentFiles(
        node: NodeEntity,
        infohash: String
    ): Either<NisabaError, List<TorrentFile>>
    suspend fun getTransferSpeed(node: NodeEntity): Either<NisabaError, TransferInfo>
}
