package com.nisaba.client.dto

data class NodeHealth(val nodeId: String, val healthy: Boolean, val version: String? = null)
data class AuthSession(val nodeId: String, val token: String)
data class AddResult(val infohash: String, val accepted: Boolean)

data class TorrentStatus(
    val infohash: String,
    val name: String,
    val progress: Float,
    val speedBps: Long,
    val state: ClientTorrentState,
    val savePath: String,
    val contentPath: String?,
    val totalSize: Long,
    val eta: Long?,
    val ratio: Float?,
    val seedingTime: Long?
)

data class TorrentProperties(val savePath: String, val seedingTime: Long?)
data class TorrentFile(val name: String, val size: Long)
data class TransferInfo(val downloadSpeedBps: Long, val uploadSpeedBps: Long)
