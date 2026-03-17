package com.nisaba.service

import com.nisaba.error.NisabaError
import com.nisaba.persistence.entity.StateTransitionEntity
import com.nisaba.persistence.entity.TorrentEntity
import com.nisaba.persistence.entity.TorrentState
import com.nisaba.persistence.entity.TorrentState.*
import com.nisaba.persistence.repository.StateTransitionRepository
import com.nisaba.persistence.repository.TorrentRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class StateMachineTest {

    private lateinit var torrentRepository: TorrentRepository
    private lateinit var transitionRepository: StateTransitionRepository
    private lateinit var stateMachine: StateMachine

    private fun torrent(
        infohash: String = "abc123",
        state: TorrentState = QUEUED
    ) = TorrentEntity(
        infohash = infohash,
        magnetUri = "magnet:?xt=urn:btih:$infohash",
        savePath = "/data/media",
        state = state,
        isNewEntity = false
    )

    @BeforeEach
    fun setUp() {
        torrentRepository = mockk()
        transitionRepository = mockk()
        stateMachine = StateMachine(torrentRepository, transitionRepository)

        every { transitionRepository.save(any<StateTransitionEntity>()) } answers { firstArg() }
        every { torrentRepository.save(any<TorrentEntity>()) } answers { firstArg() }
    }

    // --- All legal transitions succeed ---

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @CsvSource(
        "QUEUED, ASSIGNING",
        "ASSIGNING, DOWNLOADING",
        "ASSIGNING, QUEUED",
        "DOWNLOADING, STALLED",
        "DOWNLOADING, PAUSED",
        "DOWNLOADING, DONE",
        "STALLED, REASSIGNING",
        "STALLED, DOWNLOADING",
        "STALLED, QUEUED",
        "REASSIGNING, ASSIGNING",
        "REASSIGNING, FAILED",
        "PAUSED, DOWNLOADING",
        "PAUSED, QUEUED",
        "FAILED, QUEUED"
    )
    fun `legal transitions succeed`(fromStr: String, toStr: String) {
        val from = TorrentState.valueOf(fromStr)
        val to = TorrentState.valueOf(toStr)
        val t = torrent(state = from)
        every { torrentRepository.findByInfohash("abc123") } returns t

        val result = stateMachine.transition("abc123", to)

        result.isRight() shouldBe true
        result.getOrNull()!!.state shouldBe to
    }

    // --- Illegal transitions return InvalidStateTransition ---

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @CsvSource(
        "QUEUED, DOWNLOADING",
        "QUEUED, DONE",
        "QUEUED, FAILED",
        "DOWNLOADING, QUEUED",
        "DOWNLOADING, ASSIGNING",
        "DONE, QUEUED",
        "DONE, DOWNLOADING",
        "FAILED, DOWNLOADING",
        "FAILED, DONE"
    )
    fun `illegal transitions return InvalidStateTransition`(fromStr: String, toStr: String) {
        val from = TorrentState.valueOf(fromStr)
        val to = TorrentState.valueOf(toStr)
        val t = torrent(state = from)
        every { torrentRepository.findByInfohash("abc123") } returns t

        val result = stateMachine.transition("abc123", to)

        result.isLeft() shouldBe true
        val error = result.leftOrNull()!!
        error.shouldBeInstanceOf<NisabaError.InvalidStateTransition>()
        error.infohash shouldBe "abc123"
        error.from shouldBe from
        error.to shouldBe to
    }

    // --- Transition writes audit log ---

    @Test
    fun `transition saves audit log entry`() {
        val t = torrent(state = QUEUED)
        every { torrentRepository.findByInfohash("abc123") } returns t

        val transitionSlot = slot<StateTransitionEntity>()
        every { transitionRepository.save(capture(transitionSlot)) } answers { firstArg() }

        stateMachine.transition("abc123", ASSIGNING, nodeId = "node-1", reason = "scheduled")

        val captured = transitionSlot.captured
        captured.infohash shouldBe "abc123"
        captured.fromState shouldBe QUEUED
        captured.toState shouldBe ASSIGNING
        captured.nodeId shouldBe "node-1"
        captured.reason shouldBe "scheduled"
    }

    // --- Unknown infohash returns TorrentNotFound ---

    @Test
    fun `unknown infohash returns TorrentNotFound`() {
        every { torrentRepository.findByInfohash("unknown") } returns null

        val result = stateMachine.transition("unknown", ASSIGNING)

        result.isLeft() shouldBe true
        val error = result.leftOrNull()!!
        error.shouldBeInstanceOf<NisabaError.TorrentNotFound>()
        error.infohash shouldBe "unknown"
    }

    // --- DONE is terminal ---

    @Test
    fun `DONE state has no allowed transitions`() {
        StateMachine.allowedTransitions[DONE] shouldBe emptySet()
    }

    @Test
    fun `transition updates torrent state in repository`() {
        val t = torrent(state = QUEUED)
        every { torrentRepository.findByInfohash("abc123") } returns t

        val savedSlot = slot<TorrentEntity>()
        every { torrentRepository.save(capture(savedSlot)) } answers { firstArg() }

        stateMachine.transition("abc123", ASSIGNING)

        savedSlot.captured.state shouldBe ASSIGNING
        savedSlot.captured.isNew shouldBe false
    }
}
