package com.nisaba.persistence.entity

enum class TorrentState {
    QUEUED, ASSIGNING, DOWNLOADING, STALLED, REASSIGNING, PAUSED, DONE, FAILED
}
