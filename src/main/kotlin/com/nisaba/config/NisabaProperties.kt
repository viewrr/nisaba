package com.nisaba.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "nisaba")
data class NisabaProperties(
    val auth: AuthProperties,
    val nodesFile: String = "nodes.yml",
    val poll: PollProperties = PollProperties(),
    val ema: EmaProperties = EmaProperties(),
    val categories: Map<String, String> = mapOf("default" to "/data/media/misc")
) {
    data class AuthProperties(val username: String, val password: String)
    data class PollProperties(
        val intervalSeconds: Long = 30,
        val stallThresholdCycles: Int = 2,
        val assigningTimeoutSeconds: Long = 90
    )
    data class EmaProperties(
        val alpha: Float = 0.3f,
        val coldStartWeight: Float = 0.5f
    )
}
