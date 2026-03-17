package com.nisaba.api.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * API response DTO matching qBittorrent's category format.
 */
data class QBitCategory(
    val name: String,
    @JsonProperty("savePath") val savePath: String
)
