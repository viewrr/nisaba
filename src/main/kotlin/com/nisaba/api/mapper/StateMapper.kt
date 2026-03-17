package com.nisaba.api.mapper

import com.nisaba.persistence.entity.TorrentState
import com.nisaba.persistence.entity.TorrentState.*

/**
 * Maps internal [TorrentState] values to qBittorrent state strings.
 * The *arr stack expects these exact state names when querying torrent status.
 */
object StateMapper {
    fun toQBitState(state: TorrentState): String = when (state) {
        QUEUED      -> "queuedDL"
        ASSIGNING   -> "checkingResumeData"
        DOWNLOADING -> "downloading"
        STALLED     -> "stalledDL"
        REASSIGNING -> "checkingResumeData"
        PAUSED      -> "pausedDL"
        DONE        -> "pausedUP"
        FAILED      -> "error"
    }
}
