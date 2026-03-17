package com.nisaba.api.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * API response DTO matching qBittorrent's torrent info format.
 * Used by the *arr stack to query torrent status.
 */
data class QBitTorrentInfo(
    val hash: String,
    val name: String,
    val size: Long,
    val progress: Float,
    @JsonProperty("dlspeed") val dlSpeed: Long = 0,
    @JsonProperty("upspeed") val upSpeed: Long = 0,
    val state: String,
    @JsonProperty("save_path") val savePath: String,
    @JsonProperty("content_path") val contentPath: String? = null,
    val category: String? = null,
    val eta: Long = 8640000,
    val ratio: Float = 0f,
    @JsonProperty("seeding_time") val seedingTime: Long = 0,
    @JsonProperty("added_on") val addedOn: Long = 0,
    @JsonProperty("completion_on") val completionOn: Long = 0,
    @JsonProperty("amount_left") val amountLeft: Long = 0,
    val completed: Long = 0,
    val downloaded: Long = 0,
    val uploaded: Long = 0,
    @JsonProperty("num_seeds") val numSeeds: Int = 0,
    @JsonProperty("num_leechs") val numLeechs: Int = 0,
    val priority: Int = 0,
    @JsonProperty("auto_tmm") val autoTmm: Boolean = false,
    val tags: String = "",
    val tracker: String = "",
    @JsonProperty("magnet_uri") val magnetUri: String? = null
)
