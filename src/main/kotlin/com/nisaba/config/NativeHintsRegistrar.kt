package com.nisaba.config

import com.nisaba.api.controller.HealthController
import com.nisaba.api.dto.QBitCategory
import com.nisaba.api.dto.QBitPreferences
import com.nisaba.api.dto.QBitServerState
import com.nisaba.api.dto.QBitSyncMaindata
import com.nisaba.api.dto.QBitTorrentInfo
import com.nisaba.client.dto.*
import com.nisaba.client.qbittorrent.QBitTorrentFile
import com.nisaba.client.qbittorrent.QBitTorrentProperties
import com.nisaba.client.qbittorrent.QBitTransferInfo
import com.nisaba.client.qbittorrent.QBitTorrentInfo as QBitClientTorrentInfo
import com.nisaba.error.NisabaError
import com.nisaba.persistence.entity.NodeEntity
import com.nisaba.persistence.entity.TorrentEntity
import com.nisaba.persistence.entity.TorrentState
import com.nisaba.persistence.entity.BandwidthSampleEntity
import com.nisaba.persistence.entity.StateTransitionEntity
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportRuntimeHints

/**
 * Registers runtime hints for GraalVM native image compilation.
 * This ensures reflection metadata is available for Jackson serialization
 * and Spring Data JDBC entity mapping.
 */
@Configuration
@ImportRuntimeHints(NativeHintsRegistrar::class)
class NativeHintsConfig

class NativeHintsRegistrar : RuntimeHintsRegistrar {

    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        // API DTOs (Jackson serialization for REST responses)
        listOf(
            QBitTorrentInfo::class.java,
            QBitCategory::class.java,
            QBitPreferences::class.java,
            QBitSyncMaindata::class.java,
            QBitServerState::class.java,
            HealthController.HealthStatus::class.java
        ).forEach { registerForJackson(hints, it) }

        // Client DTOs (internal data transfer)
        listOf(
            NodeHealth::class.java,
            AuthSession::class.java,
            AddResult::class.java,
            TorrentStatus::class.java,
            TorrentProperties::class.java,
            TorrentFile::class.java,
            TransferInfo::class.java,
            ClientTorrentState::class.java
        ).forEach { registerForJackson(hints, it) }

        // qBittorrent client DTOs (Jackson deserialization from qBit API)
        listOf(
            QBitClientTorrentInfo::class.java,
            QBitTorrentProperties::class.java,
            QBitTorrentFile::class.java,
            QBitTransferInfo::class.java
        ).forEach { registerForJackson(hints, it) }

        // Persistence entities (Spring Data JDBC)
        listOf(
            NodeEntity::class.java,
            TorrentEntity::class.java,
            BandwidthSampleEntity::class.java,
            StateTransitionEntity::class.java,
            TorrentState::class.java
        ).forEach { registerForJdbc(hints, it) }

        // Error types (may be serialized in responses)
        listOf(
            NisabaError::class.java,
            NisabaError.NodeUnreachable::class.java,
            NisabaError.NodeRejected::class.java,
            NisabaError.AuthFailed::class.java,
            NisabaError.AlreadyExists::class.java,
            NisabaError.NoHealthyNodes::class.java,
            NisabaError.InvalidStateTransition::class.java,
            NisabaError.TorrentNotFound::class.java,
            NisabaError.ReassignmentFailed::class.java
        ).forEach { registerForJackson(hints, it) }

        // Config classes
        listOf(
            NisabaProperties::class.java,
            NisabaProperties.AuthProperties::class.java,
            NisabaProperties.PollProperties::class.java,
            NisabaProperties.EmaProperties::class.java,
            NodeDefinition::class.java,
            NodeListWrapper::class.java
        ).forEach { registerForJackson(hints, it) }

        // Register resource patterns
        hints.resources().registerPattern("db/migration/*")
        hints.resources().registerPattern("nodes.yml")
        hints.resources().registerPattern("application*.yml")
    }

    private fun registerForJackson(hints: RuntimeHints, clazz: Class<*>) {
        hints.reflection().registerType(
            TypeReference.of(clazz),
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.DECLARED_FIELDS
        )
    }

    private fun registerForJdbc(hints: RuntimeHints, clazz: Class<*>) {
        hints.reflection().registerType(
            TypeReference.of(clazz),
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.DECLARED_FIELDS,
            MemberCategory.PUBLIC_FIELDS
        )
    }
}
