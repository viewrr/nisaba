package com.nisaba.api

import com.nisaba.api.mapper.StateMapper
import com.nisaba.persistence.entity.TorrentState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class StateMapperTest {

    @ParameterizedTest(name = "{0} maps to {1}")
    @CsvSource(
        "QUEUED, queuedDL",
        "ASSIGNING, checkingResumeData",
        "DOWNLOADING, downloading",
        "STALLED, stalledDL",
        "REASSIGNING, checkingResumeData",
        "PAUSED, pausedDL",
        "DONE, pausedUP",
        "FAILED, error"
    )
    fun `toQBitState maps all states correctly`(stateStr: String, expected: String) {
        val state = TorrentState.valueOf(stateStr)
        StateMapper.toQBitState(state) shouldBe expected
    }
}
