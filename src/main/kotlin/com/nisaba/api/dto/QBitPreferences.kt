package com.nisaba.api.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * API response DTO matching qBittorrent preferences format.
 * Returns the minimal set of fields the *arr stack queries.
 */
data class QBitPreferences(
    @JsonProperty("save_path") val savePath: String = "/data/media",
    @JsonProperty("temp_path_enabled") val tempPathEnabled: Boolean = false,
    @JsonProperty("temp_path") val tempPath: String = "",
    @JsonProperty("max_connec") val maxConnec: Int = 500,
    @JsonProperty("max_connec_per_torrent") val maxConnecPerTorrent: Int = 100,
    @JsonProperty("max_uploads") val maxUploads: Int = -1,
    @JsonProperty("max_uploads_per_torrent") val maxUploadsPerTorrent: Int = -1,
    @JsonProperty("dl_limit") val dlLimit: Long = 0,
    @JsonProperty("up_limit") val upLimit: Long = 0,
    @JsonProperty("queueing_enabled") val queueingEnabled: Boolean = false,
    @JsonProperty("max_active_downloads") val maxActiveDownloads: Int = 5,
    @JsonProperty("max_active_torrents") val maxActiveTorrents: Int = 5,
    @JsonProperty("max_active_uploads") val maxActiveUploads: Int = 5,
    val locale: String = "en",
    @JsonProperty("web_ui_port") val webUiPort: Int = 8080
)
