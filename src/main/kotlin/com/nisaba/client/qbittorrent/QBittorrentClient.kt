package com.nisaba.client.qbittorrent

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.nisaba.client.TorrentClient
import com.nisaba.client.dto.*
import com.nisaba.error.NisabaError
import com.nisaba.persistence.entity.NodeEntity
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

@Component
class QBittorrentClient(
    private val webClient: WebClient,
    private val authManager: QBitAuthManager
) : TorrentClient {

    companion object {
        private val logger = LoggerFactory.getLogger(QBittorrentClient::class.java)
        private val INFOHASH_REGEX = Regex("urn:btih:([0-9a-fA-F]{40})", RegexOption.IGNORE_CASE)

        fun extractInfohash(magnetUri: String): String {
            return INFOHASH_REGEX.find(magnetUri)?.groupValues?.get(1)?.lowercase()
                ?: throw IllegalArgumentException("Cannot extract infohash from magnet URI: $magnetUri")
        }
    }

    override suspend fun probe(node: NodeEntity): Either<NisabaError, NodeHealth> = withAuth(node) { sid ->
        val version = webClient.get()
            .uri("${node.baseUrl}/api/v2/app/version")
            .cookie("SID", sid)
            .retrieve()
            .bodyToMono(String::class.java)
            .block()

        NodeHealth(nodeId = node.nodeId, healthy = true, version = version).right()
    }

    override suspend fun authenticate(node: NodeEntity): Either<NisabaError, AuthSession> {
        return try {
            val sid = authManager.authenticate(node)
            if (sid != null) {
                AuthSession(nodeId = node.nodeId, token = sid).right()
            } else {
                NisabaError.AuthFailed(node.nodeId).left()
            }
        } catch (e: Exception) {
            NisabaError.NodeUnreachable(node.nodeId, e.message ?: "Authentication failed").left()
        }
    }

    override suspend fun addTorrent(
        node: NodeEntity,
        magnetUri: String,
        savePath: String,
        category: String?,
        paused: Boolean
    ): Either<NisabaError, AddResult> = withAuth(node) { sid ->
        val infohash = extractInfohash(magnetUri)
        webClient.post()
            .uri("${node.baseUrl}/api/v2/torrents/add")
            .cookie("SID", sid)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(
                BodyInserters.fromFormData("urls", magnetUri)
                    .with("savepath", savePath)
                    .apply {
                        category?.let { with("category", it) }
                    }
                    .with("paused", paused.toString())
            )
            .retrieve()
            .bodyToMono(String::class.java)
            .block()

        AddResult(infohash = infohash, accepted = true).right()
    }

    override suspend fun pauseTorrent(
        node: NodeEntity,
        infohash: String
    ): Either<NisabaError, Unit> = withAuth(node) { sid ->
        webClient.post()
            .uri("${node.baseUrl}/api/v2/torrents/pause")
            .cookie("SID", sid)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("hashes", infohash))
            .retrieve()
            .bodyToMono(Void::class.java)
            .block()

        Unit.right()
    }

    override suspend fun resumeTorrent(
        node: NodeEntity,
        infohash: String
    ): Either<NisabaError, Unit> = withAuth(node) { sid ->
        webClient.post()
            .uri("${node.baseUrl}/api/v2/torrents/resume")
            .cookie("SID", sid)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("hashes", infohash))
            .retrieve()
            .bodyToMono(Void::class.java)
            .block()

        Unit.right()
    }

    override suspend fun removeTorrent(
        node: NodeEntity,
        infohash: String,
        deleteFiles: Boolean
    ): Either<NisabaError, Unit> = withAuth(node) { sid ->
        webClient.post()
            .uri("${node.baseUrl}/api/v2/torrents/delete")
            .cookie("SID", sid)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(
                BodyInserters.fromFormData("hashes", infohash)
                    .with("deleteFiles", deleteFiles.toString())
            )
            .retrieve()
            .bodyToMono(Void::class.java)
            .block()

        Unit.right()
    }

    override suspend fun listTorrents(
        node: NodeEntity
    ): Either<NisabaError, List<TorrentStatus>> = withAuth(node) { sid ->
        val torrents = webClient.get()
            .uri("${node.baseUrl}/api/v2/torrents/info")
            .cookie("SID", sid)
            .retrieve()
            .bodyToMono(Array<QBitTorrentInfo>::class.java)
            .block() ?: emptyArray()

        torrents.map { t ->
            TorrentStatus(
                infohash = t.hash,
                name = t.name,
                progress = t.progress,
                speedBps = t.dlSpeed,
                state = QBitStateMapper.map(t.state),
                savePath = t.savePath,
                contentPath = t.contentPath,
                totalSize = t.size,
                eta = t.eta,
                ratio = t.ratio,
                seedingTime = t.seedingTime
            )
        }.right()
    }

    override suspend fun getTorrentProperties(
        node: NodeEntity,
        infohash: String
    ): Either<NisabaError, TorrentProperties> = withAuth(node) { sid ->
        val props = webClient.get()
            .uri("${node.baseUrl}/api/v2/torrents/properties?hash=$infohash")
            .cookie("SID", sid)
            .retrieve()
            .bodyToMono(QBitTorrentProperties::class.java)
            .block()!!

        TorrentProperties(savePath = props.savePath, seedingTime = props.seedingTime).right()
    }

    override suspend fun getTorrentFiles(
        node: NodeEntity,
        infohash: String
    ): Either<NisabaError, List<TorrentFile>> = withAuth(node) { sid ->
        val files = webClient.get()
            .uri("${node.baseUrl}/api/v2/torrents/files?hash=$infohash")
            .cookie("SID", sid)
            .retrieve()
            .bodyToMono(Array<QBitTorrentFile>::class.java)
            .block() ?: emptyArray()

        files.map { f -> TorrentFile(name = f.name, size = f.size) }.right()
    }

    override suspend fun getTransferSpeed(
        node: NodeEntity
    ): Either<NisabaError, TransferInfo> = withAuth(node) { sid ->
        val info = webClient.get()
            .uri("${node.baseUrl}/api/v2/transfer/info")
            .cookie("SID", sid)
            .retrieve()
            .bodyToMono(QBitTransferInfo::class.java)
            .block()!!

        TransferInfo(
            downloadSpeedBps = info.dlInfoSpeed,
            uploadSpeedBps = info.upInfoSpeed
        ).right()
    }

    /**
     * Executes a block with a valid SID. Re-authenticates on 403.
     * Wraps all exceptions as [NisabaError.NodeUnreachable].
     */
    private suspend fun <T> withAuth(
        node: NodeEntity,
        block: suspend (sid: String) -> Either<NisabaError, T>
    ): Either<NisabaError, T> {
        return try {
            val sid = authManager.getSid(node) ?: authManager.authenticate(node)
                ?: return NisabaError.AuthFailed(node.nodeId).left()
            block(sid)
        } catch (e: org.springframework.web.reactive.function.client.WebClientResponseException) {
            if (e.statusCode == HttpStatusCode.valueOf(403)) {
                // Session expired, re-authenticate and retry
                logger.info("SID expired for node ${node.nodeId}, re-authenticating")
                authManager.invalidate(node)
                return try {
                    val newSid = authManager.authenticate(node)
                        ?: return NisabaError.AuthFailed(node.nodeId).left()
                    block(newSid)
                } catch (retryEx: Exception) {
                    NisabaError.NodeUnreachable(node.nodeId, retryEx.message ?: "Retry failed").left()
                }
            }
            NisabaError.NodeUnreachable(node.nodeId, e.message ?: "HTTP error").left()
        } catch (e: Exception) {
            NisabaError.NodeUnreachable(node.nodeId, e.message ?: "Unknown error").left()
        }
    }
}
