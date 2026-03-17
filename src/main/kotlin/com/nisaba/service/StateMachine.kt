package com.nisaba.service

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.nisaba.error.NisabaError
import com.nisaba.persistence.entity.StateTransitionEntity
import com.nisaba.persistence.entity.TorrentEntity
import com.nisaba.persistence.entity.TorrentState
import com.nisaba.persistence.entity.TorrentState.*
import com.nisaba.persistence.repository.StateTransitionRepository
import com.nisaba.persistence.repository.TorrentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class StateMachine(
    private val torrentRepository: TorrentRepository,
    private val transitionRepository: StateTransitionRepository
) {
    companion object {
        private val logger = LoggerFactory.getLogger(StateMachine::class.java)

        val allowedTransitions: Map<TorrentState, Set<TorrentState>> = mapOf(
            QUEUED       to setOf(ASSIGNING),
            ASSIGNING    to setOf(DOWNLOADING, QUEUED),
            DOWNLOADING  to setOf(STALLED, PAUSED, DONE),
            STALLED      to setOf(REASSIGNING, DOWNLOADING, QUEUED),
            REASSIGNING  to setOf(ASSIGNING, FAILED),
            PAUSED       to setOf(DOWNLOADING, QUEUED),
            FAILED       to setOf(QUEUED),
            DONE         to setOf()
        )
    }

    fun transition(
        infohash: String,
        to: TorrentState,
        nodeId: String? = null,
        reason: String? = null
    ): Either<NisabaError, TorrentEntity> = either {
        val torrent = torrentRepository.findByInfohash(infohash)
            ?: raise(NisabaError.TorrentNotFound(infohash))

        val from = torrent.state
        ensure(to in (allowedTransitions[from] ?: emptySet())) {
            NisabaError.InvalidStateTransition(infohash, from, to)
        }

        transitionRepository.save(
            StateTransitionEntity(
                infohash = infohash,
                fromState = from,
                toState = to,
                nodeId = nodeId,
                reason = reason
            )
        )

        val updated = torrent.copy(state = to, isNewEntity = false)
        torrentRepository.save(updated)

        logger.info("Torrent $infohash: $from -> $to${reason?.let { " ($it)" } ?: ""}")
        updated
    }
}
