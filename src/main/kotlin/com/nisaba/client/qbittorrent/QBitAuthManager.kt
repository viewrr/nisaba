package com.nisaba.client.qbittorrent

import com.nisaba.config.NodeDefinition
import com.nisaba.persistence.entity.NodeEntity
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages SID cookie sessions for qBittorrent nodes.
 * Caches SID per node ID and re-authenticates when sessions expire (403).
 */
@Component
class QBitAuthManager(
    private val webClient: WebClient,
    private val nodeDefinitions: List<NodeDefinition>
) {
    companion object {
        private val logger = LoggerFactory.getLogger(QBitAuthManager::class.java)
    }

    private val sessions = ConcurrentHashMap<String, String>()

    /**
     * Returns a valid SID for the given node. Uses cached value if available.
     */
    fun getSid(node: NodeEntity): String? = sessions[node.nodeId]

    /**
     * Authenticates against the qBittorrent API and caches the SID cookie.
     * Returns the SID on success, null on failure.
     */
    fun authenticate(node: NodeEntity): String? {
        val definition = nodeDefinitions.find { it.id == node.nodeId }
            ?: run {
                logger.error("No node definition found for nodeId=${node.nodeId}")
                return null
            }

        return try {
            val response = webClient.post()
                .uri("${node.baseUrl}/api/v2/auth/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(
                    BodyInserters.fromFormData("username", definition.username)
                        .with("password", definition.password)
                )
                .exchangeToMono { clientResponse ->
                    val cookies = clientResponse.cookies()
                    val sid = cookies["SID"]?.firstOrNull()?.value
                    Mono.justOrEmpty(sid)
                }
                .block()

            if (response != null) {
                sessions[node.nodeId] = response
                logger.info("Authenticated with node ${node.nodeId}")
            } else {
                logger.warn("Authentication failed for node ${node.nodeId}: no SID cookie")
            }
            response
        } catch (e: Exception) {
            logger.error("Authentication error for node ${node.nodeId}: ${e.message}")
            null
        }
    }

    /**
     * Invalidates the cached session for the given node, forcing re-authentication.
     */
    fun invalidate(node: NodeEntity) {
        sessions.remove(node.nodeId)
    }
}
