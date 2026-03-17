package com.nisaba.api.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * API response DTO matching qBittorrent's sync/maindata format.
 * Used by *arr stack for incremental sync polling.
 */
data class QBitSyncMaindata(
    val rid: Long = 0,
    @JsonProperty("full_update") val fullUpdate: Boolean = true,
    val torrents: Map<String, QBitTorrentInfo> = emptyMap(),
    @JsonProperty("torrents_removed") val torrentsRemoved: List<String> = emptyList(),
    val categories: Map<String, QBitCategory> = emptyMap(),
    @JsonProperty("server_state") val serverState: QBitServerState = QBitServerState()
)

data class QBitServerState(
    @JsonProperty("dl_info_speed") val dlInfoSpeed: Long = 0,
    @JsonProperty("dl_info_data") val dlInfoData: Long = 0,
    @JsonProperty("up_info_speed") val upInfoSpeed: Long = 0,
    @JsonProperty("up_info_data") val upInfoData: Long = 0,
    @JsonProperty("dl_rate_limit") val dlRateLimit: Long = 0,
    @JsonProperty("up_rate_limit") val upRateLimit: Long = 0,
    @JsonProperty("free_space_on_disk") val freeSpaceOnDisk: Long = 0
)
