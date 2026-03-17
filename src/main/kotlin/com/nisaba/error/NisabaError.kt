package com.nisaba.error

import com.nisaba.persistence.entity.TorrentState

sealed interface NisabaError {
    data class NodeUnreachable(val nodeId: String, val cause: String) : NisabaError
    data class NodeRejected(val nodeId: String, val reason: String) : NisabaError
    data class AuthFailed(val nodeId: String) : NisabaError
    data class AlreadyExists(val infohash: String) : NisabaError
    data class NoHealthyNodes(val reason: String) : NisabaError
    data class InvalidStateTransition(
        val infohash: String,
        val from: TorrentState,
        val to: TorrentState
    ) : NisabaError
    data class TorrentNotFound(val infohash: String) : NisabaError
    data class ReassignmentFailed(
        val infohash: String,
        val oldNode: String,
        val reason: String
    ) : NisabaError
}
