package com.nisaba.client.qbittorrent

import com.nisaba.client.dto.ClientTorrentState
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Raw qBittorrent API response DTOs.
 * These map directly to the JSON returned by the qBittorrent Web API v2.
 */

@JsonIgnoreProperties(ignoreUnknown = true)
data class QBitTorrentInfo(
    val hash: String,
    val name: String,
    val progress: Float,
    @JsonProperty("dlspeed") val dlSpeed: Long,
    @JsonProperty("upspeed") val upSpeed: Long,
    val state: String,
    @JsonProperty("save_path") val savePath: String,
    @JsonProperty("content_path") val contentPath: String?,
    val size: Long,
    val eta: Long?,
    val ratio: Float?,
    @JsonProperty("seeding_time") val seedingTime: Long?,
    val category: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class QBitTorrentProperties(
    @JsonProperty("save_path") val savePath: String,
    @JsonProperty("seeding_time") val seedingTime: Long?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class QBitTorrentFile(
    val name: String,
    val size: Long,
    val progress: Float
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class QBitTransferInfo(
    @JsonProperty("dl_info_speed") val dlInfoSpeed: Long,
    @JsonProperty("up_info_speed") val upInfoSpeed: Long,
    @JsonProperty("dl_info_data") val dlInfoData: Long?,
    @JsonProperty("up_info_data") val upInfoData: Long?
)

/**
 * Maps qBittorrent state strings to the application's [ClientTorrentState].
 * See https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-(qBittorrent-4.1)#get-torrent-list
 */
object QBitStateMapper {
    fun map(qbitState: String): ClientTorrentState = when (qbitState) {
        "allocating",
        "queuedDL",
        "queuedUP" -> ClientTorrentState.QUEUED

        "downloading",
        "forcedDL",
        "forcedMetaDL" -> ClientTorrentState.DOWNLOADING

        "stalledDL" -> ClientTorrentState.STALLED

        "pausedDL",
        "pausedUP" -> ClientTorrentState.PAUSED

        "uploading",
        "stalledUP",
        "forcedUP" -> ClientTorrentState.COMPLETED

        "checkingDL",
        "checkingUP",
        "checkingResumeData" -> ClientTorrentState.CHECKING

        "metaDL" -> ClientTorrentState.METADATA

        "error",
        "missingFiles",
        "unknown" -> ClientTorrentState.ERROR

        else -> ClientTorrentState.ERROR
    }
}
